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
        // Returns an empty set by default if nothing is selected yet
        return prefs.getStringSet("tracked_medical_conditions", emptySet()) ?: emptySet()
    }

    // Read saved configuration from local storage
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

    // Write current state back to disk securely
    private fun saveSettingsToDisk() {
        try {
            settingsFile.writeText(cachedSettings.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- GETTERS & SETTERS (The API for your layout to use) ---

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
     * Reads a specific nutrient's limits from the XML file.
     * Returns a Pair containing (Low Limit Max, Moderate Limit Max)
     */

    fun getNutrientThresholds(nutrientName: String): Pair<Int, Int> {
        val parser = context.resources.getXml(net.meinook.labelscanner.R.xml.ckd_triggers)
        var eventType = parser.eventType

        // Default fallback limits if something goes wrong reading the file
        var lowMax = 140
        var modMax = 300

        try {
            while (eventType != 1) { // 1 = END_DOCUMENT
                val tagName = parser.name
                if (eventType == 2 && tagName == "nutrient") { // 2 = START_TAG
                    val name = parser.getAttributeValue(null, "name")
                    if (name?.uppercase() == nutrientName.uppercase()) {
                        lowMax = parser.getAttributeValue(null, "low_max")?.toIntOrNull() ?: 140
                        modMax = parser.getAttributeValue(null, "moderate_max")?.toIntOrNull() ?: 300
                        break // Found our target, exit loop
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            parser.close()
        }
        return Pair(lowMax, modMax)
    }

    /**
     * Reads the triggers XML from res/xml/triggers.xml using native platform resource parsing.
     */
    fun loadTriggersFromAssets(categoryTarget: String): List<String> {
        val triggerList = mutableListOf<String>()

        // Open the platform-indexed XML resource pipe
        val parser = context.resources.getXml(R.xml.ckd_triggers)
        var eventType = parser.eventType

        try {
            while (eventType != 1) { // 1 corresponds to END_DOCUMENT
                val tagName = parser.name

                if (eventType == 2 && tagName == "trigger") { // 2 corresponds to START_TAG
                    val currentCategory = parser.getAttributeValue(null, "category")

                    // Move directly to the text inside the tag
                    eventType = parser.next()
                    if (eventType == 4) { // 4 corresponds to TEXT
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
}