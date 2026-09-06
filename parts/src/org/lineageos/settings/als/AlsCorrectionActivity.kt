/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.als

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import org.lineageos.settings.als.ui.AlsCorrectionScreen
import org.lineageos.settings.ui.theme.XiaomiPartsTheme

class AlsCorrectionActivity : ComponentActivity() {

    private lateinit var viewModel: AlsCorrectionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(
            this,
            AlsCorrectionViewModelFactory(AlsCorrectionRepository(applicationContext))
        )[AlsCorrectionViewModel::class.java]

        setContent {
            XiaomiPartsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlsCorrectionScreen(
                        viewModel = viewModel,
                        onBackPressed = onBackPressedDispatcher::onBackPressed
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startObserving()
    }

    override fun onPause() {
        viewModel.stopObserving()
        super.onPause()
    }
}
