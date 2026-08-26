package net.meinook.labelscanner

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiAnalyzer {

    private val strictConfig = generationConfig {
        responseMimeType = "application/json"
        temperature = 0.15f
    }

    private fun sanitizeJsonResponse(rawText: String?): String {
        if (rawText == null) return "{}"
        val trimmed = rawText.trim()

        // Phase 1: Strip standard markdown wrap
        val markdownRegex = "^```(?:json)?\\s*([\\s\\S]*?)\\s*```$".toRegex()
        val matchResult = markdownRegex.find(trimmed)
        val extracted = if (matchResult != null) {
            matchResult.groups[1]?.value?.trim() ?: trimmed
        } else {
            trimmed
        }

        // Phase 2 Defensive Fallback: Extract the exact outer JSON boundaries to prevent greeting-text crashes
        val firstBrace = extracted.indexOf('{')
        val lastBrace = extracted.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return extracted.substring(firstBrace, lastBrace + 1)
        }

        return extracted
    }

    suspend fun analyzeIngredientsText(
        extractedOcrText: String,
        condition: String,
        apiKey: String,
        modelIdentifier: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val model = GenerativeModel(
                modelName = modelIdentifier,
                apiKey = apiKey,
                generationConfig = strictConfig,
                systemInstruction = content {
                    text("""
                           You are a precise nutrition label text parser. Analyze the provided raw OCR text of a nutrition facts label. Output ONLY a raw, flat JSON object. No markdown, no triple backticks.
                           
                           CRITICAL SPEED RULE: Skip all internal explanations, chain-of-thought, or multi-step reasoning. Do not "think" or write down reasoning steps. Translate the raw text directly into the keys of the JSON schema instantly.

                           Required schema:
                           {
                             "nutrition_facts_found": false,
                             "servings": 1.0,
                             "calories": 0.0,
                             "sodium": 0.0,
                             "protein": 0.0,
                             "carbs": 0.0,
                             "fiber": 0.0,
                             "sugar": 0.0,
                             "added_sugar": 0.0,
                             "total_fat": 0.0,
                             "sat_fat": 0.0,
                             "trans_fat": 0.0,
                             "potassium": 0.0,
                             "di": []
                           }
                           
                           Validation rules:
                           1. Only default missing parameters to 0.0 if a partial Nutrition Facts panel is actively present in the text but is missing a specific row.
                           2. Output values as clean floating numbers. Do not attach units (e.g. use 15.0, not "15g").
                           3. Extract serving sizes as precise decimals using the "servings" key.
                           4. Populate the "di" string list with exact uppercase ingredients from the ingredient statement. If no ingredients statement is present, leave "di" empty.
                           5. If any macro is labeled 0g or Less than 1g, set its value strictly to 0.0.
                           
                           6. PARSING CORRECTION: The input text comes from on-device OCR, which might occasionally misalign lines. Look for the words 'Protein' and 'Total Sugars' and grab the numbers printed directly next to them. If 'Protein' is written as '7g', set 'protein' to 7.0. If 'Total Sugars' is '2g', set 'sugar' to 2.0. Do not map adjacent line numbers.
                           
                           7. CRITICAL PRESENCE RULE: If the input text does NOT contain any numeric nutrition declarations (such as 'Calories', 'Sodium', etc.) or is completely missing a Nutrition Facts panel, you MUST set 'nutrition_facts_found' to false and omit all numeric nutrient keys (calories, sodium, carbs, protein, fiber, sugar, total_fat, etc.) from your JSON output entirely. Do not default them to 0.0. This allows our client-side compiler to flag the scan as incomplete.
                           
                           8. NUTRITION FACTS FLAG: Set 'nutrition_facts_found' to true if a Nutrition Facts panel, table, or list of nutrient values (like calories, sodium, fat, carbs) is clearly present and readable. Set 'nutrition_facts_found' to false if the input only contains ingredients, barcodes, or cooking directions, and lacks any nutrition facts tables.
                           
                           9. TABULAR REASSEMBLY RULE: If the raw OCR text has split columns (e.g., nutrient names grouped first, followed by all numbers grouped separately), you must semantically pair them according to standard FDA sequence rules (Calories aligns with the first large number, Total Fat with the first fat metric, Sodium with the high milligram value, and Protein with the final macronutrient score).
                    """.trimIndent())
                }
            )

            val response = model.generateContent(extractedOcrText)
            sanitizeJsonResponse(response.text)
        } catch (e: Exception) {
            e.printStackTrace()
            "{}"
        }
    }

    suspend fun analyzeIngredientsImage(
        imageBitmap: Bitmap,
        condition: String,
        apiKey: String,
        modelIdentifier: String
    ): String = withContext(Dispatchers.IO) {

        try {
            val model = GenerativeModel(
                modelName = modelIdentifier,
                apiKey = apiKey,
                generationConfig = strictConfig,
                systemInstruction = content {
                    text("""
                           You are a precise nutrition label parser. Output ONLY a raw, flat JSON object. No markdown, no triple backticks. 
                           
                           CRITICAL SPEED RULE: Skip all internal explanations, chain-of-thought, or multi-step reasoning. Do not "think" or write down reasoning steps. Translate the visual lines directly into the keys of the JSON schema instantly.

                           Required schema:
                           {
                             "nutrition_facts_found": false,
                             "servings": 1.0,
                             "calories": 0.0,
                             "sodium": 0.0,
                             "protein": 0.0,
                             "carbs": 0.0,
                             "fiber": 0.0,
                             "sugar": 0.0,
                             "added_sugar": 0.0,
                             "total_fat": 0.0,
                             "sat_fat": 0.0,
                             "trans_fat": 0.0,
                             "potassium": 0.0,
                             "di": []
                           }
                           
                           Validation rules:
                           1. Only default missing parameters to 0.0 if a partial Nutrition Facts panel is actively present in the image but is missing a specific row.
                           2. Output values as clean floating numbers. Do not attach units (e.g. use 15.0, not "15g").
                           3. Extract serving sizes as precise decimals using the "servings" key.
                           4. Populate the "di" string list with exact uppercase ingredients from the ingredient statement. If no ingredients statement is present, leave "di" empty.
                           5. If any macro is labeled 0g or Less than 1g, set its value strictly to 0.0.
                           
                           6. SPATIAL ALIGNMENT & UNALIGNED ROWS RULE: Rows like 'Protein' and 'Total Sugars' typically have no percentage values on the far-right side of the label, leaving blank space there. Do NOT let your attention drift to the '0%' or '0g' from 'Includes 0g Added Sugars' directly above or 'Vitamin D 0mcg 0%' below. Isolate the text 'Protein' and 'Total Sugars' horizontally and read the numeric value (e.g., '7' from 'Protein 7g' and '2' from 'Total Sugars 2g') printed directly adjacent to those words.
                           
                           7. CRITICAL PRESENCE RULE: If the visual image does NOT contain a visible Nutrition Facts panel or explicit numeric nutrient declarations, you MUST set 'nutrition_facts_found' to false and omit all numeric nutrient keys (calories, sodium, carbs, protein, fiber, sugar, total_fat, etc.) from your JSON output entirely. Do not default them to 0.0. This allows our client-side compiler to flag the scan as incomplete.
                           
                           8. NUTRITION FACTS FLAG: Set 'nutrition_facts_found' to true if a Nutrition Facts panel, table, or list of nutrient values (like calories, sodium, fat, carbs) is clearly present and readable. Set 'nutrition_facts_found' to false if the image only contains ingredients, barcodes, or cooking directions, and lacks any nutrition facts tables.
                           
                           9. TABULAR REASSEMBLY RULE: If the input image displays split columns, you must semantically pair them according to standard FDA sequence rules (Calories aligns with the first large number, Total Fat with the first fat metric, Sodium with the high milligram value, and Protein with the final macronutrient score).
                    """.trimIndent())
                }
            )

            val response = model.generateContent(content {
                image(imageBitmap)
            })

            sanitizeJsonResponse(response.text)

        } catch (e: Exception) {
            e.printStackTrace()
            "{}"
        }
    }

    suspend fun analyzeProduceImage(
        bitmap: Bitmap,
        apiKey: String,
        modelId: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val generativeModel = GenerativeModel(
                modelName = modelId,
                apiKey = apiKey,
                generationConfig = strictConfig
            )

            val prompt = """
            Identify the single raw produce item pictured. 
            Compile standardized USDA nutrient measurements for exactly 100 grams of this item.
            Return a raw, flat JSON object (no markdown backticks) using these precise keys:
            {
              "item_name": "Name of produce",
              "servings_per_container": 1.0,
              "calories": 0.0,
              "sodium_mg": 0.0,
              "protein_g": 0.0,
              "total_carbohydrates_g": 0.0,
              "total_sugar_g": 0.0,
              "added_sugar_g": 0.0,
              "total_fat_g": 0.0,
              "saturated_fat_g": 0.0,
              "trans_fat_g": 0.0,
              "fiber": 0.0,
              "potassium_mg": 0.0,
              "di": ["RAW PRODUCE"]
            }
        """.trimIndent()

            val response = generativeModel.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            return@withContext sanitizeJsonResponse(response.text)
        } catch (e: Exception) {
            e.printStackTrace()
            "{}"
        }
    }
}