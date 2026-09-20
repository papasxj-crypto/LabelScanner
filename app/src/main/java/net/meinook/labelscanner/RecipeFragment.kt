package net.meinook.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
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

    // DUAL-URL INJECTION COMPLIANT - NO HARDCODED "HTTPS://"
    private val backendAnalysisUrl by lazy {
        if (BuildConfig.DEBUG) {
            getString(R.string.dev_URL)
        } else {
            getString(R.string.production_URL)
        }
    }

    // Container Sections for 2-Phase State Machine
    private lateinit var layoutInputSection: LinearLayout
    private lateinit var layoutResultsSection: LinearLayout

    // State 1: Input Views
    private lateinit var edtRecipeInput: EditText
    private lateinit var btnAdjustRecipe: MaterialButton
    private lateinit var edtServingsInput: EditText
    private lateinit var btnPhotoImport: MaterialButton

    // State 2: Results Views
    private lateinit var btnNewRecipe: MaterialButton
    private lateinit var txtSourceLink: TextView

    // Horizontal Multi-Color Gauge Views
    private lateinit var cardClinicalGauge: MaterialCardView
    private lateinit var barSodiumOg: ClinicalGaugeBar
    private lateinit var txtSodiumOgVal: TextView
    private lateinit var barSodiumAdj: ClinicalGaugeBar
    private lateinit var txtSodiumAdjVal: TextView

    private lateinit var barPotassiumOg: ClinicalGaugeBar
    private lateinit var txtPotassiumOgVal: TextView
    private lateinit var barPotassiumAdj: ClinicalGaugeBar
    private lateinit var txtPotassiumAdjVal: TextView

    private lateinit var barCarbsOg: ClinicalGaugeBar
    private lateinit var txtCarbsOgVal: TextView
    private lateinit var barCarbsAdj: ClinicalGaugeBar
    private lateinit var txtCarbsAdjVal: TextView

    private lateinit var barProteinOg: ClinicalGaugeBar
    private lateinit var txtProteinOgVal: TextView
    private lateinit var barProteinAdj: ClinicalGaugeBar
    private lateinit var txtProteinAdjVal: TextView

    // Output Views
    private lateinit var cardAdjustedResult: MaterialCardView
    private lateinit var progressAnalysisIndicator: LinearProgressIndicator
    private lateinit var txtAdjustedOutput: TextView
    private lateinit var txtAdjustedRecipeHeader: TextView
    private lateinit var btnCopyAdjusted: MaterialButton
    private lateinit var btnReportAdjusted: MaterialButton

    private lateinit var layoutPostAnalysisActions: View
    private lateinit var btnRetrieveSteps: MaterialButton
    private lateinit var btnRetrieveAnalysis: MaterialButton

    // Collapsible Original Drawer Views
    private lateinit var txtToggleOriginalInput: TextView
    private lateinit var txtOriginalInputCollapsed: TextView
    private var isOriginalExpanded: Boolean = false

    // State trackers
    private var lastOriginalIngredients: String = ""
    private var lastAdjustedIngredients: String = ""
    private var lastDiagnosticError: String? = null
    private var activeHistoryId: String? = null
    private var forceReprofile: Boolean = false

    // Cached diagnostics for tap-through details
    private var cachedOriginalGrade: String = ""
    private var cachedOriginalViolations: String = ""
    private var cachedAdjustedGrade: String = ""
    private var cachedAdjustedViolations: String = ""

    // Metadata
    private var scrapedRecipeTitle: String? = null
    private var scrapedRecipeUrl: String? = null
    private var scrapedServings: Int? = null

    private var tempImageUri: Uri? = null

    private var navController: NavController? = null
    private val destinationListener = NavController.OnDestinationChangedListener { _, destination, _ ->
        if (destination.id != R.id.recipeFragment) {
            resetToCleanInput()
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { runOnDeviceOcr(it) }
        } else {
            Toast.makeText(context, "Photo capture canceled", Toast.LENGTH_SHORT).show()
        }
    }

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

        layoutInputSection = root.findViewById(R.id.layoutInputSection)
        layoutResultsSection = root.findViewById(R.id.layoutResultsSection)

        edtRecipeInput = root.findViewById(R.id.edtRecipeInput)
        btnAdjustRecipe = root.findViewById(R.id.btnAdjustRecipe)
        edtServingsInput = root.findViewById(R.id.edtServingsInput)
        btnPhotoImport = root.findViewById(R.id.btnPhotoImport)

        btnNewRecipe = root.findViewById(R.id.btnNewRecipe)
        txtSourceLink = root.findViewById(R.id.txtSourceLink)

        // Gauge Bindings
        cardClinicalGauge = root.findViewById(R.id.cardClinicalGauge)
        barSodiumOg = root.findViewById(R.id.barSodiumOg)
        txtSodiumOgVal = root.findViewById(R.id.txtSodiumOgVal)
        barSodiumAdj = root.findViewById(R.id.barSodiumAdj)
        txtSodiumAdjVal = root.findViewById(R.id.txtSodiumAdjVal)

        barPotassiumOg = root.findViewById(R.id.barPotassiumOg)
        txtPotassiumOgVal = root.findViewById(R.id.txtPotassiumOgVal)
        barPotassiumAdj = root.findViewById(R.id.barPotassiumAdj)
        txtPotassiumAdjVal = root.findViewById(R.id.txtPotassiumAdjVal)

        barCarbsOg = root.findViewById(R.id.barCarbsOg)
        txtCarbsOgVal = root.findViewById(R.id.txtCarbsOgVal)
        barCarbsAdj = root.findViewById(R.id.barCarbsAdj)
        txtCarbsAdjVal = root.findViewById(R.id.txtCarbsAdjVal)

        barProteinOg = root.findViewById(R.id.barProteinOg)
        txtProteinOgVal = root.findViewById(R.id.txtProteinOgVal)
        barProteinAdj = root.findViewById(R.id.barProteinAdj)
        txtProteinAdjVal = root.findViewById(R.id.txtProteinAdjVal)

        cardAdjustedResult = root.findViewById(R.id.cardAdjustedResult)
        progressAnalysisIndicator = root.findViewById(R.id.progressAnalysisIndicator)
        txtAdjustedOutput = root.findViewById(R.id.txtAdjustedOutput)
        txtAdjustedRecipeHeader = root.findViewById(R.id.txtAdjustedRecipeHeader)
        btnCopyAdjusted = root.findViewById(R.id.btnCopyAdjusted)
        btnReportAdjusted = root.findViewById(R.id.btnReportAdjusted)

        layoutPostAnalysisActions = root.findViewById(R.id.layoutPostAnalysisActions)
        btnRetrieveSteps = root.findViewById(R.id.btnRetrieveSteps)
        btnRetrieveAnalysis = root.findViewById(R.id.btnRetrieveAnalysis)

        txtToggleOriginalInput = root.findViewById(R.id.txtToggleOriginalInput)
        txtOriginalInputCollapsed = root.findViewById(R.id.txtOriginalInputCollapsed)

        btnPhotoImport.setOnClickListener {
            showPhotoSourceDialog()
        }

        btnNewRecipe.setOnClickListener {
            showInputState()
        }

        cardClinicalGauge.setOnClickListener {
            showDiagnosticsDialog()
        }

        txtToggleOriginalInput.setOnClickListener {
            isOriginalExpanded = !isOriginalExpanded
            if (isOriginalExpanded) {
                txtOriginalInputCollapsed.visibility = View.VISIBLE
                txtToggleOriginalInput.text = "▼ Hide Original Ingredients"
            } else {
                txtOriginalInputCollapsed.visibility = View.GONE
                txtToggleOriginalInput.text = "▶ View Original Ingredients"
            }
        }

        edtRecipeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString()?.trim() ?: ""
                val isUrl = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

                if (btnAdjustRecipe.isEnabled) {
                    btnAdjustRecipe.text = if (isUrl) "Scrape Recipe" else "Profile & Adjust"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        txtSourceLink.setOnClickListener {
            val url = scrapedRecipeUrl
            if (!url.isNullOrBlank()) {
                forceReprofile = true
                scrapeWebpageContents(url)
            }
        }

        btnReportAdjusted.setOnClickListener {
            val userSettings = AppSettings(requireContext())
            val activeProfiles = userSettings.getSelectedConditions()
            val conditionsNames = activeProfiles.joinToString(", ") { id ->
                userSettings.getAvailableDietProfiles().find { it.id == id }?.displayName ?: id
            }
            val customReds = userSettings.getCustomWatchlist("RED")
            val customYellows = userSettings.getCustomWatchlist("YELLOW")

            val recipeName = txtAdjustedRecipeHeader.text.toString().removePrefix("Adjusted: ")
            val reportBody = StringBuilder().apply {
                append("TESTER OBSERVATION / FEEDBACK:\n")
                append("[Please type observations here]\n\n")
                append("=========================================\n")
                append("USER PROFILE & WATCHLISTS:\n")
                append("Active Conditions: $conditionsNames\n")
                append("Custom Watchlist (Avoid - RED): ${customReds.joinToString(", ").ifEmpty { "None" }}\n")
                append("Custom Watchlist (Caution - YELLOW): ${customYellows.joinToString(", ").ifEmpty { "None" }}\n")
                append("\n=========================================\n")
                append("RECIPE METADATA:\n")
                append("Title: $recipeName\n")
                if (!scrapedRecipeUrl.isNullOrBlank()) {
                    append("Source Link: $scrapedRecipeUrl\n")
                }
                append("\n=========================================\n")
                append("ORIGINAL RECIPE STATUS:\n")
                append("Grade: $cachedOriginalGrade\n")
                append("$cachedOriginalViolations\n")
                append("\nOriginal Ingredients:\n")
                append(lastOriginalIngredients)
                append("\n\n=========================================\n")
                append("ADJUSTED RECIPE STATUS:\n")
                append("Grade: $cachedAdjustedGrade\n")
                append("$cachedAdjustedViolations\n")
                append("\nAdjusted Ingredients:\n")
                append(lastAdjustedIngredients)
                append("\n=========================================\n")
            }.toString()

            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.support_email)))
                putExtra(Intent.EXTRA_SUBJECT, "FilterPoint Issue / Disparity: $recipeName")
                putExtra(Intent.EXTRA_TEXT, reportBody)
            }

            try {
                startActivity(Intent.createChooser(emailIntent, "Send Telemetry Report..."))
            } catch (e: Exception) {
                Toast.makeText(context, "No email app found to send report.", Toast.LENGTH_SHORT).show()
            }
        }

        btnAdjustRecipe.setOnClickListener { handleActionSubmit() }
        btnRetrieveSteps.setOnClickListener { retrieveStepByStepInstructions() }
        btnRetrieveAnalysis.setOnClickListener { retrieveAnalysisData() }

        if (arguments?.getBoolean("FORCE_REPROFILE", false) == true) {
            forceReprofile = true
            arguments?.remove("FORCE_REPROFILE")
        }

        arguments?.getString("RECIPE_INPUT")?.let { recipeText ->
            edtRecipeInput.setText(recipeText)
        }

        arguments?.getString("RECIPE_URL")?.let { url ->
            if (url.isNotBlank()) {
                scrapedRecipeUrl = url
                val originalGrade = arguments?.getString("ORIGINAL_GRADE")
                if (originalGrade.isNullOrEmpty()) {
                    edtRecipeInput.setText(url)
                }
            }
        }

        activeHistoryId = arguments?.getString("HISTORY_ITEM_ID")

        val originalGrade = arguments?.getString("ORIGINAL_GRADE")
        val adjustedOutput = arguments?.getString("RECIPE_ADJUSTED_OUTPUT")

        if (!adjustedOutput.isNullOrEmpty()) {
            showResultsState()

            val savedInstructions = arguments?.getString("RECIPE_INSTRUCTIONS") ?: ""
            val displayText = if (savedInstructions.isNotBlank()) {
                "$adjustedOutput\n\nSTEP-BY-STEP PREPARATION:\n$savedInstructions"
            } else {
                adjustedOutput
            }
            txtAdjustedOutput.text = displayText
            layoutPostAnalysisActions.visibility = View.VISIBLE

            lastOriginalIngredients = arguments?.getString("RECIPE_INPUT") ?: ""
            lastAdjustedIngredients = adjustedOutput
            txtOriginalInputCollapsed.text = lastOriginalIngredients

            val savedTitle = arguments?.getString("RECIPE_TITLE")
            txtAdjustedRecipeHeader.text = if (!savedTitle.isNullOrEmpty()) "Adjusted: $savedTitle" else "Adjusted Recipe"

            if (savedInstructions.isNotBlank()) {
                btnRetrieveSteps.isEnabled = false
                btnRetrieveSteps.text = "Steps Loaded"
            } else {
                btnRetrieveSteps.isEnabled = true
                btnRetrieveSteps.text = "Get Steps"
            }
            btnRetrieveAnalysis.isEnabled = true
            btnRetrieveAnalysis.text = "Get Analysis"

            btnCopyAdjusted.text = "Copy Ingredients"
            btnCopyAdjusted.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Recipe", displayText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Recipe copied to clipboard!", Toast.LENGTH_SHORT).show()
            }

            renderSourceChip(scrapedRecipeUrl)
        } else {
            showInputState()
        }

        if (!originalGrade.isNullOrEmpty()) {
            cachedOriginalGrade = originalGrade
            cachedOriginalViolations = arguments?.getString("ORIGINAL_VIOLATIONS") ?: ""
            cachedAdjustedGrade = arguments?.getString("ADJUSTED_GRADE") ?: ""
            cachedAdjustedViolations = arguments?.getString("ADJUSTED_VIOLATIONS") ?: ""
        }

        arguments?.remove("RECIPE_INPUT")
        arguments?.remove("RECIPE_URL")
        arguments?.remove("RECIPE_ADJUSTED_OUTPUT")
        arguments?.remove("HISTORY_ITEM_ID")
        arguments?.remove("RECIPE_TITLE")
        arguments?.remove("RECIPE_INSTRUCTIONS")
        arguments?.remove("BASE_PROFILE_ID")
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navController = findNavController().apply {
            addOnDestinationChangedListener(destinationListener)
        }

        val incomingUrl = arguments?.getString("recipe_url")
        if (!incomingUrl.isNullOrEmpty()) {
            arguments?.remove("recipe_url")
            scrapeWebpageContents(incomingUrl)
        }
    }

    override fun onDestroyView() {
        navController?.removeOnDestinationChangedListener(destinationListener)
        navController = null
        super.onDestroyView()
    }

    private fun showInputState() {
        layoutInputSection.visibility = View.VISIBLE
        layoutResultsSection.visibility = View.GONE
        btnAdjustRecipe.isEnabled = true
        btnAdjustRecipe.text = "Profile & Adjust"
    }

    private fun showResultsState() {
        hideKeyboardAndClipboard()
        layoutInputSection.visibility = View.GONE
        layoutResultsSection.visibility = View.VISIBLE
    }

    fun resetToCleanInput() {
        edtRecipeInput.setText("")
        edtServingsInput.setText("4")
        lastOriginalIngredients = ""
        lastAdjustedIngredients = ""
        lastDiagnosticError = null
        activeHistoryId = null
        scrapedRecipeTitle = null
        scrapedRecipeUrl = null
        scrapedServings = null
        forceReprofile = false
        isOriginalExpanded = false
        txtOriginalInputCollapsed.visibility = View.GONE
        txtToggleOriginalInput.text = "▶ View Original Ingredients"
        showInputState()
    }

    private fun renderSourceChip(url: String?) {
        if (!url.isNullOrBlank()) {
            val host = try {
                Uri.parse(url).host?.removePrefix("www.") ?: url
            } catch (_: Exception) {
                url
            }
            txtSourceLink.text = "🌐 $host (Re-scrape)"
            txtSourceLink.visibility = View.VISIBLE
        } else {
            txtSourceLink.visibility = View.GONE
        }
    }

    private fun hideKeyboardAndClipboard() {
        val view = activity?.currentFocus ?: edtRecipeInput
        view.clearFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showPhotoSourceDialog() {
        val options = arrayOf("Take Photo with Camera", "Choose from Gallery")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Recipe Photo Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            tempImageUri = getTmpFileUri()
                            takePicture.launch(tempImageUri)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, "Failed to launch camera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> {
                        pickMedia.launch(
                            PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                .build()
                        )
                    }
                }
            }
            .show()
    }

    private fun showDiagnosticsDialog() {
        val context = context ?: return
        val message = StringBuilder().apply {
            append("ORIGINAL RECIPE:\n")
            append("Grade: $cachedOriginalGrade\n")
            append(if (cachedOriginalViolations.isNotBlank()) "$cachedOriginalViolations\n\n" else "No violations flagged.\n\n")
            append("ADJUSTED RECIPE:\n")
            append("Grade: $cachedAdjustedGrade\n")
            append(if (cachedAdjustedViolations.isNotBlank()) "$cachedAdjustedViolations\n" else "Compliant / Safe for active profile.")
        }.toString()

        MaterialAlertDialogBuilder(context, R.style.Theme_LabelScanner)
            .setTitle("Clinical Diagnostics")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun updateTelemetryGauges(
        origSod: Double, adjSod: Double,
        origPot: Double, adjPot: Double,
        origCarb: Double, adjCarb: Double,
        origProt: Double, adjProt: Double,
        userSettings: AppSettings
    ) {
        fun computeDelta(og: Double, adj: Double): String {
            if (og <= 0.0) return ""
            val pct = (((adj - og) / og) * 100).toInt()
            return if (pct < 0) " ($pct%)" else if (pct > 0) " (+$pct%)" else " (0%)"
        }

        fun getThresholdsDouble(key: String): Pair<Double, Double> {
            val limits = userSettings.getNutrientThresholds(key)
            return Pair(limits.first.toDouble(), limits.second.toDouble())
        }

        // 1. SODIUM
        val (yellowSod, redSod) = getThresholdsDouble("sodium")
        val maxSod = maxOf(origSod, adjSod, redSod * 1.15, 1.0)
        barSodiumOg.setGaugeData(origSod, yellowSod, redSod, maxSod)
        txtSodiumOgVal.text = String.format(Locale.ROOT, "%.0f mg", origSod)
        barSodiumAdj.setGaugeData(adjSod, yellowSod, redSod, maxSod)
        txtSodiumAdjVal.text = String.format(Locale.ROOT, "%.0f mg%s", adjSod, computeDelta(origSod, adjSod))

        // 2. POTASSIUM
        val (yellowPot, redPot) = getThresholdsDouble("potassium")
        val maxPot = maxOf(origPot, adjPot, redPot * 1.15, 1.0)
        barPotassiumOg.setGaugeData(origPot, yellowPot, redPot, maxPot)
        txtPotassiumOgVal.text = String.format(Locale.ROOT, "%.0f mg", origPot)
        barPotassiumAdj.setGaugeData(adjPot, yellowPot, redPot, maxPot)
        txtPotassiumAdjVal.text = String.format(Locale.ROOT, "%.0f mg%s", adjPot, computeDelta(origPot, adjPot))

        // 3. CARBS
        val (yellowCarb, redCarb) = getThresholdsDouble("carbs")
        val maxCarb = maxOf(origCarb, adjCarb, redCarb * 1.15, 1.0)
        barCarbsOg.setGaugeData(origCarb, yellowCarb, redCarb, maxCarb)
        txtCarbsOgVal.text = String.format(Locale.ROOT, "%.1f g", origCarb)
        barCarbsAdj.setGaugeData(adjCarb, yellowCarb, redCarb, maxCarb)
        txtCarbsAdjVal.text = String.format(Locale.ROOT, "%.1f g%s", adjCarb, computeDelta(origCarb, adjCarb))

        // 4. PROTEIN
        val (yellowProt, redProt) = getThresholdsDouble("protein")
        val maxProt = maxOf(origProt, adjProt, redProt * 1.15, 1.0)
        barProteinOg.setGaugeData(origProt, yellowProt, redProt, maxProt)
        txtProteinOgVal.text = String.format(Locale.ROOT, "%.1f g", origProt)
        barProteinAdj.setGaugeData(adjProt, yellowProt, redProt, maxProt)
        txtProteinAdjVal.text = String.format(Locale.ROOT, "%.1f g%s", adjProt, computeDelta(origProt, adjProt))

        cardClinicalGauge.visibility = View.VISIBLE
    }

    private fun handleActionSubmit() {
        val rawText = edtRecipeInput.text?.toString()?.trim() ?: ""
        if (rawText.isNotBlank()) {
            if (rawText.startsWith("http://", ignoreCase = true) || rawText.startsWith("https://", ignoreCase = true)) {
                scrapeWebpageContents(rawText)
            } else {
                analyzeRecipeAndAdjust(rawText)
            }
        } else {
            Toast.makeText(context, "Please enter recipe ingredients or paste a link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim()

        if (cleanUrl.contains("?")) {
            val base = cleanUrl.substringBefore("?")
            val query = cleanUrl.substringAfter("?")
            val preservedParams = query.split("&").filter { param ->
                val key = param.substringBefore("=").lowercase(Locale.ROOT)
                !key.startsWith("utm_") && key != "fbclid" && key != "gclid" && key != "msclkid"
            }
            cleanUrl = if (preservedParams.isNotEmpty()) {
                "$base?${preservedParams.joinToString("&")}"
            } else {
                base
            }
        }

        if (cleanUrl.endsWith("/print/")) cleanUrl = cleanUrl.removeSuffix("print/")
        else if (cleanUrl.endsWith("/print")) cleanUrl = cleanUrl.removeSuffix("print")
        return cleanUrl
    }

    private fun sanitizeScrapedIngredients(text: String): String {
        return text.lines()
            .map { line ->
                var temp = line.trim()
                temp = temp.replace('\u00A0', ' ')
                    .replace('\u200B', ' ')
                    .trim()
                temp = temp.replace(Regex("""^[^a-zA-Z0-9½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞\d/()]+"""), "").trim()
                temp
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun extractRecipeCardSubstring(html: String): String {
        val lowercaseHtml = html.lowercase(Locale.ROOT)
        val targets = listOf(
            "id=\"recipecard\"",
            "id='recipecard'",
            "id=recipecard",
            "class=\"recipecard\"",
            "class='recipecard'",
            "recipe-card",
            "wp-recipe-maker",
            "mv-create-card",
            "tasty-recipes",
            "itemtype=\"http://schema.org/recipe\"",
            "itemtype='http://schema.org/recipe'",
            "itemprop=\"recipeingredient\"",
            "itemprop='recipeingredient'"
        )

        for (target in targets) {
            val index = lowercaseHtml.indexOf(target)
            if (index != -1) {
                val startAdjusted = (index - 100).coerceAtLeast(0)
                return html.substring(startAdjusted)
            }
        }
        return html
    }

    private fun cleanHtmlTagsAndNormalize(html: String): String {
        var temp = html
        temp = temp.replace(Regex("<script.*?>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<style.*?>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        temp = temp.replace(Regex("<[^>]+>"), " ")
        temp = temp.replace(Regex("\\s+"), " ")
        return temp.trim()
    }

    private fun scrapeWebpageContents(urlStr: String) {
        hideKeyboardAndClipboard()

        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Scraping page link..."
        scrapedRecipeTitle = null
        scrapedServings = null

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val canonicalUrl = sanitizeUrl(urlStr)
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(canonicalUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val rawHtml = response.body?.string() ?: ""

                    val ingredientsText = extractStructuredRecipeIngredients(rawHtml)
                    val finalOutput = if (ingredientsText.isNotBlank()) ingredientsText else cleanHtmlToText(rawHtml)
                    val sanitizedOutput = sanitizeScrapedIngredients(finalOutput)

                    withContext(Dispatchers.Main) {
                        btnAdjustRecipe.isEnabled = true
                        btnAdjustRecipe.text = "Profile & Adjust"

                        if (sanitizedOutput.isNotBlank()) {
                            scrapedRecipeUrl = canonicalUrl
                            renderSourceChip(canonicalUrl)

                            edtRecipeInput.setText(sanitizedOutput)
                            scrapedServings?.let {
                                edtServingsInput.setText(it.toString())
                            }

                            hideKeyboardAndClipboard()
                            Toast.makeText(context, "Ingredients imported! Verify servings, then click Analyze.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Could not extract readable ingredients from that URL.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnAdjustRecipe.isEnabled = true
                    btnAdjustRecipe.text = "Profile & Adjust"
                    Toast.makeText(context, "Scraping Timed Out / Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

        btnAdjustRecipe.isEnabled = false
        btnAdjustRecipe.text = "Reading image..."

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                btnAdjustRecipe.isEnabled = true
                btnAdjustRecipe.text = "Profile & Adjust"

                val rawText = visionText.text
                if (rawText.isNotBlank()) {
                    val cleanedText = cleanOcrTextLocally(rawText)
                    val sanitizedOcr = sanitizeScrapedIngredients(cleanedText)

                    scrapedRecipeTitle = null
                    edtRecipeInput.setText(sanitizedOcr)
                    hideKeyboardAndClipboard()
                    Toast.makeText(context, "Ingredients imported from photo!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No text found inside the photo.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                btnAdjustRecipe.isEnabled = true
                btnAdjustRecipe.text = "Profile & Adjust"
                Toast.makeText(context, "OCR Processing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun cleanOcrTextLocally(rawText: String): String {
        val lines = rawText.lines()
        val cleanedLines = mutableListOf<String>()
        val stopKeywords = listOf("directions", "prep:", "cook:", "total:", "instructions", "for the filling:", "for the mash:")
        val keyboardKeyPattern = Regex("^[fp]\\d+\$", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()

            if (stopKeywords.any { lower.contains(it) }) {
                break
            }

            if (trimmed.isEmpty()) continue
            if (trimmed.length <= 1) continue
            if (trimmed.matches(keyboardKeyPattern)) continue
            if (lower.startsWith("yield:") || lower.startsWith("ingredients")) continue

            cleanedLines.add(trimmed)
        }

        return cleanedLines.joinToString("\n")
    }

    private fun parseServingsFromYield(yieldRaw: Any?): Int? {
        if (yieldRaw == null) return null
        if (yieldRaw is Number) return yieldRaw.toInt()
        if (yieldRaw is JSONArray) {
            if (yieldRaw.length() > 0) {
                return parseServingsFromYield(yieldRaw.opt(0))
            }
            return null
        }
        val str = yieldRaw.toString().trim()
        if (str.isEmpty()) return null

        val match = Regex("\\d+").find(str)
        return match?.value?.toIntOrNull()
    }

    private fun extractServingsFromHtmlFallback(html: String): Int? {
        val isolatedHtml = extractRecipeCardSubstring(html)
        val plainText = cleanHtmlTagsAndNormalize(isolatedHtml)

        val pattern1 = Regex("(?:Portions|Servings|Yield|Serves)\\s*[^0-9]{0,25}\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val match1 = pattern1.find(plainText)
        if (match1 != null) {
            val val1 = match1.groups[1]?.value?.toIntOrNull()
            if (val1 != null) return val1
        }

        val pattern2 = Regex("(\\d+)\\s*[^0-9]{0,25}\\s*(?:Portions|Servings|Yield|Serves)", RegexOption.IGNORE_CASE)
        val match2 = pattern2.find(plainText)
        if (match2 != null) {
            val val2 = match2.groups[1]?.value?.toIntOrNull()
            if (val2 != null) return val2
        }

        return null
    }

    private fun extractStructuredRecipeIngredients(html: String): String {
        try {
            scrapedServings = extractServingsFromHtmlFallback(html)

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

                        val yieldRaw = recipeObj.opt("recipeYield") ?: recipeObj.opt("yield")
                        val parsedYield = parseServingsFromYield(yieldRaw)
                        if (parsedYield != null) {
                            scrapedServings = parsedYield
                        }

                        val ingredientsText = formatRecipeIngredients(recipeObj)
                        if (ingredientsText.isNotBlank()) return ingredientsText
                    }
                } catch (e: Exception) {
                    // Fail silently
                }
            }

            if (scrapedRecipeTitle.isNullOrBlank()) {
                scrapedRecipeTitle = extractHtmlTitleFallback(html)
            }

            return fallbackTargetedHtmlIngredients(html)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun extractHtmlTitleFallback(rawHtml: String): String? {
        try {
            val ogPattern = Regex(
                """<meta\s+[^>]*(?:property|name)\s*=\s*['"](?:og:title|twitter:title)['"][^>]*content\s*=\s*['"](.*?)['"]""",
                RegexOption.IGNORE_CASE
            )
            val ogMatch = ogPattern.find(rawHtml)?.groups?.get(1)?.value

            val ogReversedPattern = Regex(
                """<meta\s+[^>]*content\s*=\s*['"](.*?)['"][^>]*(?:property|name)\s*=\s*['"](?:og:title|twitter:title)['"]""",
                RegexOption.IGNORE_CASE
            )
            val ogReversedMatch = ogReversedPattern.find(rawHtml)?.groups?.get(1)?.value

            val titlePattern = Regex(
                """<title[^>]*>(.*?)</title>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val titleMatch = titlePattern.find(rawHtml)?.groups?.get(1)?.value

            val h1Pattern = Regex(
                """<h1[^>]*>(.*?)</h1>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val h1Match = h1Pattern.find(rawHtml)?.groups?.get(1)?.value

            val rawTitle = ogMatch ?: ogReversedMatch ?: titleMatch ?: h1Match ?: return null
            return cleanExtractedTitle(rawTitle)
        } catch (_: Exception) {
            return null
        }
    }

    private fun cleanExtractedTitle(rawTitle: String): String? {
        var clean = rawTitle.replace(Regex("<[^>]+>"), "")
        clean = clean.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

        if (clean.isBlank()) return null

        val delimiters = listOf(" - ", " | ", " • ", " — ", " – ", " :: ")
        for (delim in delimiters) {
            if (clean.contains(delim)) {
                clean = clean.substringBeforeLast(delim).trim()
            }
        }

        return if (clean.isNotBlank() && clean.length <= 100) clean else null
    }

    private fun formatRecipeIngredients(recipe: JSONObject): String {
        val builder = StringBuilder()
        val ingredientsObj = recipe.opt("recipeIngredient") ?: recipe.opt("ingredients")
        if (ingredientsObj != null) {
            if (ingredientsObj is JSONArray) {
                for (i in 0 until ingredientsObj.length()) {
                    val ing = ingredientsObj.optString(i, "").trim()
                    if (ing.isNotEmpty()) {
                        builder.append(ing).append("\n")
                    }
                }
            } else if (ingredientsObj is String) {
                val ing = ingredientsObj.trim()
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
            .replace("&#39;", "'")
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
            val isolatedHtml = extractRecipeCardSubstring(rawHtml)
            val extractedList = mutableListOf<String>()

            val itempropPattern = Regex(
                "<[^>]*itemprop\\s*=\\s*['\"]?(?:recipeIngredient|ingredients)['\"][^>]*>(.*?)</[^>]+>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val itempropMatches = itempropPattern.findAll(isolatedHtml)
            for (match in itempropMatches) {
                val content = match.groups[1]?.value ?: continue
                val cleanText = cleanHtmlToText(content).trim()
                if (cleanText.isNotBlank() && !extractedList.contains(cleanText)) {
                    extractedList.add(cleanText)
                }
            }

            if (extractedList.isNotEmpty()) return extractedList.joinToString("\n")

            val ingredientBlockPattern = Regex(
                "<(?:ul|ol|div)[^>]*class\\s*=\\s*['\"][^'\"]*ingredient[^'\"]*['\"][^>]*>(.*?)</(?:ul|ol|div)>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val matches = ingredientBlockPattern.findAll(isolatedHtml)

            for (match in matches) {
                val blockContent = match.groups[1]?.value ?: continue
                val liPattern = Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val items = liPattern.findAll(blockContent)

                for (item in items) {
                    val rawLi = item.groups[1]?.value ?: continue
                    val cleanText = cleanHtmlToText(rawLi).trim()
                    if (cleanText.isNotBlank() && !extractedList.contains(cleanText)) {
                        extractedList.add(cleanText)
                    }
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

    private fun generateTitleFromIngredients(ingredientsText: String): String {
        val lines = ingredientsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val primaryIngredients = mutableListOf<String>()
        val ignoreWords = setOf(
            "lange", "long", "short",
            "large", "medium", "small", "tiny", "mini", "jumbo", "giant", "thin", "thick", "whole", "half", "halves", "quarter", "quarters",
            "boneless", "skinless", "extra-virgin", "extra", "virgin", "lean", "fat-free", "low-fat", "reduced-fat", "salted", "unsalted",
            "olive", "olives", "canola", "vegetable", "coconut", "sesame", "avocado", "grapeseed",
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
            primaryIngredients.joinToString(" & ")
        } else {
            "Custom Recipe"
        }
    }

    private fun analyzeRecipeAndAdjust(rawText: String) {
        hideKeyboardAndClipboard()

        btnAdjustRecipe.isEnabled = false
        lastDiagnosticError = null

        val userSettings = AppSettings(requireContext())
        val activeProfiles = userSettings.getSelectedConditions()

        val activeServings = edtServingsInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(1)
            ?: scrapedServings
            ?: 4

        if (!forceReprofile) {
            val cachedRecipe = SavedPersistenceManager.findMatchingRecipe(
                requireContext(),
                scrapedRecipeUrl ?: "",
                rawText
            )

            if (cachedRecipe != null && cachedRecipe.adjustedOutput.isNotBlank()) {
                displayCachedRecipeResult(cachedRecipe, rawText, userSettings, activeProfiles)
                return
            }
        }

        forceReprofile = false

        showResultsState()
        cardClinicalGauge.visibility = View.GONE
        layoutPostAnalysisActions.visibility = View.GONE
        progressAnalysisIndicator.visibility = View.VISIBLE
        txtAdjustedOutput.text = "Profiling ingredients and generating clinical adjustments..."

        btnRetrieveSteps.isEnabled = true
        btnRetrieveSteps.text = "Get Steps"
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"

        lifecycleScope.launch {
            val responseString = fetchRecipeNutritionFromBackend(rawText, activeProfiles, activeServings)
            progressAnalysisIndicator.visibility = View.GONE

            if (responseString != null) {
                try {
                    val parsedResult = JSONObject(responseString)

                    val servings = activeServings
                    val adjustedIngredients = parsedResult.optString("adjusted_ingredients", "")

                    lastOriginalIngredients = rawText
                    lastAdjustedIngredients = adjustedIngredients
                    txtOriginalInputCollapsed.text = rawText

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

                    val adjustedIngredientsListForEval = adjustedIngredientsList.map { line ->
                        line.replace(Regex("\\(.*?\\)"), "").trim()
                    }.filter { it.isNotEmpty() }

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
                            isProduce = false,
                            isRecipe = true
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
                            detectedIngredients = adjustedIngredientsListForEval,
                            savedProfileIds = activeProfiles,
                            userSettings = userSettings,
                            xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                            customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                            customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                            isProduce = false,
                            isRecipe = true
                        )
                    } else null

                    // --- "DON'T FIX WHAT ISN'T BROKEN" GUARDRAIL ---
                    val isOriginalAlreadySafe = originalEvalResult?.gradeTitle?.startsWith("Green", ignoreCase = true) == true &&
                            originalEvalResult.redViolations.isEmpty() && originalEvalResult.yellowViolations.isEmpty()

                    if (originalEvalResult != null && adjustedEvalResult != null && originalJson != null && adjustedJson != null) {
                        cachedOriginalGrade = originalEvalResult.gradeTitle
                        val origReds = originalEvalResult.redViolations.distinct()
                        val origYellows = originalEvalResult.yellowViolations.distinct()
                        cachedOriginalViolations = if (origReds.isNotEmpty()) origReds.joinToString("\n• ", prefix = "• ")
                        else if (origYellows.isNotEmpty()) origYellows.joinToString("\n• ", prefix = "• ")
                        else ""

                        cachedAdjustedGrade = if (isOriginalAlreadySafe) originalEvalResult.gradeTitle else adjustedEvalResult.gradeTitle
                        val adjReds = adjustedEvalResult.redViolations.distinct()
                        val adjYellows = adjustedEvalResult.yellowViolations.distinct()
                        cachedAdjustedViolations = if (isOriginalAlreadySafe) ""
                        else if (adjReds.isNotEmpty()) adjReds.joinToString("\n• ", prefix = "• ")
                        else if (adjYellows.isNotEmpty()) adjYellows.joinToString("\n• ", prefix = "• ")
                        else ""

                        val origSod = originalJson.optDouble("sodium_mg", 0.0) / servings
                        val adjSod = if (isOriginalAlreadySafe) origSod else adjustedJson.optDouble("sodium_mg", 0.0) / servings
                        val origPot = originalJson.optDouble("potassium_mg", 0.0) / servings
                        val adjPot = if (isOriginalAlreadySafe) origPot else adjustedJson.optDouble("potassium_mg", 0.0) / servings
                        val origCarb = (originalJson.optDouble("total_carbohydrates_g", 0.0) - originalJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
                        val adjCarb = if (isOriginalAlreadySafe) origCarb else (adjustedJson.optDouble("total_carbohydrates_g", 0.0) - adjustedJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
                        val origProt = originalJson.optDouble("protein_g", 0.0) / servings
                        val adjProt = if (isOriginalAlreadySafe) origProt else adjustedJson.optDouble("protein_g", 0.0) / servings

                        updateTelemetryGauges(
                            origSod, adjSod,
                            origPot, adjPot,
                            origCarb, adjCarb,
                            origProt, adjProt,
                            userSettings
                        )
                    }

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

                    val finalAdjustedText = if (isOriginalAlreadySafe) rawText else adjustedIngredients
                    lastAdjustedIngredients = finalAdjustedText

                    if (isOriginalAlreadySafe) {
                        txtAdjustedRecipeHeader.text = "$dynamicTitle (Safe As-Is)"
                        txtAdjustedOutput.text = "✓ SAFE AS-IS: All ingredients and nutritional metrics are compliant with your active profile. No substitutions required.\n\n$rawText"
                    } else {
                        txtAdjustedRecipeHeader.text = "Adjusted: $dynamicTitle"
                        txtAdjustedOutput.text = adjustedIngredients
                    }

                    layoutPostAnalysisActions.visibility = View.VISIBLE

                    val saverId = activeHistoryId ?: System.currentTimeMillis().toString()
                    activeHistoryId = saverId

                    btnCopyAdjusted.text = "Save to Cookbook"
                    btnCopyAdjusted.isEnabled = true
                    btnCopyAdjusted.setOnClickListener {
                        val baseProfileId = activeProfiles.map { pid ->
                            val bId = userSettings.getBaseProfileIdForProfile(pid)
                            if (bId.isNotEmpty()) bId else pid
                        }.firstOrNull() ?: "healthy_baseline"

                        val savedItem = SavedItem(
                            id = saverId,
                            timestamp = System.currentTimeMillis(),
                            title = dynamicTitle,
                            baseProfileId = baseProfileId,
                            adjustedGrade = if (isOriginalAlreadySafe) "Green - Safe" else (adjustedEvalResult?.gradeTitle ?: "Unknown"),
                            sourceUrl = scrapedRecipeUrl ?: "",
                            originalInput = rawText,
                            adjustedOutput = finalAdjustedText,
                            instructions = "",
                            originalNutrition = originalJson?.toString() ?: "",
                            adjustedNutrition = if (isOriginalAlreadySafe) (originalJson?.toString() ?: "") else (adjustedJson?.toString() ?: ""),
                            servings = servings,
                            itemType = "RECIPE"
                        )
                        SavedPersistenceManager.saveItem(requireContext(), savedItem)
                        btnCopyAdjusted.text = "Saved in Cookbook"
                        btnCopyAdjusted.isEnabled = false
                        Toast.makeText(requireContext(), "Recipe saved to your Clinical Cookbook!", Toast.LENGTH_SHORT).show()
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

    private fun displayCachedRecipeResult(
        cachedRecipe: SavedItem,
        rawText: String,
        userSettings: AppSettings,
        activeProfiles: Set<String>
    ) {
        lastOriginalIngredients = rawText
        lastAdjustedIngredients = cachedRecipe.adjustedOutput
        activeHistoryId = cachedRecipe.id
        scrapedRecipeTitle = cachedRecipe.title

        val servings = cachedRecipe.servings.coerceAtLeast(1)
        edtServingsInput.setText(servings.toString())
        txtOriginalInputCollapsed.text = rawText

        txtAdjustedRecipeHeader.text = if (cachedRecipe.title.isNotBlank()) "Adjusted: ${cachedRecipe.title}" else "Adjusted Recipe"

        val displayText = if (cachedRecipe.instructions.isNotBlank()) {
            "${cachedRecipe.adjustedOutput}\n\nSTEP-BY-STEP PREPARATION:\n${cachedRecipe.instructions}"
        } else {
            cachedRecipe.adjustedOutput
        }
        txtAdjustedOutput.text = displayText

        val originalJson = try {
            if (cachedRecipe.originalNutrition.isNotBlank()) JSONObject(cachedRecipe.originalNutrition) else null
        } catch (_: Exception) { null }

        val adjustedJson = try {
            if (cachedRecipe.adjustedNutrition.isNotBlank()) JSONObject(cachedRecipe.adjustedNutrition) else null
        } catch (_: Exception) { null }

        val dynamicXmlTriggers = mutableMapOf<String, String>()
        for (profileId in activeProfiles) {
            val triggers = userSettings.getIngredientsFromAssetFile(profileId)
            for (trigger in triggers) {
                dynamicXmlTriggers[trigger.uppercase(Locale.ROOT).trim()] = profileId
            }
        }

        val originalIngredientsList = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val adjustedIngredientsList = cachedRecipe.adjustedOutput.lines()
            .map { line -> line.replace(Regex("\\(.*?\\)"), "").trim() }
            .filter { it.isNotEmpty() }

        val originalPerServing = if (originalJson != null) {
            JSONObject().apply {
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
        } else null

        val originalEvalResult = if (originalPerServing != null) {
            LabelEvaluator.evaluateScanData(
                jsonResult = originalPerServing,
                detectedIngredients = originalIngredientsList,
                savedProfileIds = activeProfiles,
                userSettings = userSettings,
                xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                isProduce = false,
                isRecipe = true
            )
        } else null

        val adjustedPerServing = if (adjustedJson != null) {
            JSONObject().apply {
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
        } else null

        val adjustedEvalResult = if (adjustedPerServing != null) {
            LabelEvaluator.evaluateScanData(
                jsonResult = adjustedPerServing,
                detectedIngredients = adjustedIngredientsList,
                savedProfileIds = activeProfiles,
                userSettings = userSettings,
                xmlRedTriggers = dynamicXmlTriggers.keys.toList(),
                customRedWatchlist = userSettings.getCustomWatchlist("RED"),
                customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW"),
                isProduce = false,
                isRecipe = true
            )
        } else null

        if (originalEvalResult != null && adjustedEvalResult != null && originalJson != null && adjustedJson != null) {
            cachedOriginalGrade = originalEvalResult.gradeTitle
            val origReds = originalEvalResult.redViolations.distinct()
            val origYellows = originalEvalResult.yellowViolations.distinct()
            cachedOriginalViolations = if (origReds.isNotEmpty()) origReds.joinToString("\n• ", prefix = "• ")
            else if (origYellows.isNotEmpty()) origYellows.joinToString("\n• ", prefix = "• ")
            else ""

            cachedAdjustedGrade = adjustedEvalResult.gradeTitle
            val adjReds = adjustedEvalResult.redViolations.distinct()
            val adjYellows = adjustedEvalResult.yellowViolations.distinct()
            cachedAdjustedViolations = if (adjReds.isNotEmpty()) adjReds.joinToString("\n• ", prefix = "• ")
            else if (adjYellows.isNotEmpty()) adjYellows.joinToString("\n• ", prefix = "• ")
            else ""

            val origSod = originalJson.optDouble("sodium_mg", 0.0) / servings
            val adjSod = adjustedJson.optDouble("sodium_mg", 0.0) / servings
            val origPot = originalJson.optDouble("potassium_mg", 0.0) / servings
            val adjPot = adjustedJson.optDouble("potassium_mg", 0.0) / servings
            val origCarb = (originalJson.optDouble("total_carbohydrates_g", 0.0) - originalJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
            val adjCarb = (adjustedJson.optDouble("total_carbohydrates_g", 0.0) - adjustedJson.optDouble("fiber_g", 0.0)).coerceAtLeast(0.0) / servings
            val origProt = originalJson.optDouble("protein_g", 0.0) / servings
            val adjProt = adjustedJson.optDouble("protein_g", 0.0) / servings

            updateTelemetryGauges(
                origSod, adjSod,
                origPot, adjPot,
                origCarb, adjCarb,
                origProt, adjProt,
                userSettings
            )
        } else {
            cardClinicalGauge.visibility = View.GONE
        }

        showResultsState()
        layoutPostAnalysisActions.visibility = View.VISIBLE

        if (cachedRecipe.instructions.isNotBlank()) {
            btnRetrieveSteps.isEnabled = false
            btnRetrieveSteps.text = "Steps Loaded"
        } else {
            btnRetrieveSteps.isEnabled = true
            btnRetrieveSteps.text = "Get Steps"
        }
        btnRetrieveAnalysis.isEnabled = true
        btnRetrieveAnalysis.text = "Get Analysis"

        btnCopyAdjusted.text = "Saved in Cookbook"
        btnCopyAdjusted.isEnabled = false

        renderSourceChip(cachedRecipe.sourceUrl)
        Toast.makeText(requireContext(), "Loaded instantly from local cache!", Toast.LENGTH_SHORT).show()
    }

    private suspend fun fetchRecipeNutritionFromBackend(
        recipeText: String,
        activeProfiles: Set<String>,
        servingsCount: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(backendAnalysisUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 90000
            conn.doOutput = true

            val context = requireContext()
            val userSettings = AppSettings(context)

            val userAllergiesList = try {
                userSettings.getCustomWatchlist("RED").toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            for (profileId in activeProfiles) {
                try {
                    val triggers = userSettings.getIngredientsFromAssetFile(profileId)
                    userAllergiesList.addAll(triggers)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val uniqueAllergies = userAllergiesList.map { it.trim().uppercase() }.distinct()

            val thresholdsObj = JSONObject()
            val targetNutrientKeys = listOf(
                "potassium", "sodium", "protein", "carbs", "calories",
                "saturated_fat", "total_fat", "total_sugar", "added_sugar"
            )
            for (key in targetNutrientKeys) {
                val dynamicLimits = userSettings.getNutrientThresholds(key)
                val yellowWarning = dynamicLimits.first
                val redLimit = dynamicLimits.second
                if (redLimit < 999) {
                    thresholdsObj.put(key, JSONObject().apply {
                        put("yellow_warning", yellowWarning.toDouble())
                        put("red_limit", redLimit.toDouble())
                    })
                }
            }

            val conditionsList = activeProfiles.map { profileId ->
                val baseId = userSettings.getBaseProfileIdForProfile(profileId)
                if (baseId.isNotEmpty()) baseId else profileId
            }.distinct()

            val jsonRequest = JSONObject().apply {
                put("action", "analyze")
                put("recipe_text", recipeText)
                put("conditions", JSONArray(conditionsList))
                put("allergies", JSONArray(uniqueAllergies))
                put("thresholds", thresholdsObj)
                put("bypass_cache", true)
                put("servings", servingsCount)

                val interventionsObj = JSONObject().apply {
                    put("avoid_phosphate_additives", userSettings.isFeatureFlagActive("avoid_phosphate_additives"))
                    put("prefer_preblended_gf_flour", userSettings.isFeatureFlagActive("prefer_preblended_gf_flour"))
                    put("strictly_avoid_coconut", userSettings.isFeatureFlagActive("strictly_avoid_coconut"))
                    put("limit_saturated_fats", userSettings.isFeatureFlagActive("limit_saturated_fats"))
                }
                put("interventions", interventionsObj)
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

                        activeHistoryId?.let { id ->
                            SavedPersistenceManager.updateItemInstructions(requireContext(), id, instructions)
                        }

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
            val url = URL(backendAnalysisUrl)
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
}