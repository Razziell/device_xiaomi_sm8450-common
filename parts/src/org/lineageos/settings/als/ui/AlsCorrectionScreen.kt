/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import org.lineageos.settings.R
import org.lineageos.settings.als.AlsCorrectionSettings
import org.lineageos.settings.als.AlsCorrectionSpec
import org.lineageos.settings.als.AlsCorrectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlsCorrectionScreen(
    viewModel: AlsCorrectionViewModel,
    onBackPressed: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val correctionState by viewModel.correctionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.als_correction_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::resetAll,
                        enabled = uiState.settings != AlsCorrectionSettings()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.als_reset_all)
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.available) {
                item { UnavailableCard() }
                return@LazyColumn
            }

            item {
                StatusCard(
                    enabled = uiState.settings.enabled,
                    correctionState = correctionState,
                    onEnabledChange = viewModel::setEnabled
                )
            }
            item {
                StrengthCard(
                    k = uiState.settings.k,
                    enabled = uiState.settings.enabled,
                    onPreview = viewModel::previewK,
                    onCommit = viewModel::commitK,
                    onReset = viewModel::resetK
                )
            }
            item {
                IntervalCard(
                    intervalMs = uiState.settings.intervalMs,
                    enabled = uiState.settings.enabled,
                    onSelect = viewModel::setIntervalMs,
                    onReset = viewModel::resetIntervalMs
                )
            }
            item {
                AdvancedCard(
                    refLuma = uiState.settings.refLuma,
                    gamma = uiState.settings.gamma,
                    enabled = uiState.settings.enabled,
                    onRefLumaPreview = viewModel::previewRefLuma,
                    onRefLumaCommit = viewModel::commitRefLuma,
                    onRefLumaReset = viewModel::resetRefLuma,
                    onGammaPreview = viewModel::previewGamma,
                    onGammaCommit = viewModel::commitGamma,
                    onGammaReset = viewModel::resetGamma
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.als_footer_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
            uiState.error?.let { error ->
                item {
                    Text(
                        text = stringResource(R.string.als_error, error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun UnavailableCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.als_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Composable
private fun StatusCard(
    enabled: Boolean,
    correctionState: String,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.als_enable),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.als_enable_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(modifier = Modifier.height(12.dp))
            val (stateText, stateColor) = when (correctionState) {
                AlsCorrectionSpec.STATE_ACTIVE ->
                    stringResource(R.string.als_state_active) to
                        MaterialTheme.colorScheme.primary
                AlsCorrectionSpec.STATE_CAPTURE_FAILED ->
                    stringResource(R.string.als_state_capture_failed) to
                        MaterialTheme.colorScheme.error
                AlsCorrectionSpec.STATE_IDLE ->
                    stringResource(R.string.als_state_idle) to
                        MaterialTheme.colorScheme.onSurfaceVariant
                AlsCorrectionSpec.STATE_OFF ->
                    stringResource(R.string.als_state_off) to
                        MaterialTheme.colorScheme.onSurfaceVariant
                else ->
                    stringResource(R.string.als_state_unknown) to
                        MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = stringResource(R.string.als_state_label, stateText),
                style = MaterialTheme.typography.bodyMedium,
                color = stateColor
            )
        }
    }
}

@Composable
private fun StrengthCard(
    k: Int,
    enabled: Boolean,
    onPreview: (Int) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            SettingHeader(
                title = stringResource(R.string.als_strength),
                resetEnabled = k != AlsCorrectionSpec.K_DEFAULT,
                onReset = onReset
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.als_strength_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (k == AlsCorrectionSpec.K_DEFAULT) {
                    stringResource(R.string.als_strength_value_default, k)
                } else {
                    stringResource(R.string.als_strength_value, k)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (k == AlsCorrectionSpec.K_DEFAULT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Slider(
                value = k.toFloat(),
                onValueChange = {
                    onPreview(
                        (it / AlsCorrectionSpec.K_STEP).roundToInt() *
                            AlsCorrectionSpec.K_STEP
                    )
                },
                onValueChangeFinished = onCommit,
                valueRange = AlsCorrectionSpec.K_RANGE.first.toFloat()..
                    AlsCorrectionSpec.K_RANGE.last.toFloat(),
                // Continuous slider: values are quantized to K_STEP above and
                // 50 tick marks are expensive to draw on every drag frame.
                steps = 0,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.als_strength_weak),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.als_strength_strong),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IntervalCard(
    intervalMs: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            SettingHeader(
                title = stringResource(R.string.als_interval),
                resetEnabled = intervalMs != AlsCorrectionSpec.INTERVAL_DEFAULT,
                onReset = onReset
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.als_interval_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val labels = arrayOf(
                    stringResource(R.string.als_interval_fast),
                    stringResource(R.string.als_interval_balanced),
                    stringResource(R.string.als_interval_power_save)
                )
                AlsCorrectionSpec.INTERVAL_CHOICES.forEachIndexed { index, choice ->
                    FilterChip(
                        selected = intervalMs == choice,
                        onClick = { onSelect(choice) },
                        enabled = enabled,
                        label = {
                            Text(
                                text = labels[index],
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingHeader(
    title: String,
    resetEnabled: Boolean,
    onReset: () -> Unit,
    titleStyleIsBody: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = if (titleStyleIsBody) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (titleStyleIsBody) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onReset,
            enabled = resetEnabled
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.als_reset)
            )
        }
    }
}

@Composable
private fun AdvancedCard(
    refLuma: Float,
    gamma: Float,
    enabled: Boolean,
    onRefLumaPreview: (Float) -> Unit,
    onRefLumaCommit: () -> Unit,
    onRefLumaReset: () -> Unit,
    onGammaPreview: (Float) -> Unit,
    onGammaCommit: () -> Unit,
    onGammaReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.als_advanced),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.als_advanced_hide
                            else R.string.als_advanced_show
                        )
                    )
                }
            }

            if (!expanded) return@Column

            SettingHeader(
                title = stringResource(
                    R.string.als_ref_luma_value,
                    String.format(Locale.US, "%.2f", refLuma)
                ),
                resetEnabled = refLuma != AlsCorrectionSpec.REF_LUMA_DEFAULT,
                onReset = onRefLumaReset,
                titleStyleIsBody = true
            )
            Text(
                text = stringResource(R.string.als_ref_luma_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = refLuma,
                onValueChange = { onRefLumaPreview((it * 100).roundToInt() / 100f) },
                onValueChangeFinished = onRefLumaCommit,
                valueRange = AlsCorrectionSpec.REF_LUMA_RANGE.start..
                    AlsCorrectionSpec.REF_LUMA_RANGE.endInclusive,
                steps = 0,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingHeader(
                title = stringResource(
                    R.string.als_gamma_value,
                    String.format(Locale.US, "%.1f", gamma)
                ),
                resetEnabled = gamma != AlsCorrectionSpec.GAMMA_DEFAULT,
                onReset = onGammaReset,
                titleStyleIsBody = true
            )
            Text(
                text = stringResource(R.string.als_gamma_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = gamma,
                onValueChange = { onGammaPreview((it * 10).roundToInt() / 10f) },
                onValueChangeFinished = onGammaCommit,
                valueRange = AlsCorrectionSpec.GAMMA_RANGE.start..
                    AlsCorrectionSpec.GAMMA_RANGE.endInclusive,
                steps = 0,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
