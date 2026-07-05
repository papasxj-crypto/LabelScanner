package net.meinook.labelscanner

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.Xml
import java.io.InputStream

class AppSettings(private val context: Context) {

    // Private storage file inside the app's secure internal sandbox
    private val settingsFile = File(context.filesDir, "user_diet_settings.json")

    // Core Data Keys
    companion object {
        const val KEY_SODIUM_LIMIT = "max_sodium_mg"
        const val KEY_CUSTOM_BLACKBOARD = "custom_blacklist_ingredients"
        const val KEY_USER_WEIGHT = "user_target_weight_lbs"
        const val KEY_CARB_LIMIT = "max_carbs_g"
        const val KEY_TOTAL_SUGAR_LIMIT = "max_total_sugar_g"
        const val KEY_ADDED_SUGAR_LIMIT = "max_added_sugar_g"
        // New Fat Tracking Keys
        const val KEY_TOTAL_FAT_LIMIT = "max_total_fat_g"
        const val KEY_SAT_FAT_LIMIT = "max_sat_fat_g"
    }

    // Default values if the user hasn't customized anything yet
    private var cachedSettings = JSONObject().apply {
        put(KEY_SODIUM_LIMIT, 140) // Standard FDA "Low Sodium" definition threshold
    }

    init {
        loadSettingsFromDisk()
    }

    fun saveSelectedConditions(conditions: Set<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putStringSet("tracked_medical_conditions", conditions).apply()
    }

    fun getSelectedConditions(): Set<String> {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("tracked_medical_conditions", emptySet()) ?: emptySet()
    }

    private fun loadSettingsFromDisk() {
        if (settingsFile.exists()) {
            try {
                val jsonString = settingsFile.readText()
                cachedSettings = JSONObject(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveSettingsToDisk() {
        try {
            settingsFile.writeText(cachedSettings.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- GETTERS & SETTERS ---

    fun getSodiumLimit(): Int {
        return cachedSettings.optInt(KEY_SODIUM_LIMIT, 140)
    }

    fun setSodiumLimit(mg: Int) {
        cachedSettings.put(KEY_SODIUM_LIMIT, mg)
        saveSettingsToDisk()
    }

    fun getCustomBlacklist(): List<String> {
        val jsonArray = cachedSettings.optJSONArray(KEY_CUSTOM_BLACKBOARD) ?: JSONArray()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i).uppercase())
        }
        return list
    }

    fun addBlacklistIngredient(ingredient: String) {
        val currentList = getCustomBlacklist().toMutableList()
        val cleanName = ingredient.trim().uppercase()
        if (cleanName.isNotEmpty() && !currentList.contains(cleanName)) {
            currentList.add(cleanName)
            cachedSettings.put(KEY_CUSTOM_BLACKBOARD, JSONArray(currentList))
            saveSettingsToDisk()
        }
    }

    fun removeBlacklistIngredient(ingredient: String) {
        val currentList = getCustomBlacklist().toMutableList()
        if (currentList.remove(ingredient.trim().uppercase())) {
            cachedSettings.put(KEY_CUSTOM_BLACKBOARD, JSONArray(currentList))
            saveSettingsToDisk()
        }
    }

    /**
     * Dynamically reads a specific nutrient's profile limits and blacklist status from XML.
     * Returns a Triple containing: (Low Max, Moderate Max, Is Blacklist Enforced)
     */
    fun getNutrientThresholds(nutrientKey: String): Triple<Int, Int, Boolean> {
        val activeConditions = getSelectedConditions()
        val isCKDActive = activeConditions.any { it.contains("CKD") || it.contains("Kidney") }

        // Determine which XML profile configuration asset file to load
        val xmlResource = if (isCKDActive) R.xml.ckd_triggers else R.xml.triggers

        val parser = context.resources.getXml(xmlResource)
        var eventType = parser.eventType

        // Establish safe default fallbacks if parsing encounters anomalies
        var lowMax = 0
        var modMax = 0
        var isBlacklist = false

        try {
            while (eventType != 1) { // 1 = END_DOCUMENT
                val tagName = parser.name
                if (eventType == 2 && tagName == "nutrient") { // 2 = START_TAG
                    val currentName = parser.getAttributeValue(null, "name")
                    if (currentName?.lowercase() == nutrientKey.lowercase()) {
                        lowMax = parser.getAttributeValue(null, "low_max")?.toIntOrNull() ?: 0
                        modMax = parser.getAttributeValue(null, "moderate_max")?.toIntOrNull() ?: 0
                        isBlacklist = parser.getAttributeValue(null, "is_blacklist")?.toBooleanStrictOrNull() ?: false
                        break // Target matched, terminate parsing pipeline loop safely
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            parser.close()
        }
        return Triple(lowMax, modMax, isBlacklist)
    }

    /**
     * Reads the triggers XML from res/xml/triggers.xml using native platform resource parsing.
     */
    fun loadTriggersFromAssets(categoryTarget: String): List<String> {
        val triggerList = mutableListOf<String>()
        val parser = context.resources.getXml(R.xml.ckd_triggers)
        var eventType = parser.eventType

        try {
            while (eventType != 1) {
                val tagName = parser.name
                if (eventType == 2 && tagName == "trigger") {
                    val currentCategory = parser.getAttributeValue(null, "category")
                    eventType = parser.next()
                    if (eventType == 4) {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty() && currentCategory == categoryTarget) {
                            triggerList.add(text.uppercase())
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            parser.close()
        }
        return triggerList
    }

    fun getUserWeight(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_USER_WEIGHT, 0.0f).toDouble()
    }

    fun setUserWeight(weight: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_USER_WEIGHT, weight.toFloat()).apply()
    }
}