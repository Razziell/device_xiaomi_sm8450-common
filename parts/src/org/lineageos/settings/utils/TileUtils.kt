/*
 * Copyright (C) 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.utils

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.lineageos.settings.R

object TileUtils {
    fun requestAddTileService(
        context: Context,
        tileServiceClass: Class<*>,
        @StringRes labelResId: Int,
        @DrawableRes iconResId: Int
    ) {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
        statusBarManager.requestAddTileService(
            ComponentName(context, tileServiceClass),
            context.getString(labelResId),
            Icon.createWithResource(context, iconResId),
            context.mainExecutor
        ) { result -> handleResult(context.applicationContext, result) }
    }

    private fun handleResult(context: Context, result: Int) {
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tile_not_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                R.string.tile_already_added
            else -> return
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
