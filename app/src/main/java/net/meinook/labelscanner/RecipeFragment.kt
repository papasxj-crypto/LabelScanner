package net.meinook.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
    private lateinit var txtAdjustedRecipeHeader: TextView
    private lateinit var btnCopyAdjusted: MaterialButton

    // Input Selector Views
    private lateinit var toggleInputMode: MaterialButtonToggleGroup
    private lateinit var tilRecipeInput: TextInputLayout
    private lateinit var layoutOcrActions: View
    private lateinit var btnSelectPhoto: MaterialButton

    // Phase 2 & 3 On-Demand Views
    private lateinit var layoutPostAnalysisActions: View
    private lateinit var btnRetrieveSteps: MaterialButton
    private lateinit var btnRetrieveAnalysis: MaterialButton

    // State trackers
    private var lastOriginalIngredients: String = ""
    private var lastAdjustedIngredients: String = ""
    private var lastDiagnosticError: String? = null
    private var currentInputModeId: Int = R.id.btnModeText
    private var activeHistoryId: String? = null

    // Class properties to capture exact metadata states
    private var scrapedRecipeTitle: String? = null
    private var scrapedRecipeUrl: String? = null

    // Temporary Uri to hold the captured high-resolution camera image
    private var tempImageUri: Uri? = null

    // Register camera intent contract
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { runOnDeviceOcr(it) }
        } else {
            Toast.makeText(context, "Photo capture canceled", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper to generate a secure temp file Uri matching your files-path XML config
    private fun getTmpFileUri(): Uri {
        val tmpFile = java.io.File.createTempFile("camera_capture_", ".jpg", requireContext().filesDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(
            requireContext(),
            "net.meinook.labelscanner.fileprovider",
            tmpFile
        )
    }

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

        layoutGradeComparison = root.findViewById(R.id.layoutGradeComparison)
        cardOriginalHealthGrade = root.findViewById(R.id.cardOriginalHealthGrade)
        txtOriginalGradeTitle = root.findViewById(R.id.txtOriginalGradeTitle)
        txtOriginalViolations = root.findViewById(R.id.txtOriginalViolations)
        txtOriginalStats = root.findViewById(R.id.txtOriginalStats)

        cardAdjustedHealthGrade = root.findViewById(R.id.cardAdjustedHealthGrade)
        txtAdjustedGradeTitle = root.findViewById(R.id.txtAdjustedGradeTitle)
        txtAdjustedViolations = root.findViewById(R.id.txtAdjustedViolations)
        txtAdjustedStats = root.findViewById(R.id.txtAdjustedStats)

        cardAdjustedResult = root.findViewById(R.id.cardAdjustedResult)
        txtAdjustedOutput = root.findViewById(R.id.txtAdjustedOutput)
        txtAdjustedRecipeHeader = root.findViewById(R.id.txtAdjustedRecipeHeader)
        btnCopyAdjusted = root.findViewById(R.id.btnCopyAdjusted)

        toggleInputMode = root.findViewById(R.id.toggleInputMode)
        tilRecipeInput = root.findViewById(R.id.tilRecipeInput)
        layoutOcrActions = root.findViewById(R.id.layoutOcrActions)
        btnSelectPhoto = root.findViewById(R.id.btnSelectPhoto)

        layoutPostAnalysisActions = root.findViewById(R.id.layoutPostAnalysisActions)
        btnRetrieveSteps = root.findViewById(R.id.btnRetrieveSteps)
        btnRetrieveAnalysis = root.findViewById(R.id.btnRetrieveAnalysis)

        val btnModeText: MaterialButton = root.findViewById(R.id.btnModeText)

        // Joint focus & click listeners immediately clear stale cards on the very first touch [Focus Bug Fix]
        edtRecipeInput.setOnClickListener { clearAnalysisResults() }
        edtRecipeInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) clearAnalysisResults() }

        // Clicking "Text / Link" now clears the entire slate instantly for a fresh start [3]
        btnModeText.setOnClickListener {
            edtRecipeInput.text = null
            clearAnalysisResults()
            activeHistoryId = null // Reset overwrite ID
            scrapedRecipeTitle = null
            scrapedRecipeUrl = null
            tilRecipeInput.helperText = null
            tilRecipeInput.hint = "Paste recipe lines or website link here" // Reset hint
            toggleInputMode.check(R.id.btnModeText)
            switchInputInterface(R.id.btnModeText)
        }

        toggleInputMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) switchInputInterface(checkedId)
        }

        btnSelectPhoto.setOnClickListener {
            try {
                // Generate the secure Uri and launch the camera directly
                tempImageUri = getTmpFileUri()
                takePicture.launch(tempImageUri)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to launch camera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        btnAdjustRecipe.setOnClickListener { handleActionSubmit() }
        btnRetrieveSteps.setOnClickListener { retrieveStepByStepInstructions() }
        btnRetrieveAnalysis.setOnClickListener { retrieveAnalysisData() }

        // --- RESTORE FULL PAYLOADS FROM THE HISTORY JOURNAL [1, 1.1.2, 1.1.5] ---
        arguments?.getString("RECIPE_INPUT")?.let { recipeText ->
            edtRecipeInput.setText(recipeText)
            tilRecipeInput.hint = "Touch to edit" // Hint remains set [3]
        }

        arguments?.getString("RECIPE_URL")?.let { url ->
            if (url.isNotBlank()) {
                scrapedRecipeUrl = url

                // CRITICAL FIX: Only overwrite the input edit text with the URL if we are NOT restoring an already adjusted recipe snapshot!
                val originalGrade = arguments?.getString("ORIGINAL_GRADE")
                if (originalGrade.isNullOrEmpty()) {
                    edtRecipeInput.setText(url)
                    toggleInputMode.check(R.id.btnModeText)
                    switchInputInterface(R.id.btnModeText)
                    tilRecipeInput.hint = "Touch to edit" // Hint remains set [3]
                }
            }
        }

        activeHistoryId = arguments?.getString("HISTORY_ITEM_ID")

        // Offline History Reconstruction UI hydration
        val originalGrade = arguments?.getString("ORIGINAL_GRADE")
        if (!originalGrade.isNullOrEmpty()) {
            layoutGradeComparison.visibility = View.VISIBLE

            val savedTitle = arguments?.getString("RECIPE_TITLE")
            if (!savedTitle.isNullOrEmpty()) {
                txtAdjustedRecipeHeader.text = "Adjusted: $savedTitle"
            } else {
                txtAdjustedRecipeHeader.text = "Adjusted Recipe"
            }

            val origBg = arguments?.getInt("ORIGINAL_BG_COLOR") ?: 0
            val origTextCol = arguments?.getInt("ORIGINAL_TEXT_COLOR") ?: 0
            cardOriginalHealthGrade.setCardBackgroundColor(origBg)
            txtOriginalGradeTitle.text = "Original: $originalGrade"
            txtOriginalGradeTitle.setTextColor(origTextCol)
            txtOriginalViolations.text = arguments?.getString("ORIGINAL_VIOLATIONS")
            txtOriginalViolations.setTextColor(origTextCol)
            txtOriginalStats.text = arguments?.getString("ORIGINAL_STATS")
            txtOriginalStats.setTextColor(origTextCol)

            val adjustedGrade = arguments?.getString("ADJUSTED_GRADE")
            val adjBg = arguments?.getInt("ADJUSTED_BG_COLOR") ?: 0
            val adjTextCol = arguments?.getInt("ADJUSTED_TEXT_COLOR") ?: 0
            cardAdjustedHealthGrade.setCardBackgroundColor(adjBg)
            txtAdjustedGradeTitle.text = "Adjusted: $adjustedGrade"
            txtAdjustedGradeTitle.setTextColor(adjTextCol)
            txtAdjustedViolations.text = arguments?.getString("ADJUSTED_VIOLATIONS")
            txtAdjustedViolations.setTextColor(adjTextCol)
            txtAdjustedStats.text = arguments?.getString("ADJUSTED_STATS")
            txtAdjustedStats.setTextColor(adjTextCol)

            cardAdjustedResult.visibility = View.VISIBLE
            val adjustedOutput = arguments?.getString("RECIPE_ADJUSTED_OUTPUT") ?: ""
            txtAdjustedOutput.text = "ADJUSTED INGREDIENTS:\n$adjustedOutput"
            layoutPostAnalysisActions.visibility = View.VISIBLE

            // Track state mappings for dynamic step / rationale button logic
            lastOriginalIngredients = arguments?.getString("RECIPE_INPUT") ?: ""
            lastAdjustedIngredients = adjustedOutput

            btnRetrieveSteps.isEnabled = true
            btnRetrieveSteps.text = "Get Steps"
            btnRetrieveAnalysis.isEnabled = true
            btnRetrieveAnalysis.text = "Get Analysis"

            scrapedRecipeUrl?.let { url ->
                if (url.isNotEmpty()) {
                    tilRecipeInput.helperText = "Source Link: $url"
                }
            }
        }

        // Prune after loading to prevent duplicate triggers on orientation change [2]
        arguments?.remove("RECIPE_INPUT")
        arguments?.remove("RECIPE_URL")
        arguments?.remove("HISTORY_ITEM_ID")
        arguments?.remove("RECIPE_TITLE")
        arguments?.remove("ORIGINAL_GRADE")
        arguments?.remove("ORIGINAL_VIOLATIONS")
        arguments?.remove("ORIGINAL_STATS")
        arguments?.remove("ORIGINAL_BG_COLOR")
        arguments?.remove("ORIGINAL_TEXT_COLOR")
        arguments?.remove("ADJUSTED_GRADE")
        arguments?.remove("ADJUSTED_VIOLATIONS")
        arguments?.remove("ADJUSTED_STATS")
        arguments?.remove("ADJUSTED_BG_COLOR")
        arguments?.remove("ADJUSTED_TEXT_COLOR")

        return root
    }

    private fun switchInputInterface(checkedId: Int) {
        if (checkedId == currentInputModeId) return
        currentInputModeId = checkedId
        clearAnalysisResults()

        when (checkedId) {
            R.id.btnModeText -> {
                tilRecipeInput.visibility = View.VISIBLE
                layoutOcrActions.visibility = View.GONE
                btnAdjustRecipe.text = "Profile & Adjust"
                tilRecipeInput.hint = "Paste recipe lines or website link here"
            }
            R.id.btnModeOcr -> {
                tilRecipeInput.visibility = View.GONE
                layoutOcrActions.visibility = View.VISIBLE
                btnAdjustRecipe.text = "Profile & Adjust"
            }
        }
    }

    private fun clearAnalysisResults() {
        layoutGradeComparison.visibility = View.GONE
        cardAdjustedResult.visibility = View.GONE
        layoutPostAnalysisActions.visibility = View.GONE
        txtAdjustedOutput.text = ""
        txtAdjustedRecipeHeader.text = "Adjusted Recipe"
        tilRecipeInput.helperText = null

        // Safety: Do NOT reset activeHistoryId here so that edits within the same text session preserve the overwrite ID [1.1.5]
        lastOriginalIngredients = ""
        lastAdjustedIngredients = ""
        lastDiagnosticError = null

        btnRetrieveSteps.isEnabled = true
        btnRetrieveSteps.text = "Get Steps"
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"
    }

    private fun handleActionSubmit() {
        when (toggleInputMode.checkedButtonId) {
            R.id.btnModeText -> {
                val rawText = edtRecipeInput.text?.toString()?.trim() ?: ""
                if (rawText.isNotBlank()) {
                    // Client intercepts pasted URL and diverts to optimized scraper cleanly
                    if (rawText.startsWith("http://", ignoreCase = true) || rawText.startsWith("https://", ignoreCase = true)) {
                        scrapeWebpageContents(rawText)
                    } else {
                        analyzeRecipeAndAdjust(rawText)
                    }
                } else {
                    Toast.makeText(context, "Please enter some recipe text or paste a link first.", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.btnModeOcr -> {
                Toast.makeText(context, "Please select a photo first to import text.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim()
        if (cleanUrl.contains("?")) cleanUrl = cleanUrl.substringBefore("?")
        if (cleanUrl.endsWith("/print/")) cleanUrl = cleanUrl.removeSuffix("print/")
        else if (cleanUrl.endsWith("/print")) cleanUrl = cleanUrl.removeSuffix("print")
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
                    val finalOutput = if (ingredientsText.isNotBlank()) ingredientsText else cleanHtmlToText(rawHtml)

                    withContext(Dispatchers.Main) {
                        btnAdjustRecipe.isEnabled = true
                        btnAdjustRecipe.text = "Profile & Adjust"

                        if (finalOutput.isNotBlank()) {
                            scrapedRecipeUrl = canonicalUrl
                            tilRecipeInput.helperText = "Source Link: $canonicalUrl"
                            edtRecipeInput.setText(finalOutput)
                            toggleInputMode.check(R.id.btnModeText)
                            tilRecipeInput.hint = "Touch to edit" // Set dynamic hint [3]
                            Toast.makeText(context, "Ingredients imported! Review below, then click Analyze.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Could not extract readable ingredients from that URL.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnAdjustRecipe.isEnabled = true
                    btnAdjustRecipe.text = "Profile & Adjust"
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

        // Lock button actions while running local OCR extraction [1]
        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Reading image..."

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                if (rawText.isNotBlank()) {
                    // Send to background clean-up flow
                    cleanOcrIngredientsWithGemini(rawText)
                } else {
                    btnAdjustRecipe.isEnabled = true
                    btnAdjustRecipe.text = "Profile & Adjust"
                    Toast.makeText(context, "No text found inside the photo.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                btnAdjustRecipe.isEnabled = true
                btnAdjustRecipe.text = "Profile & Adjust"
                Toast.makeText(context, "OCR Processing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    // Handles async call to filter noisy UI buttons, table items, and ads out of OCR [1]
    private fun cleanOcrIngredientsWithGemini(rawText: String) {
        val context = context ?: return
        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Isolating ingredients..."

        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            val modelId = getString(R.string.model_identifier_txt)

            val cleaned = fetchCleanedIngredientsFromGemini(rawText, apiKey, modelId)

            withContext(Dispatchers.Main) {
                btnAdjustRecipe.isEnabled = true
                btnAdjustRecipe.text = "Profile & Adjust"

                if (!cleaned.isNullOrBlank()) {
                    edtRecipeInput.setText(cleaned)
                    toggleInputMode.check(R.id.btnModeText)
                    switchInputInterface(R.id.btnModeText)
                    tilRecipeInput.hint = "Touch to edit"
                    Toast.makeText(context, "Isolated ingredient list successfully!", Toast.LENGTH_LONG).show()
                } else {
                    // Fail gracefully back to raw OCR text if network/API key fails [1]
                    edtRecipeInput.setText(rawText)
                    toggleInputMode.check(R.id.btnModeText)
                    switchInputInterface(R.id.btnModeText)
                    tilRecipeInput.hint = "Touch to edit"
                    Toast.makeText(context, "Showing raw OCR text (auto-isolation failed).", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Connects directly to Gemini REST API endpoints for quick, lightweight semantic text extraction [1]
    private suspend fun fetchCleanedIngredientsFromGemini(
        rawText: String,
        apiKey: String,
        modelId: String
    ): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
            }

            val systemPrompt = """
                You are a precise culinary extraction assistant. Analyze the raw OCR text extracted from a recipe photo or webpage screenshot.
                Extract ONLY the ingredient list.
                Strictly discard:
                - Webpage advertisements, cooking tips, preps, buttons, keyboard/UI artifacts, or desk/table clutter.
                - Recipe instructions, cooking steps, or preparation guidelines.
                - Conversational introductions or story text.
                
                Format the response ONLY as a clean list of ingredients, with one ingredient per line. Do not write any conversational filler, notes, or markdown formatting (such as backticks or bold headers).
            """.trimIndent()

            val jsonRequest = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "$systemPrompt\n\nRaw OCR Text:\n$rawText")
                            })
                        })
                    })
                })
            }

            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(jsonRequest.toString())
                    writer.flush()
                }
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                val responseJson = JSONObject(responseText)
                val candidates = responseJson.optJSONArray("candidates")
                val content = candidates?.optJSONObject(0)?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                parts?.optJSONObject(0)?.optString("text")?.trim()
            } else {
                val errorText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                Log.e("RECIPE_OCR_CLEAN", "Gemini HTTP $responseCode: $errorText")
                null
            }
        } catch (e: Exception) {
            Log.e("RECIPE_OCR_CLEAN", "Exception during Gemini OCR cleanup: ${e.message}", e)
            null
        } finally {
            conn?.disconnect()
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

                    val rootToken = if (sanitized.startsWith("[")) JSONArray(sanitized) else JSONObject(sanitized)
                    val recipeObj = findRecipeObject(rootToken)
                    if (recipeObj != null) {
                        scrapedRecipeTitle = recipeObj.optString("name", "").ifEmpty { null }
                        val ingredientsText = formatRecipeIngredients(recipeObj)
                        if (ingredientsText.isNotBlank()) return ingredientsText
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

        val start = if (firstBrace != -1 && firstBracket != -1) Math.min(firstBrace, firstBracket) else Math.max(firstBrace, firstBracket)
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
                    if (cleanText.isNotBlank()) extractedList.add(cleanText)
                }
            }

            if (extractedList.isNotEmpty()) return extractedList.joinToString("\n")
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
                    if (i == shakerPosition) track.append("🌱") else track.append("·")
                    if (i < trackLength - 1) track.append(" ")
                }

                btnAdjustRecipe.text = "$phase$track"

                delay(1000)
                elapsedSeconds++
                shakerPosition = (shakerPosition + 1) % trackLength
            }
        }
    }

    private fun generateTitleFromIngredients(ingredientsText: String): String {
        val lines = ingredientsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val primaryIngredients = mutableListOf<String>()

        val ignoreWords = setOf(
            "lange", "long", "short",
            "large", "medium", "small", "tiny", "mini", "jumbo", "giant", "thin", "thick", "whole", "half", "halves", "quarter", "quarters",
            "teaspoon", "teaspoons", "tablespoon", "tablespoons", "cup", "cups", "tbsp", "tsp", "gram", "grams", "ounce", "ounces", "oz",
            "pound", "pounds", "lb", "lbs", "ml", "pinch", "pinches", "dash", "dashes", "drop", "drops", "can", "cans", "bottle", "bottles",
            "jar", "jars", "bag", "bags", "pack", "packs", "package", "packages", "pkg", "pkgs", "box", "boxes", "carton", "cartons",
            "container", "containers", "slice", "slices", "piece", "pieces", "bunch", "bunches", "head", "heads", "stalk", "stalks",
            "clove", "cloves", "sprig", "sprigs", "leaf", "leaves", "handful", "handfuls", "g", "tbsp.", "tsp.", "tbs", "tbs.", "tea", "table",
            "breast", "breasts", "thigh", "thighs", "wing", "wings", "fillet", "fillets", "steak", "steaks", "chop", "chops", "cut", "cuts",
            "fresh", "raw", "cooked", "ground", "powder", "chopped", "diced", "sliced", "minced", "melted", "softened", "frozen", "chilled",
            "refrigerated", "dried", "dry", "canned", "baked", "roasted", "grilled", "fried", "shredded", "grated", "crushed", "mashed",
            "pureed", "sweet", "savory", "warm", "cold", "hot", "boiled", "stewed", "steamed", "toasted", "peeled", "seeded",
            "of", "and", "or", "with", "for", "in", "salt", "water", "oil", "pepper", "sugar", "butter", "flour", "milk", "egg", "eggs",
            "extract", "sauce", "white", "black", "red", "green", "yellow", "blue", "organic", "all-purpose", "unbleached"
        )

        for (line in lines) {
            val clean = line.lowercase()
                .replace(Regex("[^a-zA-Z\\s]"), "")
                .split("\\s+".toRegex())
                .map { it.trim() }
                .filter { it.isNotEmpty() && !ignoreWords.contains(it) }

            if (clean.isNotEmpty()) {
                val item = clean.take(2).joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
                if (item.isNotEmpty() && !primaryIngredients.contains(item)) {
                    primaryIngredients.add(item)
                }
            }
            if (primaryIngredients.size >= 2) break
        }

        return if (primaryIngredients.isNotEmpty()) {
            primaryIngredients.joinToString(" & ") + " Adjust"
        } else {
            "Custom Recipe Adjust"
        }
    }

    private fun analyzeRecipeAndAdjust(rawText: String) {
        btnAdjustRecipe.isEnabled = false
        lastDiagnosticError = null

        cardAdjustedResult.visibility = View.VISIBLE
        txtAdjustedOutput.text = "Substituting ingredients and evaluating clinical profiles... Please wait (usually takes 30-35 seconds)."
        layoutGradeComparison.visibility = View.GONE
        layoutPostAnalysisActions.visibility = View.GONE

        btnRetrieveSteps.isEnabled = true
        btnRetrieveSteps.text = "Get Steps"
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()
        val progressJob = startAnalysisProgressUpdates()

        lifecycleScope.launch {
            val responseString = fetchRecipeNutritionFromBackend(rawText, activeProfiles)
            progressJob.cancel()

            if (responseString != null) {
                try {
                    val parsedResult = JSONObject(responseString)
                    val servings = parsedResult.optInt("servings", 6).coerceAtLeast(1)
                    val adjustedIngredients = parsedResult.optString("adjusted_ingredients", "")

                    lastOriginalIngredients = rawText
                    lastAdjustedIngredients = adjustedIngredients

                    val dynamicXmlTriggers = mutableMapOf<String, String>()
                    for (profileId in activeProfiles) {
                        val triggers = userSettings.getIngredientsFromAssetFile(profileId)
                        for (trigger in triggers) {
                            dynamicXmlTriggers[trigger.uppercase(Locale.ROOT).trim()] = profileId
                        }
                    }

                    val originalIngredientsList = rawText.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    val adjustedIngredientsList = adjustedIngredients.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

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
                            detectedIngredients = originalIngredientsList,
                            savedProfileIds = activeProfiles,
                            userSettings = userSettings,
                            xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                            customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                            customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                            isProduce = false
                        )
                    } else null

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
                            detectedIngredients = adjustedIngredientsList,
                            savedProfileIds = activeProfiles,
                            userSettings = userSettings,
                            xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                            customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                            customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                            isProduce = false
                        )
                    } else null

                    var originalViolationsText = "None"
                    var originalStatsText = ""
                    var adjustedViolationsText = "None (Compliant)"
                    var adjustedStatsText = ""

                    if (originalEvalResult != null && adjustedEvalResult != null && originalJson != null && adjustedJson != null) {
                        layoutGradeComparison.visibility = View.VISIBLE

                        cardOriginalHealthGrade.setCardBackgroundColor(originalEvalResult.bgColor)
                        txtOriginalGradeTitle.text = "Original: ${originalEvalResult.gradeTitle}"
                        txtOriginalGradeTitle.setTextColor(originalEvalResult.textColor)

                        val originalReds = originalEvalResult.redViolations.distinct()
                        val originalYellows = originalEvalResult.yellowViolations.distinct()
                        val originalViosToDisplay = if (originalReds.isNotEmpty()) originalReds.take(3) else originalYellows.take(3)
                        originalViolationsText = if (originalViosToDisplay.isNotEmpty()) {
                            originalViosToDisplay.joinToString("\n• ", prefix = "• ")
                        } else {
                            "None"
                        }
                        txtOriginalViolations.text = "Violations:\n$originalViolationsText"
                        txtOriginalViolations.setTextColor(originalEvalResult.textColor)

                        val origCal = originalJson.optDouble("calories", 0.0) / servings
                        val origSod = originalJson.optDouble("sodium_mg", 0.0) / servings
                        val origPot = originalJson.optDouble("potassium_mg", 0.0) / servings
                        val origCarb = (originalJson.optDouble("total_carbohydrates_g", 0.0) - originalJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
                        val origProt = originalJson.optDouble("protein_g", 0.0) / servings

                        originalStatsText = String.format(
                            Locale.ROOT,
                            "Per Serving (%d Servings):\nCal: %.0f | Sod: %.0fmg | Prot: %.1fg\nPot: %.0fmg | Carbs: %.1fg",
                            servings, origCal, origSod, origProt, origPot, origCarb
                        )
                        txtOriginalStats.text = originalStatsText
                        txtOriginalStats.setTextColor(originalEvalResult.textColor)

                        cardAdjustedHealthGrade.setCardBackgroundColor(adjustedEvalResult.bgColor)
                        txtAdjustedGradeTitle.text = "Adjusted: ${adjustedEvalResult.gradeTitle}"
                        txtAdjustedGradeTitle.setTextColor(adjustedEvalResult.textColor)

                        val adjustedReds = adjustedEvalResult.redViolations.distinct()
                        val adjustedYellows = adjustedEvalResult.yellowViolations.distinct()
                        val adjustedViosToDisplay = if (adjustedReds.isNotEmpty()) adjustedReds.take(3) else adjustedYellows.take(3)
                        adjustedViolationsText = if (adjustedViosToDisplay.isNotEmpty()) {
                            adjustedViosToDisplay.joinToString("\n• ", prefix = "• ")
                        } else {
                            "None (Compliant)"
                        }
                        txtAdjustedViolations.text = "Violations:\n$adjustedViolationsText"
                        txtAdjustedViolations.setTextColor(adjustedEvalResult.textColor)

                        val adjCal = adjustedJson.optDouble("calories", 0.0) / servings
                        val adjSod = adjustedJson.optDouble("sodium_mg", 0.0) / servings
                        val adjPot = adjustedJson.optDouble("potassium_mg", 0.0) / servings
                        val adjCarb = (adjustedJson.optDouble("total_carbohydrates_g", 0.0) - adjustedJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
                        val adjProt = adjustedJson.optDouble("protein_g", 0.0) / servings

                        adjustedStatsText = String.format(
                            Locale.ROOT,
                            "Per Serving (%d Servings):\nCal: %.0f | Sod: %.0fmg | Prot: %.1fg\nPot: %.0fmg | Carbs: %.1fg",
                            servings, adjCal, adjSod, adjProt, adjPot, adjCarb
                        )
                        txtAdjustedStats.text = adjustedStatsText
                        txtAdjustedStats.setTextColor(adjustedEvalResult.textColor)
                    }

                    cardAdjustedResult.visibility = View.VISIBLE
                    txtAdjustedOutput.text = "ADJUSTED INGREDIENTS:\n$adjustedIngredients"
                    layoutPostAnalysisActions.visibility = View.VISIBLE

                    val saverId = activeHistoryId ?: System.currentTimeMillis().toString()

                    val dynamicTitle = scrapedRecipeTitle
                        ?: parsedResult.optString("recipe_name", "").ifEmpty { null }
                        ?: parsedResult.optString("title", "").ifEmpty { null }
                        ?: parsedResult.optString("name", "").ifEmpty { null }
                        ?: run {
                            val firstLine = rawText.lines().firstOrNull()?.trim() ?: ""
                            val ingredientKeywords = listOf("cup", "tbsp", "tsp", "gram", "spoon", "oz", "pound", "lb", "ml", "slice", "clove", "can ", "pkg", "package", "pinch", "dash")
                            val cleanFirstLine = firstLine.lowercase()
                            val isLikelyIngredient = cleanFirstLine.firstOrNull()?.isDigit() == true ||
                                    ingredientKeywords.any { cleanFirstLine.contains(it) } ||
                                    firstLine.startsWith("1/") || firstLine.startsWith("2/") || firstLine.startsWith("3/") || firstLine.startsWith("4/")

                            if (firstLine.isNotEmpty() && firstLine.length <= 50 && !isLikelyIngredient) {
                                firstLine
                            } else {
                                generateTitleFromIngredients(rawText)
                            }
                        }

                    txtAdjustedRecipeHeader.text = "Adjusted: $dynamicTitle"

                    val historyItem = HistoryItem(
                        id = saverId,
                        timestamp = System.currentTimeMillis(),
                        title = dynamicTitle,
                        subtitle = "Tweak: ${adjustedIngredients.lines().filter { it.isNotBlank() }.take(2).joinToString(", ")}",
                        originalGrade = originalEvalResult?.gradeTitle ?: "Unknown",
                        adjustedGrade = adjustedEvalResult?.gradeTitle ?: "Unknown",
                        activeProfiles = activeProfiles.joinToString(", ") { id ->
                            userSettings.getAvailableDietProfiles().find { it.id == id }?.displayName ?: id
                        },
                        sourceUrl = scrapedRecipeUrl ?: "",
                        originalInput = rawText,
                        adjustedOutput = adjustedIngredients,
                        itemType = "RECIPE",
                        originalViolations = originalViolationsText,
                        originalStats = originalStatsText,
                        adjustedViolations = adjustedViolationsText,
                        adjustedStats = adjustedStatsText,
                        originalBgColor = originalEvalResult?.bgColor ?: 0,
                        originalTextColor = originalEvalResult?.textColor ?: 0,
                        adjustedBgColor = adjustedEvalResult?.bgColor ?: 0,
                        adjustedTextColor = adjustedEvalResult?.textColor ?: 0
                    )
                    HistoryFragment.saveHistoryItem(requireContext(), historyItem)

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
            btnAdjustRecipe.text = "Profile & Adjust"
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

            // Connection and Read timeouts increased to bypass serverless cold starts gracefully
            conn.connectTimeout = 30000
            conn.readTimeout = 90000
            conn.doOutput = true

            val context = requireContext()
            val userSettings = AppSettings(context)

            // Extract custom red watchlist terms as dynamic allergy checkers on the engine
            val userAllergiesList = try {
                userSettings.getCustomWatchlist("RED").toList()
            } catch (e: Exception) {
                emptyList<String>()
            }

            // Build dynamic threshold objects matching exactly the frontend simplified mapping schema
            val thresholdsObj = JSONObject().apply {
                put("potassium", JSONObject().apply {
                    put("yellow_warning", 200.0)
                    put("red_limit", 350.0)
                })
                put("sodium", JSONObject().apply {
                    put("yellow_warning", 140.0)
                    put("red_limit", 300.0)
                })
                put("protein", JSONObject().apply {
                    put("yellow_warning", 10.0)
                    put("red_limit", 15.0)
                })
            }

            val jsonRequest = JSONObject().apply {
                put("action", "analyze")
                put("recipe_text", recipeText)
                put("conditions", JSONArray(activeProfiles.toList()))
                put("allergies", JSONArray(userAllergiesList))
                put("thresholds", thresholdsObj)
                put("bypass_cache", true)
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

    private fun retrieveStepByStepInstructions() {
        if (lastAdjustedIngredients.isBlank()) return

        btnRetrieveSteps.isEnabled = false
        btnRetrieveSteps.text = "Fetching steps..."

        val currentText = txtAdjustedOutput.text.toString()
        txtAdjustedOutput.text = "$currentText\n\nRetrieving step-by-step instructions from engine..."

        lifecycleScope.launch {
            val requestBody = JSONObject().apply {
                put("action", "steps")
                put("adjusted_ingredients", lastAdjustedIngredients)
            }
            val responseString = fetchCustomActionFromBackend(requestBody)
            if (responseString != null) {
                try {
                    val jsonResponse = JSONObject(responseString)
                    val instructions = jsonResponse.optString("instructions", "")
                    if (instructions.isNotBlank()) {
                        val updatedInstructions = "$currentText\n\nSTEP-BY-STEP PREPARATION:\n$instructions"
                        txtAdjustedOutput.text = updatedInstructions

                        btnRetrieveSteps.text = "Steps Loaded"
                        btnCopyAdjusted.setOnClickListener {
                            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Recipe & Instructions", updatedInstructions)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(requireContext(), "Full recipe and instructions copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw Exception("Empty instructions returned")
                    }
                } catch (e: Exception) {
                    txtAdjustedOutput.text = "$currentText\n\nFailed to decode step instructions."
                    btnRetrieveSteps.isEnabled = true
                    btnRetrieveSteps.text = "Get Steps"
                }
            } else {
                txtAdjustedOutput.text = "$currentText\n\nFailed to retrieve step instructions. Please check network connection."
                btnRetrieveSteps.isEnabled = true
                btnRetrieveSteps.text = "Get Steps"
            }
        }
    }

    private fun retrieveAnalysisData() {
        if (lastOriginalIngredients.isBlank() || lastAdjustedIngredients.isBlank()) return

        btnRetrieveAnalysis.isEnabled = false
        btnRetrieveAnalysis.text = "Analyzing..."

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()
        val currentText = txtAdjustedOutput.text.toString()
        txtAdjustedOutput.text = "$currentText\n\nCalculating clinical rationale details..."

        lifecycleScope.launch {
            val requestBody = JSONObject().apply {
                put("action", "rationale")
                put("original_ingredients", lastOriginalIngredients)
                put("adjusted_ingredients", lastAdjustedIngredients)
                put("conditions", JSONArray(activeProfiles.toList()))
            }
            val responseString = fetchCustomActionFromBackend(requestBody)
            if (responseString != null) {
                try {
                    val jsonResponse = JSONObject(responseString)
                    val rationale = jsonResponse.optString("rationale", "")
                    if (rationale.isNotBlank()) {
                        val updatedWithAnalysis = "$currentText\n\nCLINICAL SUBSTITUTION RATIONALE:\n$rationale"
                        txtAdjustedOutput.text = updatedWithAnalysis

                        btnRetrieveAnalysis.text = "Analysis Loaded"
                        btnCopyAdjusted.setOnClickListener {
                            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Recipe with Analysis", updatedWithAnalysis)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(requireContext(), "Full recipe and analysis copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw Exception("Empty rationale returned")
                    }
                } catch (e: Exception) {
                    txtAdjustedOutput.text = "$currentText\n\nFailed to decode clinical rationale data."
                    btnRetrieveAnalysis.isEnabled = true
                    btnRetrieveAnalysis.text = "Get Analysis"
                }
            } else {
                txtAdjustedOutput.text = "$currentText\n\nFailed to retrieve clinical rationale data."
                btnRetrieveAnalysis.isEnabled = true
                btnRetrieveAnalysis.text = "Get Analysis"
            }
        }
    }

    private suspend fun fetchCustomActionFromBackend(requestBody: JSONObject): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(BACKEND_ANALYSIS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun fetchPlainTextFromGemini(prompt: String): String? = withContext(Dispatchers.IO) {
        // Obsoleted by secure multi-route actions! Client plain text generation routed dynamically over backend.
        return@withContext null
    }
}