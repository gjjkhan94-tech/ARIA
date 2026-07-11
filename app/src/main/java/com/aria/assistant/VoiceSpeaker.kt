package com.aria.assistant

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Speaks text using Google Cloud TTS Wavenet voices.
 * Wavenet-A is a high-quality, sweet female voice specifically for Urdu (Pakistan).
 */
object VoiceSpeaker {
    private const val TAG = "VoiceSpeaker"
    private var fallbackTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

    fun init(context: Context) {
        if (fallbackTts == null) {
            fallbackTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    fallbackTts?.language = Locale("ur", "PK")
                }
            }
        }
    }

    fun speak(context: Context, text: String) {
        val apiKey = BuildConfig.GOOGLE_API_KEY // Ensure you add this to your GitHub Secrets
        if (apiKey.isBlank()) {
            Log.w(TAG, "Google API key missing, using Android fallback")
            speakWithAndroidFallback(text)
            return
        }

        thread {
            try {
                speakWithGoogle(context, text, apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "Google TTS failed, falling back to Android TTS", e)
                speakWithAndroidFallback(text)
            }
        }
    }

    private fun speakWithGoogle(context: Context, text: String, apiKey: String) {
        val url = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=${'$'}apiKey")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("input", JSONObject().apply { put("text", text) })
            put("voice", JSONObject().apply {
                put("languageCode", "ur-PK")
                put("name", "ur-PK-Wavenet-A")
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", "MP3")
                put("pitch", 2.0) // Slight pitch increase for sweetness
                put("speakingRate", 1.05)
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        if (conn.responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Google TTS error ${conn.responseCode}: ${'$'}error")
        }

        val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val audioContent = response.getString("audioContent")
        val audioBytes = android.util.Base64.decode(audioContent, android.util.Base64.DEFAULT)

        val audioFile = File(context.cacheDir, "aria_speech.mp3")
        FileOutputStream(audioFile).use { it.write(audioBytes) }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun speakWithAndroidFallback(text: String) {
        fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
}
