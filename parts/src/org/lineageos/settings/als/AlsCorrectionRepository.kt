/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import android.content.Context
import android.os.SystemProperties
import android.provider.Settings
import java.util.Locale

/** Property values are consumed on ALS callbacks or background content refreshes. */
object AlsCorrectionSpec {
    const val PROP_IMPL_CLASS = "ro.vendor.display.als_correction_class"
    const val PROP_STATE = "sys.als_correction.state"
    const val PROP_ENABLED = "persist.sys.als_correction.enabled"
    const val PROP_K = "persist.sys.als_correction.k"
    const val PROP_REF_LUMA = "persist.sys.als_correction.ref_luma"
    const val PROP_GAMMA = "persist.sys.als_correction.gamma"
    const val PROP_INTERVAL = "persist.sys.als_correction.interval_ms"

    /* Defaults and hard limits must match MarbleAlsCorrection. */
    const val K_DEFAULT = 650
    const val REF_LUMA_DEFAULT = 0.14f
    const val GAMMA_DEFAULT = 2.2f
    const val INTERVAL_DEFAULT = 500
    val K_HARD_RANGE = 0..1500
    val REF_LUMA_HARD_RANGE = 0f..1f
    val GAMMA_HARD_RANGE = 1f..4f
    val INTERVAL_HARD_RANGE = 100..5000

    /* Recommended editing ranges. Reading must not hide effective values outside these. */
    val K_RANGE = 400..900
    const val K_STEP = 10
    val REF_LUMA_RANGE = 0.05f..0.30f
    const val REF_LUMA_STEP = 0.01f
    val GAMMA_RANGE = 1.8f..2.6f
    const val GAMMA_STEP = 0.1f
    val INTERVAL_CHOICES = intArrayOf(250, 500)

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

data class AlsCorrectionSnapshot(
    val settings: AlsCorrectionSettings,
    val state: String,
    val autoBrightnessEnabled: Boolean
)

class AlsCorrectionRepository(context: Context) {
    private val context = context.applicationContext

    val isAvailable: Boolean
        get() = SystemProperties.get(AlsCorrectionSpec.PROP_IMPL_CLASS, "").isNotEmpty()

    /** Migrate the retired power-saving preset; keep every other override unchanged. */
    fun migrateLegacyInterval(): Result<Unit> = runCatching {
        if (isAvailable && SystemProperties.getInt(AlsCorrectionSpec.PROP_INTERVAL, -1) == 1000) {
            SystemProperties.set(AlsCorrectionSpec.PROP_INTERVAL, "500")
        }
    }

    fun readSnapshot(): AlsCorrectionSnapshot {
        migrateLegacyInterval().getOrThrow()
        return AlsCorrectionSnapshot(
            settings = readSettings(),
            state = SystemProperties.get(AlsCorrectionSpec.PROP_STATE, ""),
            autoBrightnessEnabled = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
    }

    /** Reflect the corrector's hard clamps, not the narrower slider ranges. No writes on read. */
    fun readSettings() = AlsCorrectionSettings(
        enabled = SystemProperties.getBoolean(AlsCorrectionSpec.PROP_ENABLED, true),
        k = SystemProperties.getInt(AlsCorrectionSpec.PROP_K, AlsCorrectionSpec.K_DEFAULT)
            .coerceIn(AlsCorrectionSpec.K_HARD_RANGE),
        refLuma = getFloat(AlsCorrectionSpec.PROP_REF_LUMA, AlsCorrectionSpec.REF_LUMA_DEFAULT)
            .coerceIn(AlsCorrectionSpec.REF_LUMA_HARD_RANGE),
        gamma = getFloat(AlsCorrectionSpec.PROP_GAMMA, AlsCorrectionSpec.GAMMA_DEFAULT)
            .coerceIn(AlsCorrectionSpec.GAMMA_HARD_RANGE),
        intervalMs = SystemProperties.getInt(
            AlsCorrectionSpec.PROP_INTERVAL, AlsCorrectionSpec.INTERVAL_DEFAULT
        ).coerceIn(AlsCorrectionSpec.INTERVAL_HARD_RANGE)
    )

    fun setEnabled(enabled: Boolean): Result<Unit> {
        val result = set(AlsCorrectionSpec.PROP_ENABLED, enabled.toString())
        if (result.isSuccess) {
            // Notify after persistence, not on Activity.onPause (which may precede the write).
            AlsCorrectionSwitchProvider.notifyChanged(context)
        }
        return result
    }

    fun setK(k: Int): Result<Unit> = set(
        AlsCorrectionSpec.PROP_K, k.coerceIn(AlsCorrectionSpec.K_RANGE).toString()
    )

    fun setRefLuma(value: Float): Result<Unit> = setFloat(
        AlsCorrectionSpec.PROP_REF_LUMA, value, AlsCorrectionSpec.REF_LUMA_RANGE
    )

    fun setGamma(value: Float): Result<Unit> = setFloat(
        AlsCorrectionSpec.PROP_GAMMA, value, AlsCorrectionSpec.GAMMA_RANGE
    )

    fun setIntervalMs(intervalMs: Int): Result<Unit> = runCatching {
        require(intervalMs in AlsCorrectionSpec.INTERVAL_CHOICES)
        SystemProperties.set(AlsCorrectionSpec.PROP_INTERVAL, intervalMs.toString())
    }

    fun resetK(): Result<Unit> = clear(AlsCorrectionSpec.PROP_K)
    fun resetRefLuma(): Result<Unit> = clear(AlsCorrectionSpec.PROP_REF_LUMA)
    fun resetGamma(): Result<Unit> = clear(AlsCorrectionSpec.PROP_GAMMA)
    fun resetIntervalMs(): Result<Unit> = clear(AlsCorrectionSpec.PROP_INTERVAL)

    /** Reset only parameters exposed here. Preserve enable state and sensor geometry overrides. */
    fun resetParameters(): Result<Unit> = runCatching {
        for (property in arrayOf(
            AlsCorrectionSpec.PROP_K,
            AlsCorrectionSpec.PROP_REF_LUMA,
            AlsCorrectionSpec.PROP_GAMMA,
            AlsCorrectionSpec.PROP_INTERVAL
        )) {
            SystemProperties.set(property, "")
        }
    }

    private fun clear(property: String): Result<Unit> = set(property, "")

    private fun set(property: String, value: String): Result<Unit> = runCatching {
        SystemProperties.set(property, value)
    }

    private fun setFloat(property: String, value: Float, range: ClosedFloatingPointRange<Float>) =
        runCatching {
            require(value.isFinite())
            // The property parser needs a dot. Display formatting uses the user's locale instead.
            val text = String.format(Locale.US, "%.2f", value.coerceIn(range))
            SystemProperties.set(property, text)
        }

    private fun getFloat(property: String, default: Float): Float =
        SystemProperties.get(property, "").toFloatOrNull()?.takeIf { it.isFinite() } ?: default
}
