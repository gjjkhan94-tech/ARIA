package com.aria.assistant

import android.content.Context

/** Tiny persistent store for the user's name and other light personalization preferences. */
object UserPrefs {
    private const val PREFS = "aria_user_prefs"
    private const val KEY_NAME = "user_name"

    fun getName(context: Context): String? {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)
        return if (name.isNullOrBlank()) null else name
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun hasName(context: Context): Boolean = getName(context) != null
}
