package net.meinook.labelscanner

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
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

private val CameraPlaceholderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "CameraPlaceholder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.White)) {
        moveTo(9f, 2f)
        lineTo(7.17f, 4f)
        horizontalLineTo(4f)
        curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
        verticalLineTo(18f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        horizontalLineTo(20f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        verticalLineTo(6f)
        curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
        horizontalLineTo(16.83f)
        lineTo(15f, 2f)
        horizontalLineTo(9f)
        close()
        moveTo(12f, 17f)
        curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
        curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
        curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
        curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
        close()
    }.build()
}

private val BarcodePlaceholderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BarcodePlaceholder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.White)) {
        moveTo(2f, 4f); horizontalLineTo(4f); verticalLineTo(20f); horizontalLineTo(2f); close()
        moveTo(5f, 4f); horizontalLineTo(6f); verticalLineTo(20f); horizontalLineTo(5f); close()
        moveTo(8f, 4f); horizontalLineTo(11f); verticalLineTo(20f); horizontalLineTo(8f); close()
        moveTo(12f, 4f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(12f); close()
        moveTo(15f, 4f); horizontalLineTo(16f); verticalLineTo(20f); horizontalLineTo(15f); close()
        moveTo(18f, 4f); horizontalLineTo(21f); verticalLineTo(20f); horizontalLineTo(18f); close()
    }.build()
}

