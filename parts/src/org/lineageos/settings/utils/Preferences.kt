/*
 * Copyright (C) 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Returns the same preferences file historically used by
 * PreferenceManager.getDefaultSharedPreferences().
 */
fun Context.xiaomiPartsPreferences(): SharedPreferences {
    val appContext = applicationContext
    return appContext.getSharedPreferences(
        "${appContext.packageName}_preferences",
        Context.MODE_PRIVATE
    )
}
