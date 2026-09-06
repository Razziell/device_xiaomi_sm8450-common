/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import kotlin.math.roundToInt
import org.lineageos.settings.R
import org.lineageos.settings.als.AlsCorrectionSettings
import org.lineageos.settings.als.AlsCorrectionSpec
import org.lineageos.settings.als.AlsCorrectionUiState
import org.lineageos.settings.als.AlsCorrectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlsCorrectionScreen(viewModel: AlsCorrectionViewModel, onBackPressed: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val snackbar = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { stringResource(R.string.als_error, it) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbar.showSnackbar(errorMessage)
            viewModel.dismissError()
        }
    }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val parametersChanged = settings.copy(enabled = true) != AlsCorrectionSettings()
    // Persistence is queued in the ViewModel; it must not dim or disable the controls.
    val editable = state.available

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.als_reset_all)) },
            text = { Text(stringResource(R.string.als_reset_confirm,
                    AlsCorrectionSpec.INTERVAL_DEFAULT)) },
            confirmButton = {
                TextButton(
                    enabled = editable,
                    onClick = { confirmReset = false; viewModel.resetParameters() }
                ) { Text(stringResource(R.string.als_reset_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.als_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.als_correction_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { confirmReset = true },
                        enabled = editable && parametersChanged
                    ) {
                        Icon(Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.als_reset_all))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.available) {
                item("unavailable") { InfoCard(stringResource(R.string.als_unavailable)) }
                return@LazyColumn
            }
            item("illustration") { AlsCorrectionIllustration() }
            item("status") { StatusCard(state, viewModel::setEnabled) }
            item("strength") {
                Card {
                    Column(Modifier.padding(20.dp)) {
                        NumericSetting(
                            title = stringResource(R.string.als_strength),
                            summary = stringResource(R.string.als_strength_summary,
                                    AlsCorrectionSpec.K_STEP),
                            value = settings.k.toFloat(),
                            defaultValue = AlsCorrectionSpec.K_DEFAULT.toFloat(),
                            range = AlsCorrectionSpec.K_RANGE.first.toFloat()..
                                AlsCorrectionSpec.K_RANGE.last.toFloat(),
                            step = AlsCorrectionSpec.K_STEP.toFloat(),
                            decimals = 0,
                            onStep = viewModel::stepK,
                            onCommit = { viewModel.setK(it.roundToInt()) },
                            onReset = viewModel::resetK
                        )
                    }
                }
            }
            item("interval") {
                IntervalCard(settings.intervalMs, editable, viewModel::setIntervalMs,
                        viewModel::resetIntervalMs)
            }
            item("advanced") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val expandAction = stringResource(if (expanded)
                                R.string.als_advanced_hide else R.string.als_advanced_show)
                        val expandState = stringResource(if (expanded)
                                R.string.als_advanced_expanded else R.string.als_advanced_collapsed)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = expandAction) {
                                    expanded = !expanded
                                }
                                .semantics { stateDescription = expandState }
                                .sizeIn(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.als_advanced), modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                            Icon(
                                painter = painterResource(R.drawable.ic_als_expand_more),
                                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (expanded) {
                            Text(stringResource(R.string.als_advanced_summary),
                                    style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            NumericSetting(
                                title = stringResource(R.string.als_ref_luma),
                                summary = stringResource(R.string.als_ref_luma_summary),
                                value = settings.refLuma,
                                defaultValue = AlsCorrectionSpec.REF_LUMA_DEFAULT,
                                range = AlsCorrectionSpec.REF_LUMA_RANGE,
                                step = AlsCorrectionSpec.REF_LUMA_STEP,
                                decimals = 2,
                                onStep = viewModel::stepRefLuma,
                                onCommit = viewModel::setRefLuma,
                                onReset = viewModel::resetRefLuma
                            )
                            Spacer(Modifier.height(24.dp))
                            NumericSetting(
                                title = stringResource(R.string.als_gamma),
                                summary = stringResource(R.string.als_gamma_summary),
                                value = settings.gamma,
                                defaultValue = AlsCorrectionSpec.GAMMA_DEFAULT,
                                range = AlsCorrectionSpec.GAMMA_RANGE,
                                step = AlsCorrectionSpec.GAMMA_STEP,
                                decimals = 1,
                                onStep = viewModel::stepGamma,
                                onCommit = viewModel::setGamma,
                                onReset = viewModel::resetGamma
                            )
                        }
                    }
                }
            }
            item("help") { CorrectionHelp() }
        }
    }
}

