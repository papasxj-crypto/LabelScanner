package net.meinook.labelscanner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object CustomProfileManager {

    private const val TAG = "CustomProfileManager"

    fun showCreateCustomProfileDialog(
        context: Context,
        appSettings: AppSettings,
        onProfileSaved: () -> Unit
    ) {
        val allProfiles = appSettings.getAvailableDietProfiles(includeAllergens = false)
        val options = mutableListOf<String>().apply {
            add("Start Clean (All Zeros)")
            addAll(allProfiles.map { it.displayName })
        }

        MaterialAlertDialogBuilder(context, R.style.Theme_LabelScanner)
            .setTitle("Select Template to Clone")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCustomEditForm(context, null, appSettings, onProfileSaved)
                } else {
                    val selectedProfile = allProfiles[which - 1]
                    showCustomEditForm(context, selectedProfile, appSettings, onProfileSaved)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomEditForm(
        context: Context,
        template: DietProfile?,
        appSettings: AppSettings,
        onProfileSaved: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density

        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        val scroll = NestedScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (400 * density).toInt()
            )
            addView(linearLayout)
        }

        fun createInputRow(title: String, defaultValue: Int): EditText {
            val titleView = TextView(context).apply {
                text = title
                setTextColor(Color.parseColor("#99A1B3"))
                textSize = 12f
                setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
            }
            val et = EditText(context).apply {
                setText(defaultValue.toString())
                setTextColor(Color.parseColor("#F4F5FC"))
                background = context.getDrawable(R.drawable.bg_height_edit_text)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                inputType = InputType.TYPE_CLASS_NUMBER

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        setBackgroundColor(Color.parseColor("#4E3B34"))
                    } else {
                        background = context.getDrawable(R.drawable.bg_height_edit_text)
                    }
                }
            }
            linearLayout.addView(titleView)
            linearLayout.addView(et)
            return et
        }

        // Title Row configuration (Always remains initialized to "Custom Plan")
        val etProfileName = createInputRow("Profile Name", 0).apply {
            setText("Custom Plan")
            inputType = InputType.TYPE_CLASS_TEXT
        }

        // Safely extract default template thresholds if cloning an existing profile
        val sodLow = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "sodium").first else 0
        val sodMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "sodium").second else 0

        val potLow = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "potassium").first else 0
        val potMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "potassium").second else 0

        val satLow = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "saturated_fat").first else 0
        val satMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "saturated_fat").second else 0

        val carbsLow = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "carbs").first else 0
        val carbsMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "carbs").second else 0

        val protLow = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "protein").first else 0
        val protMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "protein").second else 0

        // Populate Form Rows with dynamic template limits
        val etSodLow = createInputRow("Sodium Yellow Limit (mg)", sodLow)
        val etSodMod = createInputRow("Sodium Red Limit (mg)", sodMod)
        val etPotLow = createInputRow("Potassium Yellow Limit (mg)", potLow)
        val etPotMod = createInputRow("Potassium Red Limit (mg)", potMod)
        val etSatLow = createInputRow("Saturated Fat Yellow Limit (g)", satLow)
        val etSatMod = createInputRow("Saturated Fat Red Limit (g)", satMod)
        val etCarbsLow = createInputRow("Carbs Yellow Limit (g)", carbsLow)
        val etCarbsMod = createInputRow("Carbs Red Limit (g)", carbsMod)
        val etProtLow = createInputRow("Protein Yellow Limit (g)", protLow)
        val etProtMod = createInputRow("Protein Red Limit (g)", protMod)

        MaterialAlertDialogBuilder(context, R.style.Theme_LabelScanner)
            .setTitle(if (template != null) "Clone: ${template.displayName}" else "New Custom Profile")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val name = etProfileName.text.toString().trim().ifEmpty { "Custom Plan" }
                val sLow = etSodLow.text.toString().toIntOrNull() ?: 0
                val sMod = etSodMod.text.toString().toIntOrNull() ?: 0
                val pLow = etPotLow.text.toString().toIntOrNull() ?: 0
                val pMod = etPotMod.text.toString().toIntOrNull() ?: 0
                val stLow = etSatLow.text.toString().toIntOrNull() ?: 0
                val stMod = etSatMod.text.toString().toIntOrNull() ?: 0
                val cLow = etCarbsLow.text.toString().toIntOrNull() ?: 0
                val cMod = etCarbsMod.text.toString().toIntOrNull() ?: 0
                val prLow = etProtLow.text.toString().toIntOrNull() ?: 0
                val prMod = etProtMod.text.toString().toIntOrNull() ?: 0

                writeCustomProfileXml(context, name, sLow, sMod, pLow, pMod, stLow, stMod, cLow, cMod, prLow, prMod)
                onProfileSaved()
                Toast.makeText(context, "Custom profile saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeCustomProfileXml(
        context: Context,
        profileName: String,
        sodiumLow: Int, sodiumMod: Int,
        potassiumLow: Int, potassiumMod: Int,
        satFatLow: Int, satFatMod: Int,
        carbsLow: Int, carbsMod: Int,
        proteinLow: Int, proteinMod: Int
    ) {
        val xmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <diet_profile id="custom" name="$profileName" exclusivity_group="dietary_pattern">
                <flag name="use_net_carbs">${if (carbsMod in 1..25) "true" else "false"}</flag>
                <flag name="enforce_protein_floor">${if (proteinLow > 15) "true" else "false"}</flag>
                <flag name="enforce_protein_ceiling">false</flag>

                <nutrient name="sodium" low_max="$sodiumLow" moderate_max="$sodiumMod" is_blacklist="true" />
                <nutrient name="potassium" low_max="$potassiumLow" moderate_max="$potassiumMod" is_blacklist="true" />
                <nutrient name="saturated_fat" low_max="$satFatLow" moderate_max="$satFatMod" is_blacklist="true" />
                <nutrient name="carbs" low_max="$carbsLow" moderate_max="$carbsMod" is_blacklist="false" />
                <nutrient name="protein" low_max="$proteinLow" moderate_max="$proteinMod" is_blacklist="false" />
                
                <protein_rules snack_min="5" snack_max="999" meal_min="15" meal_max="999" target_ratio="0.10" />
            </diet_profile>
        """.trimIndent()

        try {
            val localDir = File(context.filesDir, "profiles")
            if (!localDir.exists()) {
                localDir.mkdirs()
            }
            val file = File(localDir, "custom.xml")

            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(xmlContent)
                    writer.flush()
                }
            }
            AppSettings.indexExclusivityGroups(context)
            Log.d(TAG, "Successfully wrote custom.xml to local filesDir")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}