/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.lineageos.settings.saturation.SaturationRepository
import org.lineageos.settings.saturation.SurfaceFlingerSaturationController
import org.lineageos.settings.thermal.ThermalUtils

/** Restores device-specific settings after direct boot and retries after full boot. */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        Log.d(TAG, "Restoring XiaomiParts for ${intent.action}")

        // Binder calls to SurfaceFlinger and ActivityManager do not belong on
        // the main thread; goAsync() keeps the process alive until done.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        Thread({
            try {
                runCatching {
                    ThermalUtils.getInstance(appContext).startService()
                }.onFailure {
                    Log.e(TAG, "Failed to start thermal profile service", it)
                }

                // LOCKED_BOOT_COMPLETED already restored saturation; only retry
                // on BOOT_COMPLETED if the first attempt failed.
                if (!saturationRestored) {
                    SaturationRepository(
                        appContext,
                        SurfaceFlingerSaturationController()
                    ).restore()
                        .onSuccess { saturationRestored = true }
                        .onFailure {
                            Log.e(TAG, "Failed to restore display saturation", it)
                        }
                }
            } finally {
                pendingResult.finish()
            }
        }, "$TAG-restore").start()
    }

    private companion object {
        const val TAG = "XiaomiPartsBoot"

        @Volatile
        var saturationRestored = false
    }
}
