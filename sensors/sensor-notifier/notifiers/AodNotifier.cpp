/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "AodNotifier"

#include "AodNotifier.h"

#include <android-base/logging.h>
#include <android-base/unique_fd.h>
#include <bitset>
#include <cerrno>
#include <display/drm/mi_disp.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <vector>

#include "SensorNotifierUtils.h"

static const std::string kDispFeatureDevice =
        "/dev/mi_display/disp_feature";

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::sensors::V1_0::Event;

namespace {

void requestDozeBrightness(int fd, __u32 dozeBrightness,
                           __u32 displayId) {
    disp_doze_brightness_req req = {};
    req.base.flag = 0;
    req.base.disp_id = displayId;
    req.doze_brightness = dozeBrightness;

    if (ioctl(fd,
              MI_DISP_IOCTL_SET_DOZE_BRIGHTNESS,
              &req) < 0) {
        PLOG(ERROR) << "failed to set doze brightness";
    }
}

class AodSensorCallback : public IEventQueueCallback {
  public:
    AodSensorCallback() {
        disp_fd_ = android::base::unique_fd(
                open(kDispFeatureDevice.c_str(), O_RDWR));
        if (disp_fd_.get() == -1) {
            PLOG(ERROR) << "failed to open "
                        << kDispFeatureDevice;
        }
    }

    Return<void> onEvent(const Event& e) {
        if (disp_fd_.get() == -1) {
            return Void();
        }

        /*
         * Do not hold displayMutex while calling ioctl(). Copy the
         * current display IDs first to avoid blocking the notifier
         * thread or creating a lock inversion.
         */
        std::vector<__u32> displays;

        {
            std::lock_guard<std::mutex> lock(
                    AodNotifier::displayMutex);
            displays.assign(
                    AodNotifier::activeDisplays.begin(),
                    AodNotifier::activeDisplays.end());
        }

        for (const auto display : displays) {
            requestDozeBrightness(
                    disp_fd_.get(),
                    (e.u.scalar == 3 || e.u.scalar == 5)
                            ? DOZE_BRIGHTNESS_LBM
                            : DOZE_BRIGHTNESS_HBM,
                    display);
        }

        return Void();
    }

  private:
    android::base::unique_fd disp_fd_;
};

}  // namespace

AodNotifier::AodNotifier(sp<ISensorManager> manager)
    : SensorNotifier(manager) {
    initializeSensorQueue(
            "xiaomi.sensor.aod",
            true,
            new AodSensorCallback());
}

AodNotifier::~AodNotifier() {
    deactivate();
}

void AodNotifier::notify() {
    if (mQueue == nullptr) {
        LOG(ERROR) << "mQueue is null";
        return;
    }

    android::base::unique_fd disp_fd_ =
            android::base::unique_fd(
                    open(kDispFeatureDevice.c_str(), O_RDWR));
    if (disp_fd_.get() == -1) {
        PLOG(ERROR) << "failed to open "
                    << kDispFeatureDevice;
        return;
    }

    /*
     * Poco F5 (marble) has one built-in display. Registering the
     * secondary display is unnecessary and can generate ioctl errors.
     */
    const std::vector<disp_display_type> displays = {
            MI_DISP_PRIMARY,
    };

    // Register for power events
    for (const disp_display_type& display : displays) {
        disp_event_req req = {};
        req.base.flag = 0;
        req.base.disp_id = display;
        req.type = MI_DISP_EVENT_POWER;

        if (ioctl(disp_fd_.get(),
                  MI_DISP_IOCTL_REGISTER_EVENT,
                  &req) < 0) {
            PLOG(ERROR) << "failed to register display power event";
            return;
        }
    }

    struct pollfd pollfds[] = {
            {
                    .fd = disp_fd_.get(),
                    .events = POLLIN,
                    .revents = 0,
            },
            {
                    .fd = stopEventFd(),
                    .events = POLLIN,
                    .revents = 0,
            },
    };

    bool sensorEnabled = false;

    while (isActive()) {
        int rc = poll(pollfds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) {
                continue;
            }

            PLOG(ERROR) << "failed to poll "
                        << kDispFeatureDevice;
            break;
        }

        if (pollfds[1].revents & POLLIN) {
            consumeStopEvent();
            break;
        }

        if (pollfds[1].revents &
                (POLLERR | POLLHUP | POLLNVAL)) {
            LOG(ERROR) << "stop eventfd failed, revents="
                       << pollfds[1].revents;
            break;
        }

        if (pollfds[0].revents &
                (POLLERR | POLLHUP | POLLNVAL)) {
            LOG(ERROR) << "display event fd failed, revents="
                       << pollfds[0].revents;
            break;
        }

        if (!(pollfds[0].revents & POLLIN)) {
            continue;
        }

        std::shared_ptr<disp_event_resp> response =
                parseDispEvent(disp_fd_.get());
        if (response == nullptr) {
            continue;
        }

        if (response->base.type != MI_DISP_EVENT_POWER) {
            continue;
        }

        int value = response->data[0];
        LOG(VERBOSE) << "received data: "
                     << std::bitset<8>(value);

        switch (response->data[0]) {
            case MI_DISP_POWER_LP1:
                FALLTHROUGH_INTENDED;

            case MI_DISP_POWER_LP2: {
                bool shouldEnable = false;

                {
                    std::lock_guard<std::mutex> lock(displayMutex);
                    shouldEnable = activeDisplays.empty();
                    activeDisplays.insert(response->base.disp_id);
                }

                if (shouldEnable && !sensorEnabled) {
                    auto result = mQueue->enableSensor(
                            mSensorHandle,
                            20000 /* sample period */,
                            0 /* latency */);
                    if (!result.isOk()) {
                        LOG(ERROR) << "enableSensor transaction failed: "
                                   << result.description();
                    } else if (result != Result::OK) {
                        LOG(ERROR) << "failed to enable AOD sensor";
                    } else {
                        sensorEnabled = true;
                    }
                }
                break;
            }

            case MI_DISP_POWER_ON:
                requestDozeBrightness(
                        disp_fd_.get(),
                        DOZE_TO_NORMAL,
                        response->base.disp_id);
                FALLTHROUGH_INTENDED;

            default: {
                bool shouldDisable = false;

                {
                    std::lock_guard<std::mutex> lock(displayMutex);
                    activeDisplays.erase(response->base.disp_id);
                    shouldDisable = activeDisplays.empty();
                }

                if (shouldDisable && sensorEnabled) {
                    auto result =
                            mQueue->disableSensor(mSensorHandle);
                    if (!result.isOk()) {
                        LOG(ERROR) << "disableSensor transaction failed: "
                                   << result.description();
                    } else if (result != Result::OK) {
                        /*
                         * A special-trigger sensor can already have
                         * disabled itself after delivering an event.
                         */
                        LOG(VERBOSE) << "AOD sensor was already disabled";
                    }

                    sensorEnabled = false;
                }
                break;
            }
        }
    }

    {
        std::lock_guard<std::mutex> lock(displayMutex);
        activeDisplays.clear();
    }

    if (sensorEnabled) {
        auto result = mQueue->disableSensor(mSensorHandle);
        if (!result.isOk()) {
            LOG(ERROR) << "disableSensor transaction failed during shutdown: "
                       << result.description();
        }
    }
}
