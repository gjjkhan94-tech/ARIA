package com.aria.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Native replacements for everything the old Termux:API version did.
 * No ADB, no Termux, no shell subprocess calls — real Android system APIs.
 */
object DeviceActions {

    fun getBatteryPercentage(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun setFlashlight(context: Context, on: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun changeVolume(context: Context, raise: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /** Checks ONLY the user-added contacts (Manage Contacts screen) - used to give them top priority. */
    fun getUserAddedNumber(context: Context, name: String): String? {
        val userContacts = ContactsStore.getAll(context)
        return matchContact(userContacts, name)
    }

    /**
     * Looks up a phone number by name. Checks user-added contacts (ContactsStore) first,
     * falling back to the old bundled assets/contacts.json for anything not yet migrated.
     * Returns null (rather than a wrong guess) when there's no confident match.
     */
    fun getPhoneNumber(context: Context, name: String): String? {
        val merged = LinkedHashMap<String, String>()

        // Old bundled file first (lower priority - loaded first so user contacts overwrite it below)
        try {
            val json = context.assets.open("contacts.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                merged[k] = obj.getString(k)
            }
        } catch (e: Exception) { /* fine if missing */ }

        // User-added contacts always win on name collisions.
        merged.putAll(ContactsStore.getAll(context))

        return matchContact(merged, name)
    }

    /** Pure matching logic, kept separate so it's easy to reason about / test. */
    private fun matchContact(contacts: Map<String, String>, name: String): String? {
        val nameLower = name.lowercase().trim()
        if (nameLower.isBlank()) return null

        // 1. Exact match - no ambiguity possible.
        contacts[nameLower]?.let { return it }

        // Ignore junk/short keys (like "a", "as") for anything but exact matches -
        // these are almost always old typos or shortcuts that falsely match everything.
        val MIN_KEY_LENGTH = 3
        val safeKeys = contacts.keys.filter { it.length >= MIN_KEY_LENGTH }

        // 2. A saved key starts with the spoken name (e.g. "waqas" -> "waqas bhijaan").
        //    Shortest first, so "waqas" itself wins over "waqas bhijaan" when both match.
        val startsWithMatches = safeKeys.filter { it.startsWith(nameLower) }.sortedBy { it.length }
        if (startsWithMatches.isNotEmpty()) return contacts[startsWithMatches.first()]

        // 3. The spoken name starts with a saved key (e.g. "waqas bhai" -> matches "waqas").
        //    Longest key first (most specific), and require the key to be a whole word,
        //    not just a prefix fragment - avoids "a" matching "ali".
        val reverseMatches = safeKeys.filter { key ->
            nameLower.startsWith(key) &&
                (nameLower.length == key.length || nameLower[key.length] == ' ')
        }.sortedByDescending { it.length }
        if (reverseMatches.isNotEmpty()) return contacts[reverseMatches.first()]

        // 4. Whole-word containment only (e.g. "message zaid friend" contains word "zaid").
        //    Never a raw substring check - that's what let "a" match "Zain Rauf" before.
        val nameWords = nameLower.split(" ")
        val wholeWordMatches = safeKeys.filter { key ->
            val keyWords = key.split(" ")
            keyWords.any { it in nameWords } || nameWords.any { it == key }
        }.sortedBy { it.length }
        if (wholeWordMatches.isNotEmpty()) return contacts[wholeWordMatches.first()]

        // No confident match - better to say "not found" than message the wrong person.
        return null
    }

    /**
     * Opens WhatsApp with the message pre-filled via deep link (no ADB needed).
     * The AriaAccessibilityService will attempt to auto-tap Send after this,
     * if accessibility permission has been granted.
     */
    fun sendWhatsAppMessage(context: Context, phone: String, message: String) {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")

        // Force WhatsApp directly (setPackage) instead of letting it resolve to a browser
        // wa.me landing page first - this opens straight into the chat with text pre-filled.
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // WhatsApp not installed under that package name (e.g. Business) - fall back to generic.
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
        AriaAccessibilityService.requestAutoSend()
    }

    fun openApp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }
}