@Composable
private fun CorrectionHelp() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // Footer on the page background, like accessibility feature explanations in Settings.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_als_info_outline),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = color
        )
        Text(stringResource(R.string.als_auto_brightness_hint),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, color = color)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.als_help_useful),
                    style = MaterialTheme.typography.bodyMedium, color = color)
            HelpBullet(stringResource(R.string.als_help_bright_background))
            HelpBullet(stringResource(R.string.als_help_content_changes))
        }
        Text(stringResource(R.string.als_footer_summary),
                style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun HelpBullet(text: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text("•", modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics {},
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusCard(state: AlsCorrectionUiState, onEnabledChange: (Boolean) -> Unit) {
    val enabled = state.settings.enabled
    val manual = state.autoBrightnessEnabled == false
    val status = when {
        !enabled -> R.string.als_state_off
        manual -> R.string.als_state_manual
        state.autoBrightnessEnabled == null -> R.string.als_state_unknown
        state.correctionState == AlsCorrectionSpec.STATE_ACTIVE -> R.string.als_state_active
        state.correctionState == AlsCorrectionSpec.STATE_CAPTURE_FAILED -> R.string.als_state_capture_failed
        state.correctionState == AlsCorrectionSpec.STATE_IDLE -> R.string.als_state_idle
        else -> R.string.als_state_unknown
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = enabled, enabled = state.available,
                    role = Role.Switch, onValueChange = onEnabledChange
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.als_enable),
                            style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.als_enable_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = null, enabled = state.available)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.als_state_label, stringResource(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = when (status) {
                    R.string.als_state_active -> MaterialTheme.colorScheme.primary
                    R.string.als_state_capture_failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

        }
    }
}

/** Locale-aware UI values; unlike persisted properties, these use the user's decimal separator. */
@Composable
private fun number(value: Float, minimumDecimals: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, minimumDecimals, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = minimumDecimals
            maximumFractionDigits = 6
        }.format(value.toDouble())
    }
}

@Composable
private fun SettingHeader(title: String, resetEnabled: Boolean, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onReset, enabled = resetEnabled) {
            Icon(Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.als_reset_parameter, title))
        }
    }
}

/** Shared control: local drag preview, explicit commit, and accessible single-step buttons. */
@Composable
private fun NumericSetting(
    title: String,
    summary: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    onStep: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onReset: () -> Unit
) {
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    // Polling must not overwrite a finger's preview. Completion or failure rereads actual state.
    LaunchedEffect(value, dragging) {
        if (!dragging) draft = value
    }
    val custom = value !in range
    LaunchedEffect(custom) {
        if (custom) dragging = false
    }
    val editable = !custom
    val displayedValue = if (dragging) draft else value
    val shownValue = number(displayedValue, decimals)
    SettingHeader(title, !dragging && value != defaultValue, onReset)
    Text(summary, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            title, increase = false,
            enabled = editable && !dragging && value > range.start,
            onClick = { onStep(-step) }
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(shownValue, style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
            // Reserve the line so leaving the default does not move the slider and buttons.
            Text(if (displayedValue == defaultValue) stringResource(R.string.als_default) else " ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
        }
        StepButton(
            title, increase = true,
            enabled = editable && !dragging && value < range.endInclusive,
            onClick = { onStep(step) }
        )
    }
    if (custom) {
        Text(stringResource(R.string.als_custom_value_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Slider(
            value = displayedValue.coerceIn(range),
            onValueChange = {
                dragging = true
                draft = ((it / step).roundToInt() * step * 100).roundToInt()
                    .div(100f).coerceIn(range)
            },
            onValueChangeFinished = {
                val committed = draft
                dragging = false
                onCommit(committed)
            },
            valueRange = range,
            enabled = editable,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = shownValue
            }
        )
    }
}

@Composable
private fun StepButton(title: String, increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        if (increase) R.string.als_increase else R.string.als_decrease, title
    )
    TextButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
    ) {
        Text(if (increase) "+" else "−", modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun IntervalCard(intervalMs: Int, enabled: Boolean, onSelect: (Int) -> Unit, onReset: () -> Unit) {
    val title = stringResource(R.string.als_interval)
    val labels = listOf(
        stringResource(R.string.als_interval_fast),
        stringResource(R.string.als_interval_balanced)
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            SettingHeader(title, enabled && intervalMs != AlsCorrectionSpec.INTERVAL_DEFAULT, onReset)
            Text(stringResource(R.string.als_interval_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (intervalMs !in AlsCorrectionSpec.INTERVAL_CHOICES) {
                Text(stringResource(R.string.als_custom_interval, intervalMs),
                        style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AlsCorrectionSpec.INTERVAL_CHOICES.forEachIndexed { index, choice ->
                    val intervalDescription = stringResource(R.string.als_interval_value, choice)
                    FilterChip(
                        selected = intervalMs == choice,
                        onClick = { onSelect(choice) },
                        enabled = enabled,
                        label = {
                            // Allow wrapping with large fonts rather than clipping mode names.
                            Text(labels[index], modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center)
                        },
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = "${labels[index]}, $intervalDescription"
                        }
                    )
                }
            }
        }
    }
}
