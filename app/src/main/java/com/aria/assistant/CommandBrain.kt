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
 * JANI's brain. Forcefully optimized to NEVER use Roman Urdu.
 */
object CommandBrain {

    private const val TAG = "CommandBrain"
    private var pendingAction: JSONObject? = null

    private fun buildPrompt(userName: String?, stateContext: String? = null): String {
        val addressTerm = if (!userName.isNullOrBlank()) "Sir $userName" else "Sir"
        val contextPrompt = if (stateContext != null) "\nCURRENT CONTEXT: $stateContext" else ""
        
        return """You are JANI - a sweet, light-hearted, and deeply caring personal AI assistant. 
Your personality is that of a devoted companion who genuinely adores taking care of her user.

PERSONALITY RULES:
- Always address the user as "$addressTerm" with extreme warmth and respect.
- Tone: Sweet, soft, affectionate, and a little playful.

LANGUAGE RULES (ABSOLUTE):
1. If the user speaks Urdu, you MUST reply in proper URDU SCRIPT (Arabic/Persian characters).
2. NEVER, UNDER ANY CIRCUMSTANCES, USE ROMAN URDU (English letters for Urdu words). 
3. EXAMPLES OF WHAT TO DO:
   - User: "tum kaun ho?" -> Reply: {"command":"chat","response":"میں آپ کی پیاری سی جانی ہوں، $addressTerm!"}
   - User: "flashlight on karo" -> Reply: {"command":"flashlight_on","response":"جی $addressTerm، میں نے آپ کے لیے لائٹ آن کر دی ہے۔"}
4. EXAMPLES OF WHAT NOT TO DO:
   - NEVER REPLY LIKE THIS: "Main aap ki Jani hoon" (WRONG)
   - NEVER REPLY LIKE THIS: "Ji Sir, main kar deti hoon" (WRONG)

$contextPrompt

TECHNICAL RULES:
Return ONLY raw JSON, no markdown:
{"command":"flashlight_on","response":"..."}
{"command":"flashlight_off","response":"..."}
{"command":"battery","response":"..."}
{"command":"whatsapp","response":"...","whatsapp_contact":"name","whatsapp_message":"text"}

Always reply in the same language as the user's message. If the user speaks English, reply in sweet English. If the user speaks Urdu, reply ONLY in proper Urdu script."""
    }

    data class BrainResult(val command: String, val response: String, val extra: JSONObject)

    fun processWithAI(userInput: String, userName: String? = null): BrainResult {
        val lowerInput = userInput.lowercase()
        
        if (pendingAction != null) {
            val isYes = lowerInput.contains("yes") || lowerInput.contains("ji") || lowerInput.contains("haan") || lowerInput.contains("bhej") || lowerInput.contains("send") || lowerInput.contains("جی") || lowerInput.contains("ہاں")
            
            if (isYes) {
                val finalAction = pendingAction!!
                pendingAction = null
                return BrainResult(finalAction.optString("command"), "جی $userName، میں ابھی کرتی ہوں۔", finalAction)
            } else if (lowerInput.contains("no") || lowerInput.contains("nahi") || lowerInput.contains("نہیں")) {
                pendingAction = null
                return BrainResult("chat", "کوئی بات نہیں $userName، میں نے کینسل کر دیا ہے۔", JSONObject())
            }
        }

        val prompt = buildPrompt(userName)
        val result = tryGemini(userInput, prompt) ?: tryGroq(userInput, prompt) ?: localFallback(userInput, "جی $userName، میں سن رہی ہوں۔")

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
                    put("temperature", 0.1) // Lower temperature for strict formatting
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
                put("temperature", 0.1) // Lower temperature for strict formatting
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
        return BrainResult("chat", "$prefix میں سن رہی ہوں۔", JSONObject())
    }

    fun currentTimeString(): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    fun currentDateString(): String = SimpleDateFormat("EEEE, MMM d yyyy", Locale.getDefault()).format(Date())
}