private val ProducePlaceholderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ProducePlaceholder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.White)) {
        moveTo(12f, 2f)
        curveTo(12f, 2f, 4f, 6f, 4f, 13f)
        curveTo(4f, 18f, 8f, 22f, 12f, 22f)
        curveTo(16f, 22f, 20f, 18f, 20f, 13f)
        curveTo(20f, 6f, 12f, 2f, 12f, 2f)
        close()
    }.build()
}

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var textExplanation: TextView
    private lateinit var textCondition: TextView
    private lateinit var cardSummary: MaterialCardView
    private lateinit var textSummaryGrade: TextView
    private lateinit var textSummaryExplanation: TextView

    private lateinit var tempPhotoUri: Uri
    private var isAnalyzing: Boolean = false

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

        val userSettings = AppSettings(requireContext())
        val isRightHanded = userSettings.isRightHanded()

        val composeView = view.findViewById<ComposeView>(R.id.composeViewMenu)
        composeView.setContent {
            VerticalThumbArchMenu(
                isRightHanded = isRightHanded,
                onLabelClick = { resetUI(); launchCameraExplicit(labelCameraLauncher) },
                onBarcodeClick = { resetUI(); barcodeScannerLauncher.launch(Intent(requireContext(), ScannerActivity::class.java)) },
                onProduceClick = { resetUI(); launchCameraExplicit(produceCameraLauncher) }
            )
        }

        cachedEval?.let { eval ->
            displaySummaryCard(eval, cachedSub, 0, 0f, 0, 0f, 0f, 0f, 0f, false, isRestoring = true)
        }
        updateConditionText()
    }

    @Composable
    fun VerticalThumbArchMenu(
        isRightHanded: Boolean,
        onLabelClick: () -> Unit,
        onBarcodeClick: () -> Unit,
        onProduceClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
            contentAlignment = if (isRightHanded) Alignment.BottomEnd else Alignment.BottomStart
        ) {
            ThumbMenuButton(
                isRightHanded = isRightHanded,
                text = "Fresh Produce",
                icon = ProducePlaceholderIcon,
                containerColor = Color(0xFF1B5E20),
                xOffset = if (isRightHanded) (-10).dp else 10.dp,
                yOffset = (-190).dp,
                onClick = onProduceClick
            )

            ThumbMenuButton(
                isRightHanded = isRightHanded,
                text = "Barcode Lookup",
                icon = BarcodePlaceholderIcon,
                containerColor = Color(0xFF2C3A47),
                xOffset = if (isRightHanded) (-80).dp else 80.dp,
                yOffset = (-65).dp,
                onClick = onBarcodeClick
            )

            ThumbMenuButton(
                isRightHanded = isRightHanded,
                text = "Camera Scan",
                icon = CameraPlaceholderIcon,
                containerColor = Color(0xFF4A148C),
                xOffset = if (isRightHanded) (-130).dp else 130.dp,
                yOffset = 60.dp,
                onClick = onLabelClick
            )
        }
    }

    @Composable
    private fun ThumbMenuButton(
        isRightHanded: Boolean,
        text: String,
        icon: ImageVector,
        containerColor: Color,
        xOffset: androidx.compose.ui.unit.Dp,
        yOffset: androidx.compose.ui.unit.Dp,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier.offset(x = xOffset, y = yOffset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRightHanded) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                FloatingActionButton(
                    onClick = onClick,
                    shape = CircleShape,
                    containerColor = containerColor,
                    contentColor = Color.White,
                    modifier = Modifier.size(96.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                FloatingActionButton(
                    onClick = onClick,
                    shape = CircleShape,
                    containerColor = containerColor,
                    contentColor = Color.White,
                    modifier = Modifier.size(96.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = text,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    private fun launchCameraExplicit(launcher: androidx.activity.result.ActivityResultLauncher<Uri>) {
        val photoFile = File(requireContext().externalCacheDir, "temp_capture.jpg")
        tempPhotoUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", photoFile)
        launcher.launch(tempPhotoUri)
    }

    private fun lookupBarcodeOnline(upcCode: String) {
        textExplanation.text = "Searching..."
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

                        // Robust schema mapper that strictly writes keys ONLY if they are populated [1]
                        val evalJson = JSONObject().apply {
                            fun hasVal(vararg keys: String): Boolean {
                                return keys.any { nutriments.has(it) && !nutriments.isNull(it) }
                            }

                            // Check for Calories keys (serving, then 100g fallback) [1]
                            if (hasVal("energy-kcal_serving")) {
                                put("calories", nutriments.optInt("energy-kcal_serving", 0))
                            } else if (hasVal("energy-kcal_100g")) {
                                put("calories", nutriments.optInt("energy-kcal_100g", 0))
                            } else if (hasVal("energy-kcal")) {
                                put("calories", nutriments.optInt("energy-kcal", 0))
                            }

                            // Check for Sodium keys (serving, then 100g fallback) [1]
                            if (hasVal("sodium_serving")) {
                                put("sodium", (nutriments.optDouble("sodium_serving", 0.0) * 1000).toInt())
                            } else if (hasVal("sodium_100g")) {
                                put("sodium", (nutriments.optDouble("sodium_100g", 0.0) * 1000).toInt())
                            } else if (hasVal("sodium")) {
                                put("sodium", (nutriments.optDouble("sodium", 0.0) * 1000).toInt())
                            }

                            // Optional macronutrient schema checking
                            if (hasVal("proteins_serving")) put("protein", nutriments.optDouble("proteins_serving", 0.0))
                            else if (hasVal("proteins_100g")) put("protein", nutriments.optDouble("proteins_100g", 0.0))

                            if (hasVal("carbohydrates_serving")) put("carbs", nutriments.optDouble("carbohydrates_serving", 0.0))
                            else if (hasVal("carbohydrates_100g")) put("carbs", nutriments.optDouble("carbohydrates_100g", 0.0))

                            if (hasVal("fiber_serving")) put("fiber", nutriments.optDouble("fiber_serving", 0.0))
                            else if (hasVal("fiber_100g")) put("fiber", nutriments.optDouble("fiber_100g", 0.0))

                            if (hasVal("sugars_serving")) put("sugar", nutriments.optDouble("sugars_serving", 0.0))
                            else if (hasVal("sugars_100g")) put("sugar", nutriments.optDouble("sugars_100g", 0.0))
                        }

                        val userSettings = AppSettings(requireContext())
                        val evalResult = LabelEvaluator.evaluateScanData(
                            evalJson,
                            ingredients,
                            userSettings.getSelectedConditions(),
                            userSettings,
                            userSettings.loadTriggersFromAssets("red"),
                            userSettings.getCustomWatchlist("RED"),
                            userSettings.getCustomWatchlist("YELLOW")
                        )
                        activity?.runOnUiThread {
                            displaySummaryCard(
                                evalResult,
                                product.optString("product_name", "Product"),
                                evalJson.optInt("calories", 0),
                                evalJson.optDouble("protein", 0.0).toFloat(),
                                evalJson.optInt("sodium", 0),
                                0f,
                                evalJson.optDouble("carbs", 0.0).toFloat(),
                                (evalJson.optDouble("carbs", 0.0) - evalJson.optDouble("fiber", 0.0)).toFloat(),
                                evalJson.optDouble("sugar", 0.0).toFloat(),
                                userSettings.getSelectedConditions().contains("keto")
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LabelScanner", "Barcode lookup background error", e)
                activity?.runOnUiThread {
                    textExplanation.text = "Error: ${e.localizedMessage ?: "Unknown connection failure"}"
                }
            }
        }
    }

    private fun extractIngredientsFromJson(json: JSONObject): List<String> {
        val keys = listOf(
            "di", "detected_ingredients", "ingredients", "ingredients_list",
            "ingredient_list", "detected_ingredients_list"
        )
        for (key in keys) {
            if (!json.has(key)) continue

            val arr = json.optJSONArray(key)
            if (arr != null) {
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "")
                    if (item.isNotEmpty()) {
                        list.add(item.uppercase().trim())
                    }
                }
                if (list.isNotEmpty()) return list
            }

            val str = json.optString(key, "")
            if (str.isNotEmpty()) {
                return str.split(",")
                    .map { it.uppercase().trim() }
                    .filter { it.isNotEmpty() }
            }
        }
        return emptyList()
    }

    private fun runAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        activity?.runOnUiThread { textExplanation.text = "Analyzing..." }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userSettings = AppSettings(requireContext())
                val rawResponse = GeminiAnalyzer.analyzeIngredientsImage(
                    imageBitmap,
                    getConditionsString(userSettings, userSettings.getSelectedConditions()),
                    BuildConfig.GEMINI_API_KEY,
                    getString(R.string.model_identifier_txt)
                )

                Log.d("LabelScanner", "HomeFragment: Raw Gemini Response = $rawResponse")

                val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())

                val ingredients = extractIngredientsFromJson(json)
                val loadedRedTriggers = userSettings.loadTriggersFromAssets("red")

                Log.d("LabelScanner", "HomeFragment: Loaded red triggers = $loadedRedTriggers")
                Log.d("LabelScanner", "HomeFragment: Safely Extracted Ingredients = $ingredients")

                val evalResult = LabelEvaluator.evaluateScanData(
                    json,
                    ingredients,
                    userSettings.getSelectedConditions(),
                    userSettings,
                    loadedRedTriggers,
                    userSettings.getCustomWatchlist("RED"),
                    userSettings.getCustomWatchlist("YELLOW")
                )
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    val fiber = if(json.has("fiber_g")) json.optDouble("fiber_g") else json.optDouble("fiber", 0.0)
                    val carbs = if(json.has("total_carbohydrates_g")) json.optDouble("total_carbohydrates_g") else json.optDouble("carbs", 0.0)
                    displaySummaryCard(
                        evalResult,
                        "Scan Result",
                        json.optInt("calories"),
                        json.optDouble("protein_g", 0.0).toFloat(),
                        json.optInt("sodium_mg", 0),
                        0f,
                        carbs.toFloat(),
                        (carbs - fiber).toFloat(),
                        json.optDouble("total_sugar_g", 0.0).toFloat(),
                        userSettings.getSelectedConditions().contains("keto")
                    )
                }
            } catch (e: Exception) {
                Log.e("LabelScanner", "Camera Scan analysis error", e)
                withContext(Dispatchers.Main) {
                    textExplanation.text = "Failed: ${e.localizedMessage ?: "Analysis error"}"
                }
            }
        }
    }

    private fun runProduceAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawResponse = GeminiAnalyzer.analyzeProduceImage(
                    imageBitmap,
                    BuildConfig.GEMINI_API_KEY,
                    getString(R.string.model_identifier_txt)
                )
                Log.d("LabelScanner", "HomeFragment: Raw Produce Response = $rawResponse")

                val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())
                val name = json.optString("item_name", "Produce")
                val evalResult = LabelEvaluator.evaluateScanData(
                    json,
                    listOf(name.uppercase().trim()),
                    AppSettings(requireContext()).getSelectedConditions(),
                    AppSettings(requireContext()),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    true
                )
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    displaySummaryCard(evalResult, name, json.optInt("calories"), 0f, 0, 0f, 0f, 0f, 0f, false)
                }
            } catch (e: Exception) {
                Log.e("LabelScanner", "Produce analysis error", e)
                withContext(Dispatchers.Main) {
                    textExplanation.text = "Failed: ${e.localizedMessage ?: "Produce error"}"
                }
            }
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
        isRestoring: Boolean = false
    ) {
        if (!isRestoring) {
            cachedEval = evaluation
            cachedSub = subtitle
            cachedMacros = Bundle().apply {
                putInt("cal", calories); putFloat("pro", protein); putInt("sod", sodium)
                putFloat("pot", potassium); putFloat("carb", carbs); putFloat("net_carb", netCarbs)
                putFloat("sug", sugar); putBoolean("is_keto", isKeto)
            }
        }
        cardSummary.visibility = View.VISIBLE

        cardSummary.setCardBackgroundColor(evaluation.bgColor)
        cardSummary.strokeColor = evaluation.textColor
        cardSummary.strokeWidth = (2 * resources.displayMetrics.density).toInt()

        textSummaryGrade.text = evaluation.gradeTitle
        textSummaryGrade.setTextColor(evaluation.textColor)

        val explanationText = if (evaluation.redViolations.isNotEmpty()) {
            val first = evaluation.redViolations.first()
            if (first.contains(":")) "Avoid: ${first.substringBefore(":")}" else "Avoid: $first"
        } else if (evaluation.yellowViolations.isNotEmpty()) {
            val first = evaluation.yellowViolations.first()
            if (first.contains(":")) "Caution: ${first.substringBefore(":")}" else "Caution: $first"
        } else {
            "Enjoy your meal"
        }

        textSummaryExplanation.text = explanationText
        textSummaryExplanation.setTextColor(evaluation.subtextColor)

        val textTapPrompt = cardSummary.findViewById<TextView>(R.id.textTapPrompt)
        textTapPrompt?.setTextColor(evaluation.textColor)

        textExplanation.text = ""

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
            cachedEval = null
            textExplanation.text = "Ready..."
            textExplanation.setTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun getConditionsString(s: AppSettings, ids: Set<String>) =
        if (ids.isNotEmpty()) {
            ids.joinToString(", ") { id -> s.getAvailableDietProfiles().find { it.id == id }?.displayName ?: id }
        } else {
            "Standard"
        }

    private fun updateConditionText() {
        textCondition.text = "Target: ${getConditionsString(AppSettings(requireContext()), AppSettings(requireContext()).getSelectedConditions())}"
    }

    override fun onResume() {
        super.onResume()
        updateConditionText()
    }
}