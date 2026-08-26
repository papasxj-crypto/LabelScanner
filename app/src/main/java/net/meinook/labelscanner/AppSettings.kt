package net.meinook.labelscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import androidx.core.content.edit

class AppSettings(private val context: Context) {

    companion object {
        const val KEY_CUSTOM_BLACKBOARD = "custom_blacklist_ingredients"
        const val KEY_USER_WEIGHT = "user_target_weight_lbs"
        const val KEY_USER_HEIGHT_INCHES = "user_height_inches"
        const val KEY_USER_GENDER = "user_gender"

        private val profileExclusivityMap = mutableMapOf<String, String>()
        private val profileConflictsMap = mutableMapOf<String, MutableSet<String>>()

        fun indexExclusivityGroups(context: Context) {
            profileExclusivityMap.clear()
            profileConflictsMap.clear()
            try {
                val fileList = context.assets.list("profiles") ?: emptyArray()
                for (fileName in fileList) {
                    if (!fileName.endsWith(".xml")) continue
                    context.assets.open("profiles/$fileName").use { inputStream ->
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
                                        profileConflictsMap.getOrPut(currentProfileId) { mutableSetOf() }.add(target)
                                        profileConflictsMap.getOrPut(target) { mutableSetOf() }.add(currentProfileId)
                                    }
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isRightHanded(): Boolean {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_right_handed", true)
    }

    fun setRightHanded(right: Boolean) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_right_handed", right).apply()
    }

    fun saveCustomWatchlistItem(ingredient: String, tier: String) {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val key = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"

        val existingItems = sharedPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        existingItems.add(ingredient.trim().uppercase())

        sharedPrefs.edit().putStringSet(key, existingItems).apply()
    }

    fun isFeatureFlagActive(flagName: String): Boolean {
        val selectedIds = getSelectedConditions()

        for (profileId in selectedIds) {
            try {
                val fileName = if (profileId.endsWith(".xml")) profileId else "$profileId.xml"
                context.assets.open("profiles/$fileName").use { inputStream ->
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
                                    return true
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    fun getCustomWatchlist(tier: String): List<String> {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val key = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"

        val itemsSet = sharedPrefs.getStringSet(key, emptySet()) ?: emptySet()
        return itemsSet.map { it.uppercase() }.sorted()
    }

    fun saveSelectedConditions(conditions: Set<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putStringSet("tracked_medical_conditions", conditions) }
    }

    fun getSelectedConditions(): Set<String> {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("tracked_medical_conditions", null) ?: setOf("healthy_baseline")
    }

    fun setPendingSaveFlag(hasSaved: Boolean) {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("pending_profile_save", hasSaved) }
    }

    fun getAndClearPendingSaveFlag(): Boolean {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val currentFlagState = prefs.getBoolean("pending_profile_save", false)
        if (currentFlagState) {
            prefs.edit { putBoolean("pending_profile_save", false) }
        }
        return currentFlagState
    }

    fun addWatchlistIngredient(ingredient: String, tier: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val cleanName = ingredient.trim().uppercase()
        if (cleanName.isEmpty()) return

        val tierUpper = tier.uppercase()
        val targetKey = if (tierUpper == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"
        val alternateKey = if (tierUpper == "RED") "custom_yellow_ingredients" else "custom_red_ingredients"

        val targetSet = (prefs.getStringSet(targetKey, emptySet()) ?: emptySet()).toMutableSet()
        val alternateSet = (prefs.getStringSet(alternateKey, emptySet()) ?: emptySet()).toMutableSet()

        alternateSet.remove(cleanName)
        targetSet.add(cleanName)

        prefs.edit {
            putStringSet(targetKey, targetSet)
            putStringSet(alternateKey, alternateSet)
        }
    }

    fun removeWatchlistIngredient(ingredient: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
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

    fun toggleConditionState(targetProfileId: String, isChecked: Boolean) {
        val selectedIds = getSelectedConditions().toMutableSet()

        if (isChecked) {
            val activeGroup = profileExclusivityMap[targetProfileId]

            if (activeGroup != null) {
                if (activeGroup == "all") {
                    selectedIds.clear()
                } else {
                    val conflictingIds = selectedIds.filter { profileId: String ->
                        profileExclusivityMap[profileId] == activeGroup || profileExclusivityMap[profileId] == "all"
                    }
                    selectedIds.removeAll(conflictingIds)
                }
            } else {
                val baselineConflictingIds = selectedIds.filter { profileId: String ->
                    profileExclusivityMap[profileId] == "all"
                }
                selectedIds.removeAll(baselineConflictingIds)
            }

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
                    if (!includeAllergens && fileName.startsWith("allergen_")) continue

                    context.assets.open("profiles/$fileName").use { inputStream ->
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
                    }
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
                "trans_fat"     -> Triple(0, 0, true)
                "total_fat"     -> Triple(5, 15, false)
                "potassium"     -> Triple(350, 700, false)
                "carbs"         -> Triple(20, 45, false)
                "calories"      -> Triple(0, 999, false)
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

                context.assets.open("profiles/$fileName").use { inputStream ->
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
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!profileMatched) return Triple(0, 999, false)
        return Triple(lowMax, modMax, isBlacklist)
    }

    fun getIngredientsFromAssetFile(fileName: String): List<String> {
        val triggerList = mutableListOf<String>()
        var openedStream: java.io.InputStream? = null

        val possibleNames = listOf(
            "profiles/$fileName.xml",
            "profiles/allergen_$fileName.xml",
            "profiles/${fileName.removePrefix("allergen_")}.xml"
        )
        for (name in possibleNames) {
            try {
                openedStream = context.assets.open(name)
                if (openedStream != null) break
            } catch (e: Exception) {
                // Seek alternative file pattern
            }
        }

        if (openedStream == null) {
            try {
                val files = context.assets.list("profiles") ?: emptyArray()
                for (file in files) {
                    if (file.endsWith(".xml")) {
                        val matchedStream = context.assets.open("profiles/$file").use { tempStream ->
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
                            matchFound
                        }
                        if (matchedStream) {
                            openedStream = context.assets.open("profiles/$file")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (openedStream == null) return emptyList()

        try {
            openedStream.use { inputStream ->
                val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                var eventType = parser.eventType

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
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
            }
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

                context.assets.open("profiles/$fileName").use { inputStream ->
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
                }
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

                context.assets.open("profiles/$fileName").use { inputStream ->
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
                }
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

                context.assets.open("profiles/$fileName").use { inputStream ->
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
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return accumulatedRules
    }

    fun getUserWeight(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_USER_WEIGHT, 0.0f).toDouble()
    }

    fun setUserWeight(weight: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putFloat(KEY_USER_WEIGHT, weight.toFloat()) }
    }

    fun getUserHeightInches(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_USER_HEIGHT_INCHES, 0.0f).toDouble()
    }

    fun setUserHeightInches(height: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putFloat(KEY_USER_HEIGHT_INCHES, height.toFloat()) }
    }

    fun getUserGender(): String {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_GENDER, "UNSPECIFIED") ?: "UNSPECIFIED"
    }

    fun setUserGender(gender: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_USER_GENDER, gender.uppercase().trim()) }
    }

    fun calculateIdealBodyWeightLbs(): Double? {
        val heightInches = getUserHeightInches()
        val gender = getUserGender()

        if (heightInches <= 0.0 || gender == "UNSPECIFIED") return null

        val baselineHeightInches = 60.0 // 5 feet
        val heightDifference = heightInches - baselineHeightInches

        val ibwKg = when (gender) {
            "MALE" -> 50.0 + (2.3 * heightDifference)
            "FEMALE" -> 45.5 + (2.3 * heightDifference)
            else -> return null
        }

        return ibwKg * 2.20462
    }

    fun calculateAdjustedBodyWeightLbs(): Double? {
        val ibwLbs = calculateIdealBodyWeightLbs() ?: return null
        val actualWeightLbs = getUserWeight()

        if (actualWeightLbs <= 0.0) return null

        // If actual weight is > 125% of Ideal Body Weight, calculate Adjusted Body Weight
        if (actualWeightLbs > (ibwLbs * 1.25)) {
            return ibwLbs + 0.4 * (actualWeightLbs - ibwLbs)
        }
        return actualWeightLbs
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