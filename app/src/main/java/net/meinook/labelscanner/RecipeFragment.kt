package net.meinook.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecipeFragment : Fragment() {

    // ===================================================================================
    // SERVER / BACKEND CONFIGURATION
    // ===================================================================================
    private val USE_SERVERLESS_BACKEND = true
    private val BACKEND_ANALYSIS_URL = "https://analyze-recipe-955054852456.us-west1.run.app"

    // Input Views
    private lateinit var edtRecipeInput: TextInputEditText
    private lateinit var btnAdjustRecipe: MaterialButton

    // Side-by-Side Grade Layout Views
    private lateinit var layoutGradeComparison: View
    private lateinit var cardOriginalHealthGrade: MaterialCardView
    private lateinit var txtOriginalGradeTitle: TextView
    private lateinit var txtOriginalViolations: TextView
    private lateinit var txtOriginalStats: TextView

    private lateinit var cardAdjustedHealthGrade: MaterialCardView
    private lateinit var txtAdjustedGradeTitle: TextView
    private lateinit var txtAdjustedViolations: TextView
    private lateinit var txtAdjustedStats: TextView

    // Output Views
    private lateinit var cardAdjustedResult: MaterialCardView
    private lateinit var txtAdjustedOutput: TextView
    private lateinit var btnCopyAdjusted: MaterialButton

    // Input Selector Views
    private lateinit var toggleInputMode: MaterialButtonToggleGroup
    private lateinit var tilRecipeInput: TextInputLayout
    private lateinit var tilRecipeUrl: TextInputLayout
    private lateinit var edtRecipeUrl: TextInputEditText
    private lateinit var layoutOcrActions: View
    private lateinit var btnSelectPhoto: MaterialButton

    // Phase 2 & 3 On-Demand Views
    private lateinit var layoutPostAnalysisActions: View
    private lateinit var btnRetrieveSteps: MaterialButton
    private lateinit var btnRetrieveAnalysis: MaterialButton

    // State trackers to pass clean metadata directly to on-demand tasks
    private var lastOriginalIngredients: String = ""
    private var lastAdjustedIngredients: String = ""
    private var lastDiagnosticError: String? = null

    // Track the currently active input mode to prevent redundant clearing / UI reset
    private var currentInputModeId: Int = R.id.btnModeText

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runOnDeviceOcr(uri)
        } else {
            Toast.makeText(context, "No photo chosen", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_recipe, container, false)

        edtRecipeInput = root.findViewById(R.id.edtRecipeInput)
        btnAdjustRecipe = root.findViewById(R.id.btnAdjustRecipe)

        // Bind Side-by-Side Comparison Layout Views
        layoutGradeComparison = root.findViewById(R.id.layoutGradeComparison)
        cardOriginalHealthGrade = root.findViewById(R.id.cardOriginalHealthGrade)
        txtOriginalGradeTitle = root.findViewById(R.id.txtOriginalGradeTitle)
        txtOriginalViolations = root.findViewById(R.id.txtOriginalViolations)
        txtOriginalStats = root.findViewById(R.id.txtOriginalStats)

        cardAdjustedHealthGrade = root.findViewById(R.id.cardAdjustedHealthGrade)
        txtAdjustedGradeTitle = root.findViewById(R.id.txtAdjustedGradeTitle)
        txtAdjustedViolations = root.findViewById(R.id.txtAdjustedViolations)
        txtAdjustedStats = root.findViewById(R.id.txtAdjustedStats)

        // Bind Output Views
        cardAdjustedResult = root.findViewById(R.id.cardAdjustedResult)
        txtAdjustedOutput = root.findViewById(R.id.txtAdjustedOutput)
        btnCopyAdjusted = root.findViewById(R.id.btnCopyAdjusted)

        // Bind Selector Views
        toggleInputMode = root.findViewById(R.id.toggleInputMode)
        tilRecipeInput = root.findViewById(R.id.tilRecipeInput)
        tilRecipeUrl = root.findViewById(R.id.tilRecipeUrl)
        edtRecipeUrl = root.findViewById(R.id.edtRecipeUrl)
        layoutOcrActions = root.findViewById(R.id.layoutOcrActions)
        btnSelectPhoto = root.findViewById(R.id.btnSelectPhoto)

        // Bind Phase 2 & 3 Actions
        layoutPostAnalysisActions = root.findViewById(R.id.layoutPostAnalysisActions)
        btnRetrieveSteps = root.findViewById(R.id.btnRetrieveSteps)
        btnRetrieveAnalysis = root.findViewById(R.id.btnRetrieveAnalysis)

        // Reference Mode Text Button for reset click actions
        val btnModeText: MaterialButton = root.findViewById(R.id.btnModeText)

        // Tapping inside the recipe input box hides the stale grade analysis, leaving text ready for edit
        edtRecipeInput.setOnClickListener {
            clearAnalysisResults()
        }

        // Fix focus-gain double touch: clear cards immediately on the very first touch when focus transitions
        edtRecipeInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                clearAnalysisResults()
            }
        }

        // Tapping the Text button clears and resets everything for a fresh recipe
        btnModeText.setOnClickListener {
            edtRecipeInput.text = null
            edtRecipeUrl.text = null
            clearAnalysisResults()
            toggleInputMode.check(R.id.btnModeText)
            switchInputInterface(R.id.btnModeText)
        }

        toggleInputMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                switchInputInterface(checkedId)
            }
        }

        btnSelectPhoto.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnAdjustRecipe.setOnClickListener {
            handleActionSubmit()
        }

        // Set listeners for Phase 2 & Phase 3 On-Demand functions
        btnRetrieveSteps.setOnClickListener {
            retrieveStepByStepInstructions()
        }

        btnRetrieveAnalysis.setOnClickListener {
            retrieveAnalysisData()
        }

        return root
    }

    private fun switchInputInterface(checkedId: Int) {
        // If the user clicks the already active mode, do nothing.
        if (checkedId == currentInputModeId) {
            return
        }
        currentInputModeId = checkedId

        // Clear previous results instantly when switching to a completely new input mode
        clearAnalysisResults()

        when (checkedId) {
            R.id.btnModeText -> {
                tilRecipeInput.visibility = View.VISIBLE
                tilRecipeUrl.visibility = View.GONE
                layoutOcrActions.visibility = View.GONE
                btnAdjustRecipe.text = "Analyze & Substitute"
            }
            R.id.btnModeUrl -> {
                tilRecipeInput.visibility = View.GONE
                tilRecipeUrl.visibility = View.VISIBLE
                layoutOcrActions.visibility = View.GONE
                btnAdjustRecipe.text = "Scrape & Populate"
            }
            R.id.btnModeOcr -> {
                tilRecipeInput.visibility = View.GONE
                tilRecipeUrl.visibility = View.GONE
                layoutOcrActions.visibility = View.VISIBLE
                btnAdjustRecipe.text = "Analyze & Substitute"
            }
        }
    }

    /**
     * Clears and hides previous evaluation and substitution outputs
     * when starting a new run or switching input interfaces.
     */
    private fun clearAnalysisResults() {
        layoutGradeComparison.visibility = View.GONE
        cardAdjustedResult.visibility = View.GONE
        layoutPostAnalysisActions.visibility = View.GONE
        txtAdjustedOutput.text = ""

        // Reset trackers
        lastOriginalIngredients = ""
        lastAdjustedIngredients = ""
        lastDiagnosticError = null

        // Reset phase action buttons
        btnRetrieveSteps.isEnabled = true
        btnRetrieveSteps.text = "Get Steps"
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"
    }

    private fun handleActionSubmit() {
        when (toggleInputMode.checkedButtonId) {
            R.id.btnModeText, R.id.btnModeOcr -> {
                val rawText = edtRecipeInput.text?.toString() ?: ""
                if (rawText.isNotBlank()) {
                    analyzeRecipeAndAdjust(rawText)
                } else {
                    Toast.makeText(context, "Please enter or scan some recipe text first.", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.btnModeUrl -> {
                val url = edtRecipeUrl.text?.toString() ?: ""
                if (url.isNotBlank()) {
                    scrapeWebpageContents(url)
                } else {
                    Toast.makeText(context, "Please enter a recipe link first.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim()
        if (cleanUrl.contains("?")) {
            cleanUrl = cleanUrl.substringBefore("?")
        }
        if (cleanUrl.endsWith("/print/")) {
            cleanUrl = cleanUrl.removeSuffix("print/")
        } else if (cleanUrl.endsWith("/print")) {
            cleanUrl = cleanUrl.removeSuffix("print")
        }
        return cleanUrl
    }

    private fun scrapeWebpageContents(urlStr: String) {
        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Scraping page link..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val canonicalUrl = sanitizeUrl(urlStr)
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(canonicalUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Network Response Code: ${response.code}")
                    val rawHtml = response.body?.string() ?: ""
                    val ingredientsText = extractStructuredRecipeIngredients(rawHtml)
                    val finalOutput = if (ingredientsText.isNotBlank()) {
                        ingredientsText
                    } else {
                        cleanHtmlToText(rawHtml)
                    }

                    withContext(Dispatchers.Main) {
                        btnAdjustRecipe.isEnabled = true
                        btnAdjustRecipe.text = "Analyze & Substitute"

                        if (finalOutput.isNotBlank()) {
                            edtRecipeInput.setText(finalOutput)
                            // Triggers the listener to switch the interface layout cleanly
                            toggleInputMode.check(R.id.btnModeText)
                            Toast.makeText(context, "Ingredients imported! Review below, then click Analyze.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Could not extract readable ingredients from that URL.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnAdjustRecipe.isEnabled = true
                    btnAdjustRecipe.text = "Analyze & Substitute"
                    Toast.makeText(context, "Scraping Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runOnDeviceOcr(uri: Uri) {
        val context = context ?: return
        val image: InputImage
        try {
            image = InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to resolve image path.", Toast.LENGTH_SHORT).show()
            return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) {
                    edtRecipeInput.setText(visionText.text)
                    toggleInputMode.check(R.id.btnModeText)
                    switchInputInterface(R.id.btnModeText)
                    Toast.makeText(context, "Text imported from image! Review below, then analyze.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "No text found inside the photo.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "OCR Processing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun extractStructuredRecipeIngredients(html: String): String {
        try {
            val pattern = Regex(
                "<script\\b[^>]*type\\s*=\\s*['\"]?application/ld\\+json['\"]?[^>]*>(.*?)</script>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val matches = pattern.findAll(html)

            for (match in matches) {
                val jsonContent = match.groups[1]?.value?.trim() ?: continue
                if (jsonContent.isEmpty()) continue

                try {
                    val sanitized = sanitizeJsonLdString(jsonContent)
                    if (sanitized.isEmpty()) continue

                    val rootToken = if (sanitized.startsWith("[")) {
                        JSONArray(sanitized)
                    } else {
                        JSONObject(sanitized)
                    }

                    val recipeObj = findRecipeObject(rootToken)
                    if (recipeObj != null) {
                        val ingredientsText = formatRecipeIngredients(recipeObj)
                        if (ingredientsText.isNotBlank()) {
                            return ingredientsText
                        }
                    }
                } catch (e: Exception) {
                    // Fail silently
                }
            }

            return fallbackTargetedHtmlIngredients(html)

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun formatRecipeIngredients(recipe: JSONObject): String {
        val builder = StringBuilder()
        val ingredientsArray = recipe.optJSONArray("recipeIngredient")
        if (ingredientsArray != null && ingredientsArray.length() > 0) {
            for (i in 0 until ingredientsArray.length()) {
                val ing = ingredientsArray.optString(i, "").trim()
                if (ing.isNotEmpty()) {
                    builder.append(ing).append("\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun findRecipeObject(json: Any): JSONObject? {
        when (json) {
            is JSONObject -> {
                val typeStr = json.optString("@type", json.optString("type", ""))
                if (typeStr.equals("Recipe", ignoreCase = true) ||
                    json.optJSONArray("@type")?.toString()?.contains("Recipe", ignoreCase = true) == true
                ) {
                    return json
                }
                val graph = json.optJSONArray("@graph")
                if (graph != null) {
                    val found = findRecipeObject(graph)
                    if (found != null) return found
                }
                for (key in json.keys()) {
                    val child = json.get(key)
                    if (child is JSONObject || child is JSONArray) {
                        val found = findRecipeObject(child)
                        if (found != null) return found
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val child = json.get(i)
                    if (child is JSONObject || child is JSONArray) {
                        val found = findRecipeObject(child)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeJsonLdString(rawJson: String): String {
        var clean = rawJson.trim()
        clean = clean.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        val firstBrace = clean.indexOf('{')
        val firstBracket = clean.indexOf('[')
        val lastBrace = clean.lastIndexOf('}')
        val lastBracket = clean.lastIndexOf(']')

        val start = if (firstBrace != -1 && firstBracket != -1) {
            Math.min(firstBrace, firstBracket)
        } else {
            @Suppress("KotlinConstantConditions")
            Math.max(firstBrace, firstBracket)
        }
        val end = Math.max(lastBrace, lastBracket)

        if (start != -1 && end != -1 && end > start) {
            return clean.substring(start, end + 1)
        }
        return clean
    }

    private fun fallbackTargetedHtmlIngredients(rawHtml: String): String {
        try {
            val ingredientBlockPattern = Regex(
                "<(?:ul|ol|div)[^>]*class\\s*=\\s*['\"][^'\"]*ingredient[^'\"]*['\"][^>]*>(.*?)</(?:ul|ol|div)>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val matches = ingredientBlockPattern.findAll(rawHtml)
            val extractedList = mutableListOf<String>()

            for (match in matches) {
                val blockContent = match.groups[1]?.value ?: continue
                val liPattern = Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val items = liPattern.findAll(blockContent)

                for (item in items) {
                    val rawLi = item.groups[1]?.value ?: continue
                    val cleanText = cleanHtmlToText(rawLi).trim()
                    if (cleanText.isNotBlank()) {
                        extractedList.add(cleanText)
                    }
                }
            }

            if (extractedList.isNotEmpty()) {
                return extractedList.joinToString("\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun cleanHtmlToText(rawHtml: String): String {
        var temp = rawHtml
        temp = temp.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        temp = temp.replace(Regex("<script.*?>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<style.*?>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<head.*?>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<nav.*?>.*?</nav>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<header.*?>.*?</header>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<footer.*?>.*?</footer>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<svg.*?>.*?</svg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val plainText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(temp, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(temp).toString()
        }

        return plainText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ===================================================================================
    // CLIENT SIDE DYNAMIC RULES EXTRACTOR
    // ===================================================================================
    private fun getXmlProfileContents(context: Context, profileId: String): String? {
        return try {
            val assetName = when (profileId) {
                "ckd_pre_dialysis" -> "ckd_preDialysis.xml"
                else -> {
                    val parts = profileId.split("_")
                    if (parts.size > 1) {
                        val camelCase = parts[0] + parts.drop(1).joinToString("") { part ->
                            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                        }
                        "$camelCase.xml"
                    } else {
                        "$profileId.xml"
                    }
                }
            }
            context.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                context.assets.open("$profileId.xml").bufferedReader().use { it.readText() }
            } catch (ex: Exception) {
                ex.printStackTrace()
                null
            }
        }
    }

    /**
     * Loops through clinical sub-steps while displaying a marching healthy sprout
     * emoji across an 8-position track. Ticks every 1.0 seconds to match
     * a realistic 30-35 second backend latency expectation.
     */
    private fun startAnalysisProgressUpdates(): Job {
        return lifecycleScope.launch(Dispatchers.Main) {
            var elapsedSeconds = 0
            var shakerPosition = 0
            val trackLength = 8

            while (true) {
                val phase = when {
                    elapsedSeconds < 6 -> "Contacting engine... "
                    elapsedSeconds < 13 -> "Parsing ingredients... "
                    elapsedSeconds < 22 -> "USDA database match... "
                    elapsedSeconds < 30 -> "Calculating swaps... "
                    else -> "Clinical safety check... "
                }

                val track = StringBuilder()
                for (i in 0 until trackLength) {
                    if (i == shakerPosition) {
                        track.append("🌱")
                    } else {
                        track.append("·")
                    }
                    if (i < trackLength - 1) {
                        track.append(" ")
                    }
                }

                btnAdjustRecipe.text = "$phase$track"

                delay(1000)
                elapsedSeconds++
                shakerPosition = (shakerPosition + 1) % trackLength
            }
        }
    }

    // ===================================================================================
    // FUNCTION 1: INITIAL ANALYSIS & INGREDIENT SUBSTITUTION (FASTER PROCESS)
    // ===================================================================================
    private fun analyzeRecipeAndAdjust(rawText: String) {
        btnAdjustRecipe.isEnabled = false
        lastDiagnosticError = null

        // Prep UI state for fast return
        cardAdjustedResult.visibility = View.VISIBLE
        txtAdjustedOutput.text = "Substituting ingredients and evaluating clinical profiles... Please wait (usually takes 30-35 seconds)."
        layoutGradeComparison.visibility = View.GONE
        layoutPostAnalysisActions.visibility = View.GONE

        // Enable actions once loaded
        btnRetrieveSteps.isEnabled = true
        btnRetrieveSteps.text = "Get Steps"
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()

        // Start the marching healthy sprout UI progress ticker
        val progressJob = startAnalysisProgressUpdates()

        lifecycleScope.launch {
            val responseString = fetchRecipeNutritionFromBackend(rawText, activeProfiles)

            // Cancel the progress ticker as soon as we get a response
            progressJob.cancel()

            if (responseString != null) {
                try {
                    val parsedResult = JSONObject(responseString)
                    val servings = parsedResult.optInt("servings", 6).coerceAtLeast(1)
                    val adjustedIngredients = parsedResult.optString("adjusted_ingredients", "")

                    // Save ingredients in memory to pass directly to Phase 2 & 3 tasks
                    lastOriginalIngredients = rawText
                    lastAdjustedIngredients = adjustedIngredients

                    val dynamicXmlTriggers = mutableMapOf<String, String>()
                    for (profileId in activeProfiles) {
                        val triggers = userSettings.getIngredientsFromAssetFile(profileId)
                        for (trigger in triggers) {
                            dynamicXmlTriggers[trigger.uppercase(Locale.ROOT).trim()] = profileId
                        }
                    }

                    // Process ORIGINAL Nutrition
                    val originalJson = parsedResult.optJSONObject("original_nutrition")
                    val originalEvalResult = if (originalJson != null) {
                        val originalPerServing = JSONObject().apply {
                            put("calories", originalJson.optDouble("calories", 0.0) / servings)
                            put("sodium_mg", originalJson.optDouble("sodium_mg", 0.0) / servings)
                            put("protein_g", originalJson.optDouble("protein_g", 0.0) / servings)
                            put("potassium_mg", originalJson.optDouble("potassium_mg", 0.0) / servings)
                            put("total_carbohydrates_g", originalJson.optDouble("total_carbohydrates_g", 0.0) / servings)
                            put("fiber_g", originalJson.optDouble("fiber_g", 0.0) / servings)
                            put("total_sugar_g", originalJson.optDouble("total_sugar_g", 0.0) / servings)
                            put("saturated_fat_g", originalJson.optDouble("saturated_fat_g", 0.0) / servings)
                            put("total_fat_g", originalJson.optDouble("total_fat_g", 0.0) / servings)
                        }
                        LabelEvaluator.evaluateScanData(
                            jsonResult = originalPerServing,
                            detectedIngredients = dynamicXmlTriggers.keys.toList(),
                            savedProfileIds = activeProfiles,
                            userSettings = userSettings,
                            xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                            customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                            customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                            isProduce = false
                        )
                    } else null

                    // Process ADJUSTED Nutrition
                    val adjustedJson = parsedResult.optJSONObject("adjusted_nutrition")
                    val adjustedEvalResult = if (adjustedJson != null) {
                        val adjustedPerServing = JSONObject().apply {
                            put("calories", adjustedJson.optDouble("calories", 0.0) / servings)
                            put("sodium_mg", adjustedJson.optDouble("sodium_mg", 0.0) / servings)
                            put("protein_g", adjustedJson.optDouble("protein_g", 0.0) / servings)
                            put("potassium_mg", adjustedJson.optDouble("potassium_mg", 0.0) / servings)
                            put("total_carbohydrates_g", adjustedJson.optDouble("total_carbohydrates_g", 0.0) / servings)
                            put("fiber_g", adjustedJson.optDouble("fiber_g", 0.0) / servings)
                            put("total_sugar_g", adjustedJson.optDouble("total_sugar_g", 0.0) / servings)
                            put("saturated_fat_g", adjustedJson.optDouble("saturated_fat_g", 0.0) / servings)
                            put("total_fat_g", adjustedJson.optDouble("total_fat_g", 0.0) / servings)
                        }
                        LabelEvaluator.evaluateScanData(
                            jsonResult = adjustedPerServing,
                            detectedIngredients = dynamicXmlTriggers.keys.toList(),
                            savedProfileIds = activeProfiles,
                            userSettings = userSettings,
                            xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                            customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                            customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                            isProduce = false
                        )
                    } else null

                    // Populate Side-by-Side Cards with Violations AND Per-Serving Stats
                    if (originalEvalResult != null && adjustedEvalResult != null && originalJson != null && adjustedJson != null) {
                        layoutGradeComparison.visibility = View.VISIBLE

                        // Original Card
                        cardOriginalHealthGrade.setCardBackgroundColor(originalEvalResult.bgColor)
                        txtOriginalGradeTitle.text = "Original: ${originalEvalResult.gradeTitle}"
                        txtOriginalGradeTitle.setTextColor(originalEvalResult.textColor)
                        val originalViolations = if (originalEvalResult.redViolations.isNotEmpty() || originalEvalResult.yellowViolations.isNotEmpty()) {
                            (originalEvalResult.redViolations.distinct() + originalEvalResult.yellowViolations.distinct()).joinToString(", ")
                        } else {
                            "None"
                        }
                        txtOriginalViolations.text = "Violations:\n$originalViolations"
                        txtOriginalViolations.setTextColor(originalEvalResult.textColor)

                        val origCal = originalJson.optDouble("calories", 0.0) / servings
                        val origSod = originalJson.optDouble("sodium_mg", 0.0) / servings
                        val origPot = originalJson.optDouble("potassium_mg", 0.0) / servings
                        val origCarb = (originalJson.optDouble("total_carbohydrates_g", 0.0) - originalJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings

                        // Original Card Stats Display (Dynamically matches evaluation layout text color)
                        txtOriginalStats.text = String.format(
                            Locale.ROOT,
                            "Per Serving (%d Servings):\nCal: %.0f | Sod: %.0fmg | Prot: %.1fg\nPot: %.0fmg | Carbs: %.1fg",
                            servings, origCal, origSod, (originalJson.optDouble("protein_g", 0.0) / servings), origPot, origCarb
                        )
                        txtOriginalStats.setTextColor(originalEvalResult.textColor)

                        // Adjusted Card
                        cardAdjustedHealthGrade.setCardBackgroundColor(adjustedEvalResult.bgColor)
                        txtAdjustedGradeTitle.text = "Adjusted: ${adjustedEvalResult.gradeTitle}"
                        txtAdjustedGradeTitle.setTextColor(adjustedEvalResult.textColor)
                        val adjustedViolations = if (adjustedEvalResult.redViolations.isNotEmpty() || adjustedEvalResult.yellowViolations.isNotEmpty()) {
                            (adjustedEvalResult.redViolations.distinct() + adjustedEvalResult.yellowViolations.distinct()).joinToString(", ")
                        } else {
                            "None (Compliant)"
                        }
                        txtAdjustedViolations.text = "Violations:\n$adjustedViolations"
                        txtAdjustedViolations.setTextColor(adjustedEvalResult.textColor)

                        val adjCal = adjustedJson.optDouble("calories", 0.0) / servings
                        val adjSod = adjustedJson.optDouble("sodium_mg", 0.0) / servings
                        val adjPot = adjustedJson.optDouble("potassium_mg", 0.0) / servings
                        val adjCarb = (adjustedJson.optDouble("total_carbohydrates_g", 0.0) - adjustedJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings

                        // Adjusted Card Stats Display (Dynamically matches evaluation layout text color)
                        txtAdjustedStats.text = String.format(
                            Locale.ROOT,
                            "Per Serving (%d Servings):\nCal: %.0f | Sod: %.0fmg | Prot: %.1fg\nPot: %.0fmg | Carbs: %.1fg",
                            servings, adjCal, adjSod, (adjustedJson.optDouble("protein_g", 0.0) / servings), adjPot, adjCarb
                        )
                        txtAdjustedStats.setTextColor(adjustedEvalResult.textColor)
                    }

                    // Display final clinical recipe ingredients output and enable secondary step buttons
                    cardAdjustedResult.visibility = View.VISIBLE
                    txtAdjustedOutput.text = "ADJUSTED INGREDIENTS:\n$adjustedIngredients"

                    // Reveal on-demand actions layout
                    layoutPostAnalysisActions.visibility = View.VISIBLE

                    btnCopyAdjusted.setOnClickListener {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Adjusted Ingredients", adjustedIngredients)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(requireContext(), "Adjusted ingredients copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "JSON Parse Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                val displayError = lastDiagnosticError ?: "Unknown connection error"
                Toast.makeText(requireContext(), "Server Error: $displayError", Toast.LENGTH_LONG).show()
            }

            btnAdjustRecipe.isEnabled = true
            btnAdjustRecipe.text = "Analyze & Substitute"
        }
    }

    private suspend fun fetchRecipeNutritionFromBackend(
        recipeText: String,
        activeProfiles: Set<String>
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(BACKEND_ANALYSIS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("recipe_text", recipeText)
                put("active_profiles", JSONArray(activeProfiles.toList()))
                // Bypass warmed container cache to guarantee fresh, uncached USDA matches
                put("bypass_cache", true)

                val xmlProfilesObj = JSONObject()
                val context = requireContext()
                for (profileId in activeProfiles) {
                    val xmlContent = getXmlProfileContents(context, profileId)
                    if (xmlContent != null) {
                        xmlProfilesObj.put(profileId, xmlContent)
                    }
                }
                put("xml_profiles", xmlProfilesObj)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                lastDiagnosticError = "HTTP Response ${conn.responseCode}: $errorText"
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastDiagnosticError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    // ===================================================================================
    // FUNCTION 2: RETRIEVE STEP-BY-STEP INSTRUCTIONS ON-DEMAND (LIGHTWEIGHT API CALL)
    // ===================================================================================
    private fun retrieveStepByStepInstructions() {
        if (lastAdjustedIngredients.isBlank()) return

        btnRetrieveSteps.isEnabled = false
        btnRetrieveSteps.text = "Fetching steps..."

        val currentText = txtAdjustedOutput.text.toString()
        txtAdjustedOutput.text = "$currentText\n\nRetrieving step-by-step instructions from engine..."

        lifecycleScope.launch {
            val prompt = """
                You are a professional chef. Given these clinically safe ingredients list, write step-by-step cooking and preparation instructions:
                $lastAdjustedIngredients

                Formatting:
                - Output ONLY clean, numbered cooking steps (e.g., "1. Sift dry elements...\n2. Mix liquid elements...").
                - Do NOT include preambles, greetings, or notes.
            """.trimIndent()

            val response = fetchPlainTextFromGemini(prompt)
            if (response != null) {
                val updatedInstructions = "$currentText\n\nSTEP-BY-STEP PREPARATION:\n$response"
                txtAdjustedOutput.text = updatedInstructions

                btnRetrieveSteps.text = "Steps Loaded"
                btnCopyAdjusted.setOnClickListener {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Recipe & Instructions", updatedInstructions)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), "Full recipe and instructions copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
            } else {
                txtAdjustedOutput.text = "$currentText\n\nFailed to retrieve step instructions. Please check network connection."
                btnRetrieveSteps.isEnabled = true
                btnRetrieveSteps.text = "Get Steps"
            }
        }
    }

    // ===================================================================================
    // FUNCTION 3: RETRIEVE ANALYSIS DATA & RATIONALE ON-DEMAND (LIGHTWEIGHT API CALL)
    // ===================================================================================
    private fun retrieveAnalysisData() {
        if (lastOriginalIngredients.isBlank() || lastAdjustedIngredients.isBlank()) return

        btnRetrieveAnalysis.isEnabled = false
        btnRetrieveAnalysis.text = "Analyzing..."

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()
        val currentText = txtAdjustedOutput.text.toString()
        txtAdjustedOutput.text = "$currentText\n\nCalculating clinical rationale details..."

        lifecycleScope.launch {
            val prompt = """
                You are an expert clinical dietitian. 
                Explain why the original ingredients were changed to the adjusted ingredients under these active clinical constraints: ${activeProfiles.joinToString(", ")}.

                Original:
                $lastOriginalIngredients

                Adjusted:
                $lastAdjustedIngredients

                Formatting:
                - Output ONLY a clean bulleted explanation of why each clinical adjustment was made.
                - Keep explanations brief and direct (e.g., "- Standard flour replaced with almond flour to reduce carb content for Keto constraints.").
                - Do NOT output generic introductions or preambles.
            """.trimIndent()

            val response = fetchPlainTextFromGemini(prompt)
            if (response != null) {
                val updatedWithAnalysis = "$currentText\n\nCLINICAL SUBSTITUTION RATIONALE:\n$response"
                txtAdjustedOutput.text = updatedWithAnalysis

                btnRetrieveAnalysis.text = "Analysis Loaded"
                btnCopyAdjusted.setOnClickListener {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Recipe with Analysis", updatedWithAnalysis)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), "Full recipe and analysis copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
            } else {
                txtAdjustedOutput.text = "$currentText\n\nFailed to retrieve clinical rationale data."
                btnRetrieveAnalysis.isEnabled = true
                btnRetrieveAnalysis.text = "Get Analysis"
            }
        }
    }

    private suspend fun fetchPlainTextFromGemini(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.GEMINI_API_KEY.replace("\"", "").trim()
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true

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
                parts.getJSONObject(0).getString("text").trim()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}