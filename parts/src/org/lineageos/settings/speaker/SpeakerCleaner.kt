/*
 * SPDX-FileCopyrightText: 2020 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.speaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import org.lineageos.settings.R

/** Owns the audio resources used by the speaker cleaning operation. */
class SpeakerCleaner(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Synchronized
    fun start(): Result<Unit> {
        stopLocked()

        return runCatching {
            audioManager?.setParameters(EARPIECE_CLEAN_ON)
            val player = MediaPlayer()
            mediaPlayer = player
            player.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
                setVolume(1f, 1f)

                appContext.resources.openRawResourceFd(R.raw.clear_speaker_sound).use { file ->
                    setDataSource(file.fileDescriptor, file.startOffset, file.length)
                }
                prepare()
            }

            player.start()
        }.onFailure { error ->
            Log.e(TAG, "Failed to play speaker clean sound", error)
            stopLocked()
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.release() }
        }
        mediaPlayer = null
        runCatching { audioManager?.setParameters(EARPIECE_CLEAN_OFF) }
            .onFailure { Log.w(TAG, "Failed to disable speaker cleaning parameter", it) }
    }

    private companion object {
        const val TAG = "SpeakerCleaner"
        const val EARPIECE_CLEAN_ON = "status_earpiece_clean=on"
        const val EARPIECE_CLEAN_OFF = "status_earpiece_clean=off"
    }
}
