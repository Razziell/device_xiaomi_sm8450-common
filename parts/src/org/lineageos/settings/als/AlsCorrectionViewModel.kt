/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class AlsCorrectionUiState(
    val available: Boolean = true,
    val settings: AlsCorrectionSettings = AlsCorrectionSettings(),
    val correctionState: String = "",
    val autoBrightnessEnabled: Boolean? = null,
    val error: String? = null
)

class AlsCorrectionViewModel(
    private val repository: AlsCorrectionRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AlsCorrectionUiState(
            available = repository.isAvailable,
            settings = repository.readSettings()
        )
    )
    val uiState = _uiState.asStateFlow()
    private val operationMutex = Mutex()
    private var observer: Job? = null

    private enum class Field { ENABLED, K, REF_LUMA, GAMMA, INTERVAL }
    private data class Write(val fields: Set<Field>, val operation: () -> Result<Unit>)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    // Main-thread only. Preserve newer requested values while older writes/readbacks finish.
    private val pending = mutableMapOf<Field, Int>()
    private var confirmedSettings = _uiState.value.settings

    init {
        viewModelScope.launch {
            for (write in writes) {
                operationMutex.withLock {
                    var snapshot: AlsCorrectionSnapshot? = null
                    var error: Throwable? = null
                    try {
                        error = withContext(Dispatchers.IO) { write.operation() }.exceptionOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                    }
                    // Read back even after a partial reset failure. A failed read falls back to
                    // the last confirmed state, never presenting an unverified value as saved.
                    try {
                        snapshot = withContext(Dispatchers.IO) { repository.readSnapshot() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (error == null) error = e
                    }
                    for (field in write.fields) {
                        val count = pending.getValue(field) - 1
                        if (count == 0) pending.remove(field) else pending[field] = count
                    }
                    if (snapshot != null) {
                        applySnapshot(snapshot)
                    } else {
                        _uiState.update { it.copy(settings = mergePending(confirmedSettings, it.settings)) }
                    }
                    error?.let(::reportError)
                }
            }
        }
    }

    /** Refresh on resume and periodically while visible, without overwriting queued edits. */
    fun startObserving() {
        if (observer?.isActive == true || !_uiState.value.available) return
        observer = viewModelScope.launch {
            while (isActive) {
                operationMutex.withLock {
                    try {
                        applySnapshot(withContext(Dispatchers.IO) { repository.readSnapshot() })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportError(e)
                    }
                }
                delay(1000)
            }
        }
    }

    fun stopObserving() {
        observer?.cancel()
        observer = null
    }

    fun setEnabled(value: Boolean) = write(setOf(Field.ENABLED),
        { it.copy(enabled = value) }) { repository.setEnabled(value) }
    fun setK(value: Int) {
        val v = value.coerceIn(AlsCorrectionSpec.K_RANGE)
        write(setOf(Field.K), { it.copy(k = v) }) { repository.setK(v) }
    }
    fun setRefLuma(value: Float) {
        if (!value.isFinite()) return
        val v = value.coerceIn(AlsCorrectionSpec.REF_LUMA_RANGE)
        write(setOf(Field.REF_LUMA), { it.copy(refLuma = v) }) { repository.setRefLuma(v) }
    }
    fun setGamma(value: Float) {
        if (!value.isFinite()) return
        val v = value.coerceIn(AlsCorrectionSpec.GAMMA_RANGE)
        write(setOf(Field.GAMMA), { it.copy(gamma = v) }) { repository.setGamma(v) }
    }
    fun setIntervalMs(value: Int) {
        if (value !in AlsCorrectionSpec.INTERVAL_CHOICES) return
        write(setOf(Field.INTERVAL), { it.copy(intervalMs = value) }) { repository.setIntervalMs(value) }
    }
    // Read the latest requested value here, not a potentially stale composable click closure.
    fun stepK(delta: Float) = setK(_uiState.value.settings.k + delta.roundToInt())
    fun stepRefLuma(delta: Float) = setRefLuma(roundStep(_uiState.value.settings.refLuma + delta))
    fun stepGamma(delta: Float) = setGamma(roundStep(_uiState.value.settings.gamma + delta))
    private fun roundStep(value: Float) = (value * 100).roundToInt() / 100f

    fun resetK() = write(setOf(Field.K),
        { it.copy(k = AlsCorrectionSpec.K_DEFAULT) }) { repository.resetK() }
    fun resetRefLuma() = write(setOf(Field.REF_LUMA),
        { it.copy(refLuma = AlsCorrectionSpec.REF_LUMA_DEFAULT) }) { repository.resetRefLuma() }
    fun resetGamma() = write(setOf(Field.GAMMA),
        { it.copy(gamma = AlsCorrectionSpec.GAMMA_DEFAULT) }) { repository.resetGamma() }
    fun resetIntervalMs() = write(setOf(Field.INTERVAL),
        { it.copy(intervalMs = AlsCorrectionSpec.INTERVAL_DEFAULT) }) { repository.resetIntervalMs() }
    fun resetParameters() = write(setOf(Field.K, Field.REF_LUMA, Field.GAMMA, Field.INTERVAL),
        { AlsCorrectionSettings(enabled = it.enabled) }) { repository.resetParameters() }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun mergePending(actual: AlsCorrectionSettings, requested: AlsCorrectionSettings) =
        actual.copy(
            enabled = if (Field.ENABLED in pending) requested.enabled else actual.enabled,
            k = if (Field.K in pending) requested.k else actual.k,
            refLuma = if (Field.REF_LUMA in pending) requested.refLuma else actual.refLuma,
            gamma = if (Field.GAMMA in pending) requested.gamma else actual.gamma,
            intervalMs = if (Field.INTERVAL in pending) requested.intervalMs else actual.intervalMs
        )

    private fun applySnapshot(snapshot: AlsCorrectionSnapshot) {
        confirmedSettings = snapshot.settings
        _uiState.update {
            it.copy(
                settings = mergePending(snapshot.settings, it.settings),
                correctionState = snapshot.state,
                autoBrightnessEnabled = snapshot.autoBrightnessEnabled
            )
        }
    }

    /** All callers are on main; one channel consumer serializes writes, including rapid taps. */
    private fun write(
        fields: Set<Field>,
        preview: (AlsCorrectionSettings) -> AlsCorrectionSettings,
        operation: () -> Result<Unit>
    ) {
        if (!_uiState.value.available) return
        for (field in fields) pending[field] = (pending[field] ?: 0) + 1
        _uiState.update { it.copy(settings = preview(it.settings)) }
        check(writes.trySend(Write(fields, operation)).isSuccess)
    }

    private fun reportError(error: Throwable) {
        _uiState.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
    }

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }
}

class AlsCorrectionViewModelFactory(
    private val repository: AlsCorrectionRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AlsCorrectionViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AlsCorrectionViewModel(repository) as T
    }
}
