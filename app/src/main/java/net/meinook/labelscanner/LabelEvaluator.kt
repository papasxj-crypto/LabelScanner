package net.meinook.labelscanner

import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.util.Log
import org.json.JSONObject

data class EvaluationResult(
    val bgColor: Int,
    val textColor: Int,
    val subtextColor: Int,
    val gradeTitle: String,
    val redViolations: List<String>,
    val yellowViolations: List<String>
) : java.io.Serializable

object LabelEvaluator {

    private data class NutrientMap(
        val xmlId: String,
        val jsonKey: String,
        val displayName: String,
        val isScaleRequired: Boolean = false,
        val useNetCarbs: Boolean = false
    )

    private val nutrientRegistry = listOf(
        NutrientMap("calories", "calories", "Calories"),
        NutrientMap("calories", "energy", "Calories"),
        NutrientMap("calories", "energy-kcal_serving", "Calories"),
        NutrientMap("sodium", "sodium_mg", "Sodium"),
        NutrientMap("sodium", "sodium", "Sodium"),
        NutrientMap("sodium", "sodium_serving", "Sodium"),
        NutrientMap("protein", "protein_g", "Protein"),
        NutrientMap("protein", "protein", "Protein"),
        NutrientMap("protein", "proteins_serving", "Protein"),
        NutrientMap("potassium", "potassium_mg", "Potassium", isScaleRequired = true),
        NutrientMap("potassium", "potassium", "Potassium", isScaleRequired = true),
        NutrientMap("potassium", "potassium_serving", "Potassium", isScaleRequired = true),
        NutrientMap("carbs", "total_carbohydrates_g", "Carbs", useNetCarbs = true),
        NutrientMap("carbs", "carbohydrates", "Carbs", useNetCarbs = true),
        NutrientMap("carbs", "carbohydrates_serving", "Carbs", useNetCarbs = true),
        NutrientMap("fiber", "fiber_g", "Fiber"),
        NutrientMap("fiber", "fiber_serving", "Fiber"),
        NutrientMap("total_sugar", "total_sugar_g", "Total Sugar"),
        NutrientMap("total_sugar", "sugar_g", "Total Sugar"),
        NutrientMap("total_sugar", "sugar", "Total Sugar"),
        NutrientMap("total_sugar", "sugars_serving", "Total Sugar"),
        NutrientMap("added_sugar", "added_sugar_g", "Added Sugar"),
        NutrientMap("saturated_fat", "saturated_fat_g", "Saturated Fat"),
        NutrientMap("total_fat", "total_fat_g", "Total Fat")
    )

