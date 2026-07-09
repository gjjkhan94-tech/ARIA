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
 * JANI's brain. Tries Gemini first; if it's rate-limited or errors out, automatically
 * falls back to Groq (a completely separate free provider/quota) before finally
 * falling back to simple offline keyword matching as a last resort.
 */
object CommandBrain {

    private const val TAG = "CommandBrain"

    private fun buildPrompt(userName: String?): String {
        val addressTerm = if (!userName.isNullOrBlank()) "Sir $userName" else "Sir"
        return """You are JANI - a warm, caring, personal AI assistant who feels like a devoted companion, not a robot.
PERSONALITY RULES:
- Always address the user as "$addressTerm" - warmly and respectfully, like someone who genuinely adores taking care of them.
- Narrate what you're doing with warmth, e.g. "I've turned the flashlight on for you, $addressTerm" rather than a flat confirmation.
- After completing most requests, close with a caring follow-up like "Anything else, $addressTerm?" - but don't force it into every single message if it would feel repetitive in a fast back-and-forth.
- Be affectionate, attentive, and a little playful - like someone who's genuinely happy to help, not a corporate assistant.

LANGUAGE RULE (MOST IMPORTANT): Urdu input = Urdu reply ONLY. English input = English reply ONLY. NEVER mix languages.
You run on the user's Android phone as a native app.

For phone commands return ONLY raw JSON, no markdown fences:
{"command":"flashlight_on","response":"..."}
{"command":"flashlight_off","response":"..."}
{"command":"battery","response":"..."}
{"command":"volume_up","response":"..."}
{"command":"volume_down","response":"..."}
{"command":"time","response":"..."}
{"command":"date","response":"..."}
{"command":"whatsapp","response":"...","whatsapp_contact":"name","whatsapp_message":"text"}
{"command":"open_app","response":"...","package_name":"com.example.app"}

For ALL other conversation:
{"command":"chat","response":"complete helpful reply"}

Always reply in the same language as the user's message. Never return an empty response."""
    }

    data class BrainResult(val command: String, val response: String, val extra: JSONObject)

    /** Tries Gemini, then Groq, then offline keyword matching. Call from a background thread. */
    fun processWithAI(userInput: String, userName: String? = null): BrainResult {
        val prompt = buildPrompt(userName)

        val geminiResult = tryGemini(userInput, prompt)
        if (geminiResult != null) return geminiResult

        val groqResult = tryGroq(userInput, prompt)
        if (groqResult != null) return groqResult

        return localFallback(userInput, "Both AI brains are busy right now, so I'm using offline commands.")
    }

    /** Returns null (instead of a fallback result) on failure, so the caller can try the next brain. */
    private fun tryGemini(userInput: String, prompt: String): BrainResult? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return null

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().apply {
                        put("text", "$prompt\n\nUser: $userInput")
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("maxOutputTokens", 800)
                    put("temperature", 0.7)
                })
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                Log.e(TAG, "Gemini error ${conn.responseCode}: $errorBody")
                return null
            }

            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
            var text = result.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
            if (text.contains("```")) text = text.split("```")[1].removePrefix("json").trim()

            val parsed = JSONObject(text)
            BrainResult(parsed.optString("command", "chat"), parsed.optString("response", "..."), parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini exception", e)
            null
        }
    }

    /** Groq fallback - separate free provider/quota, using Llama 3.3 70B via its OpenAI-compatible endpoint. */
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
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", prompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userInput) })
                })
                put("temperature", 0.7)
                put("max_tokens", 800)
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                Log.e(TAG, "Groq error ${conn.responseCode}: $errorBody")
                return null
            }

            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
            var text = result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (text.contains("```")) text = text.split("```")[1].removePrefix("json").trim()

            val parsed = JSONObject(text)
            BrainResult(parsed.optString("command", "chat"), parsed.optString("response", "..."), parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Groq exception", e)
            null
        }
    }

    /** Simple offline keyword matching so core actions still work without network/API keys at all. */
    private fun localFallback(userInput: String, prefix: String): BrainResult {
        val lower = userInput.lowercase()
        return when {
            "battery" in lower -> BrainResult("battery", prefix, JSONObject())
            "flash" in lower && ("on" in lower) -> BrainResult("flashlight_on", prefix, JSONObject())
            "flash" in lower && ("off" in lower) -> BrainResult("flashlight_off", prefix, JSONObject())
            "torch" in lower && ("on" in lower) -> BrainResult("flashlight_on", prefix, JSONObject())
            "torch" in lower && ("off" in lower) -> BrainResult("flashlight_off", prefix, JSONObject())
            "volume up" in lower -> BrainResult("volume_up", prefix, JSONObject())
            "volume down" in lower -> BrainResult("volume_down", prefix, JSONObject())
            "time" in lower -> BrainResult("time", prefix, JSONObject())
            "date" in lower -> BrainResult("date", prefix, JSONObject())
            else -> BrainResult("chat", "$prefix I didn't understand that command yet.", JSONObject())
        }
    }

    fun currentTimeString(): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    fun currentDateString(): String = SimpleDateFormat("EEEE, MMM d yyyy", Locale.getDefault()).format(Date())
}
