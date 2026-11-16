package com.example.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.VoiceProfile
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BengaliTtsManager"

class BengaliTtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var currentPitch: Float = 0.80f
    private var currentRate: Float = 0.96f
    private var preferredVoiceName: String = ""
    private var currentLocaleTag: String = "bn-BD"
    private var onSpeechCompleteCallback: (() -> Unit)? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isBengaliVoiceMissing = MutableStateFlow(false)
    val isBengaliVoiceMissing: StateFlow<Boolean> = _isBengaliVoiceMissing.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceProfile>>(emptyList())
    val availableVoices: StateFlow<List<VoiceProfile>> = _availableVoices.asStateFlow()

    private val _activeVoiceLabel = MutableStateFlow("পুরুষ বাংলা প্রোফাইল (ডিপ পিচ)")
    val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureVoicesAndLocale()
                    _isReady.value = true
                } else {
                    Log.e(TAG, "TextToSpeech initialization failed with status=$status")
                    _isReady.value = false
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeechCompleteCallback?.invoke()
                    onSpeechCompleteCallback = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeechCompleteCallback?.invoke()
                    onSpeechCompleteCallback = null
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS utterance error code=$errorCode")
                    _isSpeaking.value = false
                    onSpeechCompleteCallback?.invoke()
                    onSpeechCompleteCallback = null
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating TextToSpeech", e)
        }
    }

    fun updateSettings(
        localeTag: String,
        malePitch: Float,
        speechRate: Float,
        preferredVoice: String
    ) {
        currentLocaleTag = localeTag
        currentPitch = malePitch.coerceIn(0.6f, 1.15f)
        currentRate = speechRate.coerceIn(0.7f, 1.4f)
        preferredVoiceName = preferredVoice
        if (_isReady.value) {
            configureVoicesAndLocale()
        }
    }

    private fun configureVoicesAndLocale() {
        val engine = tts ?: return
        val requestedLocale = Locale.forLanguageTag(currentLocaleTag)
        val bangladeshLocale = Locale("bn", "BD")
        val indiaLocale = Locale("bn", "IN")

        val allVoices = runCatching { engine.voices?.toList().orEmpty() }.getOrElse { emptyList() }
        val bengaliVoices = allVoices.filter {
            it.locale.language.equals("bn", ignoreCase = true)
        }.sortedWith(
            compareByDescending<Voice> { scoreMaleVoice(it) }
                .thenByDescending { it.quality }
        )

        _availableVoices.value = bengaliVoices.map {
            VoiceProfile(
                name = it.name,
                localeTag = it.locale.toLanguageTag(),
                isNetworkRequired = it.isNetworkConnectionRequired,
                quality = it.quality
            )
        }

        val chosenVoice = bengaliVoices.firstOrNull { it.name == preferredVoiceName }
            ?: bengaliVoices.firstOrNull {
                it.locale.country.equals(requestedLocale.country, ignoreCase = true)
            }
            ?: bengaliVoices.firstOrNull()

        if (chosenVoice != null) {
            engine.voice = chosenVoice
            engine.language = chosenVoice.locale
            _isBengaliVoiceMissing.value = false
            _activeVoiceLabel.value = "${formatVoiceDisplayName(chosenVoice)} • পিচ ${"%.2f".format(currentPitch)}"
        } else {
            val resultBd = engine.setLanguage(bangladeshLocale)
            val resultIn = if (resultBd < 0) engine.setLanguage(indiaLocale) else resultBd
            _isBengaliVoiceMissing.value = resultIn < 0
            _activeVoiceLabel.value = if (resultIn < 0) {
                "বাংলা ভয়েস প্যাক ইনস্টল নেই"
            } else {
                "ডিফল্ট বাংলা পুরুষ প্রোফাইল • পিচ ${"%.2f".format(currentPitch)}"
            }
        }

        engine.setPitch(currentPitch)
        engine.setSpeechRate(currentRate)
    }

    private fun scoreMaleVoice(voice: Voice): Int {
        val lower = voice.name.lowercase(Locale.ROOT)
        var score = 0
        if (lower.contains("male") || lower.contains("-m-") || lower.contains("#male")) score += 50
        if (lower.contains("bgd") || lower.contains("ban") || lower.contains("bd")) score += 20
        if (!voice.isNetworkConnectionRequired) score += 10
        if (lower.contains("female") || lower.contains("-f-")) score -= 30
        return score
    }

    fun formatVoiceDisplayName(voice: Voice): String {
        val region = when (voice.locale.country.uppercase(Locale.ROOT)) {
            "BD" -> "বাংলাদেশ"
            "IN" -> "ভারত"
            else -> voice.locale.displayCountry.ifBlank { "বাংলা" }
        }
        val mode = if (voice.isNetworkConnectionRequired) "নেটওয়ার্ক" else "অফলাইন"
        return "বাংলা ($region • $mode)"
    }

    fun speak(text: String, interrupt: Boolean = true, onDone: (() -> Unit)? = null) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onDone?.invoke()
            return
        }
        val engine = tts
        if (engine == null || !_isReady.value) {
            Log.w(TAG, "TTS not ready yet; skipping speech")
            onDone?.invoke()
            return
        }
        onSpeechCompleteCallback = onDone
        engine.setPitch(currentPitch)
        engine.setSpeechRate(currentRate)
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = UUID.randomUUID().toString()
        engine.speak(cleanText, queueMode, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
        onSpeechCompleteCallback = null
    }

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open TTS settings", e)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
        _isSpeaking.value = false
    }
}
