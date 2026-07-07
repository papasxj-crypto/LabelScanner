package net.meinook.labelscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import androidx.core.content.edit

class AppSettings(private val context: Context) {

    private val settingsFile = File(context.filesDir, "user_diet_settings.json")

    companion object {
        const val KEY_SODIUM_LIMIT = "max_sodium_mg"
        const val KEY_CUSTOM_BLACKBOARD = "custom_blacklist_ingredients"
        const val KEY_USER_WEIGHT = "user_target_weight_lbs"
        const val KEY_CARB_LIMIT = "max_carbs_g"
        const val KEY_TOTAL_SUGAR_LIMIT = "max_total_sugar_g"
        const val KEY_ADDED_SUGAR_LIMIT = "max_added_sugar_g"
        const val KEY_TOTAL_FAT_LIMIT = "max_total_fat_g"
        const val KEY_SAT_FAT_LIMIT = "max_sat_fat_g"

        // Static map shared seamlessly by all activity contexts
        private val profileExclusivityMap = mutableMapOf<String, String>()

        /**
         * Global Boot Hook: Indexes profile assets for group metadata evaluation.
         */
        fun indexExclusivityGroups(context: Context) {
            profileExclusivityMap.clear()
            try {
                val fileList = context.assets.list("profiles") ?: emptyArray()
                for (fileName in fileList) {
                    if (!fileName.endsWith(".xml")) continue
                    val inputStream = context.assets.open("profiles/$fileName")
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }

                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            val group = parser.getAttributeValue(null, "exclusivity_group") ?: ""
                            if (group.isNotEmpty()) {
                                profileExclusivityMap[id] = group
                                android.util.Log.d("SCANNER_DEBUG", "Indexed profile ID: '$id' into Group: '$group'")
                            }
                            break
                        }
                        eventType = parser.next()
                    }
                    inputStream.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var cachedSettings = JSONObject().apply {
        put(KEY_SODIUM_LIMIT, 140)
    }

    init {
        loadSettingsFromDisk()
    }

    fun saveSelectedConditions(conditions: Set<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit { putStringSet("tracked_medical_conditions", conditions) }
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

    fun setPendingSaveFlag(hasSaved: Boolean) {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
        prefs.edit { putBoolean("pending_profile_save", hasSaved) }
    }

    fun getAndClearPendingSaveFlag(): Boolean {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
        val currentFlagState = prefs.getBoolean("pending_profile_save", false)
        if (currentFlagState) {
            prefs.edit { putBoolean("pending_profile_save", false) }
        }
        return currentFlagState
    }

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
     * Managed Instance-Level Toggle Engine: Restores explicit scope binding to getSelectedConditions()
     */
    /**
     * Managed Instance-Level Toggle Engine: Restores explicit scope binding to getSelectedConditions()
     * Supports a universal clear action if a profile belongs to group "all".
     */
    fun toggleConditionState(targetProfileId: String, isChecked: Boolean) {
        val selectedIds = getSelectedConditions().toMutableSet()

        android.util.Log.d("SCANNER_DEBUG", "Toggling ID: '$targetProfileId' | Checked: $isChecked")

        if (isChecked) {
            val activeGroup = profileExclusivityMap[targetProfileId]
            android.util.Log.d("SCANNER_DEBUG", "Profile '$targetProfileId' belongs to group: '$activeGroup'")

            if (activeGroup != null) {
                if (activeGroup == "all") {
                    // Universal Reset Strategy: If Healthy Baseline is chosen, drop ALL other choices completely
                    android.util.Log.d("SCANNER_DEBUG", "Universal baseline reset triggered. Clearing all selections.")
                    selectedIds.clear()
                } else {
                    // Selective Reset Strategy: Drop matching group conditions (like a conflicting CKD stage)
                    // AND automatically uncheck the Healthy Baseline since they are adding a restriction!
                    val conflictingIds = selectedIds.filter { profileId: String ->
                        profileExclusivityMap[profileId] == activeGroup || profileExclusivityMap[profileId] == "all"
                    }
                    android.util.Log.d("SCANNER_DEBUG", "Removing conflicting profiles: $conflictingIds")
                    selectedIds.removeAll(conflictingIds)
                }
            } else {
                // If the new choice doesn't belong to a group, we still make sure to uncheck the baseline profile
                val baselineConflictingIds = selectedIds.filter { profileId: String ->
                    profileExclusivityMap[profileId] == "all"
                }
                selectedIds.removeAll(baselineConflictingIds)
            }
            selectedIds.add(targetProfileId)
        } else {
            selectedIds.remove(targetProfileId)
        }

        saveSelectedConditions(selectedIds)
    }

    fun getAvailableDietProfiles(): List<DietProfile> {
        val profileList = mutableListOf<DietProfile>()
        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (fileName.endsWith(".xml")) {
                    val inputStream = context.assets.open("profiles/$fileName")
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            val displayName = parser.getAttributeValue(null, "name") ?: id
                            profileList.add(DietProfile(id, displayName))
                            break
                        }
                        eventType = parser.next()
                    }
                    inputStream.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return profileList
    }

    fun getNutrientThresholds(nutrientKey: String): Triple<Int, Int, Boolean> {
        val activeProfileIds = getSelectedConditions()

        if (activeProfileIds.isEmpty()) {
            return when (nutrientKey.lowercase()) {
                "sodium"        -> Triple(140, 480, false)
                "protein"       -> Triple(15, 30, false)
                "added_sugar"   -> Triple(5, 12, false)
                "total_sugar"   -> Triple(10, 25, false)
                "saturated_fat" -> Triple(2, 5, false)
                "total_fat"     -> Triple(5, 15, false)
                "potassium"     -> Triple(350, 700, false)
                else            -> Triple(0, 0, false)
            }
        }

        var lowMax = Int.MAX_VALUE
        var modMax = Int.MAX_VALUE
        var isBlacklist = false
        var profileMatched = false

        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (!fileName.endsWith(".xml")) continue

                val inputStream = context.assets.open("profiles/$fileName")
                val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                var eventType = parser.eventType
                var targetProfileActive = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    if (eventType == XmlPullParser.START_TAG) {
                        if (tagName == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            if (activeProfileIds.contains(id)) {
                                targetProfileActive = true
                            }
                        } else if (tagName == "nutrient" && targetProfileActive) {
                            val currentName = parser.getAttributeValue(null, "name")
                            if (currentName?.lowercase() == nutrientKey.lowercase()) {
                                val fileLow = parser.getAttributeValue(null, "low_max")?.toIntOrNull() ?: 0
                                val fileMod = parser.getAttributeValue(null, "moderate_max")?.toIntOrNull() ?: 0
                                val fileBlacklist = parser.getAttributeValue(null, "is_blacklist")?.toBooleanStrictOrNull() ?: false

                                lowMax = minOf(lowMax, fileLow)
                                modMax = minOf(modMax, fileMod)
                                if (fileBlacklist) isBlacklist = true
                                profileMatched = true
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!profileMatched) return Triple(0, 0, false)
        return Triple(lowMax, modMax, isBlacklist)
    }

    fun loadTriggersFromAssets(categoryTarget: String): List<String> {
        val triggerList = mutableListOf<String>()
        val activeProfileIds = getSelectedConditions()
        if (activeProfileIds.isEmpty()) return triggerList

        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (!fileName.endsWith(".xml")) continue

                val inputStream = context.assets.open("profiles/$fileName")
                val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                var eventType = parser.eventType
                var targetProfileActive = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    if (eventType == XmlPullParser.START_TAG) {
                        if (tagName == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            if (activeProfileIds.contains(id)) {
                                targetProfileActive = true
                            }
                        } else if (tagName == "trigger" && targetProfileActive) {
                            val currentCategory = parser.getAttributeValue(null, "category")
                            eventType = parser.next()
                            if (eventType == XmlPullParser.TEXT) {
                                val text = parser.text?.trim() ?: ""
                                if (text.isNotEmpty() && currentCategory?.lowercase() == categoryTarget.lowercase()) {
                                    triggerList.add(text.uppercase())
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return triggerList
    }

    fun getActiveProteinRules(): ProteinRules {
        val activeProfileIds = getSelectedConditions()

        var maxSnackMin = 0
        var minSnackMax = 999
        var maxMealMin = 0
        var minMealMax = 999
        var maxRatio = 0.0f
        var ruleDiscovered = false

        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (!fileName.endsWith(".xml")) continue

                val inputStream = context.assets.open("profiles/$fileName")
                val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                var eventType = parser.eventType
                var targetProfileActive = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    if (eventType == XmlPullParser.START_TAG) {
                        if (tagName == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            if (activeProfileIds.contains(id)) {
                                targetProfileActive = true
                            }
                        } else if (tagName == "protein_rules" && targetProfileActive) {
                            val fileSnackMin = parser.getAttributeValue(null, "snack_min")?.toIntOrNull() ?: 0
                            val fileSnackMax = parser.getAttributeValue(null, "snack_max")?.toIntOrNull() ?: 999
                            val fileMealMin = parser.getAttributeValue(null, "meal_min")?.toIntOrNull() ?: 0
                            val fileMealMax = parser.getAttributeValue(null, "meal_max")?.toIntOrNull() ?: 999
                            val fileRatio = parser.getAttributeValue(null, "target_ratio")?.toFloatOrNull() ?: 0.0f

                            maxSnackMin = maxOf(maxSnackMin, fileSnackMin)
                            maxMealMin = maxOf(maxMealMin, fileMealMin)
                            maxRatio = maxOf(maxRatio, fileRatio)
                            minSnackMax = minOf(minSnackMax, fileSnackMax)
                            minMealMax = minOf(minMealMax, fileMealMax)

                            ruleDiscovered = true
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (ruleDiscovered) {
            ProteinRules(
                snackMin = maxSnackMin,
                snackMax = minSnackMax,
                mealMin = maxMealMin,
                mealMax = minMealMax,
                targetRatio = maxRatio
            )
        } else {
            ProteinRules()
        }
    }

    fun getActiveNutrientRules(): List<NutrientRule> {
        val activeProfileIds = getSelectedConditions()
        val accumulatedRules = mutableListOf<NutrientRule>()

        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (!fileName.endsWith(".xml")) continue

                val inputStream = context.assets.open("profiles/$fileName")
                val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                var eventType = parser.eventType
                var targetProfileActive = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    if (eventType == XmlPullParser.START_TAG) {
                        if (tagName == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            if (activeProfileIds.contains(id)) {
                                targetProfileActive = true
                            }
                        } else if (tagName == "nutrient" && targetProfileActive) {
                            val name = parser.getAttributeValue(null, "name") ?: ""
                            val lowMax = parser.getAttributeValue(null, "low_max")?.toIntOrNull() ?: 0
                            val moderateMax = parser.getAttributeValue(null, "moderate_max")?.toIntOrNull() ?: 0
                            val isBlacklist = parser.getAttributeValue(null, "is_blacklist")?.toBoolean() ?: false

                            if (name.isNotEmpty()) {
                                accumulatedRules.add(NutrientRule(name, lowMax, moderateMax, isBlacklist))
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return accumulatedRules
    }

    fun getUserWeight(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_USER_WEIGHT, 0.0f).toDouble()
    }

    fun setUserWeight(weight: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit { putFloat(KEY_USER_WEIGHT, weight.toFloat()) }
    }
}

data class DietProfile(val id: String, val displayName: String)

data class ProteinRules(
    val snackMin: Int = 0,
    val snackMax: Int = 999,
    val mealMin: Int = 0,
    val mealMax: Int = 999,
    val targetRatio: Float = 0.0f
)

data class NutrientRule(
    val name: String,
    val lowMax: Int,
    val moderateMax: Int,
    val isBlacklist: Boolean
)