    fun evaluateScanData(
        jsonResult: JSONObject,
        detectedIngredients: List<String>,
        savedProfileIds: Set<String>,
        userSettings: AppSettings,
        xmlRedTriggers: List<String>,
        customRedWatchlist: List<String>,
        customYellowWatchlist: List<String>,
        isProduce: Boolean = false
    ): EvaluationResult {

        val redViolations = mutableListOf<String>()
        val yellowViolations = mutableListOf<String>()

        // 1. SANITIZE AND NORMALIZE STRINGS (Strip non-breaking spaces, collapse double spaces, handle nulls)
        val cleanIngredients = detectedIngredients.map { ingredient ->
            ingredient?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase()
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanRedTriggers = xmlRedTriggers.map { trigger ->
            trigger?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase()
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanCustomReds = customRedWatchlist.map { item ->
            item?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase()
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanCustomYellows = customYellowWatchlist.map { item ->
            item?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase()
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        Log.d("LabelEvaluator", "evaluateScanData: Cleaned Ingredients count = ${cleanIngredients.size}, values = $cleanIngredients")
        Log.d("LabelEvaluator", "evaluateScanData: Cleaned Profile Triggers count = ${cleanRedTriggers.size}, values = $cleanRedTriggers")

        // 2. PROFILE TRIGGERS CHECK (Robust Substring Matching)
        val matchedReds = cleanRedTriggers.filter { t ->
            if (t == "PHOS") {
                cleanIngredients.any { it.contains("PHOS") }
            } else {
                cleanIngredients.any { it.contains(t) }
            }
        }
        if (matchedReds.isNotEmpty()) {
            val matchedItems = cleanIngredients.filter { ingredient ->
                cleanRedTriggers.any { t ->
                    if (t == "PHOS") ingredient.contains("PHOS") else ingredient.contains(t)
                }
            }.distinct()

            val matchText = if (matchedItems.isNotEmpty()) matchedItems.joinToString(", ") else matchedReds.joinToString(", ")
            redViolations.add("Blacklisted profile ingredient matched: $matchText")
        }

        // 3. CUSTOM WATCHLISTS EVALUATION (Robust Substring Matching)
        val matchedCustomReds = cleanCustomReds.filter { upperItem ->
            cleanIngredients.any { it.contains(upperItem) }
        }
        if (matchedCustomReds.isNotEmpty()) {
            redViolations.add("Your Watchlist (Avoid): matched ${matchedCustomReds.distinct().joinToString(", ")}")
        }

        val matchedCustomYellows = cleanCustomYellows.filter { upperItem ->
            cleanIngredients.any { it.contains(upperItem) }
        }
        if (matchedCustomYellows.isNotEmpty()) {
            yellowViolations.add("Your Watchlist (Caution): matched ${matchedCustomYellows.distinct().joinToString(", ")}")
        }

        // 4. IMMEDIATE HAZARD ESCAPE: Sound the alarm immediately on blacklisted additives
        if (redViolations.isNotEmpty()) {
            return finalizeResult(redViolations, yellowViolations)
        }

        // 5. SMART ANCHOR NUTRITION CHECK
        fun hasNutrient(xmlId: String): Boolean {
            val validKeys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            return validKeys.any { jsonResult.has(it) && !jsonResult.isNull(it) }
        }

        val hasCalories = hasNutrient("calories")
        val hasSodium = hasNutrient("sodium")

        if (!isProduce && (!hasCalories || !hasSodium)) {
            return EvaluationResult(
                bgColor = "#1E2027".toColorInt(),
                textColor = "#90A4AE".toColorInt(),
                subtextColor = "#CFD8DC".toColorInt(),
                gradeTitle = "Incomplete Scan",
                redViolations = emptyList(),
                yellowViolations = listOf("Missing core nutrition data (Calories/Sodium).") + yellowViolations
            )
        }

        // Helper to extract values dynamically
        fun getVal(xmlId: String): Float {
            val keys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            for (k in keys) { if (jsonResult.has(k)) return jsonResult.optDouble(k, 0.0).toFloat() }
            return 0.0f
        }

        // 6. CLINICAL DYNAMIC PROTEIN RULES ENGINE
        val proteinRules = userSettings.getActiveProteinRules()
        val enforceFloor = userSettings.isFeatureFlagActive("enforce_protein_floor")
        val enforceCeiling = userSettings.isFeatureFlagActive("enforce_protein_ceiling")

        if (enforceFloor || enforceCeiling) {
            val proteinVal = getVal("protein")
            val caloriesVal = getVal("calories")

            // Classify snack vs. meal based on 250 calorie baseline
            val isMeal = caloriesVal > 250.0f

            if (isMeal) {
                if (enforceCeiling && proteinVal > proteinRules.mealMax) {
                    redViolations.add("Protein exceeds meal limit: found ${proteinVal.toInt()}g (limit: ${proteinRules.mealMax}g)")
                }
                if (enforceFloor && proteinVal < proteinRules.mealMin) {
                    yellowViolations.add("Protein is below meal requirement: found ${proteinVal.toInt()}g (needs: ${proteinRules.mealMin}g)")
                }
            } else {
                if (enforceCeiling && proteinVal > proteinRules.snackMax) {
                    redViolations.add("Protein exceeds snack limit: found ${proteinVal.toInt()}g (limit: ${proteinRules.snackMax}g)")
                }
                if (enforceFloor && proteinVal < proteinRules.snackMin) {
                    yellowViolations.add("Protein is below snack requirement: found ${proteinVal.toInt()}g (needs: ${proteinRules.snackMin}g)")
                }
            }
        }

        // 7. MAIN NUTRIENT EVALUATION LOOP (Static Fallbacks)
        val totalCarbs = getVal("carbs")
        val fiber = getVal("fiber")
        val netCarbs = (totalCarbs - fiber).coerceAtLeast(0.0f)

        val processed = mutableSetOf<String>()
        for (map in nutrientRegistry) {
            if (processed.contains(map.xmlId)) continue

            // Skip protein static loop if advanced XML protein rule flags are actively enforcing it
            if (map.xmlId == "protein" && (enforceFloor || enforceCeiling)) {
                processed.add(map.xmlId)
                continue
            }

            val (low, mod, blacklist) = userSettings.getNutrientThresholds(map.xmlId)
            if (mod >= 999.0) continue

            var value = if (map.useNetCarbs && savedProfileIds.contains("keto")) netCarbs else getVal(map.xmlId)
            if (map.isScaleRequired && value in 0.01f..5.0f) value *= 1000

            val actualValFormatted = when (map.xmlId) {
                "calories" -> "${value.toInt()} kcal"
                "sodium", "potassium" -> "${value.toInt()} mg"
                else -> String.format(java.util.Locale.US, "%.1f g", value.toDouble())
            }
            val modFormatted = when (map.xmlId) {
                "calories" -> "${mod.toInt()} kcal"
                "sodium", "potassium" -> "${mod.toInt()} mg"
                else -> String.format(java.util.Locale.US, "%.1f g", mod.toDouble())
            }
            val lowFormatted = when (map.xmlId) {
                "calories" -> "${low.toInt()} kcal"
                "sodium", "potassium" -> "${low.toInt()} mg"
                else -> String.format(java.util.Locale.US, "%.1f g", low.toDouble())
            }

            if (value > mod) {
                val label = if (map.useNetCarbs && savedProfileIds.contains("keto")) "Net Carbs" else map.displayName
                val message = "$label exceeds target limit: found $actualValFormatted (limit: $modFormatted)"
                if (blacklist) redViolations.add(message) else yellowViolations.add(message)
            } else if (value > low) {
                val label = if (map.useNetCarbs && savedProfileIds.contains("keto")) "Net Carbs" else map.displayName
                val message = "$label is high: found $actualValFormatted (caution limit: $lowFormatted)"
                yellowViolations.add(message)
            }
            processed.add(map.xmlId)
        }

        return finalizeResult(redViolations, yellowViolations)
    }

    private fun finalizeResult(reds: List<String>, yellows: List<String>): EvaluationResult {
        return when {
            reds.isNotEmpty() -> EvaluationResult(
                bgColor = "#321414".toColorInt(),
                textColor = "#E57373".toColorInt(),
                subtextColor = "#FFCDD2".toColorInt(),
                gradeTitle = "Red - Avoid",
                redViolations = reds.distinct(),
                yellowViolations = yellows.distinct()
            )
            yellows.isNotEmpty() -> EvaluationResult(
                bgColor = "#332500".toColorInt(),
                textColor = "#FFD54F".toColorInt(),
                subtextColor = "#FFF9C4".toColorInt(),
                gradeTitle = "Yellow - Caution",
                redViolations = emptyList(),
                yellowViolations = yellows.distinct()
            )
            else -> EvaluationResult(
                bgColor = "#14321A".toColorInt(),
                textColor = "#81C784".toColorInt(),
                subtextColor = "#C8E6C9".toColorInt(),
                gradeTitle = "Green - Safe",
                redViolations = emptyList(),
                yellowViolations = emptyList()
            )
        }
    }
}