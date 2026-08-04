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
        const val KEY_CUSTOM_BLACKBOARD = "custom_blacklist_ingredients"
        const val KEY_USER_WEIGHT = "user_target_weight_lbs"

        // Dynamic maps populated during boot index
        private val profileExclusivityMap = mutableMapOf<String, String>()
        private val profileConflictsMap = mutableMapOf<String, MutableSet<String>>()

        /**
         * Global Boot Hook: Indexes profile assets for group metadata and dynamic conflicts.
         */
        fun indexExclusivityGroups(context: Context) {
            profileExclusivityMap.clear()
            profileConflictsMap.clear()
            try {
                val fileList = context.assets.list("profiles") ?: emptyArray()
                for (fileName in fileList) {
                    if (!fileName.endsWith(".xml")) continue
                    val inputStream = context.assets.open("profiles/$fileName")
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }

                    var eventType = parser.eventType
                    var currentProfileId = ""

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name
                        if (eventType == XmlPullParser.START_TAG) {
                            if (tagName == "diet_profile") {
                                currentProfileId = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                                val group = parser.getAttributeValue(null, "exclusivity_group") ?: ""
                                if (group.isNotEmpty()) {
                                    profileExclusivityMap[currentProfileId] = group
                                }
                            } else if (tagName == "conflict" && currentProfileId.isNotEmpty()) {
                                val target = parser.getAttributeValue(null, "target") ?: ""
                                if (target.isNotEmpty()) {
                                    // Bidirectional conflict registration (If A conflicts with B, B conflicts with A)
                                    profileConflictsMap.getOrPut(currentProfileId) { mutableSetOf() }.add(target)
                                    profileConflictsMap.getOrPut(target) { mutableSetOf() }.add(currentProfileId)
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
        }
    }

    // --- USER HANDEDNESS CONFIGURATION ---

    fun isRightHanded(): Boolean {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("is_right_handed", true)
    }

    fun setRightHanded(right: Boolean) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_right_handed", right).apply()
    }

    // --- CUSTOM WATCHLIST STORAGE ENGINE ---

    /**
     * Saves a custom ingredient to a specific tier (RED or YELLOW)
     */
    fun saveCustomWatchlistItem(ingredient: String, tier: String) {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        val key = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"

        val existingItems = sharedPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        existingItems.add(ingredient.trim().uppercase())

        sharedPrefs.edit().putStringSet(key, existingItems).apply()
    }

    /**
     * Checks if a specific boolean flag (e.g. "enforce_protein_ratio")
     * is set to true in ANY currently selected profile XML.
     */
    fun isFeatureFlagActive(flagName: String): Boolean {
        val selectedIds = getSelectedConditions()

        for (profileId in selectedIds) {
            try {
                // Check if profileId already ends with .xml; if not, append it
                val fileName = if (profileId.endsWith(".xml")) profileId else "$profileId.xml"

                // Prepend the folder path here: "profiles/$fileName"
                val inputStream = context.assets.open("profiles/$fileName")

                val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(inputStream, "UTF-8")

                var eventType = parser.eventType
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "flag") {
                        val nameAttr = parser.getAttributeValue(null, "name")
                        if (nameAttr == flagName) {
                            val valueText = parser.nextText()
                            if (valueText.trim().lowercase() == "true") {
                                inputStream.close()
                                return true
                            }
                        }
                    }
                    eventType = parser.next()
                }
                inputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    /**
     * Retrieves the list of custom ingredients for a specific tier
     */
    fun getCustomWatchlist(tier: String): List<String> {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        val key = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"

        val itemsSet = sharedPrefs.getStringSet(key, emptySet()) ?: emptySet()
        return itemsSet.map { it.uppercase() }.sorted()
    }

    fun saveSelectedConditions(conditions: Set<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit { putStringSet("tracked_medical_conditions", conditions) }
    }

    fun getSelectedConditions(): Set<String> {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)

        // If "tracked_medical_conditions" does not exist yet (clean start), default to our baseline profile
        return prefs.getStringSet("tracked_medical_conditions", null) ?: setOf("healthy_baseline")
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

    // --- MODERNIZED DUAL-TIER CUSTOM INGREDIENT TRACKER ---

    /**
     * Adds an ingredient to a targeted severity watchlist and ensures it doesn't duplicate.
     */
    fun addWatchlistIngredient(ingredient: String, tier: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        val cleanName = ingredient.trim().uppercase()
        if (cleanName.isEmpty()) return

        val tierUpper = tier.uppercase()
        val targetKey = if (tierUpper == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"
        val alternateKey = if (tierUpper == "RED") "custom_yellow_ingredients" else "custom_red_ingredients"

        val targetSet = (prefs.getStringSet(targetKey, emptySet()) ?: emptySet()).toMutableSet()
        val alternateSet = (prefs.getStringSet(alternateKey, emptySet()) ?: emptySet()).toMutableSet()

        // Safety: If it's already in the other tier, migrate it over cleanly
        alternateSet.remove(cleanName)
        targetSet.add(cleanName)

        prefs.edit {
            putStringSet(targetKey, targetSet)
            putStringSet(alternateKey, alternateSet)
        }
    }

    /**
     * Cleans an ingredient completely out of both custom tracking tiers.
     */
    fun removeWatchlistIngredient(ingredient: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)
        val cleanName = ingredient.trim().uppercase()
        if (cleanName.isEmpty()) return

        val redSet = (prefs.getStringSet("custom_red_ingredients", emptySet()) ?: emptySet()).toMutableSet()
        val yellowSet = (prefs.getStringSet("custom_yellow_ingredients", emptySet()) ?: emptySet()).toMutableSet()

        val removedFromRed = redSet.remove(cleanName)
        val removedFromYellow = yellowSet.remove(cleanName)

        if (removedFromRed || removedFromYellow) {
            prefs.edit {
                putStringSet("custom_red_ingredients", redSet)
                putStringSet("custom_yellow_ingredients", yellowSet)
            }
        }
    }

    /**
     * Managed Instance-Level Toggle Engine: Restores explicit scope binding to getSelectedConditions()
     * Supports a universal clear action if a profile belongs to group "all".
     */
    fun toggleConditionState(targetProfileId: String, isChecked: Boolean) {
        val selectedIds = getSelectedConditions().toMutableSet()

        if (isChecked) {
            val activeGroup = profileExclusivityMap[targetProfileId]

            if (activeGroup != null) {
                if (activeGroup == "all") {
                    // Universal Reset Strategy: If Healthy Baseline is chosen, drop ALL other choices completely
                    selectedIds.clear()
                } else {
                    // Selective Reset Strategy: Drop matching group conditions (like a conflicting CKD stage)
                    // AND automatically uncheck the Healthy Baseline since they are adding a restriction!
                    val conflictingIds = selectedIds.filter { profileId: String ->
                        profileExclusivityMap[profileId] == activeGroup || profileExclusivityMap[profileId] == "all"
                    }
                    selectedIds.removeAll(conflictingIds)
                }
            } else {
                // If the new choice doesn't belong to a group, we still make sure to uncheck the baseline profile
                val baselineConflictingIds = selectedIds.filter { profileId: String ->
                    profileExclusivityMap[profileId] == "all"
                }
                selectedIds.removeAll(baselineConflictingIds)
            }

            // --- RUN DYNAMIC DATA-DRIVEN SAFETY INTERLOCKS ---
            // Remove any active profiles that are registered as conflicts in your XML configuration files
            val dynamicConflicts = profileConflictsMap[targetProfileId] ?: emptySet()
            selectedIds.removeAll(dynamicConflicts)

            selectedIds.add(targetProfileId)
        } else {
            selectedIds.remove(targetProfileId)
        }

        saveSelectedConditions(selectedIds)
    }

    fun getAvailableDietProfiles(includeAllergens: Boolean = true): List<DietProfile> {
        val profileList = mutableListOf<DietProfile>()
        try {
            val fileList = context.assets.list("profiles") ?: emptyArray()
            for (fileName in fileList) {
                if (fileName.endsWith(".xml")) {

                    // Clean filter logic using the boolean parameter
                    if (!includeAllergens && fileName.startsWith("allergen_")) continue

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

        // If no custom medical profiles are checked, return healthy baseline standards
        if (activeProfileIds.isEmpty()) {
            return when (nutrientKey.lowercase()) {
                "sodium"        -> Triple(140, 480, false)
                "protein"       -> Triple(15, 30, false)
                "added_sugar"   -> Triple(5, 12, false)
                "total_sugar"   -> Triple(10, 25, false)
                "saturated_fat" -> Triple(2, 5, false)
                "trans_fat"     -> Triple(0, 0, true) // Permanent binary restriction cap
                "total_fat"     -> Triple(5, 15, false)
                "potassium"     -> Triple(350, 700, false)
                "carbs"         -> Triple(20, 45, false)
                "calories"      -> Triple(0, 999, false) // 999 maps to unlimited, skipping static validation
                "fiber"         -> Triple(0, 999, false)
                else            -> Triple(0, 999, false)
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

        // Return 999 (unlimited) instead of 0 if the nutrient element is omitted in active xml files
        if (!profileMatched) return Triple(0, 999, false)
        return Triple(lowMax, modMax, isBlacklist)
    }

    fun getIngredientsFromAssetFile(fileName: String): List<String> {
        val triggerList = mutableListOf<String>()
        var inputStream: java.io.InputStream? = null

        // 1. Try common filename combinations directly
        val possibleNames = listOf(
            "profiles/$fileName.xml",
            "profiles/allergen_$fileName.xml",
            "profiles/${fileName.removePrefix("allergen_")}.xml"
        )
        for (name in possibleNames) {
            try {
                inputStream = context.assets.open(name)
                if (inputStream != null) break
            } catch (e: Exception) {
                // Try next pattern
            }
        }

        // 2. Fallback: Scan XML headers to find the one matching the id attribute
        if (inputStream == null) {
            try {
                val files = context.assets.list("profiles") ?: emptyArray()
                for (file in files) {
                    if (file.endsWith(".xml")) {
                        val tempStream = context.assets.open("profiles/$file")
                        val parser = Xml.newPullParser().apply { setInput(tempStream, null) }
                        var eventType = parser.eventType
                        var matchFound = false
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG && parser.name == "diet_profile") {
                                val id = parser.getAttributeValue(null, "id") ?: file.removeSuffix(".xml")
                                if (id == fileName) {
                                    matchFound = true
                                    break
                                }
                            }
                            eventType = parser.next()
                        }
                        tempStream.close()
                        if (matchFound) {
                            inputStream = context.assets.open("profiles/$file")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (inputStream == null) return emptyList()

        try {
            val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                // Parse both 'trigger' and 'ingredient' tags for maximum flexibility
                if (eventType == XmlPullParser.START_TAG && (tagName == "trigger" || tagName == "ingredient")) {
                    eventType = parser.next()
                    if (eventType == XmlPullParser.TEXT) {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            triggerList.add(text.uppercase())
                        }
                    }
                }
                eventType = parser.next()
            }
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return triggerList
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