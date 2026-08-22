/*
 * SPDX-FileCopyrightText: 2020 The LineageOS Project
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.thermal

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.telecom.DefaultDialerManager.getDefaultDialerApplication
import androidx.annotation.StringRes
import java.util.concurrent.ConcurrentHashMap
import org.lineageos.settings.R
import org.lineageos.settings.utils.Logging
import org.lineageos.settings.utils.xiaomiPartsPreferences

/** Helper utility class for thermal profiles. */
class ThermalUtils
private constructor(
    private val context: Context,
) {
    private val sharedPrefs = context.xiaomiPartsPreferences()
    private val serviceIntent = Intent(context, ThermalService::class.java)
    private val sysfsController = ThermalSysfsController()

    private val lock = Any()

    @Volatile
    private var lastAppliedConfig: String? = null

    @Volatile
    private var parsedPackages: Map<ThermalState, LinkedHashSet<String>>? = null

    @Volatile
    private var resolver: ResolverSnapshot? = null

    /** Cache of resolved default profiles, keyed by package name. */
    private val defaultStateCache = ConcurrentHashMap<String, ThermalState>()

    @Volatile
    var enabled: Boolean = sharedPrefs.getBoolean(THERMAL_ENABLED, false)
        set(value) {
            synchronized(lock) {
                if (field == value) return
                field = value
                sharedPrefs.edit().putBoolean(THERMAL_ENABLED, value).apply()
                if (value) {
                    startService()
                } else {
                    setDefaultThermalProfile()
                    stopService()
                }
            }
        }

    private var savedValue: String = sharedPrefs.getString(THERMAL_CONTROL, null) ?: DEFAULT_VALUE
        set(value) {
            if (field == value) return
            field = value
            parsedPackages = null
            sharedPrefs.edit().putString(THERMAL_CONTROL, value).apply()
        }

    fun startService() {
        if (enabled) {
            Logging.d(TAG, "startService")
            context.startService(serviceIntent)
        }
    }

    fun stopService() {
        Logging.d(TAG, "stopService")
        context.stopService(serviceIntent)
    }

    fun writePackage(packageName: String, mode: Int) {
        val selectedState = ThermalState.values().firstOrNull { it.id == mode }
        if (selectedState == null || packageName.isBlank()) {
            Logging.w(TAG, "Ignoring invalid package profile: $packageName -> $mode")
            return
        }

        synchronized(lock) {
            Logging.d(TAG, "writePackage: $packageName -> $selectedState")
            val packagesByState = parseValue(savedValue)
            packagesByState.values.forEach { it.remove(packageName) }
            packagesByState.getValue(selectedState).add(packageName)
            savedValue = serializePackages(packagesByState)
        }
        reapplyCurrentProfile()
    }

    fun getStateForPackage(packageName: String): ThermalState {
        savedStateLookup()[packageName]?.let { return it }
        return defaultStateCache.getOrPut(packageName) {
            resolveDefaultState(packageName, resolverSnapshot())
        }
    }

    /**
     * Resolves profiles for many packages at once. Expensive PackageManager
     * lookups (dialer, browsers, camera apps, app categories) are performed
     * once per snapshot instead of once per package.
     */
    fun resolveStates(packageNames: Collection<String>): Map<String, ThermalState> {
        val saved = savedStateLookup()
        val missing = packageNames.filter { it !in saved && !defaultStateCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            val snapshot = resolverSnapshot()
            missing.forEach { packageName ->
                defaultStateCache[packageName] = resolveDefaultState(packageName, snapshot)
            }
        }
        return packageNames.associateWith { packageName ->
            saved[packageName] ?: defaultStateCache[packageName] ?: ThermalState.DEFAULT
        }
    }

    fun removePackage(packageName: String) {
        if (packageName.isBlank()) return

        synchronized(lock) {
            val packagesByState = parseValue(savedValue)
            val removed = packagesByState.values.any { it.remove(packageName) }
            if (removed) {
                Logging.d(TAG, "removePackage: $packageName")
                savedValue = serializePackages(packagesByState)
            }
        }
        defaultStateCache.remove(packageName)
        resolver = null
    }

    fun resetProfiles() {
        Logging.d(TAG, "resetProfiles")
        synchronized(lock) {
            savedValue = DEFAULT_VALUE
        }
        reapplyCurrentProfile()
    }

    /**
     * Forgets the last written sysfs value so the next apply is written even if
     * an external component (such as the vendor thermal daemon) changed it.
     */
    fun invalidateAppliedConfig() {
        lastAppliedConfig = null
    }

    private fun reapplyCurrentProfile() {
        if (enabled) {
            // Starting an already running service invokes onStartCommand(), which refreshes
            // the focused app and applies its newly selected profile immediately.
            startService()
        }
    }

    private fun savedStateLookup(): Map<String, ThermalState> {
        val lookup = HashMap<String, ThermalState>()
        packagesByState().forEach { (state, packages) ->
            packages.forEach { lookup[it] = state }
        }
        return lookup
    }

    private fun packagesByState(): Map<ThermalState, LinkedHashSet<String>> =
        parsedPackages ?: parseValue(savedValue).also { parsedPackages = it }

    private fun parseValue(value: String): LinkedHashMap<ThermalState, LinkedHashSet<String>> {
        val segments = value.split(":")
        return linkedMapOf<ThermalState, LinkedHashSet<String>>().apply {
            ThermalState.values().forEach { state ->
                val segment = segments.getOrNull(state.id).orEmpty()
                val packageList = if (segment.startsWith(state.prefix)) {
                    segment.removePrefix(state.prefix)
                } else {
                    ""
                }
                val packages = packageList
                    .split(',')
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toCollection(linkedSetOf())
                put(state, packages)
            }
        }
    }

    private fun serializePackages(
        packagesByState: Map<ThermalState, Set<String>>
    ): String = ThermalState.values().joinToString(":") { state ->
        val packages = packagesByState[state].orEmpty()
        buildString {
            append(state.prefix)
            append(',')
            if (packages.isNotEmpty()) {
                append(packages.joinToString(","))
                append(',')
            }
        }
    }

    fun setDefaultThermalProfile() {
        Logging.d(TAG, "setDefaultThermalProfile")
        writeThermalConfig(ThermalState.DEFAULT.config)
    }

    fun setThermalProfile(packageName: String) {
        if (packageName.isBlank()) {
            Logging.d(TAG, "setThermalProfile: no focused package, using default")
            setDefaultThermalProfile()
            return
        }
        val state = getStateForPackage(packageName)
        Logging.d(TAG, "setThermalProfile: $packageName -> $state")
        writeThermalConfig(state.config)
    }

    private fun writeThermalConfig(config: String) {
        synchronized(lock) {
            if (lastAppliedConfig == config) {
                Logging.d(TAG, "writeThermalConfig: already applied $config")
                return
            }

            if (sysfsController.writeConfig(config)) {
                lastAppliedConfig = config
            } else {
                Logging.w(TAG, "Failed to write thermal config: $config")
            }
        }
    }

    private fun resolveDefaultState(
        packageName: String,
        snapshot: ResolverSnapshot
    ): ThermalState {
        val category = snapshot.categories[packageName]
            ?: runCatching { context.packageManager.getApplicationInfo(packageName, 0).category }
                .getOrElse { return ThermalState.DEFAULT }

        when (category) {
            ApplicationInfo.CATEGORY_GAME -> return ThermalState.GAMING
            ApplicationInfo.CATEGORY_VIDEO -> return ThermalState.VIDEO
            ApplicationInfo.CATEGORY_MAPS -> return ThermalState.NAVIGATION
        }

        return when {
            packageName in NAVIGATION_PACKAGES -> ThermalState.NAVIGATION
            packageName in VIDEO_CALL_PACKAGES -> ThermalState.VIDEOCALL
            packageName in BENCHMARKING_APPS -> ThermalState.BENCHMARK
            snapshot.defaultDialer == packageName -> ThermalState.DIALER
            packageName in snapshot.browserPackages -> ThermalState.BROWSER
            packageName in snapshot.cameraPackages -> ThermalState.CAMERA
            else -> ThermalState.DEFAULT
        }
    }

    private fun resolverSnapshot(): ResolverSnapshot {
        resolver?.let {
            if (SystemClock.elapsedRealtime() - it.createdAt < RESOLVER_TTL_MS) return it
        }

        val categories = runCatching {
            context.packageManager
                .getInstalledApplications(0)
                .associate { it.packageName to it.category }
        }.getOrElse { emptyMap() }

        val snapshot = ResolverSnapshot(
            defaultDialer = runCatching { getDefaultDialerApplication(context) }.getOrNull(),
            browserPackages = queryPackages(
                Intent(Intent.ACTION_VIEW, Uri.parse("http:"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            ),
            cameraPackages = queryPackages(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ),
            categories = categories,
            createdAt = SystemClock.elapsedRealtime(),
        )
        resolver = snapshot
        return snapshot
    }

    private fun queryPackages(intent: Intent): Set<String> = runCatching {
        context.packageManager
            .queryIntentActivitiesAsUser(intent, PackageManager.MATCH_ALL, UserHandle.myUserId())
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrElse {
        Logging.w(TAG, "Failed to query packages for ${intent.action}", it)
        emptySet()
    }

    private data class ResolverSnapshot(
        val defaultDialer: String?,
        val browserPackages: Set<String>,
        val cameraPackages: Set<String>,
        val categories: Map<String, Int>,
        val createdAt: Long,
    )

    enum class ThermalState(
        val id: Int,
        val config: String,
        val prefix: String,
        @param:StringRes val label: Int,
    ) {
        BENCHMARK(0, "20", "thermal.benchmark=", R.string.thermal_benchmark),
        BROWSER(1, "11", "thermal.browser=", R.string.thermal_browser),
        CAMERA(2, "12", "thermal.camera=", R.string.thermal_camera),
        DIALER(3, "8", "thermal.dialer=", R.string.thermal_dialer),
        GAMING(4, "13", "thermal.gaming=", R.string.thermal_gaming),
        NAVIGATION(5, "19", "thermal.navigation=", R.string.thermal_navigation),
        VIDEOCALL(6, "4", "thermal.streaming=", R.string.thermal_streaming),
        VIDEO(7, "21", "thermal.video=", R.string.thermal_video),
        DEFAULT(8, "0", "thermal.default=", R.string.thermal_default),
    }

    companion object {
        private const val TAG = "ThermalUtils"
        private const val THERMAL_CONTROL = "thermal_control_v2"
        private const val THERMAL_ENABLED = "thermal_enabled"
        private const val RESOLVER_TTL_MS = 60_000L

        private val DEFAULT_VALUE = ThermalState.values().joinToString(":") { "${it.prefix}," }

        private val NAVIGATION_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.google.android.apps.mapslite",
            "com.waze",
        )
        private val VIDEO_CALL_PACKAGES = setOf(
            "com.google.android.apps.tachyon",
            "us.zoom.videomeetings",
            "com.microsoft.teams",
            "com.skype.raider",
        )
        private val BENCHMARKING_APPS = setOf(
            "com.primatelabs.geekbench5",
            "com.primatelabs.geekbench6",
            "com.antutu.ABenchMark",
            "com.futuremark.dmandroid.application",
            "com.futuremark.pcmark.android.benchmark",
            "com.glbenchmark.glbenchmark27",
            "com.texts.throttlebench",
            "skynet.cputhrottlingtest",
        )

        @Volatile private var instance: ThermalUtils? = null

        @JvmStatic
        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: ThermalUtils(context.applicationContext).also { instance = it }
            }
    }
}
