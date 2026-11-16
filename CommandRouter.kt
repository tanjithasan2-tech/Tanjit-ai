package com.example.ai

import android.util.Log
import com.example.data.AssistantAction
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "CommandRouter"

data class RoutedCommand(
    val action: AssistantAction,
    val aiReplyBn: String? = null,
    val usedGemini: Boolean = false
)

class CommandRouter {

    private val intentSchema = Schema.obj(
        properties = mapOf(
            "actionType" to Schema.enumeration(
                values = listOf(
                    "OPEN_YOUTUBE",
                    "OPEN_FACEBOOK",
                    "OPEN_WEBSITE",
                    "CALL_CONTACT",
                    "FLASHLIGHT_ON",
                    "FLASHLIGHT_OFF",
                    "OPEN_CAMERA",
                    "OPEN_SELFIE_CAMERA",
                    "ANSWER_CALL",
                    "DECLINE_CALL",
                    "CHAT"
                ),
                description = "The exact device action or CHAT for general Bengali Q&A"
            ),
            "target" to Schema.string(
                description = "URL/domain for OPEN_WEBSITE, YouTube search query for OPEN_YOUTUBE (empty if home), or exact contact name for CALL_CONTACT.",
                nullable = true
            ),
            "replyBn" to Schema.string(
                description = "Natural, polite, concise male Bengali voice response (1-3 sentences in Bengali script)."
            )
        ),
        optionalProperties = listOf("target")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = intentSchema
                temperature = 0.35f
            },
            systemInstruction = content {
                text(
                    """
                    You are TANJIT AI, a futuristic Bengali male voice assistant running on Android.
                    Always respond in natural, confident, polite Bengali script (বাংলা) suitable for a male voice response.
                    Keep replies concise (1 to 3 sentences) unless asked for detailed information.
                    Map user requests to device actions whenever applicable:
                    - YouTube -> OPEN_YOUTUBE (put search keywords in target only if searching).
                    - Facebook -> OPEN_FACEBOOK.
                    - Any website/URL -> OPEN_WEBSITE with full domain or https URL in target.
                    - Calling someone -> CALL_CONTACT with the closest matching name from the provided contact list (or spoken name) in target.
                    - Flashlight on/off -> FLASHLIGHT_ON / FLASHLIGHT_OFF.
                    - Camera / selfie -> OPEN_CAMERA / OPEN_SELFIE_CAMERA.
                    - Answer/decline incoming call -> ANSWER_CALL / DECLINE_CALL.
                    - Otherwise use CHAT and answer the user's question directly in Bengali.
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun routeCommand(
        rawUtterance: String,
        contactNamesHint: List<String> = emptyList(),
        isCallRinging: Boolean = false
    ): RoutedCommand {
        val text = rawUtterance.trim()
        val lower = text.lowercase(Locale.ROOT)

        // 1. Instant local deterministic checks (zero latency)
        parseLocalCommand(text, lower, isCallRinging)?.let { localAction ->
            return RoutedCommand(action = localAction, usedGemini = false)
        }

        // 2. Intelligent Bengali NLU & Conversation via Firebase AI Logic (Gemini)
        return try {
            val contactHintBlock = if (contactNamesHint.isNotEmpty()) {
                "Saved contacts: ${contactNamesHint.take(40).joinToString(", ")}\n"
            } else {
                ""
            }
            val prompt = "${contactHintBlock}Incoming call ringing: $isCallRinging\nUser command: $text"
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val jsonStr = response.text
                ?: throw IllegalStateException("AI থেকে কোনো উত্তর পাওয়া যায়নি।")
            val json = JSONObject(jsonStr)
            val actionType = json.optString("actionType", "CHAT")
            val target = json.optString("target", "").trim()
            val replyBn = json.optString("replyBn", "জি, আমি আপনার কথা বুঝতে পেরেছি।").trim()

            val action = when (actionType) {
                "OPEN_YOUTUBE" -> AssistantAction.OpenYouTube(target.ifBlank { null })
                "OPEN_FACEBOOK" -> AssistantAction.OpenFacebook
                "OPEN_WEBSITE" -> {
                    if (target.isNotBlank()) {
                        AssistantAction.OpenWebsite(target, target)
                    } else {
                        AssistantAction.ConversationalOnly
                    }
                }
                "CALL_CONTACT" -> {
                    if (target.isNotBlank()) {
                        AssistantAction.CallContact(target)
                    } else {
                        AssistantAction.ConversationalOnly
                    }
                }
                "FLASHLIGHT_ON" -> AssistantAction.SetFlashlight(true)
                "FLASHLIGHT_OFF" -> AssistantAction.SetFlashlight(false)
                "OPEN_CAMERA" -> AssistantAction.OpenCamera(frontFacing = false)
                "OPEN_SELFIE_CAMERA" -> AssistantAction.OpenCamera(frontFacing = true)
                "ANSWER_CALL" -> AssistantAction.AnswerIncomingCall
                "DECLINE_CALL" -> AssistantAction.DeclineIncomingCall
                else -> AssistantAction.ConversationalOnly
            }

            RoutedCommand(action = action, aiReplyBn = replyBn, usedGemini = true)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini command routing timed out", e)
            RoutedCommand(
                action = AssistantAction.ConversationalOnly,
                aiReplyBn = "নেটওয়ার্ক সাড়া দিতে দেরি করছে। অনুগ্রহ করে আবার চেষ্টা করুন।"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI inference failed", e)
            RoutedCommand(
                action = AssistantAction.ConversationalOnly,
                aiReplyBn = "AI সার্ভারে সংযোগ পাওয়া যায়নি: ${e.localizedMessage ?: "ইন্টারনেট বা App Check পরীক্ষা করুন।"}"
            )
        }
    }

    private fun parseLocalCommand(
        text: String,
        lower: String,
        isCallRinging: Boolean
    ): AssistantAction? {
        // Incoming call quick voice commands
        if (isCallRinging) {
            if (containsAny(lower, "রিসিভ", "ধরো", "উত্তর", "হ্যালো", "receive", "answer", "accept", "pick up")) {
                return AssistantAction.AnswerIncomingCall
            }
            if (containsAny(lower, "কেটে দাও", "কাটো", "রিজেক্ট", "বন্ধ করো", "decline", "reject", "cut", "hang up")) {
                return AssistantAction.DeclineIncomingCall
            }
        }

        // Flashlight ON / OFF
        if (containsAny(lower, "ফ্ল্যাশ", "টর্চ", "flashlight", "torch", "ফ্ল্যাশলাইট")) {
            return if (containsAny(lower, "বন্ধ", "অফ", "নেভাও", "off", "close", "stop")) {
                AssistantAction.SetFlashlight(false)
            } else {
                AssistantAction.SetFlashlight(true)
            }
        }

        // Camera / Selfie
        if (containsAny(lower, "সেলফি", "selfie", "front camera", "সামনের ক্যামেরা")) {
            return AssistantAction.OpenCamera(frontFacing = true)
        }
        if (containsAny(lower, "ক্যামেরা", "ছবি তোলো", "camera", "take photo")) {
            return AssistantAction.OpenCamera(frontFacing = false)
        }

        // YouTube
        if (containsAny(lower, "ইউটিউব", "youtube")) {
            val searchQuery = extractYouTubeSearchQuery(text, lower)
            return AssistantAction.OpenYouTube(searchQuery)
        }

        // Facebook
        if (containsAny(lower, "ফেসবুক", "facebook", "fb খোলো")) {
            return AssistantAction.OpenFacebook
        }

        // Phone Call
        if (containsAny(lower, "কল করো", "কল দাও", "ফোন করো", "ফোন দাও", "ডায়াল করো", "call ")) {
            val contactQuery = extractContactQuery(text)
            if (contactQuery.isNotBlank()) {
                return AssistantAction.CallContact(contactQuery)
            }
        }

        // Popular Bengali/English websites
        val knownSites = mapOf(
            "গুগল" to ("https://www.google.com" to "Google"),
            "google" to ("https://www.google.com" to "Google"),
            "প্রথম আলো" to ("https://www.prothomalo.com" to "প্রথম আলো"),
            "prothom alo" to ("https://www.prothomalo.com" to "Prothom Alo"),
            "উইকিপিডিয়া" to ("https://bn.wikipedia.org" to "উইকিপিডিয়া"),
            "উইকিপিডিয়া" to ("https://bn.wikipedia.org" to "উইকিপিডিয়া"),
            "wikipedia" to ("https://en.wikipedia.org" to "Wikipedia"),
            "জিমেইল" to ("https://mail.google.com" to "Gmail"),
            "gmail" to ("https://mail.google.com" to "Gmail"),
            "ইনস্টাগ্রাম" to ("https://www.instagram.com" to "Instagram"),
            "instagram" to ("https://www.instagram.com" to "Instagram"),
            "গিটহাব" to ("https://github.com" to "GitHub"),
            "github" to ("https://github.com" to "GitHub"),
            "বিবিসি বাংলা" to ("https://www.bbc.com/bengali" to "বিবিসি বাংলা"),
            "দারাজ" to ("https://www.daraz.com.bd" to "দারাজ")
        )
        knownSites.entries.firstOrNull { (keyword, _) ->
            lower.contains(keyword) && containsAny(lower, "খোলো", "যাও", "ওপেন", "দেখাও", "open", "visit", "go to")
        }?.let { (_, target) ->
            return AssistantAction.OpenWebsite(target.first, target.second)
        }

        // Direct domain extraction (e.g. "prothomalo.com খোলো", "open bbc.com", "ওয়েবসাইট example.org")
        val domainRegex = Regex("""([a-zA-Z0-9-]+\.(com|org|net|edu|gov|io|ai|bd|com\.bd|info|app|dev)(/[^\s]*)?)""")
        domainRegex.find(lower)?.let { match ->
            val domain = match.groupValues[1]
            return AssistantAction.OpenWebsite(domain, domain)
        }

        if (containsAny(lower, "ওয়েবসাইট", "ওয়েবসাইট", "ডট কম", "dot com")) {
            val cleaned = text
                .replace(Regex("(ওয়েবসাইট|ওয়েবসাইট|খোলো|চালু করো|ওপেন করো|যাও|open|website)", RegexOption.IGNORE_CASE), " ")
                .replace("ডট কম", ".com")
                .replace("ডট অর্গ", ".org")
                .replace("ডট বিডি", ".com.bd")
                .replace(Regex("\\s+"), "")
                .trim()
            if (cleaned.contains(".")) {
                return AssistantAction.OpenWebsite(cleaned, cleaned)
            }
        }

        return null
    }

    private fun extractYouTubeSearchQuery(raw: String, lower: String): String? {
        if (!containsAny(lower, "সার্চ", "খোঁজো", "খুঁজো", "search", "চালাও", "play")) {
            return null
        }
        val stripped = raw
            .replace(Regex("(ইউটিউবে|ইউটিউব|youtube|সার্চ করো|সার্চ|খোঁজো|খুঁজো|চালাও|খোলো|search|play|on|in)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.ifBlank { null }
    }

    private fun extractContactQuery(raw: String): String {
        return raw
            .replace(Regex("(কে|কল করো|কল দাও|ফোন করো|ফোন দাও|ডায়াল করো|দয়া করে|প্লিজ|call|dial|please)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[-–]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
