package net.meinook.labelscanner

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var cbLeftHanded: MaterialCheckBox
    private lateinit var btnAddNewProfile: MaterialButton
    private lateinit var btnDeleteProfile: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        val btnBackTop = findViewById<ImageView>(R.id.btnBackTop)
        cbLeftHanded = findViewById(R.id.cb_left_handed)
        btnAddNewProfile = findViewById(R.id.btnAddNewProfile)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)
        val txtAppVersion = findViewById<TextView>(R.id.txtAppVersion)

        btnBackTop.setOnClickListener {
            finish()
        }

        // Bind dynamic version and build number
        txtAppVersion.text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // Populate initial state (Checked if NOT right-handed)
        cbLeftHanded.isChecked = !settings.isRightHanded()

        // Auto-save preference immediately on toggle
        cbLeftHanded.setOnCheckedChangeListener { _, isChecked ->
            settings.setRightHanded(!isChecked)
            settings.setPendingSaveFlag(true)
        }

        // Single Point: Add New Profile
        btnAddNewProfile.setOnClickListener {
            showAddNewProfileDialog()
        }

        // Single Point: Delete Profile
        btnDeleteProfile.setOnClickListener {
            showDeleteProfileDialog()
        }
    }

    private fun showAddNewProfileDialog() {
        val density = resources.displayMetrics.density

        val inputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }
        val inputEdit = TextInputEditText(this).apply {
            hint = "Profile name (e.g. Dad, Sarah)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        inputLayout.addView(inputEdit)

        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Add New Profile")
            .setView(inputLayout)
            .setPositiveButton("Create") { dialog, _ ->
                val name = inputEdit.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    val currentList = settings.getProfilesList()
                    if (currentList.contains(name)) {
                        Toast.makeText(this, "A profile named '$name' already exists.", Toast.LENGTH_SHORT).show()
                    } else {
                        settings.createProfile(name)
                        settings.setActiveProfile(name)
                        settings.setPendingSaveFlag(true)
                        Toast.makeText(this, "Profile '$name' created. Configure targets below.", Toast.LENGTH_SHORT).show()

                        // Automatically redirect to My Health to configure the new profile
                        val navIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_TARGET_TAB_ID, R.id.navigation_my_health)
                        }
                        startActivity(navIntent)
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Profile name cannot be empty.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteProfileDialog() {
        val currentProfiles = settings.getProfilesList()

        if (currentProfiles.size <= 1) {
            MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
                .setTitle("Cannot Delete Profile")
                .setMessage("FilterPoint requires at least one active profile. Add another profile before deleting '${currentProfiles.first()}'.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Select Profile to Delete")
            .setItems(currentProfiles.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                val selectedName = currentProfiles[which]
                confirmDeleteProfile(selectedName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteProfile(profileName: String) {
        MaterialAlertDialogBuilder(this, R.style.Theme_LabelScanner)
            .setTitle("Delete '$profileName'?")
            .setMessage("All clinical targets, measurements, custom macro sliders, and watchlists for '$profileName' will be permanently removed.")
            .setPositiveButton("Delete") { _, _ ->
                settings.deleteProfile(profileName)
                settings.setPendingSaveFlag(true)
                Toast.makeText(this, "Profile '$profileName' deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}