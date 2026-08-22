/*
 * Copyright (C) 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.saturation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SaturationUiState(
    val value: Int = SATURATION_DEFAULT,
    val error: String? = null
)

class SaturationViewModel(
    private val repository: SaturationRepository
) : ViewModel() {

    private val initialValue = repository.getSavedValue()
    private val _uiState = MutableStateFlow(SaturationUiState(value = initialValue))
    val uiState: StateFlow<SaturationUiState> = _uiState.asStateFlow()

    private val pendingValues = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        viewModelScope.launch {
            applyValue(initialValue)
            pendingValues.collectLatest(::applyValue)
        }
    }

    fun setValue(value: Int) {
        val constrainedValue = value.coerceIn(SATURATION_MIN, SATURATION_MAX)
        _uiState.update { it.copy(value = constrainedValue, error = null) }
        pendingValues.tryEmit(constrainedValue)
    }

    fun saveValue() {
        repository.saveValue(_uiState.value.value)
    }

    fun reset() {
        setValue(SATURATION_DEFAULT)
        repository.saveValue(SATURATION_DEFAULT)
    }

    private suspend fun applyValue(value: Int) {
        val result = withContext(Dispatchers.IO) {
            repository.applyValue(value)
        }
        result.onFailure { error ->
            _uiState.update {
                it.copy(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}

class SaturationViewModelFactory(
    private val repository: SaturationRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SaturationViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SaturationViewModel(repository) as T
    }
}
