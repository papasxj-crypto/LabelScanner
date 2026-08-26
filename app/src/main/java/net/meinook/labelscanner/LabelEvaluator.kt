package net.meinook.labelscanner

import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.util.Log
import org.json.JSONObject
import java.util.Locale

data class EvaluationResult(
    val bgColor: Int,
    val textColor: Int,
    val subtextColor: Int,
    val gradeTitle: String,
    val redViolations: List<String>,
    val yellowViolations: List<String>
) : java.io.Serializable

data class ProductAlternative(
    val name: String,
    val brand: String,
    val code: String,
    val gradeTitle: String
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

        NutrientMap("carbs", "carbs", "Carbs", useNetCarbs = true),
        NutrientMap("carbs", "total_carbohydrates_g", "Carbs", useNetCarbs = true),
        NutrientMap("carbs", "carbohydrates", "Carbs", useNetCarbs = true),
        NutrientMap("carbs", "carbohydrates_serving", "Carbs", useNetCarbs = true),

        NutrientMap("fiber", "fiber", "Fiber"),
        NutrientMap("fiber", "fiber_g", "Fiber"),
        NutrientMap("fiber", "fiber_serving", "Fiber"),

        NutrientMap("total_sugar", "total_sugar_g", "Total Sugar"),
        NutrientMap("total_sugar", "sugar_g", "Total Sugar"),
        NutrientMap("total_sugar", "sugar", "Total Sugar"),
        NutrientMap("total_sugar", "sugars_serving", "Total Sugar"),

        NutrientMap("added_sugar", "added_sugar", "Added Sugar"),
        NutrientMap("added_sugar", "added_sugar_g", "Added Sugar"),

        NutrientMap("saturated_fat", "sat_fat", "Saturated Fat"),
        NutrientMap("saturated_fat", "saturated_fat_g", "Saturated Fat"),

        NutrientMap("total_fat", "total_fat", "Total Fat"),
        NutrientMap("total_fat", "total_fat_g", "Total Fat")
    )

    private fun getSafeFloat(jsonResult: JSONObject, key: String): Float {
        if (!jsonResult.has(key) || jsonResult.isNull(key)) return 0.0f
        val rawValue = jsonResult.opt(key) ?: return 0.0f
        if (rawValue is Number) return rawValue.toFloat()

        val stringValue = rawValue.toString().trim()
        val numericPart = stringValue.takeWhile { it.isDigit() || it == '.' || it == '-' }
        return numericPart.toFloatOrNull() ?: 0.0f
    }

    private fun isIngredientMatch(ingredient: String, trigger: String): Boolean {
        if (trigger == "PHOS") {
            return ingredient.contains("PHOS")
        }
        val escapedTrigger = Regex.escape(trigger)
        val boundaryRegex = "\\b$escapedTrigger\\b".toRegex()
        return ingredient.contains(boundaryRegex) || ingredient == trigger
    }

    fun evaluateScanData(
        jsonResult: JSONObject,
        detectedIngredients: List<String>,
        savedProfileIds: Set<String>,
        userSettings: AppSettings,
        xmlRedTriggers: List<String>,
        customRedWatchlist: List<String>,
        customYellowWatchlist: List<String>,
        isProduce: Boolean = false,
        isRecipe: Boolean = false // Upgraded safety parameter prevents metric scale corruption
    ): EvaluationResult {

        val redViolations = mutableListOf<String>()
        val yellowViolations = mutableListOf<String>()

        val cleanIngredients = detectedIngredients.map { ingredient ->
            ingredient?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase(Locale.US)
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanRedTriggers = xmlRedTriggers.map { trigger ->
            trigger?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase(Locale.US)
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanCustomReds = customRedWatchlist.map { item ->
            item?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase(Locale.US)
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        val cleanCustomYellows = customYellowWatchlist.map { item ->
            item?.replace("\u00A0", " ")
                ?.replace("\\s+".toRegex(), " ")
                ?.uppercase(Locale.US)
                ?.trim() ?: ""
        }.filter { it.isNotEmpty() }

        Log.d("LabelEvaluator", "evaluateScanData: Cleaned Ingredients count = ${cleanIngredients.size}")

        // 1. PROFILE TRIGGERS CHECK (Precise Word Boundaries)
        val matchedReds = cleanRedTriggers.filter { t ->
            cleanIngredients.any { isIngredientMatch(it, t) }
        }
        if (matchedReds.isNotEmpty()) {
            val matchedItems = cleanIngredients.filter { ingredient ->
                cleanRedTriggers.any { t -> isIngredientMatch(ingredient, t) }
            }.distinct()

            val matchText = if (matchedItems.isNotEmpty()) matchedItems.joinToString(", ") else matchedReds.joinToString(", ")
            redViolations.add("Blacklisted profile ingredient matched: $matchText")
        }

        // 2. CUSTOM WATCHLISTS EVALUATION (Precise Word Boundaries)
        val matchedCustomReds = cleanCustomReds.filter { upperItem ->
            cleanIngredients.any { isIngredientMatch(it, upperItem) }
        }
        if (matchedCustomReds.isNotEmpty()) {
            redViolations.add("Your Watchlist (Avoid): matched ${matchedCustomReds.distinct().joinToString(", ")}")
        }

        val matchedCustomYellows = cleanCustomYellows.filter { upperItem ->
            cleanIngredients.any { isIngredientMatch(it, upperItem) }
        }
        if (matchedCustomYellows.isNotEmpty()) {
            yellowViolations.add("Your Watchlist (Caution): matched ${matchedCustomYellows.distinct().joinToString(", ")}")
        }

        // 3. ROUTING & STATE CONTROLLER
        val nutritionFactsFound = jsonResult.optBoolean("nutrition_facts_found", true)

        fun hasNutrient(xmlId: String): Boolean {
            if (!nutritionFactsFound) return false
            val validKeys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            return validKeys.any { jsonResult.has(it) && !jsonResult.isNull(it) }
        }

        val hasCalories = hasNutrient("calories")
        val hasSodium = hasNutrient("sodium")
        val hasIngredients = cleanIngredients.isNotEmpty()

        val isIngredientsOnlyMode = !isProduce && (!hasCalories || !hasSodium) && hasIngredients

        if (isIngredientsOnlyMode) {
            return finalizeResult(redViolations, yellowViolations, isIngredientsOnly = true)
        }

        if (!isProduce && (!hasCalories || !hasSodium) && !hasIngredients) {
            return EvaluationResult(
                bgColor = "#1E2027".toColorInt(),
                textColor = "#90A4AE".toColorInt(),
                subtextColor = "#CFD8DC".toColorInt(),
                gradeTitle = "Incomplete Scan",
                redViolations = emptyList(),
                yellowViolations = listOf("Missing core nutrition data (Calories/Sodium) and ingredients list.") + yellowViolations
            )
        }

        if (redViolations.isNotEmpty()) {
            return finalizeResult(redViolations, yellowViolations)
        }

        fun getVal(xmlId: String): Float {
            val keys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            for (k in keys) {
                if (jsonResult.has(k)) {
                    return getSafeFloat(jsonResult, k)
                }
            }
            return 0.0f
        }

        // 4. CLINICAL DYNAMIC PROTEIN RULES ENGINE
        val proteinRules = userSettings.getActiveProteinRules()
        val enforceFloor = userSettings.isFeatureFlagActive("enforce_protein_floor")
        val enforceCeiling = userSettings.isFeatureFlagActive("enforce_protein_ceiling")

        if (enforceFloor || enforceCeiling) {
            val proteinVal = getVal("protein")
            val caloriesVal = getVal("calories")
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

        // 5. MAIN NUTRIENT EVALUATION LOOP
        val totalCarbs = getVal("carbs")
        val fiber = getVal("fiber")
        val netCarbs = (totalCarbs - fiber).coerceAtLeast(0.0f)

        // Dynamic flag evaluation maps cleanly from condition files
        val useNetCarbsActive = savedProfileIds.contains("keto") || userSettings.isFeatureFlagActive("use_net_carbs")

        val processed = mutableSetOf<String>()
        for (map in nutrientRegistry) {
            if (processed.contains(map.xmlId)) continue

            if (map.xmlId == "protein" && (enforceFloor || enforceCeiling)) {
                processed.add(map.xmlId)
                continue
            }

            val (low, mod, blacklist) = userSettings.getNutrientThresholds(map.xmlId)
            if (mod >= 999.0) continue

            var value = if (map.useNetCarbs && useNetCarbsActive) netCarbs else getVal(map.xmlId)

            // Bypass scale adjustments completely for calculated recipe models
            if (!isRecipe && map.isScaleRequired && value in 0.01f..5.0f) {
                value *= 1000
            }

            val actualValFormatted = when (map.xmlId) {
                "calories" -> "${value.toInt()} kcal"
                "sodium", "potassium" -> "${value.toInt()} mg"
                else -> String.format(Locale.US, "%.1f g", value.toDouble())
            }
            val modFormatted = when (map.xmlId) {
                "calories" -> "${mod.toInt()} kcal"
                "sodium", "potassium" -> "${mod.toInt()} mg"
                else -> String.format(Locale.US, "%.1f g", mod.toDouble())
            }
            val lowFormatted = when (map.xmlId) {
                "calories" -> "${low.toInt()} kcal"
                "sodium", "potassium" -> "${low.toInt()} mg"
                else -> String.format(Locale.US, "%.1f g", low.toDouble())
            }

            if (value > mod) {
                val label = if (map.useNetCarbs && useNetCarbsActive) "Net Carbs" else map.displayName
                val message = "$label exceeds target limit: found $actualValFormatted (limit: $modFormatted)"
                if (blacklist) redViolations.add(message) else yellowViolations.add(message)
            } else if (value > low) {
                val label = if (map.useNetCarbs && useNetCarbsActive) "Net Carbs" else map.displayName
                val message = "$label is high: found $actualValFormatted (caution limit: $lowFormatted)"
                yellowViolations.add(message)
            }
            processed.add(map.xmlId)
        }

        return finalizeResult(redViolations, yellowViolations)
    }

    private fun finalizeResult(
        reds: List<String>,
        yellows: List<String>,
        isIngredientsOnly: Boolean = false
    ): EvaluationResult {
        return when {
            reds.isNotEmpty() -> EvaluationResult(
                bgColor = "#321414".toColorInt(),
                textColor = "#E57373".toColorInt(),
                subtextColor = "#FFCDD2".toColorInt(),
                gradeTitle = if (isIngredientsOnly) "Red - Avoid (Watchlist)" else "Red - Avoid",
                redViolations = reds.distinct(),
                yellowViolations = yellows.distinct()
            )
            yellows.isNotEmpty() -> EvaluationResult(
                bgColor = "#332500".toColorInt(),
                textColor = "#FFD54F".toColorInt(),
                subtextColor = "#FFF9C4".toColorInt(),
                gradeTitle = if (isIngredientsOnly) "Yellow - Caution (Watchlist)" else "Yellow - Caution",
                redViolations = emptyList(),
                yellowViolations = yellows.distinct()
            )
            isIngredientsOnly -> EvaluationResult(
                bgColor = "#222A35".toColorInt(),
                textColor = "#90A4AE".toColorInt(),
                subtextColor = "#CFD8DC".toColorInt(),
                gradeTitle = "Watchlist Clear (Unrated)",
                redViolations = emptyList(),
                yellowViolations = listOf("No blacklisted ingredients found. Nutrition facts not scanned.")
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