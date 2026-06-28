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
        apiKey: String // 1. Key enters the machine here
    ): String = withContext(Dispatchers.IO) {

        try {
            // 2. Build the model on-the-fly using the parameter key
            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig = strictConfig,
                systemInstruction = content {
                    text("""
                        You are a precise medical data extraction engine. Analyze the provided image of a food nutrition label and ingredients list.
                        You must extract the data and return it strictly as a single JSON object matching this format exactly:
                        {
                          "detected_ingredients": ["INGREDIENT1", "INGREDIENT2"],
                          "sodium_mg": 0,
                          "sodium_dv_percent": 0,
                          "servings_per_container": 1.0
                        }

                        Guidelines for data extraction:
                        1. Parse the entire ingredients list. Convert all ingredient names to uppercase text and place them in the "detected_ingredients" array.
                        2. Locate the "Sodium" line in the nutrition facts panel. Extract the raw milligram count as a whole integer for "sodium_mg". If not found, enter 0.
                        3. Extract the Daily Value percentage for sodium as a whole integer for "sodium_dv_percent". If not found, enter 0.
                        4. Locate the "Servings Per Container" or "Servings Per Pack" value. Extract it as a decimal number for "servings_per_container". If it says something like "about 2.5", enter 2.5. If not found, default to 1.0.
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
}
