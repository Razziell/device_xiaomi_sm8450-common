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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.lineageos.settings.utils.Logging

/**
 * Service to monitor the current top (foreground) app and set the thermal
 * profile accordingly.
 *
 * All profile evaluation is serialized on a dedicated background thread:
 * task-stack callbacks arrive on binder threads and screen broadcasts on the
 * main thread, so the work is posted to [handler] to avoid races and to keep
 * PackageManager/sysfs work off the main thread.
 */
class ThermalService : Service() {

    private lateinit var thermalUtils: ThermalUtils
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var taskListenerRegistered = false
    private var receiverRegistered = false

    // Accessed only on the handler thread.
    private var currentApp = ""
    private var screenOn = true

    private val taskListener =
        object : TaskStackListener() {
            override fun onTaskStackChanged() {
                // Called on a binder thread; serialize onto the handler thread.
                handler.post { updateCurrentApp(forceApply = false) }
            }
        }

    private val intentReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Delivered on the handler thread via the registerReceiver scheduler.
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> setScreenOn(false)
                    Intent.ACTION_SCREEN_ON -> {
                        // The vendor thermal daemon may rewrite sconfig while the
                        // screen is off; force the next apply to hit sysfs.
                        thermalUtils.invalidateAppliedConfig()
                        setScreenOn(true)
                    }
                }
            }
        }

    override fun onCreate() {
        Logging.d(TAG, "Creating service")
        super.onCreate()
        thermalUtils = ThermalUtils.getInstance(this)
        handlerThread = HandlerThread(TAG).also { it.start() }
        handler = Handler(handlerThread.looper)

        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        handler.post { screenOn = interactive }
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

        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logging.d(TAG, "Starting service")

        if (!thermalUtils.enabled) {
            Logging.d(TAG, "Thermal profiles are disabled; stopping service")
            stopSelf(startId)
            return START_NOT_STICKY
        }

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
                    /* broadcastPermission = */ null,
                    /* scheduler = */ handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }.onFailure {
                Logging.e(TAG, "Failed to register screen receiver", it)
            }
        }

        handler.post {
            thermalUtils.invalidateAppliedConfig()
            updateCurrentApp(forceApply = true)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setScreenOn(value: Boolean) {
        if (screenOn == value) return
        screenOn = value
        Logging.d(TAG, "Screen state changed: $value")
        setThermalProfile()
    }

    private fun updateCurrentApp(forceApply: Boolean) {
        runCatching {
            val focusedTask = ActivityTaskManager.getService().focusedRootTaskInfo
            val packageName = focusedTask?.topActivity?.packageName.orEmpty()
            if (currentApp == packageName) {
                if (forceApply) setThermalProfile()
            } else {
                currentApp = packageName
                Logging.d(TAG, "Top app changed: $packageName")
                setThermalProfile()
            }
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
