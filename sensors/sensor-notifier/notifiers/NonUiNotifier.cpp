/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "NonUiNotifier"

#include "NonUiNotifier.h"

#include <android-base/logging.h>
#include <android-base/unique_fd.h>
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
        touch_fd_ = android::base::unique_fd(open(kTouchDevice.c_str(), O_RDWR));
        if (touch_fd_.get() == -1) {
            LOG(ERROR) << "failed to open " << kTouchDevice;
        }
    }

    Return<void> onEvent(const Event& e) {
        if (touch_fd_.get() == -1) return Void();

        struct touch_mode_request request = {
                .mode = TOUCH_MODE_NONUI_MODE,
                .value = static_cast<int>(e.u.scalar),
        };
        ioctl(touch_fd_.get(), TOUCH_IOC_SET_CUR_VALUE, &request);

        return Void();
    }

  private:
    android::base::unique_fd touch_fd_;
};

}  // namespace

NonUiNotifier::NonUiNotifier(sp<ISensorManager> manager) : SensorNotifier(manager) {
    initializeSensorQueue("xiaomi.sensor.nonui", true, new NonUiSensorCallback());
}

NonUiNotifier::~NonUiNotifier() {
    deactivate();
}

void NonUiNotifier::notify() {
    if (mQueue == nullptr) {
        LOG(ERROR) << "mQueue is null, cannot notify";
        mActive = false;
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
            // It's normal for side-fps devices like marble to miss FOD paths
            LOG(INFO) << "Skipping missing path: " << path;
            continue;
        }
        fds.emplace_back(fd);
        pollfds.push_back({fd, POLLPRI, 0});
    }

    if (pollfds.empty()) {
        LOG(ERROR) << "No touch sensor paths found. Exiting notify.";
        mActive = false;
        return;
    }

    while (mActive) {
        int rc = poll(pollfds.data(), pollfds.size(), 1000); // 1000ms timeout to prevent deadlocks
        if (rc < 0) {
            LOG(ERROR) << "failed to poll, err: " << rc;
            continue;
        }
        if (rc == 0) continue; // Timeout, loop again to check mActive flag

        bool enabled = false;
        for (const auto& pfd : pollfds) {
            enabled = enabled || readBool(pfd.fd);
        }
        if (enabled) {
            if (!mQueue->enableSensor(mSensorHandle, 20000 /* sample period */, 0 /* latency */).isOk()) {
                LOG(ERROR) << "failed to enable sensor";
            }
        } else {
            if (!mQueue->disableSensor(mSensorHandle).isOk()) {
                LOG(DEBUG) << "failed to disable sensor";
            }
        }
    }
}
