package net.meinook.labelscanner

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

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
        isRecipe: Boolean = false
    ): EvaluationResult {

        val redViolations = mutableListOf<String>()
        val yellowViolations = mutableListOf<String>()
        val context = userSettings.context

        // GUARDRAIL: Check if the AI backend flagged the image as an invalid food item or label
        val isValidFood = jsonResult.optBoolean("is_food",
            jsonResult.optBoolean("is_valid_food",
                jsonResult.optBoolean("valid_food",
                    jsonResult.optBoolean("is_valid_label",
                        jsonResult.optBoolean("valid_label", true)
                    )
                )
            )
        )
        val backendError = jsonResult.optString("error", jsonResult.optString("message", ""))

        if (!isValidFood || (backendError.isNotBlank() && !backendError.equals("null", ignoreCase = true))) {
            val errorMessage = if (backendError.isNotBlank() && !backendError.equals("null", ignoreCase = true)) {
                backendError
            } else {
                context.getString(R.string.invalid_food_item_message)
            }
            return EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.gradeAvoidBackground),
                textColor = ContextCompat.getColor(context, R.color.gradeAvoid),
                subtextColor = ContextCompat.getColor(context, R.color.gradeAvoidSubtext),
                gradeTitle = context.getString(R.string.grade_invalid_scan),
                redViolations = listOf(errorMessage),
                yellowViolations = emptyList()
            )
        }

        val cleanIngredients = detectedIngredients.map { ingredient ->
            ingredient.replace("\u00A0", " ")
                .replace("\\s+".toRegex(), " ")
                .uppercase(Locale.US)
                .trim()
        }.filter { it.isNotEmpty() }

        val cleanRedTriggers = xmlRedTriggers.map { trigger ->
            trigger.replace("\u00A0", " ")
                .replace("\\s+".toRegex(), " ")
                .uppercase(Locale.US)
                .trim()
        }.filter { it.isNotEmpty() }

        val cleanCustomReds = customRedWatchlist.map { item ->
            item.replace("\u00A0", " ")
                .replace("\\s+".toRegex(), " ")
                .uppercase(Locale.US)
                .trim()
        }.filter { it.isNotEmpty() }

        val cleanCustomYellows = customYellowWatchlist.map { item ->
            item.replace("\u00A0", " ")
                .replace("\\s+".toRegex(), " ")
                .uppercase(Locale.US)
                .trim()
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
            redViolations.add(context.getString(R.string.violation_blacklisted_profile, matchText))
        }

        // 2. CUSTOM WATCHLISTS EVALUATION (Precise Word Boundaries)
        val matchedCustomReds = cleanCustomReds.filter { upperItem ->
            cleanIngredients.any { isIngredientMatch(it, upperItem) }
        }
        if (matchedCustomReds.isNotEmpty()) {
            redViolations.add(context.getString(R.string.violation_watchlist_avoid, matchedCustomReds.distinct().joinToString(", ")))
        }

        val matchedCustomYellows = cleanCustomYellows.filter { upperItem ->
            cleanIngredients.any { isIngredientMatch(it, upperItem) }
        }
        if (matchedCustomYellows.isNotEmpty()) {
            yellowViolations.add(context.getString(R.string.violation_watchlist_caution, matchedCustomYellows.distinct().joinToString(", ")))
        }

        // 3. ROUTING & STATE CONTROLLER
        val nutritionFactsFound = jsonResult.optBoolean("nutrition_facts_found", true)

        val anyNutrientFound = nutritionFactsFound && nutrientRegistry.any { map ->
            jsonResult.has(map.jsonKey) && !jsonResult.isNull(map.jsonKey)
        }
        val hasIngredients = cleanIngredients.isNotEmpty()

        // If no nutrients exist at all, handle pure ingredients or empty scan
        if (!isProduce && !anyNutrientFound) {
            return if (hasIngredients) {
                finalizeResult(redViolations, yellowViolations, isIngredientsOnly = true, context = context)
            } else {
                EvaluationResult(
                    bgColor = ContextCompat.getColor(context, R.color.cardSurface),
                    textColor = ContextCompat.getColor(context, R.color.textMuted),
                    subtextColor = ContextCompat.getColor(context, R.color.textSecondary),
                    gradeTitle = context.getString(R.string.grade_incomplete_scan),
                    redViolations = emptyList(),
                    yellowViolations = listOf(context.getString(R.string.no_nutrition_or_ingredients)) + yellowViolations
                )
            }
        }

        if (redViolations.isNotEmpty()) {
            return finalizeResult(redViolations, yellowViolations, isIngredientsOnly = false, context = context)
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

            // Check A: Absolute single-sitting overload (Exceeds full meal ceiling -> RED)
            if (enforceCeiling && proteinVal > proteinRules.mealMax) {
                redViolations.add(context.getString(R.string.protein_exceeds_meal_ceiling, proteinVal.toInt(), proteinRules.mealMax))
            }
            // Check B: Full meal with inadequate protein for dialysis requirements
            else if (isMeal && enforceFloor && proteinVal < proteinRules.mealMin) {
                yellowViolations.add(context.getString(R.string.protein_below_meal_target, proteinVal.toInt(), proteinRules.mealMin))
            }
            // Check C: Low-calorie but high protein density (e.g. Sausage, Chicken, Tuna)
            else if (!isMeal && enforceCeiling && proteinVal > proteinRules.snackMax) {
                yellowViolations.add(context.getString(R.string.protein_meal_component_exceeds_snack, proteinVal.toInt(), proteinRules.mealMax, proteinRules.snackMax))
            }
        }

        // 5. MAIN NUTRIENT EVALUATION LOOP
        val totalCarbs = getVal("carbs")
        val fiber = getVal("fiber")
        val netCarbs = (totalCarbs - fiber).coerceAtLeast(0.0f)

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

            if (!isRecipe && map.isScaleRequired && value in 0.01f..5.0f) {
                value *= 1000
            }

            val actualValFormatted = when (map.xmlId) {
                "calories" -> "${value.toInt()} kcal"
                "sodium", "potassium" -> "${value.toInt()} mg"
                else -> String.format(Locale.US, "%.1f g", value.toDouble())
            }
            val modFormatted = when (map.xmlId) {
                "calories" -> "$mod kcal"
                "sodium", "potassium" -> "$mod mg"
                else -> String.format(Locale.US, "%.1f g", mod.toDouble())
            }
            val lowFormatted = when (map.xmlId) {
                "calories" -> "$low kcal"
                "sodium", "potassium" -> "$low mg"
                else -> String.format(Locale.US, "%.1f g", low.toDouble())
            }

            if (value > mod) {
                val label = if (map.useNetCarbs && useNetCarbsActive) "Net Carbs" else map.displayName
                val message = context.getString(R.string.nutrient_exceeds_limit, label, actualValFormatted, modFormatted)

                // Cumulative dietary metrics (fats, calories) NEVER trigger a Hard RED
                val isCumulativeDietaryMetric = (map.xmlId == "saturated_fat" || map.xmlId == "total_fat" || map.xmlId == "calories")
                if (blacklist && !isCumulativeDietaryMetric) {
                    redViolations.add(message)
                } else {
                    yellowViolations.add(message)
                }
            } else if (value > low) {
                val label = if (map.useNetCarbs && useNetCarbsActive) "Net Carbs" else map.displayName
                val message = context.getString(R.string.nutrient_is_high, label, actualValFormatted, lowFormatted)
                yellowViolations.add(message)
            }
            processed.add(map.xmlId)
        }

        // ULTIMATE CATCH-ALL: If all core macros are 0.0 and no violations found,
        // never allow an unrecognized or unidentifiable object to be graded Green - Safe.
        val hasZeroNutritionalData = (getVal("calories") == 0.0f &&
                getVal("protein") == 0.0f &&
                getVal("sodium") == 0.0f &&
                getVal("potassium") == 0.0f &&
                getVal("carbs") == 0.0f)

        if (redViolations.isEmpty() && yellowViolations.isEmpty() && hasZeroNutritionalData) {
            return EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.cardSurface),
                textColor = ContextCompat.getColor(context, R.color.gradeCaution),
                subtextColor = ContextCompat.getColor(context, R.color.textSecondary),
                gradeTitle = "Unrecognized Item (Unverified)",
                redViolations = emptyList(),
                yellowViolations = listOf("Caution: No valid food properties or nutritional data could be verified. Safety unknown.")
            )
        }

        return finalizeResult(redViolations, yellowViolations, isIngredientsOnly = false, context = context)
    }

    private fun finalizeResult(
        reds: List<String>,
        yellows: List<String>,
        isIngredientsOnly: Boolean = false,
        context: Context
    ): EvaluationResult {
        return when {
            reds.isNotEmpty() -> EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.gradeAvoidBackground),
                textColor = ContextCompat.getColor(context, R.color.gradeAvoid),
                subtextColor = ContextCompat.getColor(context, R.color.gradeAvoidSubtext),
                gradeTitle = if (isIngredientsOnly) context.getString(R.string.grade_red_avoid_watchlist) else context.getString(R.string.grade_red_avoid),
                redViolations = reds.distinct(),
                yellowViolations = yellows.distinct()
            )
            yellows.isNotEmpty() -> EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.gradeCautionBackground),
                textColor = ContextCompat.getColor(context, R.color.gradeCaution),
                subtextColor = ContextCompat.getColor(context, R.color.gradeCautionSubtext),
                gradeTitle = if (isIngredientsOnly) context.getString(R.string.grade_yellow_caution_watchlist) else context.getString(R.string.grade_yellow_caution),
                redViolations = emptyList(),
                yellowViolations = yellows.distinct()
            )
            isIngredientsOnly -> EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.gradeUnrated),
                textColor = ContextCompat.getColor(context, R.color.gradeUnratedText),
                subtextColor = ContextCompat.getColor(context, R.color.gradeUnratedSubtext),
                gradeTitle = context.getString(R.string.grade_watchlist_clear),
                redViolations = emptyList(),
                yellowViolations = listOf(context.getString(R.string.watchlist_clear_message))
            )
            else -> EvaluationResult(
                bgColor = ContextCompat.getColor(context, R.color.gradeSafeBackground),
                textColor = ContextCompat.getColor(context, R.color.gradeSafe),
                subtextColor = ContextCompat.getColor(context, R.color.gradeSafeSubtext),
                gradeTitle = context.getString(R.string.grade_green_safe),
                redViolations = emptyList(),
                yellowViolations = emptyList()
            )
        }
    }
}