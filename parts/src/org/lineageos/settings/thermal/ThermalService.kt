/*
 * SPDX-FileCopyrightText: 2020 The LineageOS Project
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.thermal

import android.app.ActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import org.lineageos.settings.utils.Logging

/** Service to monitor current top (foreground) app and set thermal profile accordingly. */
class ThermalService : Service() {

    private lateinit var thermalUtils: ThermalUtils

    private var taskListenerRegistered = false
    private var receiverRegistered = false

    private var currentApp = ""
        set(value) {
            if (field == value) return
            field = value
            Logging.d(TAG, "Top app changed: $value")
            setThermalProfile()
        }

    private var screenOn = true
        set(value) {
            if (field == value) return
            field = value
            Logging.d(TAG, "Screen state changed: $value")
            setThermalProfile()
        }

    private val taskListener =
        object : TaskStackListener() {
            override fun onTaskStackChanged() {
                updateCurrentApp()
            }
        }

    private val intentReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                    Intent.ACTION_SCREEN_ON -> screenOn = true
                }
            }
        }

    override fun onCreate() {
        Logging.d(TAG, "Creating service")
        super.onCreate()
        thermalUtils = ThermalUtils.getInstance(this)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    override fun onDestroy() {
        Logging.d(TAG, "Destroying service")

        if (receiverRegistered) {
            runCatching {
                unregisterReceiver(intentReceiver)
            }.onFailure {
                Logging.e(TAG, "Failed to unregister screen receiver", it)
            }
            receiverRegistered = false
        }

        if (taskListenerRegistered) {
            runCatching {
                ActivityTaskManager.getService().unregisterTaskStackListener(taskListener)
            }.onFailure {
                Logging.e(TAG, "Failed to unregister task stack listener", it)
            }
            taskListenerRegistered = false
        }

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logging.d(TAG, "Starting service")

        if (!taskListenerRegistered) {
            runCatching {
                ActivityTaskManager.getService().registerTaskStackListener(taskListener)
                taskListenerRegistered = true
            }.onFailure {
                Logging.e(TAG, "Failed to register task stack listener", it)
            }
        }

        if (!receiverRegistered) {
            runCatching {
                registerReceiver(
                    intentReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                    },
                )
                receiverRegistered = true
            }.onFailure {
                Logging.e(TAG, "Failed to register screen receiver", it)
            }
        }

        updateCurrentApp()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateCurrentApp() {
        runCatching {
            val focusedTask = ActivityTaskManager.getService().focusedRootTaskInfo
            focusedTask?.topActivity?.let { currentApp = it.packageName }
        }.onFailure {
            Logging.e(TAG, "Failed to update current app", it)
        }
    }

    private fun setThermalProfile() {
        if (screenOn) {
            thermalUtils.setThermalProfile(currentApp)
        } else {
            thermalUtils.setDefaultThermalProfile()
        }
    }

    companion object {
        private const val TAG = "ThermalService"
    }
}
