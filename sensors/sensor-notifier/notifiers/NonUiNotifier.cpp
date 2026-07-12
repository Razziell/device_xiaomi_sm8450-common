/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "NonUiNotifier"

#include "NonUiNotifier.h"

#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <cerrno>
#include <linux/xiaomi_touch.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <vector>

#include "SensorNotifierUtils.h"

static const std::string kTouchDevice = "/dev/xiaomi-touch";

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::sensors::V1_0::Event;

namespace {

class NonUiSensorCallback : public IEventQueueCallback {
  public:
    NonUiSensorCallback() {
        touch_fd_ = android::base::unique_fd(
                open(kTouchDevice.c_str(), O_RDWR));
        if (touch_fd_.get() == -1) {
            PLOG(ERROR) << "failed to open " << kTouchDevice;
        }
    }

    Return<void> onEvent(const Event& e) {
        if (touch_fd_.get() == -1) {
            return Void();
        }

        struct touch_mode_request request = {
                .mode = TOUCH_MODE_NONUI_MODE,
                .value = static_cast<int>(e.u.scalar),
        };

        if (ioctl(touch_fd_.get(),
                  TOUCH_IOC_SET_CUR_VALUE, &request) < 0) {
            PLOG(ERROR) << "failed to set NonUI touch mode";
        }

        return Void();
    }

  private:
    android::base::unique_fd touch_fd_;
};

}  // namespace

NonUiNotifier::NonUiNotifier(sp<ISensorManager> manager)
    : SensorNotifier(manager) {
    initializeSensorQueue(
            "xiaomi.sensor.nonui",
            true,
            new NonUiSensorCallback());
}

NonUiNotifier::~NonUiNotifier() {
    deactivate();
}

void NonUiNotifier::notify() {
    if (mQueue == nullptr) {
        LOG(ERROR) << "mQueue is null, cannot notify";
        return;
    }

    // Enable states of touchscreen sensors
    const std::vector<const char*> paths = {
            "/sys/class/touch/touch_dev/fod_longpress_gesture_enabled",
            "/sys/class/touch/touch_dev/gesture_single_tap_enabled",
            "/sys/class/touch/touch_dev/gesture_double_tap_enabled"};

    std::vector<android::base::unique_fd> fds;
    std::vector<pollfd> pollfds;

    for (const char* path : paths) {
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            // Side-fps devices such as marble do not have every FOD node.
            PLOG(INFO) << "Skipping missing path: " << path;
            continue;
        }

        fds.emplace_back(fd);
        pollfds.push_back({
                .fd = fd,
                .events = POLLPRI,
                .revents = 0,
        });
    }

    if (pollfds.empty()) {
        LOG(ERROR) << "No touch sensor paths found. Exiting notify.";
        return;
    }

    /*
     * The last poll entry is eventfd from SensorNotifier. It allows
     * poll() to sleep indefinitely and still exit immediately when
     * deactivate() is called.
     */
    pollfds.push_back({
            .fd = stopEventFd(),
            .events = POLLIN,
            .revents = 0,
    });

    bool sensorEnabled = false;
    bool initialState = false;

    /*
     * Read every sysfs node. Do not use short-circuit logical OR,
     * because all sysfs events must be consumed.
     */
    for (const auto& fd : fds) {
        const bool value = readBool(fd.get());
        initialState |= value;
    }

    if (initialState) {
        auto result = mQueue->enableSensor(
                mSensorHandle,
                20000 /* sample period */,
                0 /* latency */);
        if (!result.isOk()) {
            LOG(ERROR) << "enableSensor transaction failed: "
                       << result.description();
        } else if (result != Result::OK) {
            LOG(ERROR) << "failed to enable NonUI sensor";
        } else {
            sensorEnabled = true;
        }
    }

    while (isActive()) {
        int rc = poll(pollfds.data(), pollfds.size(), -1);
        if (rc < 0) {
            if (errno == EINTR) {
                continue;
            }

            PLOG(ERROR) << "failed to poll touch sensor nodes";
            break;
        }

        pollfd& stopPollFd = pollfds.back();
        if (stopPollFd.revents & POLLIN) {
            consumeStopEvent();
            break;
        }

        if (stopPollFd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            LOG(ERROR) << "stop eventfd failed, revents="
                       << stopPollFd.revents;
            break;
        }

        bool hasFatalError = false;

        for (size_t i = 0; i < fds.size(); i++) {
            if (pollfds[i].revents &
                    (POLLERR | POLLHUP | POLLNVAL)) {
                LOG(ERROR) << "touch sysfs poll failed, fd="
                           << pollfds[i].fd
                           << ", revents=" << pollfds[i].revents;
                hasFatalError = true;
            }
        }

        if (hasFatalError) {
            break;
        }

        /*
         * Read all nodes unconditionally. The old expression:
         *
         *     enabled = enabled || readBool(...)
         *
         * stopped reading after the first true value and left later
         * POLLPRI events pending, causing an immediate poll loop and
         * repeated enableSensor() calls.
         */
        bool newState = false;

        for (const auto& fd : fds) {
            const bool value = readBool(fd.get());
            newState |= value;
        }

        if (newState == sensorEnabled) {
            continue;
        }

        if (newState) {
            auto result = mQueue->enableSensor(
                    mSensorHandle,
                    20000 /* sample period */,
                    0 /* latency */);
            if (!result.isOk()) {
                LOG(ERROR) << "enableSensor transaction failed: "
                           << result.description();
                continue;
            }

            if (result != Result::OK) {
                LOG(ERROR) << "failed to enable NonUI sensor";
                continue;
            }
        } else {
            auto result = mQueue->disableSensor(mSensorHandle);
            if (!result.isOk()) {
                LOG(ERROR) << "disableSensor transaction failed: "
                           << result.description();
                continue;
            }

            if (result != Result::OK) {
                LOG(VERBOSE) << "failed to disable NonUI sensor";
                continue;
            }
        }

        sensorEnabled = newState;
    }

    if (sensorEnabled) {
        auto result = mQueue->disableSensor(mSensorHandle);
        if (!result.isOk()) {
            LOG(ERROR) << "disableSensor transaction failed during shutdown: "
                       << result.description();
        }
    }
}
