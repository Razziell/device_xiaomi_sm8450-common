/*
 * Copyright (C) 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.saturation

import android.content.Context
import org.lineageos.settings.utils.xiaomiPartsPreferences

class SaturationRepository(
    context: Context,
    private val controller: SurfaceFlingerSaturationController
) {
    private val preferences = context.xiaomiPartsPreferences()

    fun getSavedValue(): Int = preferences
        .getInt(SATURATION_PREFERENCE_KEY, SATURATION_DEFAULT)
        .coerceIn(SATURATION_MIN, SATURATION_MAX)

    fun saveValue(value: Int) {
        preferences.edit()
            .putInt(SATURATION_PREFERENCE_KEY, value.coerceIn(SATURATION_MIN, SATURATION_MAX))
            .apply()
    }

    fun applyValue(value: Int): Result<Unit> = controller.apply(value)

    fun restore(): Result<Unit> = applyValue(getSavedValue())
}
