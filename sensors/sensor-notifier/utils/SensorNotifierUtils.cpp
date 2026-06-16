/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "SensorNotifierUtils"

#include "SensorNotifierUtils.h"

#include <android-base/logging.h>
#include <algorithm>
#include <cstring>

bool readBool(int fd) {
    char c;
    int rc;

    rc = lseek(fd, 0, SEEK_SET);
    if (rc) {
        LOG(ERROR) << "failed to seek fd, err: " << rc;
        return false;
    }

    rc = read(fd, &c, sizeof(char));
    if (rc != 1) {
        LOG(ERROR) << "failed to read bool from fd, err: " << rc;
        return false;
    }

    return c != '0';
}

std::shared_ptr<disp_event_resp> parseDispEvent(int fd) {
    disp_event header;
    ssize_t headerSize = read(fd, &header, sizeof(header));
    if (headerSize < sizeof(header)) {
        LOG(ERROR) << "unexpected display event header size: " << headerSize;
        return nullptr;
    }

    int dataLength = header.length - sizeof(header);
    if (dataLength < 0) {
        LOG(ERROR) << "invalid data length: " << header.length;
        return nullptr;
    }

    // Allocate enough memory to prevent OOB read when accessing response->data[0] on empty payload
    size_t allocSize = std::max<size_t>(header.length, sizeof(disp_event_resp));
    std::shared_ptr<disp_event_resp> response(
            static_cast<disp_event_resp*>(malloc(allocSize)), free);

    if (!response) {
        LOG(ERROR) << "failed to allocate memory for display event response";
        return nullptr;
    }

    response->base = header;

    // Zero-initialize payload area to prevent garbage values
    if (allocSize > sizeof(disp_event)) {
        memset(response->data, 0, allocSize - sizeof(disp_event));
    }

    if (dataLength > 0) {
        ssize_t dataSize = read(fd, response->data, dataLength);
        if (dataSize < dataLength) {
            LOG(ERROR) << "unexpected display event data size: " << dataSize;
            return nullptr;
        }
    }

    return response;
}
