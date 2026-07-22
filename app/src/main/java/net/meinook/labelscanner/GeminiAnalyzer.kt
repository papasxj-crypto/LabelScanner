package net.meinook.labelscanner

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiAnalyzer {

    // Define your configuration variables here at the top since they don't depend on the API key
    private val strictConfig = generationConfig {
        responseMimeType = "application/json"
    }

    suspend fun analyzeIngredientsImage(
        imageBitmap: Bitmap,
        condition: String,
        apiKey: String,
        modelIdentifier: String// 1. Key enters the machine here
    ): String = withContext(Dispatchers.IO) {

        try {
            // 2. Build the model on-the-fly using the parameter key
            val model = GenerativeModel(
                modelName = modelIdentifier,
                apiKey = apiKey,
                generationConfig = strictConfig,
                systemInstruction = content {
                    text("""
                           You are a precise nutrition label scanning engine. Analyze the provided image of a nutrition facts label and ingredient list. 

                           You MUST return your response as a strict, single JSON object. Do not wrap it in markdown code blocks like ```json. 

                           The JSON structure must use these exact keys:
                           {
                             "servings": 1.0,
                             "calories": 0,
                             "sodium": 0,
                             "protein": 0,
                             "carbs": 0,
                             "fiber": 0,
                             "sugar": 0,
                             "added_sugar": 0,
                             "total_fat": 0,
                             "sat_fat": 0,
                             "trans_fat": 0,
                             "potassium": 0,
                             "di": []
                           }
                           Rules for values:
                           1. If an item is missing or unreadable on the label, default its numeric value to 0.
                           2. Only include ingredients in the "detected_ingredients" array.
                           3. Extract exact whole numbers for the gram (g) and milligram (mg) values.
                           4. If a macro is explicitly listed as 0g or Less than 1g on the label, you MUST return its value as 0.0. Do not round up or hallucinate values.
                           5. Extract the "servings per container" value as a precise decimal number (e.g., 2.5). Look for the key "servings_per_container".
                           6. CRITICAL ACCURACY RULE: Read the numerical values directly from the label text exactly as they are printed. Do not infer, estimate, or extrapolate numbers based on typical serving sizes or standard database items. If the label explicitly states 0g, you must return 0.
                    """.trimIndent())
                }
            )

            // 3. Dispatch the bitmap to the model instance
            val response = model.generateContent(content {
                image(imageBitmap)
            })

            // Return the raw text string back to MainActivity
            response.text ?: "{}"

        } catch (e: Exception) {
            e.printStackTrace()
            "{}" // Return an empty JSON block fallback on network error
        }
    }

    suspend fun analyzeProduceImage(
        bitmap: Bitmap,
        apiKey: String,
        modelId: String
    ): String = withContext(Dispatchers.IO) {
        val generativeModel = GenerativeModel(
            modelName = modelId,
            apiKey = apiKey
        )

        val prompt = """
        You are a clinical nutrition database. Identify the single raw, fresh produce item in this image (e.g., Apple, Broccoli, Mango). 
        Provide the standard USDA nutritional values for exactly 100 grams of this item.
        Respond ONLY with a valid JSON object using this exact structure. Do not include markdown formatting or backticks.
        {
          "item_name": "Name of produce",
          "servings_per_container": 1.0,
          "calories": 0,
          "sodium_mg": 0,
          "protein_g": 0.0,
          "total_carbohydrates_g": 0.0,
          "total_sugar_g": 0.0,
          "added_sugar_g": 0.0,
          "total_fat_g": 0.0,
          "saturated_fat_g": 0.0,
          "trans_fat_g": 0.0,
          "fiber": 0.0,
          "potassium_g": 0.0,
          "detected_ingredients": ["RAW PRODUCE"]
        }
    """.trimIndent()

        val response = generativeModel.generateContent(
            content {
                image(bitmap)
                text(prompt)
            }
        )

        return@withContext response.text ?: "{}"
    }
}
