/*
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.thermal

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lineageos.settings.thermal.ThermalUtils.ThermalState
import org.lineageos.settings.thermal.model.AppThermalState
import org.lineageos.settings.thermal.model.ThermalUiState
import org.lineageos.settings.utils.Logging

class ThermalViewModel(
    private val appContext: Context,
    private val launcherApps: LauncherApps
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThermalUiState())
    val uiState: StateFlow<ThermalUiState> = _uiState.asStateFlow()

    private val ioMutex = Mutex()
    private var launcherCallbackRegistered = false

    private fun thermalUtils(): ThermalUtils = ThermalUtils.getInstance(appContext)

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            if (user != Process.myUserHandle()) return
            Logging.d(TAG, "onPackageRemoved: $packageName")
            removeApp(packageName)
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            if (user != Process.myUserHandle()) return
            Logging.d(TAG, "onPackageAdded: $packageName")
            viewModelScope.launch {
                addNewApp(packageName)
            }
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            // No action needed
        }

        override fun onPackagesAvailable(
            packageNames: Array<String>,
            user: UserHandle,
            replacing: Boolean
        ) {
            // No action needed
        }

        override fun onPackagesUnavailable(
            packageNames: Array<String>,
            user: UserHandle,
            replacing: Boolean
        ) {
            // No action needed
        }
    }

    init {
        viewModelScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                ioMutex.withLock { thermalUtils().enabled }
            }
            _uiState.update { it.copy(isEnabled = enabled) }
            if (enabled && _uiState.value.apps.isEmpty()) {
                loadApps()
            }
        }

        // The no-handler overload binds to the current (main) Looper.
        runCatching {
            launcherApps.registerCallback(launcherAppsCallback)
        }.onSuccess {
            launcherCallbackRegistered = true
            Logging.d(TAG, "LauncherApps callback registered")
        }.onFailure {
            Logging.e(TAG, "Failed to register LauncherApps callback", it)
        }
    }

    fun toggleThermalEnabled(enabled: Boolean) {
        Logging.d(TAG, "toggleThermalEnabled: $enabled")
        _uiState.update { it.copy(isEnabled = enabled) }

        viewModelScope.launch(Dispatchers.IO) {
            // Persists the switch and, when disabling, writes the default
            // profile to sysfs; serialized and kept off the main thread.
            ioMutex.withLock {
                thermalUtils().enabled = enabled
            }
        }

        if (enabled && _uiState.value.apps.isEmpty()) {
            loadApps()
        }
    }

    fun updateAppThermalState(packageName: String, state: ThermalState) {
        Logging.d(TAG, "updateAppThermalState: $packageName -> $state")
        viewModelScope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                thermalUtils().writePackage(packageName, state.id)
            }
        }

        // Update UI state
        _uiState.update { currentState ->
            currentState.copy(
                apps = currentState.apps.map { app ->
                    if (app.packageName == packageName) {
                        app.copy(currentState = state)
                    } else {
                        app
                    }
                }
            )
        }
    }

    fun resetProfiles() {
        Logging.d(TAG, "resetProfiles")
        viewModelScope.launch {
            val existingApps = _uiState.value.apps
            if (existingApps.isEmpty()) {
                withContext(Dispatchers.IO) {
                    ioMutex.withLock { thermalUtils().resetProfiles() }
                }
                loadApps()
                return@launch
            }

            val states = withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    val utils = thermalUtils()
                    utils.resetProfiles()
                    utils.resolveStates(existingApps.map { it.packageName })
                }
            }
            _uiState.update { currentState ->
                currentState.copy(
                    apps = currentState.apps.map { app ->
                        app.copy(currentState = states[app.packageName] ?: ThermalState.DEFAULT)
                    }
                )
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val apps = withContext(Dispatchers.IO) {
                    val activities = launcherApps
                        .getActivityList(null, Process.myUserHandle())
                        .distinctBy { it.componentName.packageName }

                    // Resolve every profile in one pass; expensive PackageManager
                    // queries are shared across all packages.
                    val states = ioMutex.withLock {
                        thermalUtils().resolveStates(
                            activities.map { it.componentName.packageName }
                        )
                    }

                    val collator = Collator.getInstance()
                    activities
                        .map { info ->
                            val packageName = info.componentName.packageName
                            AppThermalState(
                                packageName = packageName,
                                label = info.label.toString(),
                                icon = info.getIcon(0).toImageBitmap(ICON_SIZE_PX),
                                currentState = states.getValue(packageName)
                            )
                        }
                        .sortedWith(compareBy(collator) { it.label })
                }

                Logging.d(TAG, "Loaded ${apps.size} apps")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        apps = apps,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error loading apps", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun removeApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                thermalUtils().removePackage(packageName)
            }
        }
        _uiState.update { currentState ->
            currentState.copy(
                apps = currentState.apps.filter { it.packageName != packageName }
            )
        }
    }

    private suspend fun addNewApp(packageName: String) {
        withContext(Dispatchers.IO) {
            val info = launcherApps.getActivityList(packageName, Process.myUserHandle())
                .firstOrNull() ?: return@withContext

            val state = ioMutex.withLock {
                thermalUtils().getStateForPackage(info.componentName.packageName)
            }
            val newApp = AppThermalState(
                packageName = info.componentName.packageName,
                label = info.label.toString(),
                icon = info.getIcon(0).toImageBitmap(ICON_SIZE_PX),
                currentState = state
            )

            val collator = Collator.getInstance()
            _uiState.update { currentState ->
                // Check if app already exists
                if (currentState.apps.any { it.packageName == packageName }) {
                    return@update currentState
                }

                // Insert in sorted position
                val updatedApps = (currentState.apps + newApp)
                    .sortedWith(compareBy(collator) { it.label })

                currentState.copy(apps = updatedApps)
            }
        }
    }

    override fun onCleared() {
        if (launcherCallbackRegistered) {
            runCatching {
                launcherApps.unregisterCallback(launcherAppsCallback)
            }.onFailure {
                Logging.e(TAG, "Failed to unregister LauncherApps callback", it)
            }
        }
        Logging.d(TAG, "ViewModel cleared")
        super.onCleared()
    }

    companion object {
        private const val TAG = "ThermalViewModel"

        /** Fixed raster size for app icons shown in the list (40dp at ~3x). */
        private const val ICON_SIZE_PX = 128

        private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
            return bitmap.asImageBitmap()
        }
    }
}

/**
 * Factory for creating ThermalViewModel with dependencies.
 */
class ThermalViewModelFactory(
    private val appContext: Context,
    private val launcherApps: LauncherApps
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ThermalViewModel::class.java)) {
            return ThermalViewModel(appContext, launcherApps) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
