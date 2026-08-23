/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemProperties

/**
 * Exposes the correction master switch to the Settings dashboard tile.
 *
 * Settings uses this provider to render the injected activity as a
 * PrimarySwitchPreference: tapping the row opens the detail screen while the
 * trailing switch changes the persistent correction property in place.
 */
class AlsCorrectionSwitchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_IS_CHECKED -> Bundle().apply {
            putBoolean(
                EXTRA_SWITCH_CHECKED_STATE,
                SystemProperties.getBoolean(AlsCorrectionSpec.PROP_ENABLED, true)
            )
        }

        METHOD_ON_CHECKED_CHANGED -> Bundle().apply {
            // A missing extras bundle must not stringify a null Boolean into "null".
            val checked = extras?.getBoolean(EXTRA_SWITCH_CHECKED_STATE, true) ?: true
            val result = runCatching {
                SystemProperties.set(AlsCorrectionSpec.PROP_ENABLED, checked.toString())
            }
            putBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR, result.isFailure)
            result.exceptionOrNull()?.message?.let {
                putString(EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE, it)
            }
            if (result.isSuccess) {
                context?.contentResolver?.notifyChange(IS_CHECKED_URI, null)
            }
        }

        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = throw UnsupportedOperationException()

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "org.lineageos.settings.als.switch"

        private const val KEY = "als_correction"
        private const val METHOD_IS_CHECKED = "isChecked"
        private const val METHOD_ON_CHECKED_CHANGED = "onCheckedChanged"

        private const val EXTRA_SWITCH_CHECKED_STATE = "checked_state"
        private const val EXTRA_SWITCH_SET_CHECKED_ERROR = "set_checked_error"
        private const val EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE = "set_checked_error_message"

        fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(IS_CHECKED_URI, null)
        }

        private val IS_CHECKED_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(METHOD_IS_CHECKED)
            .appendPath(KEY)
            .build()
    }
}
