/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>
#include <mutex>
#include <type_traits>

enum notify_t {
    BRIGHTNESS = 17,
    DC_STATE = 18,
    DISPLAY_FREQUENCY = 20,
    REPORT_VALUE = 201,
    POWER_STATE = 202,
};

struct _oem_msg {
    uint32_t sensorType;
    notify_t notifyType;
    float unknown1;
    float unknown2;
    float notifyTypeFloat;
    float value;

    // Add padding up to 264 bytes
    float unused[60];
};

static_assert(sizeof(notify_t) == sizeof(int32_t),
              "notify_t must remain 32-bit");
static_assert(sizeof(_oem_msg) == 264,
              "_oem_msg ABI size must remain 264 bytes");
static_assert(std::is_standard_layout_v<_oem_msg>,
              "_oem_msg must remain a standard-layout type");

typedef void (*init_current_sensors_t)(bool debug);
typedef void (*process_msg_t)(_oem_msg* msg);

class SscCalApiWrapper {
  public:
    static SscCalApiWrapper& getInstance();

    void initCurrentSensors(bool debug);
    void processMsg(_oem_msg* msg);

    SscCalApiWrapper(const SscCalApiWrapper&) = delete;
    SscCalApiWrapper& operator=(const SscCalApiWrapper&) = delete;
    SscCalApiWrapper(SscCalApiWrapper&&) = delete;
    SscCalApiWrapper& operator=(SscCalApiWrapper&&) = delete;

  private:
    SscCalApiWrapper();
    ~SscCalApiWrapper();

    void* mSscCalApiHandle = nullptr;
    process_msg_t process_msg = nullptr;
    init_current_sensors_t init_current_sensors = nullptr;
    std::mutex mMutex;
};
