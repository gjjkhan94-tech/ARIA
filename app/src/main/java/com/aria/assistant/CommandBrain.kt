package com.aria.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ported from the old ai_brain.py — same persona and JSON command schema,
 * just called over HTTPS from the phone instead of from a Termux Python process.
 */
object CommandBrain {

    private const val TAG = "CommandBrain"

    private const val PROMPT = """You are JANI - a sweet, caring, personal AI assistant like a best friend.
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

    data class BrainResult(val command: String, val response: String, val extra: JSONObject)

    /** Calls Gemini. Runs on a background thread — call from a coroutine or executor, never the main thread. */
    fun processWithAI(userInput: String): BrainResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            return localFallback(userInput, "No API key configured yet — using offline commands only.")
        }

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().apply {
                        put("text", "$PROMPT\n\nUser: $userInput")
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("maxOutputTokens", 800)
                    put("temperature", 0.7)
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }
                Log.e(TAG, "Gemini API error code $responseCode: $errorBody")
                val shortReason = errorBody.take(150).replace("\n", " ")
                return localFallback(userInput, "AI error ($responseCode): $shortReason")
            }

            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
            var text = result.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            if (text.contains("```")) {
                text = text.split("```")[1].removePrefix("json").trim()
            }

            val parsed = JSONObject(text)
            BrainResult(
                command = parsed.optString("command", "chat"),
                response = parsed.optString("response", "..."),
                extra = parsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "processWithAI failed", e)
            localFallback(userInput, "Network error: ${e.javaClass.simpleName} - ${e.message}. Offline mode:")
        }
    }

    /** Simple offline keyword matching so core actions still work without network/API key. */
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
