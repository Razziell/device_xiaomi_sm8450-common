/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlsCorrectionUiState(
    val available: Boolean = true,
    val settings: AlsCorrectionSettings = AlsCorrectionSettings(),
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
    val uiState: StateFlow<AlsCorrectionUiState> = _uiState.asStateFlow()

    /**
     * Live operating state published by the corrector in
     * sys.als_correction.state. Polled only while the screen is visible;
     * the property has no change notification suitable for a UI here.
     */
    val correctionState: StateFlow<String> = flow {
        while (true) {
            emit(repository.readState())
            delay(STATE_POLL_INTERVAL_MS)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STATE_POLL_STOP_TIMEOUT_MS),
            ""
        )

    fun setEnabled(enabled: Boolean) {
        updateSettings { it.copy(enabled = enabled) }
        write { repository.setEnabled(enabled) }
    }

    /** Live slider movement; only updates the UI, no property write. */
    fun previewK(k: Int) {
        updateSettings { it.copy(k = k.coerceIn(AlsCorrectionSpec.K_RANGE)) }
    }

    /** Slider released; persist the value. */
    fun commitK() = write { repository.setK(_uiState.value.settings.k) }

    fun previewRefLuma(refLuma: Float) {
        updateSettings {
            it.copy(
                refLuma = refLuma.coerceIn(
                    AlsCorrectionSpec.REF_LUMA_RANGE.start,
                    AlsCorrectionSpec.REF_LUMA_RANGE.endInclusive
                )
            )
        }
    }

    fun commitRefLuma() = write { repository.setRefLuma(_uiState.value.settings.refLuma) }

    fun previewGamma(gamma: Float) {
        updateSettings {
            it.copy(
                gamma = gamma.coerceIn(
                    AlsCorrectionSpec.GAMMA_RANGE.start,
                    AlsCorrectionSpec.GAMMA_RANGE.endInclusive
                )
            )
        }
    }

    fun commitGamma() = write { repository.setGamma(_uiState.value.settings.gamma) }

    fun setIntervalMs(intervalMs: Int) {
        updateSettings { it.copy(intervalMs = intervalMs) }
        write { repository.setIntervalMs(intervalMs) }
    }

    fun resetK() {
        updateSettings { it.copy(k = AlsCorrectionSpec.K_DEFAULT) }
        write { repository.resetK() }
    }

    fun resetRefLuma() {
        updateSettings { it.copy(refLuma = AlsCorrectionSpec.REF_LUMA_DEFAULT) }
        write { repository.resetRefLuma() }
    }

    fun resetGamma() {
        updateSettings { it.copy(gamma = AlsCorrectionSpec.GAMMA_DEFAULT) }
        write { repository.resetGamma() }
    }

    fun resetIntervalMs() {
        updateSettings { it.copy(intervalMs = AlsCorrectionSpec.INTERVAL_DEFAULT) }
        write { repository.resetIntervalMs() }
    }

    fun resetAll() {
        updateSettings { AlsCorrectionSettings() }
        write { repository.resetAll() }
    }

    private fun updateSettings(
        transform: (AlsCorrectionSettings) -> AlsCorrectionSettings
    ) {
        _uiState.update { it.copy(settings = transform(it.settings), error = null) }
    }

    private fun write(operation: () -> Result<Unit>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { operation() }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private companion object {
        const val STATE_POLL_INTERVAL_MS = 1000L
        const val STATE_POLL_STOP_TIMEOUT_MS = 5000L
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
