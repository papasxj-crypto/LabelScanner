package net.meinook.labelscanner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

object CustomProfileManager {

    private const val TAG = "CustomProfileManager"
    private var isUpdatingSliders = false

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

        val activeName = appSettings.getActiveProfile()
        val etProfileName = createInputRow(context, linearLayout, density, "Profile Name").apply {
            setText(if (template != null) "${template.displayName} ($activeName)" else "Custom Plan ($activeName)")
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val sodMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "sodium").second else 0
        val sodLow = (if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "sodium").first else 0).coerceAtMost(sodMod)

        val potMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "potassium").second else 0
        val potLow = (if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "potassium").first else 0).coerceAtMost(potMod)

        val satMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "saturated_fat").second else 0
        val satLow = (if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "saturated_fat").first else 0).coerceAtMost(satMod)

        val carbsMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "carbs").second else 0
        val carbsLow = (if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "carbs").first else 0).coerceAtMost(carbsMod)

        val protMod = if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "protein").second else 0
        val protLow = (if (template != null) appSettings.getNutrientThresholdsForProfile(template.id, "protein").first else 0).coerceAtMost(protMod)

        val sliderSodLow = createSliderRow(context, linearLayout, density, "Sodium Yellow Limit", "mg", sodLow, 0f, 3000f, 50f)
        val sliderSodMod = createSliderRow(context, linearLayout, density, "Sodium Red Limit", "mg", sodMod, 0f, 3000f, 50f)

        val sliderPotLow = createSliderRow(context, linearLayout, density, "Potassium Yellow Limit", "mg", potLow, 0f, 3000f, 50f)
        val sliderPotMod = createSliderRow(context, linearLayout, density, "Potassium Red Limit", "mg", potMod, 0f, 3000f, 50f)

        val sliderSatLow = createSliderRow(context, linearLayout, density, "Saturated Fat Yellow Limit", "g", satLow, 0f, 50f, 1f)
        val sliderSatMod = createSliderRow(context, linearLayout, density, "Saturated Fat Red Limit", "g", satMod, 0f, 50f, 1f)

        val sliderCarbsLow = createSliderRow(context, linearLayout, density, "Carbohydrates Yellow Limit", "g", carbsLow, 0f, 300f, 5f)
        val sliderCarbsMod = createSliderRow(context, linearLayout, density, "Carbohydrates Red Limit", "g", carbsMod, 0f, 300f, 5f)

        val sliderProtLow = createSliderRow(context, linearLayout, density, "Protein Yellow Limit", "g", protLow, 0f, 150f, 5f)
        val sliderProtMod = createSliderRow(context, linearLayout, density, "Protein Red Limit", "g", protMod, 0f, 150f, 5f)

        setupSliderGuard(sliderSodLow, sliderSodMod)
        setupSliderGuard(sliderPotLow, sliderPotMod)
        setupSliderGuard(sliderSatLow, sliderSatMod)
        setupSliderGuard(sliderCarbsLow, sliderCarbsMod)
        setupSliderGuard(sliderProtLow, sliderProtMod)

        // Read Template Flags Dynamically
        val initPhosphate = if (template != null) appSettings.isFeatureFlagActiveForProfile(template.id, "avoid_phosphate_additives") else false
        val initGfFlour = if (template != null) appSettings.isFeatureFlagActiveForProfile(template.id, "prefer_preblended_gf_flour") else false
        val initCoconut = if (template != null) appSettings.isFeatureFlagActiveForProfile(template.id, "strictly_avoid_coconut") else false
        val initSatFats = if (template != null) appSettings.isFeatureFlagActiveForProfile(template.id, "limit_saturated_fats") else false

        linearLayout.addView(TextView(context).apply {
            text = "TACTICAL INTERVENTIONS"
            setTextColor(Color.parseColor("#99A1B3"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (20 * density).toInt(), 0, (6 * density).toInt())
        })

        val togglePhosphate = createToggleRow(context, linearLayout, density, "Avoid Phosphate Additives", "Replaces baking powder with baking soda + safe acid activator", initPhosphate)
        val toggleGfFlour = createToggleRow(context, linearLayout, density, "Prefer 1:1 GF Flour Blend", "Favors balanced commercial blends instead of single nut flours", initGfFlour)
        val toggleCoconut = createToggleRow(context, linearLayout, density, "Strictly Avoid Coconut", "Prevents high-potassium/phosphorus coconut flours and milks", initCoconut)
        val toggleSatFats = createToggleRow(context, linearLayout, density, "Limit Saturated Fats", "Swaps heavy butter or coconut oils for healthier options", initSatFats)

        MaterialAlertDialogBuilder(context, R.style.Theme_LabelScanner)
            .setTitle(if (template != null) "Clone: ${template.displayName}" else "New Custom Profile")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val name = etProfileName.text.toString().trim().ifEmpty { "Custom Plan ($activeName)" }
                val sLow = sliderSodLow.value.toInt()
                val sMod = sliderSodMod.value.toInt()
                val pLow = sliderPotLow.value.toInt()
                val pMod = sliderPotMod.value.toInt()
                val stLow = sliderSatLow.value.toInt()
                val stMod = sliderSatMod.value.toInt()
                val cLow = sliderCarbsLow.value.toInt()
                val cMod = sliderCarbsMod.value.toInt()
                val prLow = sliderProtLow.value.toInt()
                val prMod = sliderProtMod.value.toInt()
                val avoidPhosphate = togglePhosphate.isChecked
                val preferGfFlour = toggleGfFlour.isChecked
                val avoidCoconut = toggleCoconut.isChecked
                val limitSatFats = toggleSatFats.isChecked

                writeCustomProfileXml(
                    context, name, sLow, sMod, pLow, pMod, stLow, stMod, cLow, cMod, prLow, prMod,
                    avoidPhosphate, preferGfFlour, avoidCoconut, limitSatFats, template?.id
                )
                onProfileSaved()
                Toast.makeText(context, "Custom profile saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createInputRow(
        context: Context,
        linearLayout: LinearLayout,
        density: Float,
        title: String
    ): EditText {
        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#99A1B3"))
            textSize = 12f
            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
        }
        val et = EditText(context).apply {
            setTextColor(Color.parseColor("#F4F5FC"))
            background = context.getDrawable(R.drawable.bg_height_edit_text)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
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

    private fun createSliderRow(
        context: Context,
        linearLayout: LinearLayout,
        density: Float,
        title: String,
        unit: String,
        defaultValue: Int,
        minVal: Float,
        maxTo: Float,
        step: Float
    ): com.google.android.material.slider.Slider {
        val safeMaxTo = if (maxTo <= minVal) minVal + 1.0f else maxTo
        val roundedValue = if (step > 0f) {
            val stepsCount = Math.round((defaultValue - minVal) / step)
            (minVal + (stepsCount * step)).coerceIn(minVal, safeMaxTo)
        } else {
            defaultValue.toFloat().coerceIn(minVal, safeMaxTo)
        }

        val labelView = TextView(context).apply {
            text = "$title: ${roundedValue.toInt()} $unit"
            setTextColor(Color.parseColor("#99A1B3"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }

        val slider = com.google.android.material.slider.Slider(context).apply {
            valueFrom = minVal
            valueTo = safeMaxTo
            if (step > 0f) {
                stepSize = step
            }
            value = roundedValue

            trackActiveTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4E3B34"))
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#99A1B3"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F4F5FC"))

            addOnChangeListener { _, value, _ ->
                labelView.text = "$title: ${value.toInt()} $unit"
            }
        }

        linearLayout.addView(labelView)
        linearLayout.addView(slider)
        return slider
    }

    private fun createToggleRow(
        context: Context,
        linearLayout: LinearLayout,
        density: Float,
        title: String,
        description: String,
        defaultChecked: Boolean
    ): SwitchCompat {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#F4F5FC"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }

        val descView = TextView(context).apply {
            text = description
            setTextColor(Color.parseColor("#99A1B3"))
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }

        textContainer.addView(titleView)
        textContainer.addView(descView)

        val toggle = SwitchCompat(context).apply {
            isChecked = defaultChecked
            trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#99A1B3"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F4F5FC"))
        }

        container.addView(textContainer)
        container.addView(toggle)
        linearLayout.addView(container)
        return toggle
    }

    private fun setupSliderGuard(
        lowSlider: com.google.android.material.slider.Slider,
        modSlider: com.google.android.material.slider.Slider
    ) {
        lowSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingSliders) return@addOnChangeListener
            if (value > modSlider.value) {
                isUpdatingSliders = true
                lowSlider.value = modSlider.value
                isUpdatingSliders = false
            }
        }

        modSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingSliders) return@addOnChangeListener
            if (value < lowSlider.value) {
                isUpdatingSliders = true
                modSlider.value = lowSlider.value
                isUpdatingSliders = false
            }
        }
    }

    private fun writeCustomProfileXml(
        context: Context,
        profileName: String,
        sodiumLow: Int, sodiumMod: Int,
        potassiumLow: Int, potassiumMod: Int,
        satFatLow: Int, satFatMod: Int,
        carbsLow: Int, carbsMod: Int,
        proteinLow: Int, proteinMod: Int,
        avoidPhosphate: Boolean,
        preferGfFlour: Boolean,
        avoidCoconut: Boolean,
        limitSatFats: Boolean,
        baseTemplateId: String?
    ) {
        val appSettings = AppSettings(context)
        val activeSuffix = appSettings.getActiveProfile().lowercase(Locale.ROOT).replace(" ", "_")
        val activeProfileId = "custom_$activeSuffix"

        // Inherit ceiling flag if cloning from CKD or renal profile
        val inheritProteinCeiling = if (baseTemplateId != null) {
            appSettings.isFeatureFlagActiveForProfile(baseTemplateId, "enforce_protein_ceiling")
        } else false

        val xmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <diet_profile id="$activeProfileId" name="$profileName" base_profile_id="${baseTemplateId ?: "healthy_baseline"}" exclusivity_group="dietary_pattern">
                <flag name="use_net_carbs">${if (carbsMod in 1..25) "true" else "false"}</flag>
                <flag name="enforce_protein_floor">${if (proteinLow > 15) "true" else "false"}</flag>
                <flag name="enforce_protein_ceiling">${if (inheritProteinCeiling) "true" else "false"}</flag>
                <flag name="avoid_phosphate_additives">${if (avoidPhosphate) "true" else "false"}</flag>
                <flag name="prefer_preblended_gf_flour">${if (preferGfFlour) "true" else "false"}</flag>
                <flag name="strictly_avoid_coconut">${if (avoidCoconut) "true" else "false"}</flag>
                <flag name="limit_saturated_fats">${if (limitSatFats) "true" else "false"}</flag>

                <nutrient name="sodium" low_max="$sodiumLow" moderate_max="$sodiumMod" is_blacklist="true" />
                <nutrient name="potassium" low_max="$potassiumLow" moderate_max="$potassiumMod" is_blacklist="true" />
                <nutrient name="saturated_fat" low_max="$satFatLow" moderate_max="$satFatMod" is_blacklist="true" />
                <nutrient name="carbs" low_max="$carbsLow" moderate_max="$carbsMod" is_blacklist="false" />
                <nutrient name="protein" low_max="$proteinLow" moderate_max="$proteinMod" is_blacklist="false" />
                
                <protein_rules snack_min="0" snack_max="8" meal_min="0" meal_max="20" target_ratio="0.01" />
            </diet_profile>
        """.trimIndent()

        try {
            val localDir = File(context.filesDir, "profiles")
            if (!localDir.exists()) {
                localDir.mkdirs()
            }
            val file = File(localDir, "$activeProfileId.xml")

            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(xmlContent)
                    writer.flush()
                }
            }
            AppSettings.indexExclusivityGroups(context)
            Log.d(TAG, "Successfully wrote $activeProfileId.xml to local filesDir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write custom profile XML", e)
        }
    }
}