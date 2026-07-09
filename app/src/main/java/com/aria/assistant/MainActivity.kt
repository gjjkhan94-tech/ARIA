package com.aria.assistant

import android.Manifest
import android.app.AlertDialog
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
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aria.assistant.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        VoiceSpeaker.init(this)

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

        binding.btnManageContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
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

        askForNameIfNeeded()
    }

    /** First-launch only: ask what to call the user, so JANI can personalize "Sir <Name>". */
    private fun askForNameIfNeeded() {
        if (UserPrefs.hasName(this)) {
            greetOnStartup()
            return
        }
        val input = EditText(this).apply { hint = "Your name (optional)" }
        AlertDialog.Builder(this)
            .setTitle("What should JANI call you?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) UserPrefs.setName(this, name)
                greetOnStartup()
            }
            .setNegativeButton("Skip") { _, _ -> greetOnStartup() }
            .show()
    }

    /** Time-aware greeting spoken/logged the moment the app opens - no AI call needed. */
    private fun greetOnStartup() {
        val name = UserPrefs.getName(this)
        val addressTerm = if (name != null) "Sir $name" else "Sir"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 5 -> "You're up late, $addressTerm. I'm here whenever you need me."
            hour < 12 -> "Good morning, $addressTerm! How can I help you today?"
            hour < 17 -> "Good afternoon, $addressTerm! What can I do for you?"
            hour < 21 -> "Good evening, $addressTerm! I'm here for you."
            else -> "It's getting late, $addressTerm - I'm still here if you need anything."
        }
        log("ARIA: $greeting")
        speak(greeting)
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.RECORD_AUDIO)
        needed.add(Manifest.permission.CAMERA)
        needed.add(Manifest.permission.READ_CONTACTS)
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

    private fun addressTerm(): String {
        val name = UserPrefs.getName(this)
        return if (name != null) "Sir $name" else "Sir"
    }

    private fun handleCommand(text: String) {
        log("You: $text")

        // If we're waiting on a yes/no confirmation before actually sending, handle that first.
        val pendingConfirm = pendingConfirmSend
        if (pendingConfirm != null) {
            val reply = text.trim().lowercase()
            when {
                reply in listOf("yes", "y", "confirm", "send", "ok", "okay", "yeah", "yep") -> {
                    pendingConfirmSend = null
                    DeviceActions.sendWhatsAppMessage(this, pendingConfirm.phone, pendingConfirm.message)
                    val msg = "Sending it now, ${addressTerm()}. Anything else?"
                    log("ARIA: $msg")
                    speak(msg)
                    return
                }
                reply in listOf("no", "n", "cancel", "stop", "don't", "dont") -> {
                    pendingConfirmSend = null
                    val msg = "Okay, cancelled, ${addressTerm()}. Just let me know if you need anything else."
                    log("ARIA: $msg")
                    speak(msg)
                    return
                }
                else -> {
                    // Anything else - drop the pending confirmation and treat as a fresh command.
                    pendingConfirmSend = null
                }
            }
        }

        // If we're waiting on the user to pick between ambiguous contacts, handle
        // that locally first - don't waste an AI call on a bare "1"/"2" reply.
        val pendingMsg = pendingWhatsAppMessage
        if (pendingMsg != null) {
            val choiceIndex = text.trim().toIntOrNull()
            if (choiceIndex != null && choiceIndex - 1 in lastAmbiguousMatches.indices) {
                val chosen = lastAmbiguousMatches[choiceIndex - 1]
                pendingWhatsAppMessage = null
                askToConfirmSend(chosen.displayName, chosen.phoneNumber, pendingMsg)
                return
            } else {
                // User said something else entirely - drop the pending state and continue normally.
                pendingWhatsAppMessage = null
            }
        }

        binding.thinkingIndicator.visibility = View.VISIBLE
        val userName = UserPrefs.getName(this)

        thread {
            val result = CommandBrain.processWithAI(text, userName)
            runOnUiThread {
                binding.thinkingIndicator.visibility = View.GONE
                // For WhatsApp sends specifically, skip the AI's own "sending now!" filler line -
                // it contradicts the confirm-before-send prompt that dispatch() is about to show.
                if (result.command != "whatsapp") {
                    log("ARIA: ${result.response}")
                    speak(result.response)
                }
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
                speak("Battery is at $pct percent, ${addressTerm()}.")
            }
            "volume_up" -> DeviceActions.changeVolume(this, true)
            "volume_down" -> DeviceActions.changeVolume(this, false)
            "time" -> speak(CommandBrain.currentTimeString())
            "date" -> speak(CommandBrain.currentDateString())
            "whatsapp" -> {
                val contact = result.extra.optString("whatsapp_contact")
                val message = result.extra.optString("whatsapp_message")
                handleWhatsAppRequest(contact, message)
            }
            "open_app" -> {
                val pkg = result.extra.optString("package_name")
                if (pkg.isNotBlank()) DeviceActions.openApp(this, pkg)
            }
            else -> { /* chat — response already spoken above */ }
        }
    }

    /** Pending WhatsApp send waiting on the user to pick which contact they meant. */
    private var pendingWhatsAppMessage: String? = null
    private var lastAmbiguousMatches: List<ContactResolver.Match> = emptyList()

    /** Pending WhatsApp send waiting on a yes/no confirmation from the user. */
    private data class PendingSend(val displayName: String, val phone: String, val message: String)
    private var pendingConfirmSend: PendingSend? = null

    private fun askToConfirmSend(displayName: String, phone: String, message: String) {
        pendingConfirmSend = PendingSend(displayName, phone, message)
        val msg = "Send '$message' to $displayName, ${addressTerm()}? Say yes or no."
        log("ARIA: $msg")
        speak(msg)
    }

    private fun handleWhatsAppRequest(contact: String, message: String) {
        // 1. User-added contacts (Manage Contacts screen) always win - most trustworthy source.
        val userAddedPhone = DeviceActions.getUserAddedNumber(this, contact)
        if (userAddedPhone != null) {
            askToConfirmSend(contact, userAddedPhone, message)
            return
        }

        // 2. Real phone contacts.
        val realMatches = ContactResolver.findContacts(this, contact)
        when {
            realMatches.size == 1 -> {
                val match = realMatches[0]
                askToConfirmSend(match.displayName, match.phoneNumber, message)
            }
            realMatches.size > 1 -> {
                // Genuinely ambiguous - e.g. two contacts both named "Ali". Ask, don't guess.
                lastAmbiguousMatches = realMatches
                pendingWhatsAppMessage = message
                val options = realMatches.mapIndexed { i, m -> "${i + 1}) ${m.displayName} - ${m.phoneNumber}" }
                    .joinToString("\n")
                log("Multiple contacts named '$contact':\n$options\nReply with the number.")
                speak("I found more than one contact named $contact, ${addressTerm()}. Check the screen and tell me which number.")
            }
            else -> {
                // Not found in real contacts - try the legacy JSON list as a fallback
                // (useful for WhatsApp-only numbers that aren't saved as phone contacts).
                val legacyPhone = DeviceActions.getPhoneNumber(this, contact)
                if (legacyPhone != null) {
                    askToConfirmSend(contact, legacyPhone, message)
                } else {
                    log("No contact found for '$contact'")
                    speak("I couldn't find a contact named $contact, ${addressTerm()}. You can add them in Manage Contacts.")
                }
            }
        }
    }

    private fun speak(text: String) {
        VoiceSpeaker.speak(this, text)
    }

    private fun log(message: String) {
        binding.logText.append("\n$message")
    }
}
