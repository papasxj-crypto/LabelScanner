package net.meinook.labelscanner

import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.util.Log
import org.json.JSONObject

data class EvaluationResult(
    val bgColor: Int,
    val textColor: Int,
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

        // 1. SMART ANCHOR CHECK
        fun hasNutrient(xmlId: String): Boolean {
            val validKeys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            return validKeys.any { jsonResult.has(it) && !jsonResult.isNull(it) }
        }

        val hasCalories = hasNutrient("calories")
        val hasSodium = hasNutrient("sodium")

        // Relaxation: Mission Chips work if we have Calories and Sodium
        if (!isProduce && (!hasCalories || !hasSodium)) {
            return EvaluationResult(
                bgColor = "#607D8B".toColorInt(),
                textColor = Color.WHITE,
                gradeTitle = "Incomplete Scan",
                redViolations = emptyList(),
                yellowViolations = listOf("Missing core nutrition data (Calories/Sodium).")
            )
        }

        // 2. MAIN EVALUATION LOOP
        fun getVal(xmlId: String): Float {
            val keys = nutrientRegistry.filter { it.xmlId == xmlId }.map { it.jsonKey }
            for (k in keys) { if (jsonResult.has(k)) return jsonResult.optDouble(k, 0.0).toFloat() }
            return 0.0f
        }

        val totalCarbs = getVal("carbs")
        val fiber = getVal("fiber")
        val netCarbs = (totalCarbs - fiber).coerceAtLeast(0.0f)

        val processed = mutableSetOf<String>()
        for (map in nutrientRegistry) {
            if (processed.contains(map.xmlId)) continue

            val (low, mod, blacklist) = userSettings.getNutrientThresholds(map.xmlId)
            if (mod >= 999.0) continue

            var value = if (map.useNetCarbs && savedProfileIds.contains("keto")) netCarbs else getVal(map.xmlId)
            if (map.isScaleRequired && value in 0.01f..5.0f) value *= 1000

            if (value > mod) {
                val label = if (map.useNetCarbs && savedProfileIds.contains("keto")) "Net Carbs" else map.displayName
                if (blacklist) redViolations.add(label) else yellowViolations.add(label)
            } else if (value > low) {
                val label = if (map.useNetCarbs && savedProfileIds.contains("keto")) "Net Carbs" else map.displayName
                yellowViolations.add(label)
            }
            processed.add(map.xmlId)
        }

        // 3. TRIGGERS
        val matchedReds = xmlRedTriggers.filter { t ->
            if (t == "PHOS") detectedIngredients.any { it.contains("PHOS") } else detectedIngredients.contains(t)
        }
        if (matchedReds.isNotEmpty()) redViolations.add("Risk Additive")

        return finalizeResult(redViolations, yellowViolations)
    }

    private fun finalizeResult(reds: List<String>, yellows: List<String>): EvaluationResult {
        return when {
            reds.isNotEmpty() -> EvaluationResult("#F44336".toColorInt(), Color.WHITE, "Red - Avoid", reds.distinct(), yellows.distinct())
            yellows.isNotEmpty() -> EvaluationResult("#FFEB3B".toColorInt(), Color.BLACK, "Yellow - Caution", emptyList(), yellows.distinct())
            else -> EvaluationResult("#4CAF50".toColorInt(), Color.WHITE, "Green - Safe", emptyList(), emptyList())
        }
    }
}