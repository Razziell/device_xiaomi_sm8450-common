/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (C) 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.thermal

import java.io.File
import org.lineageos.settings.utils.Logging

/** Owns access to the device thermal profile sysfs node. */
class ThermalSysfsController(
    private val configFile: File = File(THERMAL_SCONFIG)
) {
    fun writeConfig(config: String): Boolean = runCatching {
        configFile.bufferedWriter().use { writer ->
            writer.write(config)
        }
    }.onFailure {
        Logging.e(TAG, "Failed to write $config to ${configFile.path}", it)
    }.isSuccess

    private companion object {
        const val TAG = "ThermalSysfsController"
        const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
    }
}
