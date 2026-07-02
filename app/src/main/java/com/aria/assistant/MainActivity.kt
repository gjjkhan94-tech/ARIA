package com.aria.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aria.assistant.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = results?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            binding.commandInput.setText(spoken)
            handleCommand(spoken)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results not individually needed here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
            }
        }

        requestNeededPermissions()
        requestBatteryOptimizationExemption()

        // Restore whatever the last known service state was (helps after app updates/reinstalls too)
        binding.statusText.text = if (ServicePrefs.wasRunning(this)) "Status: Running" else "Status: Stopped"

        binding.btnStartService.setOnClickListener {
            startForegroundService(Intent(this, AssistantForegroundService::class.java))
            ServicePrefs.setRunning(this, true)
            binding.statusText.text = "Status: Running"
            log("ARIA service started")
        }

        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, AssistantForegroundService::class.java))
            ServicePrefs.setRunning(this, false)
            binding.statusText.text = "Status: Stopped"
            log("ARIA service stopped")
        }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ARIA log", binding.logText.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log copied — paste it anywhere", Toast.LENGTH_SHORT).show()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.commandInput.text.toString().trim()
            if (text.isNotEmpty()) {
                handleCommand(text)
                binding.commandInput.setText("")
            }
        }

        binding.btnMic.setOnClickListener {
            launchSpeechRecognizer()
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.RECORD_AUDIO)
        needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Asks the user to whitelist ARIA from battery optimization - critical on Infinix/Transsion
     *  devices which aggressively kill background apps otherwise. */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                log("Couldn't open battery settings automatically: ${e.message}")
            }
        }
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            log("Speech recognition not available on this device: ${e.message}")
        }
    }

    private fun handleCommand(text: String) {
        log("You: $text")
        thread {
            val result = CommandBrain.processWithAI(text)
            runOnUiThread {
                log("ARIA: ${result.response}")
                speak(result.response)
                dispatch(result)
            }
        }
    }

    private fun dispatch(result: CommandBrain.BrainResult) {
        when (result.command) {
            "flashlight_on" -> DeviceActions.setFlashlight(this, true)
            "flashlight_off" -> DeviceActions.setFlashlight(this, false)
            "battery" -> {
                val pct = DeviceActions.getBatteryPercentage(this)
                log("Battery: $pct%")
                speak("Battery is at $pct percent")
            }
            "volume_up" -> DeviceActions.changeVolume(this, true)
            "volume_down" -> DeviceActions.changeVolume(this, false)
            "time" -> speak(CommandBrain.currentTimeString())
            "date" -> speak(CommandBrain.currentDateString())
            "whatsapp" -> {
                val contact = result.extra.optString("whatsapp_contact")
                val message = result.extra.optString("whatsapp_message")
                val phone = DeviceActions.getPhoneNumber(this, contact)
                if (phone != null) {
                    DeviceActions.sendWhatsAppMessage(this, phone, message)
                } else {
                    log("No contact found for '$contact'")
                    speak("I couldn't find a contact named $contact")
                }
            }
            "open_app" -> {
                val pkg = result.extra.optString("package_name")
                if (pkg.isNotBlank()) DeviceActions.openApp(this, pkg)
            }
            else -> { /* chat — response already spoken above */ }
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun log(message: String) {
        binding.logText.append("\n$message")
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
