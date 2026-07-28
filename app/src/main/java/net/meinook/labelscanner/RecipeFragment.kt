package net.meinook.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class RecipeFragment : Fragment() {

    private lateinit var edtRecipeInput: TextInputEditText
    private lateinit var btnAdjustRecipe: MaterialButton
    private lateinit var cardRecipeHealthGrade: MaterialCardView
    private lateinit var txtRecipeHealthGradeTitle: TextView
    private lateinit var txtRecipeHealthViolations: TextView
    private lateinit var txtRecipeHealthStats: TextView
    private lateinit var cardAdjustedResult: MaterialCardView
    private lateinit var txtAdjustedOutput: TextView
    private lateinit var btnCopyAdjusted: MaterialButton

    // Holds exact gateway feedback/exceptions for easy UI diagnostics
    private var lastDiagnosticError: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_recipe, container, false)

        edtRecipeInput = root.findViewById(R.id.edtRecipeInput)
        btnAdjustRecipe = root.findViewById(R.id.btnAdjustRecipe)
        cardRecipeHealthGrade = root.findViewById(R.id.cardRecipeHealthGrade)
        txtRecipeHealthGradeTitle = root.findViewById(R.id.txtRecipeHealthGradeTitle)
        txtRecipeHealthViolations = root.findViewById(R.id.txtRecipeHealthViolations)
        txtRecipeHealthStats = root.findViewById(R.id.txtRecipeHealthStats)
        cardAdjustedResult = root.findViewById(R.id.cardAdjustedResult)
        txtAdjustedOutput = root.findViewById(R.id.txtAdjustedOutput)
        btnCopyAdjusted = root.findViewById(R.id.btnCopyAdjusted)

        btnAdjustRecipe.setOnClickListener {
            val rawText = edtRecipeInput.text?.toString() ?: ""
            if (rawText.isNotBlank()) {
                analyzeRecipeAndAdjust(rawText)
            }
        }

        return root
    }

    private fun analyzeRecipeAndAdjust(rawText: String) {
        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Analyzing recipe..."
        lastDiagnosticError = null

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()

        lifecycleScope.launch {
            val responseString = fetchRecipeNutritionFromGemini(rawText, activeProfiles)
            if (responseString != null) {
                try {
                    val parsedResult = JSONObject(responseString)

                    val totalCalories = parsedResult.optDouble("calories", 0.0)
                    val totalSodium = parsedResult.optDouble("sodium_mg", 0.0)
                    val totalProtein = parsedResult.optDouble("protein_g", 0.0)
                    val totalPotassium = parsedResult.optDouble("potassium_mg", 0.0)
                    val totalCarbs = parsedResult.optDouble("total_carbohydrates_g", 0.0)
                    val totalFiber = parsedResult.optDouble("fiber_g", 0.0)
                    val totalSugar = parsedResult.optDouble("total_sugar_g", 0.0)
                    val totalSatFat = parsedResult.optDouble("saturated_fat_g", 0.0)
                    val totalFat = parsedResult.optDouble("total_fat_g", 0.0)
                    val servings = parsedResult.optInt("servings", 6).coerceAtLeast(1)
                    val adjustedRecipe = parsedResult.optString("adjusted_recipe", "")
                    val rationale = parsedResult.optString("rationale", "")

                    val perServingJson = JSONObject().apply {
                        put("calories", totalCalories / servings)
                        put("sodium_mg", totalSodium / servings)
                        put("protein_g", totalProtein / servings)
                        put("potassium_mg", totalPotassium / servings)
                        put("total_carbohydrates_g", totalCarbs / servings)
                        put("fiber_g", totalFiber / servings)
                        put("total_sugar_g", totalSugar / servings)
                        put("saturated_fat_g", totalSatFat / servings)
                        put("total_fat_g", totalFat / servings)
                    }

                    val dynamicXmlTriggers = mutableMapOf<String, String>()
                    for (profileId in activeProfiles) {
                        val triggers = userSettings.getIngredientsFromAssetFile(profileId)
                        for (trigger in triggers) {
                            dynamicXmlTriggers[trigger.uppercase(Locale.ROOT).trim()] = profileId
                        }
                    }

                    val evaluationResult = LabelEvaluator.evaluateScanData(
                        jsonResult = perServingJson,
                        detectedIngredients = dynamicXmlTriggers.keys.toList(),
                        savedProfileIds = activeProfiles,
                        userSettings = userSettings,
                        xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                        customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                        customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                        isProduce = false
                    )

                    cardRecipeHealthGrade.visibility = View.VISIBLE
                    cardRecipeHealthGrade.setCardBackgroundColor(evaluationResult.bgColor)

                    txtRecipeHealthGradeTitle.text = "Recipe Analysis: ${evaluationResult.gradeTitle}"
                    txtRecipeHealthGradeTitle.setTextColor(evaluationResult.textColor)

                    val violations = mutableListOf<String>()
                    if (evaluationResult.redViolations.isNotEmpty()) {
                        violations.add("Critical Violations: ${evaluationResult.redViolations.distinct().joinToString(", ")}")
                    }
                    if (evaluationResult.yellowViolations.isNotEmpty()) {
                        violations.add("Warnings: ${evaluationResult.yellowViolations.distinct().joinToString(", ")}")
                    }
                    txtRecipeHealthViolations.text = if (violations.isNotEmpty()) {
                        violations.joinToString("\n")
                    } else {
                        "This recipe complies fully with selected dietary configurations."
                    }
                    txtRecipeHealthViolations.setTextColor(evaluationResult.textColor)

                    txtRecipeHealthStats.text = String.format(
                        Locale.ROOT,
                        "Estimated Nutrition (Per Serving, based on %d servings total):\nCalories: %.0f | Sodium: %.0fmg | Protein: %.1fg | Potassium: %.0fmg | Net Carbs: %.1fg",
                        servings,
                        totalCalories / servings,
                        totalSodium / servings,
                        totalProtein / servings,
                        totalPotassium / servings,
                        (totalCarbs - totalFiber).coerceAtLeast(0.0) / servings
                    )
                    txtRecipeHealthStats.setTextColor(evaluationResult.textColor)

                    cardAdjustedResult.visibility = View.VISIBLE
                    txtAdjustedOutput.text = "$adjustedRecipe\n\nClinical Substitution Rationale:\n$rationale"

                    btnCopyAdjusted.setOnClickListener {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Adjusted Recipe", adjustedRecipe)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(requireContext(), "Adjusted recipe copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "JSON Parse Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                // Display the dynamic clinical adjustment payload error cleanly on-screen
                val displayError = lastDiagnosticError ?: "Unknown connection error"
                Toast.makeText(requireContext(), "Server Error: $displayError", Toast.LENGTH_LONG).show()
            }

            btnAdjustRecipe.isEnabled = true
            btnAdjustRecipe.text = "Analyze & Substitute"
        }
    }

    private suspend fun fetchRecipeNutritionFromGemini(
        recipeText: String,
        activeProfiles: Set<String>
    ): String? = withContext(Dispatchers.IO) {
        try {
            // CRITICAL FIX: AGP escape characters leave nested quotes inside BuildConfig String.
            // Sanitizing by stripping out any double quotes from the URL key string:
            val apiKey = BuildConfig.GEMINI_API_KEY.replace("\"", "").trim()

            if (apiKey.isEmpty() || apiKey == "null") {
                lastDiagnosticError = "API Key is empty or null inside BuildConfig."
                return@withContext null
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val prompt = """
                You are an expert culinary clinical dietitian. Analyze the following raw recipe text:
                $recipeText

                1. Calculate the total estimated nutritional facts of this entire recipe (sum of all ingredients combined).
                2. Generate an adjusted, clinically safe version of the recipe tailored for the active clinical profiles: ${activeProfiles.joinToString(", ")}.
                   Substitute hazard items (like high sodium sauce, heavy salt, or high protein/potassium items in CKD, or carbs/sugars in Keto) with safe alternatives and adjust measurements cleanly.

                Return a raw JSON object following this schema exactly, do not add any markdown formatting, code blocks, or backticks:
                {
                  "calories": 3035.0,
                  "sodium_mg": 4500.0,
                  "protein_g": 147.0,
                  "potassium_mg": 3400.0,
                  "total_carbohydrates_g": 365.0,
                  "fiber_g": 24.0,
                  "total_sugar_g": 35.0,
                  "saturated_fat_g": 15.0,
                  "total_fat_g": 100.0,
                  "servings": 6,
                  "adjusted_recipe": "complete adjusted recipe text here",
                  "rationale": "short explanation of clinical changes made"
                }
            """.trimIndent()

            val jsonRequest = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseText)
                val candidates = responseJson.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                parts.getJSONObject(0).getString("text")
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                lastDiagnosticError = "HTTP Response ${conn.responseCode}: $errorText"
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Gracefully catch security exceptions (missing permission) or network timeouts
            lastDiagnosticError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }
}