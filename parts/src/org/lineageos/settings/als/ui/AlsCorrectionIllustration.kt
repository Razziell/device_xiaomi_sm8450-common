/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.lineageos.settings.R

/** Static conceptual diagram, not a preview of the current screen or correction strength. */
@Composable
fun AlsCorrectionIllustration() {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.als_illustration_description)
    Card(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                // One logical coordinate system keeps the three symbols aligned.
                // Conceptual layout: screen, compensation, under-panel ALS.
                // This is not a map of sensor placement or the processing pipeline.
                val sx = size.width / 360f
                val sy = size.height / 132f
                withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
                    fun roundedRect(x: Float, y: Float, w: Float, h: Float,
                            radius: Float, color: Color) {
                        drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
                    }
                    fun line(x1: Float, y1: Float, x2: Float, y2: Float,
                            color: Color, width: Float = 3f) {
                        drawLine(color, Offset(x1, y1), Offset(x2, y2), width,
                                cap = StrokeCap.Round)
                    }
                    fun arrow(x1: Float, x2: Float, y: Float) {
                        line(x1, y, x2, y, colors.outline)
                        line(x2 - 6f, y - 5f, x2, y, colors.outline)
                        line(x2 - 6f, y + 5f, x2, y, colors.outline)
                    }

                    // Screen: a bright content block and a highlighted sampling area near the top.
                    drawCircle(colors.primaryContainer, 49f, Offset(60f, 66f))
                    roundedRect(29f, 9f, 62f, 114f, 13f, colors.primary)
                    roundedRect(34f, 15f, 52f, 102f, 9f, colors.surface)
                    roundedRect(49f, 20f, 22f, 3f, 1.5f, colors.outline)
                    roundedRect(40f, 33f, 40f, 34f, 6f, colors.primaryContainer)
                    line(46f, 43f, 66f, 43f, colors.onPrimaryContainer)
                    line(46f, 51f, 59f, 51f, colors.onPrimaryContainer)
                    roundedRect(40f, 74f, 40f, 8f, 4f, colors.secondaryContainer)
                    roundedRect(40f, 88f, 29f, 8f, 4f, colors.secondaryContainer)
                    line(51f, 110f, 69f, 110f, colors.outline, 2f)
                    drawCircle(colors.tertiary, 5f, Offset(77f, 30f))
                    drawCircle(colors.surface, 2f, Offset(77f, 30f))

                    // Screen contribution entering compensation; shaded beam is illustrative only.
                    val beam = Path().apply {
                        moveTo(98f, 48f)
                        lineTo(149f, 58f)
                        lineTo(149f, 74f)
                        lineTo(98f, 84f)
                        close()
                    }
                    drawPath(beam, colors.tertiary.copy(alpha = 0.12f))
                    arrow(105f, 133f, 66f)

                    // Sensor: chip outline, contact pins and photosensitive centre.
                    drawCircle(colors.secondaryContainer, 40f, Offset(300f, 66f))
                    for (pin in listOf(286f, 300f, 314f)) {
                        line(pin, 35f, pin, 43f, colors.secondary)
                        line(pin, 89f, pin, 97f, colors.secondary)
                    }
                    for (pin in listOf(52f, 66f, 80f)) {
                        line(269f, pin, 277f, pin, colors.secondary)
                        line(323f, pin, 331f, pin, colors.secondary)
                    }
                    roundedRect(277f, 43f, 46f, 46f, 10f, colors.secondary)
                    drawCircle(colors.onSecondary, 14f, Offset(300f, 66f))
                    drawCircle(colors.tertiary, 7f, Offset(300f, 66f))
                    arrow(225f, 251f, 66f)

                    // Plus/minus denotes adjustment; the actual lux correction remains subtractive.
                    drawCircle(colors.tertiaryContainer, 42f, Offset(180f, 66f))
                    drawCircle(colors.onTertiaryContainer, 27f, Offset(180f, 66f),
                            style = Stroke(width = 3f))
                    line(170f, 59f, 190f, 59f, colors.onTertiaryContainer, 4f)
                    line(180f, 49f, 180f, 69f, colors.onTertiaryContainer, 4f)
                    line(170f, 79f, 190f, 79f, colors.onTertiaryContainer, 4f)
                }
            }
        }
    }
}
