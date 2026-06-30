package net.meinook.labelscanner

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var textCurrentBlacklist: TextView
    private lateinit var editUserWeight: EditText
    // Checkbox references
    private lateinit var checkCKD: CheckBox
    private lateinit var checkGLP1: CheckBox
    private lateinit var checkLowSodium: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        val editSodium = findViewById<EditText>(R.id.editSodiumLimit)
        val editBlacklistInput = findViewById<EditText>(R.id.editBlacklistInput)
        val buttonAdd = findViewById<Button>(R.id.buttonAddIngredient)
        val buttonSave = findViewById<Button>(R.id.buttonSaveSettings)
        textCurrentBlacklist = findViewById(R.id.textCurrentBlacklist)
        editUserWeight = findViewById(R.id.editUserWeight)

        // 1. Initialize Checkboxes from your activity_settings.xml layout
        checkCKD = findViewById(R.id.checkCKD)
        checkGLP1 = findViewById(R.id.checkGLP1)
        checkLowSodium = findViewById(R.id.checkLowSodium)

        // 2. Pre-populate fields and set Checkbox states from saved storage
        editSodium.setText(settings.getSodiumLimit().toString())
        // Load existing weight. If no weight exists yet, default to a sensible baseline string like "195"
        val savedWeight = settings.getUserWeight() // We will add this helper to AppSettings next
        editUserWeight.setText(if (savedWeight > 0) savedWeight.toString() else "195")
        refreshBlacklistDisplay()

        val savedConditions = settings.getSelectedConditions()
        checkCKD.isChecked = savedConditions.contains("CKD")
        checkGLP1.isChecked = savedConditions.contains("GLP-1")
        checkLowSodium.isChecked = savedConditions.contains("Low Sodium")

        // Handle adding a word to the dynamic array list
        buttonAdd.setOnClickListener {
            val input = editBlacklistInput.text.toString().trim()
            if (input.isNotEmpty()) {
                settings.addBlacklistIngredient(input)
                editBlacklistInput.setText("")
                refreshBlacklistDisplay()
                Toast.makeText(this, getString(R.string.toast_added_ingredient, input.uppercase()), Toast.LENGTH_SHORT).show()
            }
        }

        // Handle persisting the data changes and exiting back to the scanner screen
        buttonSave.setOnClickListener {
            val sodiumText = editSodium.text.toString().trim()
            if (sodiumText.isNotEmpty()) {
                settings.setSodiumLimit(sodiumText.toInt())
            }

            // Capture and save the custom weight input
            val weightText = editUserWeight.text.toString().trim()
            if (weightText.isNotEmpty()) {
                settings.setUserWeight(weightText.toDouble()) // We will add this helper to AppSettings next
            }

            // 3. Gather checked states and save them via AppSettings
            val conditionsToSave = mutableSetOf<String>()
            if (checkCKD.isChecked) conditionsToSave.add("CKD")
            if (checkGLP1.isChecked) conditionsToSave.add("GLP-1")
            if (checkLowSodium.isChecked) conditionsToSave.add("Low Sodium")

            settings.saveSelectedConditions(conditionsToSave)

            Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
            finish() // Safely closes this layout and goes back to MainActivity
        }
    }

    private fun refreshBlacklistDisplay() {
        val currentItems = settings.getCustomBlacklist()
        if (currentItems.isEmpty()) {
            textCurrentBlacklist.text = getString(R.string.no_custom_items)
        } else {
            val stringBuilder = StringBuilder(getString(R.string.watchlist_header))
            currentItems.forEach { item ->
                stringBuilder.append("• $item\n")
            }
            textCurrentBlacklist.text = stringBuilder.toString()
        }
    }
}