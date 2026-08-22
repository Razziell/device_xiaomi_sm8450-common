/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.speaker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val CLEANING_DURATION_SECONDS = 30

data class ClearSpeakerUiState(
    val isStarting: Boolean = false,
    val isPlaying: Boolean = false,
    val secondsRemaining: Int = CLEANING_DURATION_SECONDS,
    val error: String? = null
)

class ClearSpeakerViewModel(
    private val speakerCleaner: SpeakerCleaner
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClearSpeakerUiState())
    val uiState: StateFlow<ClearSpeakerUiState> = _uiState.asStateFlow()

    private var cleaningJob: Job? = null

    /**
     * MediaPlayer teardown is a blocking IPC to the media server; it must not
     * run on the main thread. This scope outlives [viewModelScope] cancellation
     * so cleanup still runs from [onCleared].
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startCleaning() {
        if (_uiState.value.isStarting || _uiState.value.isPlaying) return

        cleaningJob = viewModelScope.launch {
            _uiState.value = ClearSpeakerUiState(isStarting = true)

            val result = try {
                withContext(Dispatchers.IO) {
                    speakerCleaner.start()
                }
            } catch (e: CancellationException) {
                // Cancelled while start() was still running: stop() is queued
                // behind start() on the cleaner lock, so playback never leaks.
                cleanupScope.launch { speakerCleaner.stop() }
                throw e
            }

            result.onFailure { error ->
                _uiState.value = ClearSpeakerUiState(
                    error = error.message ?: error.javaClass.simpleName
                )
                return@launch
            }

            _uiState.value = ClearSpeakerUiState(isPlaying = true)

            repeat(CLEANING_DURATION_SECONDS) {
                delay(1_000)
                _uiState.update { state ->
                    state.copy(secondsRemaining = state.secondsRemaining - 1)
                }
            }

            stopCleaning()
        }
    }

    fun stopCleaning() {
        cleaningJob?.cancel()
        cleaningJob = null
        // Preserve the last error so the user can still read it after leaving.
        _uiState.update { ClearSpeakerUiState(error = it.error) }
        cleanupScope.launch { speakerCleaner.stop() }
    }

    override fun onCleared() {
        cleanupScope.launch { speakerCleaner.stop() }
        super.onCleared()
    }
}

class ClearSpeakerViewModelFactory(
    private val speakerCleaner: SpeakerCleaner
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ClearSpeakerViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ClearSpeakerViewModel(speakerCleaner) as T
    }
}
