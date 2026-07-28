package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var editUserWeight: EditText
    private lateinit var containerMedicalProfiles: LinearLayout

    // A mapping list to keep track of our programmatically generated checkboxes
    private val generatedCheckBoxes = mutableListOf<ProfileCheckBoxMap>()

    // Local helper data class to map a profile ID to its visual UI CheckBox
    data class ProfileCheckBoxMap(val profileId: String, val checkBox: CheckBox)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        // 1. Initialize core UI element views
        val buttonSave = findViewById<Button>(R.id.buttonSaveSettings)
        val layoutManageAllergens = findViewById<LinearLayout>(R.id.layoutManageAllergens)
        editUserWeight = findViewById<EditText>(R.id.editUserWeight)
        containerMedicalProfiles = findViewById<LinearLayout>(R.id.containerMedicalProfiles)

        // Pre-populate user weight field from data store
        val savedWeight = settings.getUserWeight()
        editUserWeight.setText(if (savedWeight > 0) savedWeight.toString() else "195")

        // 2. Wire up the premium Allergen Page Sub-Navigation Row

        // 3. Render dynamic medical profile checkboxes from app asset files
        setupDynamicProfileCheckboxes()

        // 4. Master Save button profile configuration listener
        buttonSave.setOnClickListener {
            saveUserConfigurationSettings()
        }
    }

    /**
     * Loops through available XML profile groups to construct matching checkboxes
     * while enforcing exclusivity states dynamically.
     */
    private fun setupDynamicProfileCheckboxes() {
        var isUpdatingLayoutTree = false
        val savedConditions = settings.getSelectedConditions()
        val availableProfiles = settings.getAvailableDietProfiles()

        availableProfiles.forEach { profile ->
            val checkBox = CheckBox(this).apply {
                text = profile.displayName
                textSize = 16f
                setPadding(16, 16, 16, 16)
                isChecked = savedConditions.contains(profile.id)
            }

            containerMedicalProfiles.addView(checkBox)
            val currentMap = ProfileCheckBoxMap(profile.id, checkBox)
            generatedCheckBoxes.add(currentMap)

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingLayoutTree) return@setOnCheckedChangeListener

                // Persist the checkbox change to AppSettings structure
                settings.toggleConditionState(profile.id, isChecked)

                // If checking an item, automatically resolve mutually exclusive groups
                if (isChecked) {
                    isUpdatingLayoutTree = true
                    val currentStoredConditions = settings.getSelectedConditions()

                    generatedCheckBoxes.forEach { mapping ->
                        if (mapping.profileId != profile.id) {
                            mapping.checkBox.isChecked = currentStoredConditions.contains(mapping.profileId)
                        }
                    }
                    isUpdatingLayoutTree = false
                }
            }
        }
    }

    /**
     * Harvests form variables and pushes finalized weight and profiles back to disk.
     */
    private fun saveUserConfigurationSettings() {
        val weightText = editUserWeight.text.toString().trim()
        if (weightText.isNotEmpty()) {
            settings.setUserWeight(weightText.toDouble())
        }

        val conditionsToSave = mutableSetOf<String>()
        generatedCheckBoxes.forEach { mapping ->
            if (mapping.checkBox.isChecked) {
                conditionsToSave.add(mapping.profileId)
            }
        }
        settings.saveSelectedConditions(conditionsToSave)

        val rootView = findViewById<View>(Window.ID_ANDROID_CONTENT)
        Snackbar.make(rootView, getString(R.string.toast_settings_saved), Snackbar.LENGTH_SHORT).show()

        settings.setPendingSaveFlag(true)
        finish()
    }
}