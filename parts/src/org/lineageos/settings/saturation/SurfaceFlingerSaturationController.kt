/*
 * Copyright (C) 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.saturation

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.os.SystemProperties
import android.util.Log

/** Isolates the device-specific SurfaceFlinger transaction from UI code. */
class SurfaceFlingerSaturationController {

    @Volatile
    private var surfaceFlinger: IBinder? = null

    @Synchronized
    fun apply(value: Int): Result<Unit> = runCatching {
        require(value in SATURATION_MIN..SATURATION_MAX) {
            "Saturation value is out of range: $value"
        }

        val binder = getSurfaceFlinger()
            ?: error("SurfaceFlinger service is unavailable")
        val data = Parcel.obtain()
        val saturation = value.toSurfaceFlingerValue()

        try {
            data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE)
            data.writeFloat(saturation)

            check(binder.transact(SET_SATURATION_TRANSACTION, data, null, 0)) {
                "SurfaceFlinger rejected the saturation transaction"
            }
            publishApplied(saturation)
        } catch (error: Exception) {
            surfaceFlinger = null
            throw error
        } finally {
            data.recycle()
        }
    }.onFailure {
        Log.e(TAG, "Failed to apply saturation value $value", it)
    }

    /**
     * SurfaceFlinger cannot be asked for its saturation. The under-display ALS correction needs
     * the value to know what the panel emits. Failing to publish it must not fail the change.
     */
    private fun publishApplied(saturation: Float) {
        runCatching { SystemProperties.set(APPLIED_SATURATION_PROPERTY, saturation.toString()) }
            .onFailure { Log.w(TAG, "Cannot publish the applied saturation", it) }
    }

    private fun getSurfaceFlinger(): IBinder? {
        val cached = surfaceFlinger
        if (cached?.isBinderAlive == true) return cached

        return ServiceManager.getService(SURFACE_FLINGER_SERVICE).also {
            surfaceFlinger = it
        }
    }

    private fun Int.toSurfaceFlingerValue(): Float =
        if (this == SATURATION_DEFAULT) 1.001f else this / 100f

    private companion object {
        const val TAG = "SaturationController"
        const val SURFACE_FLINGER_SERVICE = "SurfaceFlinger"
        const val SURFACE_COMPOSER_INTERFACE = "android.ui.ISurfaceComposer"
        const val SET_SATURATION_TRANSACTION = 1022
        const val APPLIED_SATURATION_PROPERTY = "sys.parts.sf_saturation"
    }
}

internal const val SATURATION_MIN = 0
internal const val SATURATION_MAX = 200
internal const val SATURATION_DEFAULT = 100
internal const val SATURATION_PREFERENCE_KEY = "saturation"
