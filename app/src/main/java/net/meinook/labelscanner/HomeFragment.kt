package net.meinook.labelscanner

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var textExplanation: TextView
    private lateinit var textCondition: TextView
    private lateinit var cardSummary: MaterialCardView
    private lateinit var textSummaryGrade: TextView
    private lateinit var textSummaryExplanation: TextView

    private lateinit var tempPhotoUri: Uri
    private var isAnalyzing: Boolean = false

    // --- STATE CACHE (The "Memory") ---
    private var cachedEval: EvaluationResult? = null
    private var cachedSub: String = ""
    private var cachedMacros: Bundle? = null

    private val labelCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val inputStream = requireContext().contentResolver.openInputStream(tempPhotoUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap?.let { runAnalysis(it) }
        }
    }

    private val barcodeScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val upc = result.data?.getStringExtra("SCAN_RESULT") ?: ""
            if (upc.isNotEmpty()) lookupBarcodeOnline(upc)
        }
    }

    private val produceCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val inputStream = requireContext().contentResolver.openInputStream(tempPhotoUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap?.let { runProduceAnalysis(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textExplanation = view.findViewById(R.id.textExplanation)
        textCondition = view.findViewById(R.id.textCondition)
        cardSummary = view.findViewById(R.id.cardSummary)
        textSummaryGrade = view.findViewById(R.id.textSummaryGrade)
        textSummaryExplanation = view.findViewById(R.id.textSummaryExplanation)

        view.findViewById<Button>(R.id.btnLabelScan).setOnClickListener { resetUI(); launchCameraExplicit(labelCameraLauncher) }
        view.findViewById<Button>(R.id.btnBarcodeScan).setOnClickListener { resetUI(); barcodeScannerLauncher.launch(Intent(requireContext(), ScannerActivity::class.java)) }
        view.findViewById<Button>(R.id.btnProduceScan).setOnClickListener { resetUI(); launchCameraExplicit(produceCameraLauncher) }

        // --- RESTORE UI IF RETURNING FROM DETAIL ---
        cachedEval?.let { eval ->
            displaySummaryCard(eval, cachedSub, 0, 0f, 0, 0f, 0f, 0f, 0f, false, isRestoring = true)
        }

        updateConditionText()
    }

    private fun launchCameraExplicit(launcher: androidx.activity.result.ActivityResultLauncher<Uri>) {
        val photoFile = File(requireContext().externalCacheDir, "temp_capture.jpg")
        tempPhotoUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", photoFile)
        launcher.launch(tempPhotoUri)
    }

    private fun lookupBarcodeOnline(upcCode: String) {
        textExplanation.text = "Searching online..."
        kotlin.concurrent.thread {
            try {
                val url = URL("https://world.openfoodfacts.org/api/v2/product/$upcCode.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "LabelScanner/1.0")
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    if (json.optInt("status", 0) == 1) {
                        val product = json.getJSONObject("product")
                        val nutriments = product.optJSONObject("nutriments") ?: JSONObject()
                        val ingredients = product.optString("ingredients_text", "").split(",").map { it.trim().uppercase() }

                        val evalJson = JSONObject().apply {
                            put("calories", nutriments.optInt("energy-kcal_serving", 0))
                            put("sodium", (nutriments.optDouble("sodium_serving", 0.0) * 1000).toInt())
                            put("protein", nutriments.optDouble("proteins_serving", 0.0))
                            put("carbs", nutriments.optDouble("carbohydrates_serving", 0.0))
                            put("fiber", nutriments.optDouble("fiber_serving", 0.0))
                            put("sugar", nutriments.optDouble("sugars_serving", 0.0))
                        }

                        val userSettings = AppSettings(requireContext())
                        val evalResult = LabelEvaluator.evaluateScanData(evalJson, ingredients, userSettings.getSelectedConditions(), userSettings,
                            userSettings.loadTriggersFromAssets("red"), userSettings.getCustomWatchlist("RED"), userSettings.getCustomWatchlist("YELLOW"))

                        activity?.runOnUiThread {
                            displaySummaryCard(evalResult, product.optString("product_name", "Product"),
                                evalJson.getInt("calories"), evalJson.getDouble("protein").toFloat(),
                                evalJson.getInt("sodium"), 0f, evalJson.getDouble("carbs").toFloat(),
                                (evalJson.getDouble("carbs") - evalJson.getDouble("fiber")).toFloat(),
                                evalJson.getDouble("sugar").toFloat(), userSettings.getSelectedConditions().contains("keto"))
                        }
                    }
                }
            } catch (e: Exception) { activity?.runOnUiThread { textExplanation.text = "Error." } }
        }
    }

    private fun runAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        activity?.runOnUiThread { textExplanation.text = "Analyzing..." }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userSettings = AppSettings(requireContext())
                val rawResponse = GeminiAnalyzer.analyzeIngredientsImage(imageBitmap, getConditionsString(userSettings, userSettings.getSelectedConditions()), BuildConfig.GEMINI_API_KEY, getString(R.string.model_identifier_txt))
                val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())
                val ingredients = (json.optJSONArray("detected_ingredients") ?: json.optJSONArray("ingredients"))?.let { arr -> (0 until arr.length()).map { arr.getString(it).uppercase() } } ?: emptyList()

                val evalResult = LabelEvaluator.evaluateScanData(json, ingredients, userSettings.getSelectedConditions(), userSettings,
                    userSettings.loadTriggersFromAssets("red"), userSettings.getCustomWatchlist("RED"), userSettings.getCustomWatchlist("YELLOW"))

                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    val fiber = if(json.has("fiber_g")) json.optDouble("fiber_g") else json.optDouble("fiber", 0.0)
                    val carbs = if(json.has("total_carbohydrates_g")) json.optDouble("total_carbohydrates_g") else json.optDouble("carbs", 0.0)
                    displaySummaryCard(evalResult, "Scan Result", json.optInt("calories"), json.optDouble("protein_g", 0.0).toFloat(),
                        json.optInt("sodium_mg", 0), 0f, carbs.toFloat(), (carbs - fiber).toFloat(), json.optDouble("total_sugar_g", 0.0).toFloat(), userSettings.getSelectedConditions().contains("keto"))
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { textExplanation.text = "Failed." } }
        }
    }

    private fun runProduceAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawResponse = GeminiAnalyzer.analyzeProduceImage(imageBitmap, BuildConfig.GEMINI_API_KEY, getString(R.string.model_identifier_txt))
                val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())
                val name = json.optString("item_name", "Produce")
                val evalResult = LabelEvaluator.evaluateScanData(json, listOf(name.uppercase()), AppSettings(requireContext()).getSelectedConditions(), AppSettings(requireContext()), emptyList(), emptyList(), emptyList(), true)
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    displaySummaryCard(evalResult, name, json.optInt("calories"), 0f, 0, 0f, 0f, 0f, 0f, false)
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { textExplanation.text = "Failed." } }
        }
    }

    private fun displaySummaryCard(
        evaluation: EvaluationResult,
        subtitle: String,
        calories: Int,
        protein: Float,
        sodium: Int,
        potassium: Float,
        carbs: Float,
        netCarbs: Float,
        sugar: Float,
        isKeto: Boolean,
        isRestoring: Boolean = false // Flag to prevent infinite loops
    ) {
        // 1. Save to Memory
        if (!isRestoring) {
            cachedEval = evaluation
            cachedSub = subtitle
            cachedMacros = Bundle().apply {
                putInt("cal", calories); putFloat("pro", protein); putInt("sod", sodium)
                putFloat("pot", potassium); putFloat("carb", carbs); putFloat("net_carb", netCarbs)
                putFloat("sug", sugar); putBoolean("is_keto", isKeto)
            }
        }

        // 2. Show the Card
        cardSummary.visibility = View.VISIBLE
        cardSummary.setCardBackgroundColor(evaluation.bgColor)
        textSummaryGrade.text = evaluation.gradeTitle
        textSummaryGrade.setTextColor(evaluation.textColor)
        textSummaryExplanation.text = if (evaluation.redViolations.isNotEmpty()) "Avoid: ${evaluation.redViolations.first()}" else "Tap for Details"
        textExplanation.text = ""

        // 3. Set Navigation Click
        cardSummary.setOnClickListener {
            val bundle = Bundle().apply {
                putSerializable("EVAL", evaluation)
                putString("SUB", subtitle)
                putBundle("MACROS", cachedMacros)
            }
            val extras = FragmentNavigatorExtras(cardSummary to "shared_element_container")
            findNavController().navigate(R.id.action_home_to_detail, bundle, null, extras)
        }
    }

    private fun resetUI() {
        activity?.runOnUiThread {
            cardSummary.visibility = View.GONE
            cachedEval = null // Clear the memory for a fresh scan
            textExplanation.text = "Ready..."
            textExplanation.setTextColor(Color.WHITE)
        }
    }

    private fun updateConditionText() {
        val userSettings = AppSettings(requireContext())
        textCondition.text = "Target: ${getConditionsString(userSettings, userSettings.getSelectedConditions())}"
    }

    private fun getConditionsString(s: AppSettings, ids: Set<String>) = if (ids.isNotEmpty()) ids.joinToString(", ") { id -> s.getAvailableDietProfiles().find { it.id == id }?.displayName ?: id } else "Standard"

    override fun onResume() { super.onResume(); updateConditionText() }
}