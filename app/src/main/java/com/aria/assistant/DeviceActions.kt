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

    /**
     * Loads the contacts bundled in assets/contacts.json (same format as the old app).
     * Returns the phone number for a name, or null if not found.
     */
    fun getPhoneNumber(context: Context, name: String): String? {
        return try {
            val json = context.assets.open("contacts.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val nameLower = name.lowercase().trim()

            if (obj.has(nameLower)) return obj.getString(nameLower)

            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase().contains(nameLower) || nameLower.contains(key.lowercase())) {
                    return obj.getString(key)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens WhatsApp with the message pre-filled via deep link (no ADB needed).
     * The AriaAccessibilityService will attempt to auto-tap Send after this,
     * if accessibility permission has been granted.
     */
    fun sendWhatsAppMessage(context: Context, phone: String, message: String) {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        val uri = Uri.parse("https://wa.me/$cleanPhone?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
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
