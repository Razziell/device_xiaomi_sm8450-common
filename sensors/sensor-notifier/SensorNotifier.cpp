/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "SensorNotifier"

#include "SensorNotifier.h"

#include <android-base/logging.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <sys/eventfd.h>
#include <unistd.h>

using android::hardware::sensors::V1_0::SensorFlagBits;
using android::hardware::sensors::V1_0::SensorInfo;

SensorNotifier::SensorNotifier(sp<ISensorManager> manager) : mManager(manager) {
    mStopEventFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (mStopEventFd < 0) {
        PLOG(FATAL) << "failed to create stop eventfd";
    }
}

SensorNotifier::~SensorNotifier() {
    /*
     * Derived destructors call deactivate() before reaching this destructor.
     * Keep this fallback to avoid destroying a joinable std::thread.
     */
    mActive.store(false);

    if (mStopEventFd >= 0) {
        uint64_t value = 1;
        ssize_t rc = write(mStopEventFd, &value, sizeof(value));
        if (rc < 0 && errno != EAGAIN) {
            PLOG(ERROR) << "failed to signal stop eventfd";
        }
    }

    if (mThread.joinable()) {
        mThread.join();
    }

    if (mQueue != nullptr) {
        /*
         * Free the event queue.
         * kernel calls decStrong() on server side implementation of IEventQueue,
         * hence resources (including the callback) are freed as well.
         */
        mQueue = nullptr;
    }

    if (mStopEventFd >= 0) {
        close(mStopEventFd);
        mStopEventFd = -1;
    }
}

Result SensorNotifier::initializeSensorQueue(std::string typeAsString, bool wakeup,
                                             sp<IEventQueueCallback> callback) {
    Result res = Result::UNKNOWN_ERROR;
    std::vector<SensorInfo> sensorList;

    auto status = mManager->getSensorList([&sensorList, &res](const auto& l, auto r) {
        sensorList = l;
        res = r;
    });
    if (!status.isOk()) {
        LOG(ERROR) << "getSensorList transaction failed: " << status.description();
        return Result::UNKNOWN_ERROR;
    }
    if (res != Result::OK) {
        LOG(ERROR) << "failed to get sensors list";
        return res;
    }

    auto it = std::find_if(sensorList.begin(), sensorList.end(),
                           [&typeAsString, &wakeup](const SensorInfo& sensor) {
                               return (sensor.typeAsString == typeAsString) &&
                                      ((sensor.flags & SensorFlagBits::WAKE_UP) == wakeup);
                           });

    if (it != sensorList.end()) {
        mSensorHandle = it->sensorHandle;
    } else {
        LOG(ERROR) << "failed to get " << typeAsString << " sensor with wake-up: " << wakeup;
        return Result::NOT_EXIST;
    }

    status = mManager->createEventQueue(callback, [this, &res](const auto& q, auto r) {
        mQueue = q;
        res = r;
    });
    if (!status.isOk()) {
        LOG(ERROR) << "createEventQueue transaction failed: " << status.description();
        return Result::UNKNOWN_ERROR;
    }
    if (res != Result::OK) {
        LOG(ERROR) << "failed to create event queue";
        return res;
    }

    return Result::OK;
}

void SensorNotifier::activate() {
    bool expected = false;
    if (!mActive.compare_exchange_strong(expected, true)) {
        return;
    }

    consumeStopEvent();

    if (mThread.joinable()) {
        mThread.join();
    }

    mThread = std::thread(&SensorNotifier::notify, this);
}

void SensorNotifier::deactivate() {
    mActive.store(false);

    if (mStopEventFd >= 0) {
        uint64_t value = 1;
        ssize_t rc = write(mStopEventFd, &value, sizeof(value));
        if (rc < 0 && errno != EAGAIN) {
            PLOG(ERROR) << "failed to signal stop eventfd";
        }
    }

    if (mThread.joinable()) {
        mThread.join();
    }

    consumeStopEvent();

    if (mQueue != nullptr && mSensorHandle >= 0) {
        auto result = mQueue->disableSensor(mSensorHandle);
        if (!result.isOk()) {
            LOG(ERROR) << "disableSensor transaction failed: " << result.description();
        }
    }
}

bool SensorNotifier::isActive() const {
    return mActive.load();
}

int SensorNotifier::stopEventFd() const {
    return mStopEventFd;
}

void SensorNotifier::consumeStopEvent() {
    if (mStopEventFd < 0) {
        return;
    }

    uint64_t value;
    while (read(mStopEventFd, &value, sizeof(value)) == sizeof(value)) {
    }

    if (errno != EAGAIN && errno != EWOULDBLOCK) {
        PLOG(ERROR) << "failed to consume stop eventfd";
    }
}
