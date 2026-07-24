package net.meinook.labelscanner

import android.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject

data class EvaluationResult(
    val bgColor: Int,
    val textColor: Int,
    val gradeTitle: String,
    val redViolations: List<String>,
    val yellowViolations: List<String>
)

object LabelEvaluator {

    fun evaluateScanData(
        jsonResult: JSONObject,
        detectedIngredients: List<String>,
        savedProfileIds: Set<String>,
        userSettings: AppSettings,
        xmlRedTriggers: List<String>,
        customRedWatchlist: List<String>,
        customYellowWatchlist: List<String>
    ): EvaluationResult {

        var bgColor = "#4CAF50".toColorInt() // Default Green
        var textColor = Color.WHITE
        var gradeTitle = ""

        val redViolations = mutableListOf<String>()
        val yellowViolations = mutableListOf<String>()

        // --- 1. EXTRACT RAW NUTRITION FIELDS ---
        val rawCalories = jsonResult.optInt("calories", 0)
        val sodiumMg = jsonResult.optInt("sodium_mg", 0)
        val proteinGrams = jsonResult.optDouble("protein_g", 0.0).toFloat()
        val totalSugarGrams = jsonResult.optDouble("total_sugar_g", 0.0).toFloat()
        val addedSugarGrams = jsonResult.optDouble("added_sugar_g", 0.0).toFloat()
        val totalFatGrams = jsonResult.optDouble("total_fat_g", 0.0).toFloat()
        val satFatGrams = jsonResult.optDouble("saturated_fat_g", 0.0).toFloat()
        val transFatGrams = jsonResult.optDouble("trans_fat_g", 0.0).toFloat()
        val carbsGrams = jsonResult.optDouble("total_carbohydrates_g", 0.0).toFloat()
        val potassiumRaw = jsonResult.optDouble("potassium_g", 0.0).toFloat()
        val potassiumMg = if (potassiumRaw in 0.01f..5.0f) (potassiumRaw * 1000).toInt().toFloat() else potassiumRaw
        val totalCarbs = jsonResult.optDouble("total_carbohydrates_g", 0.0).toFloat()
        val fiber = jsonResult.optDouble("fiber_g", 0.0).toFloat()
        val sugarAlcohols = jsonResult.optDouble("sugar_alcohols_g", 0.0).toFloat()
        val netCarbs = (totalCarbs - fiber - sugarAlcohols).coerceAtLeast(0.0f)

        // --- 2. THRESHOLDS EVALUATION ---
        val (sodiumLowMax, sodiumModMax, sodiumIsBlacklist) = userSettings.getNutrientThresholds("sodium")
        val (addedSugarLowMax, addedSugarModMax, addedSugarIsBlacklist) = userSettings.getNutrientThresholds("added_sugar")
        val (totalSugarLowMax, totalSugarModMax, totalSugarIsBlacklist) = userSettings.getNutrientThresholds("total_sugar")
        val (satFatLowMax, satFatModMax, satFatIsBlacklist) = userSettings.getNutrientThresholds("saturated_fat")
        val (_, totalFatModMax, totalFatIsBlacklist) = userSettings.getNutrientThresholds("total_fat")
        val (potassiumLowMax, potassiumModMax, potassiumIsBlacklist) = userSettings.getNutrientThresholds("potassium")
        val (carbsLowMax, carbsModMax, carbsIsBlacklist) = userSettings.getNutrientThresholds("carbs")

        // --- 0. ZERO DATA / SUSPICIOUS LABEL CHECK ---
        val macroCheckSum = rawCalories + sodiumMg + proteinGrams + carbsGrams + totalFatGrams
        if (macroCheckSum <= 0.0f && detectedIngredients.isEmpty()) {
            return EvaluationResult(
                bgColor = "#FF9800".toColorInt(), // Orange / Caution
                textColor = Color.WHITE,
                gradeTitle = "Warning: Incomplete / Zero Nutrition Data Detected.\nPlease scan the physical Nutrition Facts label on the package.",
                redViolations = emptyList(),
                yellowViolations = listOf("No valid nutritional data found.")
            )
        }

        if (sodiumMg > sodiumModMax) {
            if (sodiumIsBlacklist) redViolations.add("Sodium") else yellowViolations.add("Sodium")
        } else if (sodiumMg > sodiumLowMax) yellowViolations.add("Sodium")

        if (totalSugarGrams > totalSugarModMax) {
            if (totalSugarIsBlacklist) redViolations.add("Total Sugar") else yellowViolations.add("Total Sugar")
        } else if (totalSugarGrams > totalSugarLowMax) yellowViolations.add("Total Sugar")

        if (addedSugarGrams > addedSugarModMax) {
            if (addedSugarIsBlacklist) redViolations.add("Added Sugar") else yellowViolations.add("Added Sugar")
        } else if (addedSugarGrams > addedSugarLowMax) yellowViolations.add("Added Sugar")

        if (satFatGrams > satFatModMax) {
            if (satFatIsBlacklist) redViolations.add("Saturated Fat") else yellowViolations.add("Saturated Fat")
        } else if (satFatGrams > satFatLowMax) yellowViolations.add("Saturated Fat")

        if (totalFatGrams > totalFatModMax) {
            if (totalFatIsBlacklist) redViolations.add("Total Fat") else yellowViolations.add("Total Fat")
        }

        if (potassiumMg > potassiumModMax) {
            if (potassiumIsBlacklist) redViolations.add("Potassium") else yellowViolations.add("Potassium")
        } else if (potassiumMg > potassiumLowMax) yellowViolations.add("Potassium")

        if (carbsGrams > carbsModMax) {
            if (carbsIsBlacklist) redViolations.add("Carbs") else yellowViolations.add("Carbs")
        } else if (carbsGrams > carbsLowMax) yellowViolations.add("Carbs")

        if (transFatGrams > 0.0f) redViolations.add("Trans Fat")

        // --- 3. AGNOSTIC CLINICAL ENGINE (Driven 100% by AppSettings flags) ---
        val proteinRules = userSettings.getActiveProteinRules()

        val enforceRatio = userSettings.isFeatureFlagActive("enforce_protein_ratio")
        val enforceFloor = userSettings.isFeatureFlagActive("enforce_protein_floor")
        val enforceCeiling = userSettings.isFeatureFlagActive("enforce_protein_ceiling")

        // Ratio Floor Rule
        if (enforceRatio && proteinRules.targetRatio > 0.0f) {
            val currentRatio = if (rawCalories > 0) proteinGrams / rawCalories else 0.0f
            if (currentRatio < proteinRules.targetRatio) {
                yellowViolations.add("Low Protein Ratio (${String.format("%.2f", currentRatio)})")
            }
        }

        // Meal/Snack Protein Floor Rule
        if (enforceFloor) {
            val isMealWindow = rawCalories >= 250
            val activeMin = if (isMealWindow) proteinRules.mealMin else proteinRules.snackMin
            val contextLabel = if (isMealWindow) "Meal" else "Snack"

            if (activeMin > 0 && proteinGrams < activeMin) {
                yellowViolations.add("Low Protein for $contextLabel (<${activeMin}g)")
            }
        }

        // Meal/Snack Protein Ceiling Rule
        if (enforceCeiling || proteinRules.mealMax < 999) {
            val isMealWindow = rawCalories >= 250
            val activeMax = if (isMealWindow) proteinRules.mealMax else proteinRules.snackMax
            val contextLabel = if (isMealWindow) "Meal" else "Snack"

            if (activeMax < 999 && proteinGrams > activeMax) {
                redViolations.add("High Protein for $contextLabel (>${activeMax}g)")
            }
        }

        // --- 4. WATCHLIST & TRIGGER MATCHING ---
        val matchedCustomRed = customRedWatchlist.filter { detectedIngredients.contains(it) }
        val matchedCustomYellow = customYellowWatchlist.filter { detectedIngredients.contains(it) }
        val matchedXmlRed = xmlRedTriggers.filter { trigger ->
            if (trigger == "PHOS") detectedIngredients.any { it.contains("PHOS") } else detectedIngredients.contains(trigger)
        }

        // --- 5. RESULT RENDER ASSIGNMENT ---
        when {
            matchedCustomRed.isNotEmpty() -> {
                bgColor = "#F44336".toColorInt()
                gradeTitle = "Red - Avoid Custom Allergen (${matchedCustomRed.joinToString()})"
            }
            matchedXmlRed.isNotEmpty() -> {
                bgColor = "#F44336".toColorInt()
                val offender = detectedIngredients.find { it.contains("PHOS") } ?: matchedXmlRed.first()
                gradeTitle = "Red - High Risk Additive ($offender)"
            }
            redViolations.isNotEmpty() -> {
                bgColor = "#F44336".toColorInt()
                gradeTitle = "Red - Limit Exceeded (${redViolations.joinToString()})"
            }
            matchedCustomYellow.isNotEmpty() -> {
                bgColor = "#FFEB3B".toColorInt()
                textColor = Color.BLACK
                gradeTitle = "Sensitivity Warning: ${matchedCustomYellow.joinToString()}"
            }
            yellowViolations.isNotEmpty() -> {
                bgColor = "#FFEB3B".toColorInt()
                textColor = Color.BLACK
                gradeTitle = "Limit Warning: ${yellowViolations.joinToString()}"
            }
            else -> {
                bgColor = "#4CAF50".toColorInt()
                gradeTitle = "Green - Safe Baseline"
            }
        }

        return EvaluationResult(bgColor, textColor, gradeTitle, redViolations, yellowViolations)
    }
}