/*
 * SPDX-FileCopyrightText: 2020 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.speaker

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import org.lineageos.settings.speaker.ui.ClearSpeakerScreen
import org.lineageos.settings.ui.theme.XiaomiPartsTheme

class ClearSpeakerComposeActivity : ComponentActivity() {

    private lateinit var viewModel: ClearSpeakerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC

        viewModel = ViewModelProvider(
            this,
            ClearSpeakerViewModelFactory(SpeakerCleaner(applicationContext))
        )[ClearSpeakerViewModel::class.java]

        setContent {
            XiaomiPartsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClearSpeakerScreen(
                        viewModel = viewModel,
                        onBackPressed = onBackPressedDispatcher::onBackPressed
                    )
                }
            }
        }
    }

    override fun onStop() {
        viewModel.stopCleaning()
        super.onStop()
    }
}
