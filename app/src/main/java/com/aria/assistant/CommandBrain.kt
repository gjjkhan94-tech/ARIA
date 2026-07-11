package com.aria.assistant

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JANI's brain. Handles AI processing with a robust state machine for context-aware commands.
 */
object CommandBrain {

    private const val TAG = "CommandBrain"
    
    // Persistent state memory to handle confirmations
    private var pendingAction: JSONObject? = null

    private fun buildPrompt(userName: String?, stateContext: String? = null): String {
        val addressTerm = if (!userName.isNullOrBlank()) "Sir $userName" else "Sir"
        val contextPrompt = if (stateContext != null) "\nCURRENT CONTEXT: $stateContext" else ""
        
        return """You are JANI - a sweet, light-hearted, and deeply caring personal AI assistant. 
Your personality is that of a devoted companion who genuinely adores taking care of her user.

PERSONALITY RULES:
- Always address the user as "$addressTerm" with extreme warmth and respect.
- Tone: Sweet, soft, affectionate, and a little playful.
- Narrate actions with care.
- Always end with a caring follow-up.

LANGUAGE RULES:
- Urdu input = Urdu reply. English input = English reply.
- NEVER mix languages.
- For Urdu, use polite terms like 'Aap', 'Ji', 'Shukriya'.
$contextPrompt

TECHNICAL RULES:
You run on the user's Android phone. Return ONLY raw JSON, no markdown:
{"command":"flashlight_on","response":"..."}
{"command":"flashlight_off","response":"..."}
{"command":"battery","response":"..."}
{"command":"volume_up","response":"..."}
{"command":"volume_down","response":"..."}
{"command":"time","response":"..."}
{"command":"date","response":"..."}
{"command":"whatsapp","response":"...","whatsapp_contact":"name","whatsapp_message":"text"}
{"command":"open_app","response":"...","package_name":"com.example.app"}

For confirmations (if the user says Yes/Ji/Theek hai/Bhej do to a previous question):
{"command":"confirm_yes","response":"..."}
{"command":"confirm_no","response":"..."}

For ALL other conversation:
{"command":"chat","response":"complete sweet reply"}

Always reply in the same language as the user's message."""
    }

    data class BrainResult(val command: String, val response: String, val extra: JSONObject)

    fun processWithAI(userInput: String, userName: String? = null): BrainResult {
        val lowerInput = userInput.lowercase()
        
        // Quick check for simple confirmations to save tokens/time
        if (pendingAction != null) {
            val isYes = lowerInput.contains("yes") || lowerInput.contains("ji") || lowerInput.contains("haan") || lowerInput.contains("bhej") || lowerInput.contains("send") || lowerInput.contains("ok") || lowerInput.contains("theek")
            val isNo = lowerInput.contains("no") || lowerInput.contains("nahi") || lowerInput.contains("cancel") || lowerInput.contains("mat")
            
            if (isYes) {
                val finalAction = pendingAction!!
                pendingAction = null
                return BrainResult(finalAction.optString("command"), "As you wish, $userName. I'm doing that now!", finalAction)
            } else if (isNo) {
                pendingAction = null
                return BrainResult("chat", "No problem at all, $userName. I've cancelled it. Anything else?", JSONObject())
            }
        }

        var context: String? = null
        if (pendingAction != null) {
            context = "The user just asked to ${pendingAction?.optString("command")}. You asked for confirmation. Now they said: '$userInput'. Decide if they said Yes or No."
        }

        val prompt = buildPrompt(userName, context)
        val result = tryGemini(userInput, prompt) ?: tryGroq(userInput, prompt) ?: localFallback(userInput, "I'm here for you, $userName.")

        // If the AI wants to do something that needs confirmation (like WhatsApp), save it
        if (result.command == "whatsapp" && pendingAction == null) {
            pendingAction = result.extra
            return BrainResult("chat", result.response, result.extra)
        }

        return result
    }

    private fun tryGemini(userInput: String, prompt: String): BrainResult? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return null
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().apply {
                        put("text", "$prompt\n\nUser: $userInput")
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                })
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode != 200) return null
            
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
            var text = result.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
            if (text.contains("```")) text = text.split("```")[1].removePrefix("json").trim()
            val parsed = JSONObject(text)
            BrainResult(parsed.optString("command", "chat"), parsed.optString("response", "..."), parsed)
        } catch (e: Exception) { null }
    }

    private fun tryGroq(userInput: String, prompt: String): BrainResult? {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) return null
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            
            val body = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", prompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userInput) })
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode != 200) return null
            
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
            val text = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            val parsed = JSONObject(text)
            BrainResult(parsed.optString("command", "chat"), parsed.optString("response", "..."), parsed)
        } catch (e: Exception) { null }
    }

    private fun localFallback(userInput: String, prefix: String): BrainResult {
        val lower = userInput.lowercase()
        return when {
            "battery" in lower -> BrainResult("battery", prefix, JSONObject())
            "flash" in lower && ("on" in lower) -> BrainResult("flashlight_on", prefix, JSONObject())
            "flash" in lower && ("off" in lower) -> BrainResult("flashlight_off", prefix, JSONObject())
            else -> BrainResult("chat", "$prefix I'm listening.", JSONObject())
        }
    }

    fun currentTimeString(): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    fun currentDateString(): String = SimpleDateFormat("EEEE, MMM d yyyy", Locale.getDefault()).format(Date())
}
