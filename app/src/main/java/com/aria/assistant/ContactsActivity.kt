package com.aria.assistant

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ContactsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var inputName: EditText
    private lateinit var inputPhone: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        listContainer = findViewById(R.id.contactsListContainer)
        inputName = findViewById(R.id.inputName)
        inputPhone = findViewById(R.id.inputPhone)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveContact)

        btnSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val phone = inputPhone.text.toString().trim()

            if (name.isBlank() || phone.isBlank()) {
                Toast.makeText(this, "Enter both a name and a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val digitsOnly = phone.replace(Regex("[^0-9+]"), "")
            if (digitsOnly.length < 7) {
                Toast.makeText(this, "That phone number looks too short - include the country code", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            ContactsStore.save(this, name, digitsOnly)
            inputName.text.clear()
            inputPhone.text.clear()
            Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
            refreshList()
        }

        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val contacts = ContactsStore.getAll(this)

        if (contacts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No contacts saved yet. Add one above."
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 16, 0, 16)
            }
            listContainer.addView(empty)
            return
        }

        // Show newest-added first
        contacts.entries.reversed().forEach { (name, phone) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val info = TextView(this).apply {
                text = "$name\n$phone"
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val deleteBtn = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    ContactsStore.delete(this@ContactsActivity, name)
                    Toast.makeText(this@ContactsActivity, "Deleted $name", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
            }

            row.addView(info)
            row.addView(deleteBtn)
            listContainer.addView(row)
        }
    }
}
