package com.aria.assistant

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Speaks text using a high-quality, free Neural TTS service (Edge-style).
 * No API key or credit card required. Optimized for ur-PK (Urdu Pakistan).
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
        thread {
            try {
                speakWithFreeNeural(context, text)
            } catch (e: Exception) {
                Log.e(TAG, "Neural TTS failed, falling back to Android TTS", e)
                speakWithAndroidFallback(text)
            }
        }
    }

    private fun speakWithFreeNeural(context: Context, text: String) {
        // Using a free, high-quality neural endpoint for Urdu (Pakistan)
        // We apply SSML-style tuning to make it sound sweet and light.
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val voice = "ur-PK-UzmaNeural" // Note: This is the Neural version which sounds much better than standard
        
        // Constructing the request to a free high-quality neural engine
        val url = URL("https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/ssml+xml")
        conn.setRequestProperty("X-Microsoft-OutputFormat", "audio-16khz-64kbitrate-mono-mp3")
        conn.doOutput = true

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ur-PK'>
                <voice name='$voice'>
                    <prosody pitch='+15%' rate='+10%'>$text</prosody>
                </voice>
            </speak>
        """.trimIndent()

        conn.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }

        if (conn.responseCode != 200) {
            throw Exception("Free TTS error ${conn.responseCode}")
        }

        val audioFile = File(context.cacheDir, "aria_speech.mp3")
        conn.inputStream.use { input ->
            FileOutputStream(audioFile).use { output -> input.copyTo(output) }
        }

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
