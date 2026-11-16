package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tanjitDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tanjit_ai_settings"
)

data class AssistantSettings(
    val speechLocale: String = "bn-BD",
    val malePitch: Float = 0.80f,
    val speechRate: Float = 0.96f,
    val preferredVoiceName: String = "",
    val wakeWordOnlyInBackground: Boolean = true,
    val incomingCallAnnounce: Boolean = true,
    val incomingCallVoiceControl: Boolean = true,
    val permissionOnboardingDismissed: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SPEECH_LOCALE = stringPreferencesKey("speech_locale")
        val MALE_PITCH = floatPreferencesKey("male_pitch")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PREFERRED_VOICE = stringPreferencesKey("preferred_voice")
        val WAKE_WORD_ONLY = booleanPreferencesKey("wake_word_only")
        val INCOMING_ANNOUNCE = booleanPreferencesKey("incoming_announce")
        val INCOMING_VOICE_CONTROL = booleanPreferencesKey("incoming_voice_control")
        val PERMISSION_ONBOARDING_DISMISSED = booleanPreferencesKey("permission_onboarding_dismissed")
    }

    val settingsFlow: Flow<AssistantSettings> = context.tanjitDataStore.data.map { prefs ->
        AssistantSettings(
            speechLocale = prefs[Keys.SPEECH_LOCALE] ?: "bn-BD",
            malePitch = prefs[Keys.MALE_PITCH] ?: 0.80f,
            speechRate = prefs[Keys.SPEECH_RATE] ?: 0.96f,
            preferredVoiceName = prefs[Keys.PREFERRED_VOICE] ?: "",
            wakeWordOnlyInBackground = prefs[Keys.WAKE_WORD_ONLY] ?: true,
            incomingCallAnnounce = prefs[Keys.INCOMING_ANNOUNCE] ?: true,
            incomingCallVoiceControl = prefs[Keys.INCOMING_VOICE_CONTROL] ?: true,
            permissionOnboardingDismissed = prefs[Keys.PERMISSION_ONBOARDING_DISMISSED] ?: false
        )
    }

    suspend fun setSpeechLocale(localeTag: String) {
        context.tanjitDataStore.edit { it[Keys.SPEECH_LOCALE] = localeTag }
    }

    suspend fun setMalePitch(pitch: Float) {
        context.tanjitDataStore.edit { it[Keys.MALE_PITCH] = pitch.coerceIn(0.6f, 1.15f) }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.tanjitDataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.7f, 1.4f) }
    }

    suspend fun setPreferredVoiceName(voiceName: String) {
        context.tanjitDataStore.edit { it[Keys.PREFERRED_VOICE] = voiceName }
    }

    suspend fun setWakeWordOnlyInBackground(wakeWordOnly: Boolean) {
        context.tanjitDataStore.edit { it[Keys.WAKE_WORD_ONLY] = wakeWordOnly }
    }

    suspend fun setIncomingCallAnnounce(enabled: Boolean) {
        context.tanjitDataStore.edit { it[Keys.INCOMING_ANNOUNCE] = enabled }
    }

    suspend fun setIncomingCallVoiceControl(enabled: Boolean) {
        context.tanjitDataStore.edit { it[Keys.INCOMING_VOICE_CONTROL] = enabled }
    }

    suspend fun setPermissionOnboardingDismissed(dismissed: Boolean) {
        context.tanjitDataStore.edit { it[Keys.PERMISSION_ONBOARDING_DISMISSED] = dismissed }
    }
}
