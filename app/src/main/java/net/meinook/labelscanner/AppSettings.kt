@file:Suppress("unused") // Suppresses all helper API unused alerts for clean build integrations
package net.meinook.labelscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import androidx.core.content.edit
import java.util.Locale
import android.util.Log

class AppSettings(private val context: Context) {

    companion object {
        private const val TAG = "AppSettings"

        const val KEY_CUSTOM_BLACKBOARD = "custom_blacklist_ingredients"
        const val KEY_USER_WEIGHT = "user_target_weight_lbs"
        const val KEY_USER_HEIGHT_INCHES = "user_height_inches"
        const val KEY_USER_GENDER = "user_gender"

        const val KEY_SUBSCRIPTION_ACTIVE = "subscription_active"
        const val KEY_USER_REVOKED = "is_revoked"
        const val KEY_REVOCATION_REASON = "revocation_reason"

        // Safety switch to allow testing the paywall on a debug build
        const val KEY_DEBUG_OVERRIDE_DISABLED = "debug_override_disabled"

        private val profileExclusivityMap = mutableMapOf<String, String>()
        private val profileConflictsMap = mutableMapOf<String, MutableSet<String>>()

        fun indexExclusivityGroups(context: Context) {
            profileExclusivityMap.clear()
            profileConflictsMap.clear()
            try {
                val mergedFileList = getMergedFileList(context)
                for (fileName in mergedFileList) {
                    openProfileStream(context, fileName).use { inputStream ->
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
                Log.e(TAG, "Error indexing exclusivity groups", e)
            }
        }

        // Merges assets and filesDir dynamically
        fun getMergedFileList(context: Context): List<String> {
            val assetFiles = context.assets.list("profiles") ?: emptyArray()
            val localDir = File(context.filesDir, "profiles")
            val localFiles = if (localDir.exists()) localDir.list() ?: emptyArray() else emptyArray()
            return (assetFiles + localFiles).distinct()
        }

        fun openProfileStream(context: Context, fileName: String): InputStream {
            val file = File(File(context.filesDir, "profiles"), fileName)
            return if (file.exists()) {
                FileInputStream(file)
            } else {
                context.assets.open("profiles/$fileName")
            }
        }
    }

    // MULTI-PROFILE UTILITIES
    fun getProfilesList(): List<String> {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val profilesSet = prefs.getStringSet("profiles_list", null)
        return if (profilesSet.isNullOrEmpty()) {
            listOf("Me")
        } else {
            profilesSet.toList().sorted()
        }
    }

    fun saveProfilesList(profiles: List<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putStringSet("profiles_list", profiles.toSet())
        }
    }

    fun createProfile(profileName: String) {
        val nameClean = profileName.trim()
        if (nameClean.isEmpty()) return
        val currentList = getProfilesList().toMutableList()
        if (!currentList.contains(nameClean)) {
            currentList.add(nameClean)
            saveProfilesList(currentList)
        }
    }

    fun deleteProfile(profileName: String) {
        val nameClean = profileName.trim()
        if (nameClean.isEmpty() || nameClean == "Me") return
        val currentList = getProfilesList().toMutableList()
        if (currentList.remove(nameClean)) {
            saveProfilesList(currentList)
            if (getActiveProfile() == nameClean) {
                setActiveProfile("Me")
            }
        }
    }

    fun getActiveProfile(): String {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getString("active_profile_id", "Me") ?: "Me"
    }

    fun setActiveProfile(profileName: String) {
        val nameClean = profileName.trim()
        if (nameClean.isEmpty()) return
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("active_profile_id", nameClean) }
    }

