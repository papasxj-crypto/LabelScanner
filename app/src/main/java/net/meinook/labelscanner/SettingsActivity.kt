package net.meinook.labelscanner

import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbLeftHanded: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        cbLeftHanded = findViewById(R.id.cb_left_handed)
        val buttonSave = findViewById<Button>(R.id.buttonSaveSettings)
        val btnManageProfiles = findViewById<MaterialButton>(R.id.btnManageProfiles)
        val txtAppVersion = findViewById<TextView>(R.id.txtAppVersion)

        // Bind dynamic version and build number
        txtAppVersion.text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // Populate left-handed check state (Checked if NOT right-handed)
        cbLeftHanded.isChecked = !settings.isRightHanded()

        // Master Save button preference listener
        buttonSave.setOnClickListener {
            saveUserConfigurationSettings()
        }

        // Launch Profile Management Hub
        btnManageProfiles.setOnClickListener {
            showManageProfilesDialog()
        }
    }

    private fun showManageProfilesDialog() {
        val profiles = settings.getProfilesList()

        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Select Profile to Manage")
            .setItems(profiles.toTypedArray()) { _, which ->
                val selectedProfile = profiles[which]
                showProfileActionsDialog(selectedProfile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfileActionsDialog(profileName: String) {
        val isDefault = profileName == "Me"
        val options = if (isDefault) {
            arrayOf("🎚️ Calibrate Macro Sliders")
        } else {
            arrayOf("🎚️ Calibrate Macro Sliders", "🗑️ Delete Profile")
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Manage: $profileName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val previousActive = settings.getActiveProfile()
                        settings.setActiveProfile(profileName)
                        CustomProfileManager.showCreateCustomProfileDialog(this, settings) {
                            settings.setPendingSaveFlag(true)
                            settings.setActiveProfile(previousActive)
                        }
                    }
                    1 -> {
                        confirmDeleteProfile(profileName)
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showManageProfilesDialog() }
            .show()
    }

    private fun confirmDeleteProfile(profileName: String) {
        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Delete Profile?")
            .setMessage("Are you sure you want to delete '$profileName'? All custom clinical targets and watchlists for this profile will be permanently removed.")
            .setPositiveButton("Delete") { _, _ ->
                settings.deleteProfile(profileName)
                settings.setPendingSaveFlag(true)
                Toast.makeText(this, "Profile '$profileName' deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Persists the lefty preference, signals a home menu update, and closes Settings
     */
    private fun saveUserConfigurationSettings() {
        settings.setRightHanded(!cbLeftHanded.isChecked)

        val rootView = findViewById<View>(Window.ID_ANDROID_CONTENT)
        Snackbar.make(rootView, getString(R.string.toast_settings_saved), Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.cardSurface))
            .setTextColor(ContextCompat.getColor(this, R.color.textPrimary))
            .show()

        settings.setPendingSaveFlag(true)
        finish()
    }
}