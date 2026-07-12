/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "SscCalApiWrapper"

#include "SscCalApi.h"

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <dlfcn.h>

namespace {

constexpr const char* kDebugProperty =
        "persist.vendor.debug.sensor_notifier";

bool isDebugEnabled() {
    return android::base::GetBoolProperty(kDebugProperty, false);
}

}  // namespace

SscCalApiWrapper::SscCalApiWrapper() {
    mSscCalApiHandle = dlopen("libssccalapi@2.0.so", RTLD_NOW);
    if (mSscCalApiHandle == nullptr) {
        LOG(ERROR) << "could not dlopen libssccalapi@2.0.so: " << dlerror();
        return;
    }

    dlerror();
    init_current_sensors = reinterpret_cast<init_current_sensors_t>(
            dlsym(mSscCalApiHandle, "_Z20init_current_sensorsb"));
    const char* error = dlerror();
    if (error != nullptr) {
        init_current_sensors = nullptr;
        LOG(ERROR) << "could not find init_current_sensors: " << error;
    }

    dlerror();
    process_msg = reinterpret_cast<process_msg_t>(
            dlsym(mSscCalApiHandle, "_Z11process_msgP8_oem_msg"));
    error = dlerror();
    if (error != nullptr) {
        process_msg = nullptr;
        LOG(ERROR) << "could not find process_msg: " << error;
    }
}

SscCalApiWrapper::~SscCalApiWrapper() {
    std::lock_guard<std::mutex> lock(mMutex);

    process_msg = nullptr;
    init_current_sensors = nullptr;

    if (mSscCalApiHandle != nullptr) {
        dlclose(mSscCalApiHandle);
        mSscCalApiHandle = nullptr;
    }
}

SscCalApiWrapper& SscCalApiWrapper::getInstance() {
    static SscCalApiWrapper instance;
    return instance;
}

void SscCalApiWrapper::initCurrentSensors(bool debug) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (init_current_sensors != nullptr) {
        if (isDebugEnabled()) {
            LOG(INFO) << "initializing current SSC sensors, debug=" << debug;
        }

        init_current_sensors(debug);
    }
}

void SscCalApiWrapper::processMsg(_oem_msg* msg) {
    if (msg == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(mMutex);

    if (process_msg != nullptr) {
        /*
         * Read the property dynamically. It can be enabled and disabled
         * through adb without restarting sensor-notifier.
         */
        if (isDebugEnabled()) {
            LOG(INFO) << "sending oem_msg for sensor " << msg->sensorType
                      << " with type: " << msg->notifyType
                      << " and value: " << msg->value;
        }

        process_msg(msg);
    }
}
