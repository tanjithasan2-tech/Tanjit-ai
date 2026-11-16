package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.actions.DeviceActionExecutor
import com.example.ai.CommandRouter
import com.example.data.AssistantAction
import com.example.data.AssistantSettings
import com.example.data.CallAssistEvent
import com.example.data.CommandExecutionResult
import com.example.data.ContactMatch
import com.example.data.ConversationMessage
import com.example.data.IncomingCallState
import com.example.data.MessageSender
import com.example.data.OrbState
import com.example.data.PermissionSummary
import com.example.data.SettingsRepository
import com.example.data.VoiceProfile
import com.example.voice.BengaliSpeechRecognizerManager
import com.example.voice.BengaliTtsManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AssistantHub"

class AssistantHub private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(appContext)
    val ttsManager = BengaliTtsManager(appContext)
    val speechRecognizer = BengaliSpeechRecognizerManager(appContext)
    val actionExecutor = DeviceActionExecutor(appContext)
    val commandRouter = CommandRouter()

    val settings: StateFlow<AssistantSettings> = settingsRepository.settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AssistantSettings()
    )

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ConversationMessage(
                id = "welcome",
                sender = MessageSender.ASSISTANT,
                text = "আসসালামু আলাইকুম! আমি TANJIT AI, আপনার বাংলা ভয়েস অ্যাসিস্ট্যান্ট। মাইক্রোফোন ট্যাপ করে বলুন — যেমন 'ইউটিউব খোলো', 'ফেসবুক খোলো', 'ফ্ল্যাশলাইট জ্বালাও' বা যেকোনো প্রশ্ন করুন।",
                actionBadge = "TANJIT CORE READY"
            )
        )
    )
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _isBackgroundServiceRunning = MutableStateFlow(false)
    val isBackgroundServiceRunning: StateFlow<Boolean> = _isBackgroundServiceRunning.asStateFlow()

    private val _incomingCallState = MutableStateFlow(IncomingCallState())
    val incomingCallState: StateFlow<IncomingCallState> = _incomingCallState.asStateFlow()

    private val _callAssistEvents = MutableStateFlow<List<CallAssistEvent>>(emptyList())
    val callAssistEvents: StateFlow<List<CallAssistEvent>> = _callAssistEvents.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactMatch>>(emptyList())
    val contacts: StateFlow<List<ContactMatch>> = _contacts.asStateFlow()

    private val _permissionSummary = MutableStateFlow(PermissionSummary())
    val permissionSummary: StateFlow<PermissionSummary> = _permissionSummary.asStateFlow()

    val isTorchOn: StateFlow<Boolean> = actionExecutor.isTorchOn
    val hasFlashlight: StateFlow<Boolean> = actionExecutor.hasFlashlight
    val rmsLevel: StateFlow<Float> = speechRecognizer.rmsLevel
    val partialSpeechText: StateFlow<String> = speechRecognizer.partialText
    val isSpeechListening: StateFlow<Boolean> = speechRecognizer.isListening
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking
    val isBengaliVoiceMissing: StateFlow<Boolean> = ttsManager.isBengaliVoiceMissing
    val availableVoices: StateFlow<List<VoiceProfile>> = ttsManager.availableVoices
    val activeVoiceLabel: StateFlow<String> = ttsManager.activeVoiceLabel

    init {
        refreshPermissions()

        scope.launch {
            settingsRepository.settingsFlow.collectLatest { cfg ->
                ttsManager.updateSettings(
                    localeTag = cfg.speechLocale,
                    malePitch = cfg.malePitch,
                    speechRate = cfg.speechRate,
                    preferredVoice = cfg.preferredVoiceName
                )
            }
        }

        scope.launch {
            ttsManager.isSpeaking.collectLatest { speaking ->
                if (speaking) {
                    _orbState.value = OrbState.SPEAKING
                } else if (_orbState.value == OrbState.SPEAKING) {
                    _orbState.value = if (speechRecognizer.isListening.value) OrbState.LISTENING else OrbState.IDLE
                }
            }
        }

        scope.launch {
            speechRecognizer.isListening.collectLatest { listening ->
                if (listening) {
                    _orbState.value = OrbState.LISTENING
                } else if (_orbState.value == OrbState.LISTENING) {
                    _orbState.value = OrbState.IDLE
                }
            }
        }
    }

    fun refreshPermissions() {
        val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
        val contacts = hasPermission(Manifest.permission.READ_CONTACTS)
        val phone = hasPermission(Manifest.permission.READ_PHONE_STATE) &&
            hasPermission(Manifest.permission.CALL_PHONE) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || hasPermission(Manifest.permission.ANSWER_PHONE_CALLS))
        val camera = hasPermission(Manifest.permission.CAMERA)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        _permissionSummary.value = PermissionSummary(
            microphone = mic,
            contacts = contacts,
            phoneAndCalls = phone,
            camera = camera,
            notifications = notifications
        )

        if (contacts) {
            refreshContacts()
        }
    }

    fun refreshContacts() {
        scope.launch {
            _contacts.value = actionExecutor.loadSavedContacts(limit = 300)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun startForegroundListening(onFinished: (() -> Unit)? = null) {
        if (ttsManager.isSpeaking.value) {
            ttsManager.stopSpeaking()
        }
        val locale = settings.value.speechLocale
        _orbState.value = OrbState.LISTENING
        speechRecognizer.startListening(
            localeTag = locale,
            onResult = { recognized ->
                scope.launch {
                    processUserCommand(recognized, speakReply = true)
                    onFinished?.invoke()
                }
            },
            onError = { _, message ->
                _orbState.value = OrbState.ERROR
                appendSystemMessage(message, isError = true)
                onFinished?.invoke()
            }
        )
    }

    fun stopForegroundListening() {
        speechRecognizer.stopListening()
        if (!ttsManager.isSpeaking.value) {
            _orbState.value = OrbState.IDLE
        }
    }

    suspend fun processUserCommand(
        utterance: String,
        speakReply: Boolean = true,
        onSpeechDone: (() -> Unit)? = null
    ) {
        val clean = utterance.trim()
        if (clean.isEmpty()) {
            onSpeechDone?.invoke()
            return
        }

        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.USER,
                text = clean
            )
        )

        _orbState.value = OrbState.THINKING

        val contactHints = _contacts.value.map { it.name }
        val routed = commandRouter.routeCommand(
            rawUtterance = clean,
            contactNamesHint = contactHints,
            isCallRinging = _incomingCallState.value.isRinging
        )

        val execResult = executeAction(routed.action)
        val finalReply = when {
            execResult.isError -> execResult.spokenReplyBn
            routed.action is AssistantAction.ConversationalOnly ->
                routed.aiReplyBn ?: execResult.spokenReplyBn
            !routed.aiReplyBn.isNullOrBlank() && routed.usedGemini -> routed.aiReplyBn
            else -> execResult.spokenReplyBn
        }

        val badge = execResult.actionBadge ?: if (routed.usedGemini) "TANJIT AI • Gemini" else null

        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                text = finalReply,
                actionBadge = badge,
                isError = execResult.isError
            )
        )

        if (speakReply) {
            ttsManager.speak(finalReply) {
                if (_orbState.value == OrbState.SPEAKING) {
                    _orbState.value = OrbState.IDLE
                }
                onSpeechDone?.invoke()
            }
        } else {
            _orbState.value = OrbState.IDLE
            onSpeechDone?.invoke()
        }
    }

    private suspend fun executeAction(action: AssistantAction): CommandExecutionResult {
        return when (action) {
            is AssistantAction.OpenYouTube -> actionExecutor.openYouTube(action.searchQuery)
            AssistantAction.OpenFacebook -> actionExecutor.openFacebook()
            is AssistantAction.OpenWebsite -> actionExecutor.openWebsite(action.url, action.displayLabel)
            is AssistantAction.CallContact -> actionExecutor.callContactByQuery(action.queryName)
            is AssistantAction.SetFlashlight -> actionExecutor.setFlashlight(action.enabled)
            is AssistantAction.OpenCamera -> actionExecutor.openCamera(action.frontFacing)
            AssistantAction.AnswerIncomingCall -> actionExecutor.answerIncomingCall()
            AssistantAction.DeclineIncomingCall -> actionExecutor.declineIncomingCall()
            AssistantAction.ConversationalOnly -> CommandExecutionResult(
                spokenReplyBn = "জি, বলুন আমি কীভাবে সাহায্য করতে পারি?",
                actionBadge = "AI Reply"
            )
        }
    }

    fun toggleFlashlightAndAnnounce() {
        val result = actionExecutor.toggleFlashlight()
        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                text = result.spokenReplyBn,
                actionBadge = result.actionBadge,
                isError = result.isError
            )
        )
        ttsManager.speak(result.spokenReplyBn)
    }

    fun launchCameraAndAnnounce(frontFacing: Boolean = false) {
        val result = actionExecutor.openCamera(frontFacing)
        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                text = result.spokenReplyBn,
                actionBadge = result.actionBadge,
                isError = result.isError
            )
        )
        ttsManager.speak(result.spokenReplyBn)
    }

    fun callContactDirect(contact: ContactMatch) {
        val result = actionExecutor.callSpecificContact(contact)
        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                text = result.spokenReplyBn,
                actionBadge = result.actionBadge,
                isError = result.isError
            )
        )
        ttsManager.speak(result.spokenReplyBn)
    }

    fun startBackgroundService(): Boolean {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            appendSystemMessage(
                "ব্যাকগ্রাউন্ড ভয়েস সার্ভিস চালু করতে মাইক্রোফোন অনুমতি প্রয়োজন।",
                isError = true
            )
            return false
        }
        return try {
            val intent = Intent(appContext, TanjitVoiceService::class.java).apply {
                action = TanjitVoiceService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start TanjitVoiceService", e)
            appendSystemMessage("ব্যাকগ্রাউন্ড সার্ভিস চালু করা যায়নি: ${e.localizedMessage}", isError = true)
            false
        }
    }

    fun stopBackgroundService() {
        val intent = Intent(appContext, TanjitVoiceService::class.java).apply {
            action = TanjitVoiceService.ACTION_STOP
        }
        appContext.startService(intent)
        _isBackgroundServiceRunning.value = false
    }

    internal fun setBackgroundServiceRunning(running: Boolean) {
        _isBackgroundServiceRunning.value = running
    }

    fun onIncomingCallRinging(phoneNumber: String?) {
        scope.launch {
            val resolvedName = actionExecutor.lookupCallerName(phoneNumber)
            val callerDisplay = resolvedName ?: phoneNumber?.takeIf { it.isNotBlank() } ?: "অজানা নম্বর"
            _incomingCallState.value = IncomingCallState(
                isRinging = true,
                callerName = resolvedName,
                phoneNumber = phoneNumber,
                announcementSpoken = settings.value.incomingCallAnnounce
            )

            addCallEvent(callerDisplay, "ইনকামিং কল রিং হচ্ছে")

            if (settings.value.incomingCallAnnounce) {
                val spokenCaller = resolvedName ?: phoneNumber?.toCharArray()?.joinToString(" ") ?: "অজানা নম্বর"
                val announcement = "ইনকামিং কল আসছে। $spokenCaller আপনাকে ফোন করছেন।"
                ttsManager.speak(announcement)
            }
        }
    }

    fun onCallStateEnded(wasOffhook: Boolean) {
        val prev = _incomingCallState.value
        if (prev.isRinging) {
            val label = prev.callerName ?: prev.phoneNumber ?: "অজানা নম্বর"
            addCallEvent(label, if (wasOffhook) "কল রিসিভ করা হয়েছে" else "কল শেষ / মিসড")
        }
        _incomingCallState.value = IncomingCallState(isRinging = false)
    }

    private fun addCallEvent(caller: String, status: String) {
        _callAssistEvents.update { current ->
            (listOf(CallAssistEvent(UUID.randomUUID().toString(), caller, status)) + current).take(15)
        }
    }

    fun testMaleBengaliVoice() {
        ttsManager.speak("হ্যালো, আমি তানজিত এআই। আমি আপনার বাংলা ভয়েস অ্যাসিস্ট্যান্ট হিসেবে প্রস্তুত আছি।")
    }

    fun clearConversation() {
        _messages.value = listOf(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                text = "চ্যাট পরিষ্কার করা হয়েছে। নতুন বাংলা ভয়েস কমান্ড দিন।",
                actionBadge = "READY"
            )
        )
    }

    private fun appendMessage(message: ConversationMessage) {
        _messages.update { (it + message).takeLast(60) }
    }

    private fun appendSystemMessage(text: String, isError: Boolean = false) {
        appendMessage(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.SYSTEM,
                text = text,
                isError = isError
            )
        )
    }

    companion object {
        @Volatile
        private var instance: AssistantHub? = null

        fun getInstance(context: Context): AssistantHub {
            return instance ?: synchronized(this) {
                instance ?: AssistantHub(context.applicationContext).also { instance = it }
            }
        }
    }
}
