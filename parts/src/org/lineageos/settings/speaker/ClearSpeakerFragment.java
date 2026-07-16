/*
 * Copyright (C) 2020 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.speaker;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.settings.R;

import java.io.IOException;

public class ClearSpeakerFragment extends PreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = ClearSpeakerFragment.class.getSimpleName();

    private static final String PREF_CLEAR_SPEAKER = "clear_speaker_pref";

    private AudioManager mAudioManager;
    private Handler mHandler;
    private MediaPlayer mMediaPlayer;
    private SwitchPreferenceCompat mClearSpeakerPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.clear_speaker_settings);

        mClearSpeakerPref = (SwitchPreferenceCompat) findPreference(PREF_CLEAR_SPEAKER);
        if (mClearSpeakerPref != null) {
            mClearSpeakerPref.setOnPreferenceChangeListener(this);
        }

        mHandler = new Handler(Looper.getMainLooper());

        Context context = getContext();
        if (context != null) {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mClearSpeakerPref) {
            boolean value = (Boolean) newValue;
            if (value) {
                if (startPlaying()) {
                    mHandler.removeCallbacksAndMessages(null);
                    mHandler.postDelayed(() -> {
                        stopPlaying();
                    }, 30000);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onStop() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        stopPlaying();
        super.onStop();
    }

    public boolean startPlaying() {
        stopPlaying();

        if (mAudioManager != null) {
            mAudioManager.setParameters("status_earpiece_clean=on");
        }

        mMediaPlayer = new MediaPlayer();

        if (getActivity() != null) {
            getActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);
        }

        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(true);

        try {
            AssetFileDescriptor file = getResources().openRawResourceFd(R.raw.clear_speaker_sound);
            try {
                mMediaPlayer.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            } finally {
                file.close();
            }

            if (mClearSpeakerPref != null) {
                mClearSpeakerPref.setEnabled(false);
            }

            mMediaPlayer.setVolume(1.0f, 1.0f);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Failed to play speaker clean sound!", e);
            stopPlaying();
            return false;
        }

        return true;
    }

    public void stopPlaying() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }

            try {
                mMediaPlayer.release();
            } catch (Exception ignored) {
            }

            mMediaPlayer = null;
        }

        if (mAudioManager != null) {
            mAudioManager.setParameters("status_earpiece_clean=off");
        }

        if (mClearSpeakerPref != null) {
            mClearSpeakerPref.setEnabled(true);
            mClearSpeakerPref.setChecked(false);
        }
    }
}
