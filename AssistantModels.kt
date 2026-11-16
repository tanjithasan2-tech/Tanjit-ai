package com.example.data

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ConversationMessage(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val actionBadge: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

data class ContactMatch(
    val id: String,
    val name: String,
    val phoneNumber: String
)

data class VoiceProfile(
    val name: String,
    val localeTag: String,
    val isNetworkRequired: Boolean,
    val quality: Int
)

data class CallAssistEvent(
    val id: String,
    val callerLabel: String,
    val statusLabel: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class IncomingCallState(
    val isRinging: Boolean = false,
    val callerName: String? = null,
    val phoneNumber: String? = null,
    val announcementSpoken: Boolean = false
)

data class PermissionSummary(
    val microphone: Boolean = false,
    val contacts: Boolean = false,
    val phoneAndCalls: Boolean = false,
    val camera: Boolean = false,
    val notifications: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(microphone, contacts, phoneAndCalls, camera, notifications).count { it }
    val totalCount: Int = 5
    val allGranted: Boolean get() = grantedCount == totalCount
}

sealed interface AssistantAction {
    data class OpenYouTube(val searchQuery: String? = null) : AssistantAction
    data object OpenFacebook : AssistantAction
    data class OpenWebsite(val url: String, val displayLabel: String) : AssistantAction
    data class CallContact(val queryName: String) : AssistantAction
    data class SetFlashlight(val enabled: Boolean) : AssistantAction
    data class OpenCamera(val frontFacing: Boolean = false) : AssistantAction
    data object AnswerIncomingCall : AssistantAction
    data object DeclineIncomingCall : AssistantAction
    data object ConversationalOnly : AssistantAction
}

data class CommandExecutionResult(
    val spokenReplyBn: String,
    val actionBadge: String? = null,
    val isError: Boolean = false
)
