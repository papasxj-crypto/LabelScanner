package net.meinook.labelscanner

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var textCurrentBlacklist: TextView
    private lateinit var editUserWeight: EditText

    // The master layout container that will dynamically hold our checkboxes
    private lateinit var containerMedicalProfiles: LinearLayout

    // A mapping list to keep track of our programmatically generated checkboxes
    private val generatedCheckBoxes = mutableListOf<ProfileCheckBoxMap>()

    // Local helper data class to map a profile ID to its visual UI CheckBox
    data class ProfileCheckBoxMap(val profileId: String, val checkBox: CheckBox)

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

        // Find our empty UI canvas container
        containerMedicalProfiles = findViewById(R.id.containerMedicalProfiles)

        // Pre-populate core settings fields
        editSodium.setText(settings.getSodiumLimit().toString())
        val savedWeight = settings.getUserWeight()
        editUserWeight.setText(if (savedWeight > 0) savedWeight.toString() else "195")
        refreshBlacklistDisplay()

        // Centralized tracking list of active medical conditions
        val savedConditions = settings.getSelectedConditions()

        // ---> DYNAMIC FILE ENGINE IN ACTION <---
        // Fetch every profile metadata definition discovered inside your XML sheets
        val availableProfiles = settings.getAvailableDietProfiles()

        // A quick boolean flag to prevent nested event triggers from step-shadowing each other
        var isUpdatingLayoutTree = false

        availableProfiles.forEach { profile ->
            // Construct a pristine CheckBox programmatically
            val checkBox = CheckBox(this).apply {
                text = profile.displayName // Human-readable label (e.g. "Stage 3A CKD")
                textSize = 16f
                setPadding(16, 16, 16, 16)

                // If this profile ID was checked before, turn it back on instantly
                isChecked = savedConditions.contains(profile.id)
            }

            // Append the fresh view directly to the user's screen layout
            containerMedicalProfiles.addView(checkBox)

            // Stash it in our tracking array map so we can harvest the check states during save
            val currentMap = ProfileCheckBoxMap(profile.id, checkBox)
            generatedCheckBoxes.add(currentMap)

            // ---> ADDED: MUTUAL EXCLUSIVITY EVENT HOOKS <---
            // Listen for user taps immediately instead of waiting for the save button click
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                // If we are currently running a visual sweep, drop out immediately to prevent infinite cascades
                if (isUpdatingLayoutTree) return@setOnCheckedChangeListener

                // 1. Mutate SharedPreferences via our XML data-driven engine
                settings.toggleConditionState(profile.id, isChecked)

                // 2. If the user checked a box, perform a window message sweep
                if (isChecked) {
                    // Set the guard flag BEFORE altering checkbox states
                    isUpdatingLayoutTree = true

                    // Fetch what is currently active in storage after our clean toggle mutation
                    val currentStoredConditions = settings.getSelectedConditions()

                    // Force all other UI checkbox widgets to match the fresh sanitized data layer state
                    generatedCheckBoxes.forEach { mapping ->
                        if (mapping.profileId != profile.id) {
                            // This suppresses recursive triggers and ensures UI correctness
                            mapping.checkBox.isChecked = currentStoredConditions.contains(mapping.profileId)
                        }
                    }
                    // Release the layout tree guard lock once redrawing settles
                    isUpdatingLayoutTree = false
                }
            }
            // ------------------------------------------------
        }
        // ----------------------------------------

        buttonAdd.setOnClickListener {
            val input = editBlacklistInput.text.toString().trim()
            if (input.isNotEmpty()) {
                settings.addBlacklistIngredient(input)
                editBlacklistInput.setText("")
                refreshBlacklistDisplay()
                Toast.makeText(this, getString(R.string.toast_added_ingredient, input.uppercase()), Toast.LENGTH_SHORT).show()
            }
        }

        buttonSave.setOnClickListener {
            val sodiumText = editSodium.text.toString().trim()
            if (sodiumText.isNotEmpty()) {
                settings.setSodiumLimit(sodiumText.toInt())
            }

            val weightText = editUserWeight.text.toString().trim()
            if (weightText.isNotEmpty()) {
                settings.setUserWeight(weightText.toDouble())
            }

            // NOTE: settings.toggleConditionState now handles conditions dynamically!
            // We just ensure the final state is captured.
            val conditionsToSave = mutableSetOf<String>()
            generatedCheckBoxes.forEach { mapping ->
                if (mapping.checkBox.isChecked) {
                    conditionsToSave.add(mapping.profileId)
                }
            }
            settings.saveSelectedConditions(conditionsToSave)

            // Find the root view of your activity layout to anchor the Snackbar to
            val rootView = findViewById<android.view.View>(android.view.Window.ID_ANDROID_CONTENT)

            com.google.android.material.snackbar.Snackbar
                .make(rootView, getString(R.string.toast_settings_saved), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()

            // Set a quick flag in SharedPreferences so MainActivity knows it needs to celebrate a save
            settings.setPendingSaveFlag(true)
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