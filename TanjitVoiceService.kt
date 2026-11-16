package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "TanjitVoiceService"

class TanjitVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hub: AssistantHub
    private var callReceiver: IncomingCallReceiver? = null
    private var isActive = false
    private var directCommandWindowOpen = false

    private val restartRunnable = Runnable {
        if (isActive) {
            beginBackgroundListenCycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        hub = AssistantHub.getInstance(applicationContext)
        createNotificationChannel()
        registerCallReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceGracefully()
                return START_NOT_STICKY
            }
            ACTION_LISTEN_NOW -> {
                if (hasMicPermission()) {
                    ensureForeground()
                    isActive = true
                    hub.setBackgroundServiceRunning(true)
                    directCommandWindowOpen = true
                    beginBackgroundListenCycle()
                }
                return START_STICKY
            }
            else -> {
                if (!hasMicPermission()) {
                    Log.e(TAG, "Cannot start microphone foreground service without RECORD_AUDIO")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureForeground()
                if (!isActive) {
                    isActive = true
                    hub.setBackgroundServiceRunning(true)
                    updateNotification(getString(R.string.notification_listening))
                    scheduleNextListen(delayMs = 400L)
                }
                return START_STICKY
            }
        }
    }

    private fun ensureForeground() {
        val notification = buildNotification(getString(R.string.notification_standby))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginBackgroundListenCycle() {
        if (!isActive || !hasMicPermission()) return
        if (hub.isSpeaking.value) {
            scheduleNextListen(900L)
            return
        }

        val locale = hub.settings.value.speechLocale
        updateNotification(getString(R.string.notification_listening))

        hub.speechRecognizer.startListening(
            localeTag = locale,
            onResult = { recognized ->
                handleBackgroundUtterance(recognized)
            },
            onError = { errorCode, _ ->
                if (!isActive) return@startListening
                val delay = when (errorCode) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 4000L
                    else -> 800L
                }
                updateNotification(getString(R.string.notification_standby))
                scheduleNextListen(delay)
            }
        )
    }

    private fun handleBackgroundUtterance(utterance: String) {
        if (!isActive) return
        val wakeWordOnly = hub.settings.value.wakeWordOnlyInBackground
        val isRinging = hub.incomingCallState.value.isRinging

        // Allow direct receive/reject commands while phone is ringing even without wake word
        val cleaned = utterance.trim()
        val hasWakeWord = containsWakeWord(cleaned)

        if (!wakeWordOnly || directCommandWindowOpen || isRinging || hasWakeWord) {
            val commandPart = if (hasWakeWord) stripWakeWord(cleaned) else cleaned
            directCommandWindowOpen = false

            if (commandPart.isBlank()) {
                directCommandWindowOpen = true
                hub.ttsManager.speak("জি, বলুন। আমি শুনছি।") {
                    scheduleNextListen(300L)
                }
                return
            }

            serviceScope.launch {
                hub.processUserCommand(commandPart, speakReply = true) {
                    if (isActive) {
                        scheduleNextListen(600L)
                    }
                }
            }
        } else {
            // Wake word required and not detected; resume standby listening quietly
            scheduleNextListen(350L)
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return WAKE_WORDS.any { lower.contains(it) }
    }

    private fun stripWakeWord(text: String): String {
        var result = text
        for (word in WAKE_WORDS) {
            result = result.replace(Regex(Regex.escape(word), RegexOption.IGNORE_CASE), " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun scheduleNextListen(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        if (isActive) {
            handler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun stopServiceGracefully() {
        isActive = false
        directCommandWindowOpen = false
        handler.removeCallbacks(restartRunnable)
        hub.speechRecognizer.cancel()
        hub.setBackgroundServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerCallReceiver() {
        if (callReceiver != null) return
        val receiver = IncomingCallReceiver()
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        callReceiver = receiver
    }

    private fun unregisterCallReceiver() {
        callReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        callReceiver = null
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val listenNowIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, TanjitVoiceService::class.java).apply { action = ACTION_LISTEN_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, TanjitVoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "শুনুন", listenNowIntent)
            .addAction(0, "বন্ধ করুন", stopIntent)
            .build()
    }

    override fun onDestroy() {
        isActive = false
        handler.removeCallbacks(restartRunnable)
        unregisterCallReceiver()
        hub.setBackgroundServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "tanjit_voice_assistant_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_LISTEN_NOW = "com.example.service.ACTION_LISTEN_NOW"

        private val WAKE_WORDS = listOf(
            "হ্যালো তানজিত",
            "ওকে তানজিত",
            "তানজিত এআই",
            "তানজিত",
            "hey tanjit",
            "ok tanjit",
            "hello tanjit",
            "tanjit ai",
            "tanjit"
        )
    }
}
