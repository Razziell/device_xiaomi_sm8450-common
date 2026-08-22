/*
 * Copyright (C) 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.saturation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import org.lineageos.settings.R
import org.lineageos.settings.saturation.ui.SaturationScreen
import org.lineageos.settings.ui.theme.XiaomiPartsTheme
import org.lineageos.settings.utils.TileUtils

class SaturationActivity : ComponentActivity() {

    private lateinit var viewModel: SaturationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SaturationRepository(
            applicationContext,
            SurfaceFlingerSaturationController()
        )
        viewModel = ViewModelProvider(
            this,
            SaturationViewModelFactory(repository)
        )[SaturationViewModel::class.java]

        setContent {
            XiaomiPartsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SaturationScreen(
                        viewModel = viewModel,
                        onBackPressed = onBackPressedDispatcher::onBackPressed,
                        onAddTile = {
                            TileUtils.requestAddTileService(
                                this,
                                SaturationTileService::class.java,
                                R.string.saturation_title,
                                R.drawable.ic_saturation_tile
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        viewModel.saveValue()
        super.onStop()
    }
}
