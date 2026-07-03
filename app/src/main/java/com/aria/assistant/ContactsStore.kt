package com.aria.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * User-editable contacts, saved as a JSON file in the app's private internal storage
 * (survives app restarts and reboots, but is NOT the old read-only assets/contacts.json).
 * Contacts added/edited here always take priority over the old bundled file.
 */
object ContactsStore {
    private const val FILE_NAME = "user_contacts.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun getAll(context: Context): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val f = file(context)
        if (!f.exists()) return result
        return try {
            val obj = JSONObject(f.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.getString(k)
            }
            result
        } catch (e: Exception) {
            result
        }
    }

    fun save(context: Context, name: String, phone: String) {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        val all = getAll(context)
        all[name.lowercase().trim()] = clean
        writeAll(context, all)
    }

    fun delete(context: Context, name: String) {
        val all = getAll(context)
        all.remove(name.lowercase().trim())
        writeAll(context, all)
    }

    private fun writeAll(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        file(context).writeText(obj.toString())
    }
}
