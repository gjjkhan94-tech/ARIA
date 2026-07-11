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
import kotlin.concurrent.thread

/**
 * Speaks text using Azure's natural voices.
 * Optimized for a "sweet, light girl" persona using ur-IN-GulNeural (softer than Uzma)
 * and SSML pitch/rate tuning to achieve the desired persona for free.
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
        val key = BuildConfig.AZURE_SPEECH_KEY
        val region = BuildConfig.AZURE_SPEECH_REGION
        if (key.isBlank() || region.isBlank()) {
            Log.w(TAG, "Azure keys missing, using Android fallback")
            speakWithAndroidFallback(text)
            return
        }

        thread {
            try {
                speakWithAzure(context, text, key, region)
            } catch (e: Exception) {
                Log.e(TAG, "Azure TTS failed, falling back to Android TTS", e)
                speakWithAndroidFallback(text)
            }
        }
    }

    private fun speakWithAzure(context: Context, text: String, key: String, region: String) {
        // We use ur-IN-GulNeural because it's naturally softer/sweeter than ur-PK-Uzma.
        // We then apply SSML to increase pitch and rate to make it sound younger and lighter.
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        
        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ur-PK'>
                <voice name='ur-IN-GulNeural'>
                    <prosody pitch='+12%' rate='+8%' contour='(0%, +15Hz) (100%, -5Hz)'>
                        $escaped
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()

        val url = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Ocp-Apim-Subscription-Key", key)
        conn.setRequestProperty("Content-Type", "application/ssml+xml")
        conn.setRequestProperty("X-Microsoft-OutputFormat", "audio-16khz-64kbitrate-mono-mp3")
        conn.setRequestProperty("User-Agent", "ARIA")
        conn.doOutput = true
        conn.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }

        if (conn.responseCode != 200) {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Azure TTS error ${conn.responseCode}: $errorBody")
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
