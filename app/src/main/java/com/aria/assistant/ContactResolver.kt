package com.aria.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Looks up phone numbers from the phone's real Contacts app instead of a
 * hardcoded JSON list. This is the source of truth WhatsApp/Phone/everything
 * else already uses, so names and numbers are always accurate and never need
 * manual maintenance.
 */
object ContactResolver {

    data class Match(val displayName: String, val phoneNumber: String)

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns every contact whose display name matches (exact, case-insensitive first;
     * otherwise "starts with" the spoken name). Ordering: exact matches first.
     * Multiple results mean the name is genuinely ambiguous (e.g. two "Ali"s) -
     * the caller should ask the user to pick rather than silently guessing.
     */
    fun findContacts(context: Context, spokenName: String): List<Match> {
        if (!hasPermission(context)) return emptyList()
        val nameLower = spokenName.trim().lowercase()
        if (nameLower.isEmpty()) return emptyList()

        val exact = mutableListOf<Match>()
        val startsWith = mutableListOf<Match>()
        val fuzzy = mutableListOf<Match>()
        val seenNumbers = mutableSetOf<String>()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val displayName = it.getString(nameIdx) ?: continue
                val rawNumber = it.getString(numberIdx) ?: continue
                val cleanNumber = rawNumber.replace(Regex("[^0-9+]"), "")
                if (cleanNumber.isEmpty() || !seenNumbers.add(displayName + cleanNumber)) continue

                val displayLower = displayName.lowercase()
                when {
                    displayLower == nameLower -> exact.add(Match(displayName, cleanNumber))
                    displayLower.startsWith(nameLower) -> startsWith.add(Match(displayName, cleanNumber))
                    displayLower.length >= 3 && FuzzyMatch.isCloseMatch(displayLower, nameLower) ->
                        fuzzy.add(Match(displayName, cleanNumber))
                }
            }
        }

        // Prefer exact, then prefix, then fuzzy - only reach fuzzy if nothing more confident exists.
        return exact.ifEmpty { startsWith.ifEmpty { fuzzy } }
    }
}
