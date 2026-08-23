/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import android.os.SystemProperties
import java.util.Locale

/**
 * Tunables of the under-display ALS content correction running inside
 * system_server (see device/xiaomi/marble/als-correction).
 *
 * All values are plain persistent system properties. The corrector re-reads
 * them on every ALS event and additionally clamps them to hard hardware
 * limits, so writes below take effect immediately and cannot break
 * auto-brightness. An empty property value means "use the compiled default".
 */
object AlsCorrectionSpec {
    const val PROP_IMPL_CLASS = "ro.vendor.display.als_correction_class"
    const val PROP_STATE = "sys.als_correction.state"

    const val PROP_ENABLED = "persist.sys.als_correction.enabled"
    const val PROP_K = "persist.sys.als_correction.k"
    const val PROP_REF_LUMA = "persist.sys.als_correction.ref_luma"
    const val PROP_GAMMA = "persist.sys.als_correction.gamma"
    const val PROP_INTERVAL = "persist.sys.als_correction.interval_ms"

    /* Compiled defaults of MarbleAlsCorrection. */
    const val K_DEFAULT = 650
    const val REF_LUMA_DEFAULT = 0.14f
    const val GAMMA_DEFAULT = 2.2f
    const val INTERVAL_DEFAULT = 250

    /* Recommended user-facing ranges (narrower than the corrector clamps). */
    val K_RANGE = 400..900
    const val K_STEP = 10
    val REF_LUMA_RANGE = 0.05f..0.30f
    val GAMMA_RANGE = 1.8f..2.6f
    val INTERVAL_CHOICES = intArrayOf(250, 500, 1000)

    /* Values of PROP_STATE published by the corrector. */
    const val STATE_ACTIVE = "active"
    const val STATE_CAPTURE_FAILED = "capture_failed"
    const val STATE_IDLE = "idle"
    const val STATE_OFF = "off"
}

data class AlsCorrectionSettings(
    val enabled: Boolean = true,
    val k: Int = AlsCorrectionSpec.K_DEFAULT,
    val refLuma: Float = AlsCorrectionSpec.REF_LUMA_DEFAULT,
    val gamma: Float = AlsCorrectionSpec.GAMMA_DEFAULT,
    val intervalMs: Int = AlsCorrectionSpec.INTERVAL_DEFAULT
)

class AlsCorrectionRepository {

    val isAvailable: Boolean
        get() = SystemProperties.get(AlsCorrectionSpec.PROP_IMPL_CLASS, "").isNotEmpty()

    fun readState(): String = SystemProperties.get(AlsCorrectionSpec.PROP_STATE, "")

    fun readSettings() = AlsCorrectionSettings(
        enabled = SystemProperties.getBoolean(AlsCorrectionSpec.PROP_ENABLED, true),
        k = SystemProperties.getInt(AlsCorrectionSpec.PROP_K, AlsCorrectionSpec.K_DEFAULT)
            .coerceIn(AlsCorrectionSpec.K_RANGE),
        refLuma = getFloat(AlsCorrectionSpec.PROP_REF_LUMA, AlsCorrectionSpec.REF_LUMA_DEFAULT)
            .coerceIn(AlsCorrectionSpec.REF_LUMA_RANGE.start, AlsCorrectionSpec.REF_LUMA_RANGE.endInclusive),
        gamma = getFloat(AlsCorrectionSpec.PROP_GAMMA, AlsCorrectionSpec.GAMMA_DEFAULT)
            .coerceIn(AlsCorrectionSpec.GAMMA_RANGE.start, AlsCorrectionSpec.GAMMA_RANGE.endInclusive),
        intervalMs = SystemProperties.getInt(
            AlsCorrectionSpec.PROP_INTERVAL, AlsCorrectionSpec.INTERVAL_DEFAULT
        )
    )

    fun setEnabled(enabled: Boolean): Result<Unit> = set(
        AlsCorrectionSpec.PROP_ENABLED, if (enabled) "true" else "false"
    )

    fun setK(k: Int): Result<Unit> = set(
        AlsCorrectionSpec.PROP_K,
        k.coerceIn(AlsCorrectionSpec.K_RANGE).toString()
    )

    fun setRefLuma(refLuma: Float): Result<Unit> = set(
        AlsCorrectionSpec.PROP_REF_LUMA, formatFloat(refLuma)
    )

    fun setGamma(gamma: Float): Result<Unit> = set(
        AlsCorrectionSpec.PROP_GAMMA, formatFloat(gamma)
    )

    fun setIntervalMs(intervalMs: Int): Result<Unit> = set(
        AlsCorrectionSpec.PROP_INTERVAL, intervalMs.toString()
    )

    /** Clears one override; the corrector falls back to its compiled default. */
    fun resetK(): Result<Unit> = clear(AlsCorrectionSpec.PROP_K)

    fun resetRefLuma(): Result<Unit> = clear(AlsCorrectionSpec.PROP_REF_LUMA)

    fun resetGamma(): Result<Unit> = clear(AlsCorrectionSpec.PROP_GAMMA)

    fun resetIntervalMs(): Result<Unit> = clear(AlsCorrectionSpec.PROP_INTERVAL)

    /** Clears all overrides; the corrector falls back to compiled defaults. */
    fun resetAll(): Result<Unit> = runCatching {
        for (property in arrayOf(
            AlsCorrectionSpec.PROP_ENABLED,
            AlsCorrectionSpec.PROP_K,
            AlsCorrectionSpec.PROP_REF_LUMA,
            AlsCorrectionSpec.PROP_GAMMA,
            AlsCorrectionSpec.PROP_INTERVAL
        )) {
            SystemProperties.set(property, "")
        }
    }

    private fun clear(property: String): Result<Unit> = set(property, "")

    private fun set(property: String, value: String): Result<Unit> =
        runCatching { SystemProperties.set(property, value) }

    /**
     * The corrector parses floats with Float.parseFloat(), which only accepts
     * a dot as the decimal separator; never format with the user locale here.
     */
    private fun formatFloat(value: Float): String =
        String.format(Locale.US, "%.2f", value)

    private fun getFloat(property: String, default: Float): Float =
        SystemProperties.get(property, "").toFloatOrNull() ?: default
}
