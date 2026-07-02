package com.aria.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires when the phone finishes booting. If ARIA's service was running
 * before the restart (tracked via ServicePrefs), start it again automatically
 * so the user doesn't have to manually reopen the app and tap Start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (ServicePrefs.wasRunning(context)) {
                val serviceIntent = Intent(context, AssistantForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}

/** Tiny shared-preferences wrapper tracking whether the user last had ARIA's service turned on. */
object ServicePrefs {
    private const val PREFS = "aria_prefs"
    private const val KEY_RUNNING = "service_running"

    fun setRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun wasRunning(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)
    }
}