    fun isSubscriptionActive(): Boolean {
        // Automatically unlocks if the build config flag is compiled as true
        if (BuildConfig.BYPASS_PAYWALL) {
            return true
        }
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SUBSCRIPTION_ACTIVE, false)
    }

    fun setSubscriptionActive(active: Boolean) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_SUBSCRIPTION_ACTIVE, active) }
    }

    fun isUserRevoked(): Boolean {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USER_REVOKED, false)
    }

    fun setUserRevoked(revoked: Boolean, reason: String = "") {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_USER_REVOKED, revoked)
            putString(KEY_REVOCATION_REASON, reason)
        }
    }

    fun getRevocationReason(): String {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_REVOCATION_REASON, "Access revoked.") ?: "Access revoked."
    }

    fun isDebugOverrideDisabled(): Boolean {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEBUG_OVERRIDE_DISABLED, false)
    }

    fun setDebugOverrideDisabled(disabled: Boolean) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_DEBUG_OVERRIDE_DISABLED, disabled) }
    }

    fun isRightHanded(): Boolean {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_right_handed", true)
    }

    fun setRightHanded(right: Boolean) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("is_right_handed", right) }
    }

    fun saveCustomWatchlistItem(ingredient: String, tier: String) {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val baseKey = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"
        val key = "${getActiveProfile()}_$baseKey"

        val existingItems = sharedPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        existingItems.add(ingredient.trim().uppercase())

        sharedPrefs.edit { putStringSet(key, existingItems) }
    }

    fun isFeatureFlagActive(flagName: String): Boolean {
        val selectedIds = getSelectedConditions()
        if (selectedIds.isEmpty()) return false

        try {
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                    var eventType = parser.eventType
                    var targetProfileActive = false

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name
                        if (eventType == XmlPullParser.START_TAG) {
                            if (tagName == "diet_profile") {
                                val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                                if (selectedIds.contains(id)) {
                                    targetProfileActive = true
                                }
                            } else if (tagName == "flag" && targetProfileActive) {
                                val nameAttr = parser.getAttributeValue(null, "name")
                                if (nameAttr == flagName) {
                                    val valueText = parser.nextText()
                                    if (valueText.trim().equals("true", ignoreCase = true)) {
                                        return true
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking feature flag $flagName", e)
        }
        return false
    }

    fun getCustomWatchlist(tier: String): List<String> {
        val sharedPrefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val baseKey = if (tier.uppercase() == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"
        val key = "${getActiveProfile()}_$baseKey"

        val itemsSet = sharedPrefs.getStringSet(key, emptySet()) ?: emptySet()
        return itemsSet.map { it.uppercase() }.sorted()
    }

    fun saveSelectedConditions(conditions: Set<String>) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val key = "${getActiveProfile()}_tracked_medical_conditions"
        // If the user unchecks the last item, automatically fall back to healthy_baseline
        val finalConditions = if (conditions.isEmpty()) setOf("healthy_baseline") else conditions
        prefs.edit { putStringSet(key, finalConditions) }
    }

    fun getSelectedConditions(): Set<String> {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val key = "${getActiveProfile()}_tracked_medical_conditions"
        val saved = prefs.getStringSet(key, null)
        // If empty or null, guarantee healthy_baseline is returned
        return if (saved.isNullOrEmpty()) setOf("healthy_baseline") else saved
    }

    fun setPendingSaveFlag(hasSaved: Boolean) {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("pending_profile_save", hasSaved) }
    }

    fun addWatchlistIngredient(ingredient: String, tier: String) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        val cleanName = ingredient.trim().uppercase()
        if (cleanName.isEmpty()) return

        val tierUpper = tier.uppercase()
        val targetBaseKey = if (tierUpper == "RED") "custom_red_ingredients" else "custom_yellow_ingredients"
        val alternateBaseKey = if (tierUpper == "RED") "custom_yellow_ingredients" else "custom_red_ingredients"

        val targetKey = "${getActiveProfile()}_$targetBaseKey"
        val alternateKey = "${getActiveProfile()}_$alternateBaseKey"

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

        val redKey = "${getActiveProfile()}_custom_red_ingredients"
        val yellowKey = "${getActiveProfile()}_custom_yellow_ingredients"

        val redSet = (prefs.getStringSet(redKey, emptySet()) ?: emptySet()).toMutableSet()
        val yellowSet = (prefs.getStringSet(yellowKey, emptySet()) ?: emptySet()).toMutableSet()

        val removedFromRed = redSet.remove(cleanName)
        val removedFromYellow = yellowSet.remove(cleanName)

        if (removedFromRed || removedFromYellow) {
            prefs.edit {
                putStringSet(redKey, redSet)
                putStringSet(yellowKey, yellowSet)
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
                    selectedIds.removeAll(conflictingIds.toSet())
                }
            } else {
                val baselineConflictingIds = selectedIds.filter { profileId: String ->
                    profileExclusivityMap[profileId] == "all"
                }
                selectedIds.removeAll(baselineConflictingIds.toSet())
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
        val activeSuffix = getActiveProfile().lowercase(Locale.ROOT).replace(" ", "_")

        val mergedFileList = getMergedFileList(context)
        for (fileName in mergedFileList) {
            if (fileName.endsWith(".xml")) {
                if (!includeAllergens && fileName.startsWith("allergen_")) continue

                // Only load the custom profile belonging specifically to the active profile configuration
                if (fileName.startsWith("custom_") && !fileName.equals("custom_$activeSuffix.xml", ignoreCase = true)) {
                    continue
                }
                // Prevent any legacy un-suffixed custom profiles from showing up
                if (fileName.equals("custom.xml", ignoreCase = true)) {
                    continue
                }

                // Isolated try-catch prevents an empty file in filesDir from hiding your asset profiles
                try {
                    openProfileStream(context, fileName).use { inputStream ->
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
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unparseable profile file: $fileName", e)
                }
            }
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
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
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
            Log.e(TAG, "Error getting nutrient thresholds", e)
        }

        if (!profileMatched) return Triple(0, 999, false)
        return Triple(lowMax, modMax, isBlacklist)
    }

    // Dynamic scanner matches profile IDs and extracts thresholds independent of uppercase/lowercase filename casing
    fun getNutrientThresholdsForProfile(profileId: String, nutrientKey: String): Triple<Int, Int, Boolean> {
        var lowMax = 0
        var modMax = 0
        var isBlacklist = false
        var matched = false

        val mergedFileList = getMergedFileList(context)
        for (fileName in mergedFileList) {
            if (!fileName.endsWith(".xml")) continue

            try {
                var targetProfileActive = false
                openProfileStream(context, fileName).use { inputStream ->
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                    var eventType = parser.eventType

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name
                        if (eventType == XmlPullParser.START_TAG) {
                            if (tagName == "diet_profile") {
                                val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                                if (id == profileId) {
                                    targetProfileActive = true
                                }
                            } else if (tagName == "nutrient" && targetProfileActive) {
                                val currentName = parser.getAttributeValue(null, "name")
                                if (currentName?.lowercase() == nutrientKey.lowercase()) {
                                    val fileLow = parser.getAttributeValue(null, "low_max")?.toIntOrNull() ?: 0
                                    val fileMod = parser.getAttributeValue(null, "moderate_max")?.toIntOrNull() ?: 0
                                    val fileBlacklist = parser.getAttributeValue(null, "is_blacklist")?.toBoolean() ?: false

                                    lowMax = fileLow
                                    modMax = fileMod
                                    isBlacklist = fileBlacklist
                                    matched = true
                                }
                            } else if (tagName == "protein_rules" && targetProfileActive && nutrientKey.lowercase() == "protein") {
                                val fileLow = parser.getAttributeValue(null, "snack_max")?.toIntOrNull() ?: 0
                                val fileMod = parser.getAttributeValue(null, "meal_max")?.toIntOrNull() ?: 0

                                lowMax = fileLow
                                modMax = fileMod
                                isBlacklist = false
                                matched = true
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unparseable or empty profile file: $fileName", e)
            }
            if (matched) {
                break
            }
        }

        if (!matched) return Triple(0, 0, false)
        return Triple(lowMax, modMax, isBlacklist)
    }

    // Scans local Custom XML files and returns their saved base_profile_id attribute
    fun getBaseProfileIdForProfile(profileId: String): String {
        val mergedFileList = getMergedFileList(context)
        for (fileName in mergedFileList) {
            if (!fileName.endsWith(".xml")) continue

            try {
                openProfileStream(context, fileName).use { inputStream ->
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == "diet_profile") {
                            val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                            if (id == profileId) {
                                return parser.getAttributeValue(null, "base_profile_id") ?: ""
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading base_profile_id for profile $profileId", e)
            }
        }
        return ""
    }

    fun isFeatureFlagActiveForProfile(profileId: String, flagName: String): Boolean {
        try {
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
                    val parser = Xml.newPullParser().apply { setInput(inputStream, null) }
                    var eventType = parser.eventType
                    var targetProfileActive = false

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name
                        if (eventType == XmlPullParser.START_TAG) {
                            if (tagName == "diet_profile") {
                                val id = parser.getAttributeValue(null, "id") ?: fileName.removeSuffix(".xml")
                                if (id == profileId) {
                                    targetProfileActive = true
                                }
                            } else if (tagName == "flag" && targetProfileActive) {
                                val nameAttr = parser.getAttributeValue(null, "name")
                                if (nameAttr == flagName) {
                                    val valueText = parser.nextText()
                                    return valueText.trim().equals("true", ignoreCase = true)
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking flag $flagName for profile $profileId", e)
        }
        return false
    }

    fun getIngredientsFromAssetFile(fileName: String): List<String> {
        val triggerList = mutableListOf<String>()
        var openedStream: InputStream? = null

        val possibleNames = listOf(
            "profiles/$fileName.xml",
            "profiles/allergen_$fileName.xml",
            "profiles/${fileName.removePrefix("allergen_")}.xml"
        )
        for (name in possibleNames) {
            try {
                val localFile = File(File(context.filesDir, "profiles"), name.substringAfter("profiles/"))
                (if (localFile.exists()) FileInputStream(localFile) else context.assets.open(name)).also { openedStream = it }
                break
            } catch (e: Exception) {
                // Seek alternative file pattern
            }
        }

        if (openedStream == null) {
            try {
                val files = getMergedFileList(context)
                for (file in files) {
                    if (file.endsWith(".xml")) {
                        val matchedStream = openProfileStream(context, file).use { tempStream ->
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
                            openedStream = openProfileStream(context, file)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dynamic asset file lookup failed", e)
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
            Log.e(TAG, "Error parsing ingredients stream", e)
        }
        return triggerList
    }

    fun loadTriggersFromAssets(categoryTarget: String): List<String> {
        val triggerList = mutableListOf<String>()
        val activeProfileIds = getSelectedConditions()
        if (activeProfileIds.isEmpty()) return triggerList

        try {
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
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
            Log.e(TAG, "Error loading triggers from assets", e)
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
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
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
            Log.e(TAG, "Error getting active protein rules", e)
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
            val mergedFileList = getMergedFileList(context)
            for (fileName in mergedFileList) {
                if (!fileName.endsWith(".xml")) continue

                openProfileStream(context, fileName).use { inputStream ->
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
            Log.e(TAG, "Error getting active nutrient rules", e)
        }
        return accumulatedRules
    }

    fun getUserWeight(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        var weight = 0.0f

        try {
            weight = prefs.getFloat(KEY_USER_WEIGHT, 0.0f)
        } catch (e: Exception) {
            try {
                val str = prefs.getString(KEY_USER_WEIGHT, "0")
                weight = str?.toFloatOrNull() ?: 0.0f
            } catch (inner: Exception) {}
        }

        if (weight == 0.0f) {
            val defaultPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            try {
                weight = defaultPrefs.getFloat(KEY_USER_WEIGHT, 0.0f)
            } catch (e: Exception) {
                try {
                    val str = defaultPrefs.getString(KEY_USER_WEIGHT, "0")
                    weight = str?.toFloatOrNull() ?: 0.0f
                } catch (inner: Exception) {}
            }
        }
        return weight.toDouble()
    }

    fun setUserWeight(weight: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putFloat(KEY_USER_WEIGHT, weight.toFloat()) }
    }

    fun getUserHeightInches(): Double {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        var height = 0.0f

        try {
            height = prefs.getFloat(KEY_USER_HEIGHT_INCHES, 0.0f)
        } catch (e: Exception) {
            try {
                val str = prefs.getString(KEY_USER_HEIGHT_INCHES, "0")
                height = str?.toFloatOrNull() ?: 0.0f
            } catch (inner: Exception) {}
        }

        if (height == 0.0f) {
            val defaultPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            try {
                height = defaultPrefs.getFloat(KEY_USER_HEIGHT_INCHES, 0.0f)
            } catch (e: Exception) {
                try {
                    val str = defaultPrefs.getString(KEY_USER_HEIGHT_INCHES, "0")
                    height = str?.toFloatOrNull() ?: 0.0f
                } catch (inner: Exception) {}
            }
        }
        return height.toDouble()
    }

    fun setUserHeightInches(height: Double) {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        prefs.edit { putFloat(KEY_USER_HEIGHT_INCHES, height.toFloat()) }
    }

    fun getUserGender(): String {
        val prefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
        var gender = prefs.getString(KEY_USER_GENDER, "UNSPECIFIED") ?: "UNSPECIFIED"

        // Fallback: If not found in custom prefs, check default package preferences
        if (gender == "UNSPECIFIED") {
            val defaultPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            gender = defaultPrefs.getString(KEY_USER_GENDER, "UNSPECIFIED") ?: "UNSPECIFIED"
        }
        return gender.uppercase().trim()
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