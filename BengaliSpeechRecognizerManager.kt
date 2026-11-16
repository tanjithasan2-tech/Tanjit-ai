package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BengaliSpeechRecognizer"

class BengaliSpeechRecognizerManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var onFinalResult: ((String) -> Unit)? = null
    private var onErrorCallback: ((Int, String) -> Unit)? = null

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startListening(
        localeTag: String = "bn-BD",
        onResult: (String) -> Unit,
        onError: (Int, String) -> Unit
    ) {
        mainHandler.post {
            if (!hasRecordAudioPermission()) {
                onError(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    "মাইক্রোফোন অনুমতি দেওয়া হয়নি। অনুগ্রহ করে সেটআপ থেকে অনুমতি দিন।"
                )
                return@post
            }

            if (!isRecognitionAvailable()) {
                onError(
                    SpeechRecognizer.ERROR_CLIENT,
                    "এই ডিভাইসে ভয়েস রেকগনিশন সার্ভিস পাওয়া যায়নি। নিচের টেক্সট বক্স ব্যবহার করতে পারেন।"
                )
                return@post
            }

            onFinalResult = onResult
            onErrorCallback = onError
            _partialText.value = ""
            _rmsLevel.value = 0f

            destroyInternal()

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                recognizer.setRecognitionListener(createListener())
                speechRecognizer = recognizer

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                _isListening.value = true
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SpeechRecognizer", e)
                _isListening.value = false
                onError(
                    SpeechRecognizer.ERROR_CLIENT,
                    "ভয়েস চালু করতে সমস্যা হয়েছে: ${e.localizedMessage ?: "অজানা ত্রুটি"}"
                )
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "stopListening exception", e)
            }
            _isListening.value = false
            _rmsLevel.value = 0f
        }
    }

    fun cancel() {
        mainHandler.post {
            destroyInternal()
            _isListening.value = false
            _rmsLevel.value = 0f
            _partialText.value = ""
        }
    }

    private fun destroyInternal() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroyInternal error", e)
        } finally {
            speechRecognizer = null
        }
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
        }

        override fun onBeginningOfSpeech() {
            _isListening.value = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            _isListening.value = false
            _rmsLevel.value = 0f
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "অডিও রেকর্ডিং ত্রুটি হয়েছে।"
                SpeechRecognizer.ERROR_CLIENT -> "ভয়েস ক্লায়েন্ট ত্রুটি হয়েছে।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোন অনুমতি প্রয়োজন।"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ভয়েস রেকগনিশনের জন্য ইন্টারনেট সংযোগ প্রয়োজন।"
                SpeechRecognizer.ERROR_NO_MATCH -> "কোনো কথা বোঝা যায়নি, আবার চেষ্টা করুন।"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ভয়েস সার্ভিস ব্যস্ত, একটু পর চেষ্টা করুন।"
                SpeechRecognizer.ERROR_SERVER -> "সার্ভার থেকে ভয়েস প্রসেস করা যায়নি।"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো শব্দ শোনা যায়নি।"
                else -> "ভয়েস শোনা সম্ভব হয়নি (কোড $error)।"
            }
            onErrorCallback?.invoke(error, message)
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _rmsLevel.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            _partialText.value = ""
            if (best.isNotEmpty()) {
                onFinalResult?.invoke(best)
            } else {
                onErrorCallback?.invoke(
                    SpeechRecognizer.ERROR_NO_MATCH,
                    "কোনো কথা শোনা যায়নি, আবার বলুন।"
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) {
                _partialText.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun destroy() {
        mainHandler.post {
            destroyInternal()
            _isListening.value = false
        }
    }
}
