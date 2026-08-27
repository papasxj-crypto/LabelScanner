package net.meinook.labelscanner

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbLeftHanded: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        cbLeftHanded = findViewById(R.id.cb_left_handed)
        val buttonSave = findViewById<Button>(R.id.buttonSaveSettings)
        val btnCreateCustomProfile = findViewById<Button>(R.id.btnCreateCustomProfile)

        // Populate left-handed check state (Checked if NOT right-handed) [1]
        cbLeftHanded.isChecked = !settings.isRightHanded()

        // Master Save button preference listener [2]
        buttonSave.setOnClickListener {
            saveUserConfigurationSettings()
        }

        // Launch custom profile creator flow [SettingsActivity.kt]
        btnCreateCustomProfile.setOnClickListener {
            CustomProfileManager.showCreateCustomProfileDialog(this, settings) {
                // Set the pending save flag so fragments recreate the check lists cleanly [SettingsActivity.kt]
                settings.setPendingSaveFlag(true)
            }
        }
    }

    /**
     * Persists the lefty preference, signals a home menu update, and closesSettings [1, 2]
     */
    private fun saveUserConfigurationSettings() {
        // Save inverse of lefty check state as your RightHanded preference [1]
        settings.setRightHanded(!cbLeftHanded.isChecked)

        val rootView = findViewById<View>(Window.ID_ANDROID_CONTENT)
        Snackbar.make(rootView, getString(R.string.toast_settings_saved), Snackbar.LENGTH_SHORT).show()

        // Set the pending save flag so HomeFragment recreates and flips the Compose menu
        settings.setPendingSaveFlag(true)
        finish()
    }
}