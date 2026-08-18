package net.meinook.labelscanner

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private var cachedSuggestions: ArrayList<ProductAlternative>? = null
    private var isNavigatingToDetail: Boolean = false

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

    private enum class PendingCameraAction { NONE, CAMERA_SCAN, PRODUCE_SCAN, BARCODE_SCAN }
    private var pendingAction = PendingCameraAction.NONE

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            executePendingCameraAction()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to scan labels and barcodes.", Toast.LENGTH_LONG).show()
        }
        pendingAction = PendingCameraAction.NONE
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
                onLabelClick = { runWithCameraPermission(PendingCameraAction.CAMERA_SCAN) },
                onBarcodeClick = { runWithCameraPermission(PendingCameraAction.BARCODE_SCAN) },
                onProduceClick = { runWithCameraPermission(PendingCameraAction.PRODUCE_SCAN) }
            )
        }

        val bottomNav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
            ?: activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
            ?: activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav_host_fragment)

        bottomNav?.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.navigation_home) {
                resetUI()
            }
        }

        view.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        arguments?.getString("AUTO_LOOKUP_BARCODE")?.let { barcode ->
            arguments?.remove("AUTO_LOOKUP_BARCODE")
            lookupBarcodeOnline(barcode)
        }

        cachedEval?.let { eval ->
            displaySummaryCard(
                evaluation = eval,
                subtitle = cachedSub,
                calories = 0,
                protein = 0f,
                sodium = 0,
                potassium = 0f,
                carbs = 0f,
                netCarbs = 0f,
                sugar = 0f,
                isKeto = false,
                isRestoring = true,
                suggestions = cachedSuggestions
            )
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
        val unifiedButtonColor = Color(0xFF32221A)

        val produceIcon = ImageVector.vectorResource(id = R.drawable.ic_produce)
        val barcodeIcon = ImageVector.vectorResource(id = R.drawable.ic_barcode)
        val cameraIcon = ImageVector.vectorResource(id = R.drawable.ic_camera)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
            contentAlignment = if (isRightHanded) Alignment.BottomEnd else Alignment.BottomStart
        ) {
            ThumbMenuButton(
                icon = produceIcon,
                containerColor = unifiedButtonColor,
                xOffset = if (isRightHanded) (-10).dp else 10.dp,
                yOffset = (-190).dp,
                onClick = onProduceClick
            )

            ThumbMenuButton(
                icon = barcodeIcon,
                containerColor = unifiedButtonColor,
                xOffset = if (isRightHanded) (-80).dp else 80.dp,
                yOffset = (-65).dp,
                onClick = onBarcodeClick
            )

            ThumbMenuButton(
                icon = cameraIcon,
                containerColor = unifiedButtonColor,
                xOffset = if (isRightHanded) (-130).dp else 130.dp,
                yOffset = 60.dp,
                onClick = onLabelClick
            )
        }
    }

    @Composable
    private fun ThumbMenuButton(
        icon: ImageVector,
        containerColor: Color,
        xOffset: androidx.compose.ui.unit.Dp,
        yOffset: androidx.compose.ui.unit.Dp,
        onClick: () -> Unit
    ) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = containerColor,
            modifier = Modifier
                .size(90.dp)
                .offset(x = xOffset, y = yOffset)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(64.dp)
            )
        }
    }

    private fun launchCameraExplicit(launcher: androidx.activity.result.ActivityResultLauncher<Uri>) {
        val photoFile = File(requireContext().externalCacheDir, "temp_capture.jpg")
        tempPhotoUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", photoFile)
        launcher.launch(tempPhotoUri)
    }

    private fun lookupBarcodeOnline(upcCode: String) {
        textExplanation.text = "Searching..."

        val cleanCode = upcCode.trim()

        val isVariableWeight = (cleanCode.length == 12 && cleanCode.startsWith("2")) ||
                (cleanCode.length == 13 && (cleanCode.startsWith("02") || (cleanCode.substring(0, 2).toIntOrNull() in 20..29)))

        if (isVariableWeight) {
            activity?.runOnUiThread {
                resetUI()
                textExplanation.text = "This is a store-packaged variable weight item. Please use 'Camera Scan' to evaluate its ingredient label directly!"
                textExplanation.setTextColor(android.graphics.Color.WHITE)
            }
            return
        }

        kotlin.concurrent.thread {
            try {
                val url = URL("https://world.openfoodfacts.org/api/v2/product/$cleanCode.json")
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

                        val categoriesTags = product.optJSONArray("categories_tags")
                        var categoryTag: String? = null
                        if (categoriesTags != null && categoriesTags.length() > 0) {
                            for (i in categoriesTags.length() - 1 downTo 0) {
                                val tag = categoriesTags.optString(i, "")
                                if (tag.startsWith("en:")) {
                                    categoryTag = tag
                                    break
                                }
                            }
                        }

                        val evalJson = JSONObject().apply {
                            fun hasVal(vararg keys: String): Boolean {
                                return keys.any { nutriments.has(it) && !nutriments.isNull(it) }
                            }

                            if (hasVal("energy-kcal_serving")) {
                                put("calories", nutriments.optInt("energy-kcal_serving", 0))
                            } else if (hasVal("energy-kcal_100g")) {
                                put("calories", nutriments.optInt("energy-kcal_100g", 0))
                            } else if (hasVal("energy-kcal")) {
                                put("calories", nutriments.optInt("energy-kcal", 0))
                            }

                            if (hasVal("sodium_serving")) {
                                put("sodium", (nutriments.optDouble("sodium_serving", 0.0) * 1000).toInt())
                            } else if (hasVal("sodium_100g")) {
                                put("sodium", (nutriments.optDouble("sodium_100g", 0.0) * 1000).toInt())
                            } else if (hasVal("sodium")) {
                                put("sodium", (nutriments.optDouble("sodium", 0.0) * 1000).toInt())
                            }

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

                        val suggestionsList = ArrayList<ProductAlternative>()
                        val tempYellowSuggestions = ArrayList<ProductAlternative>()

                        Log.d("LabelScanner", "Category Search: Target category code = $categoryTag, product grade = ${evalResult.gradeTitle}")

                        if (!categoryTag.isNullOrEmpty() && (evalResult.gradeTitle.startsWith("Red") || evalResult.gradeTitle.startsWith("Yellow"))) {
                            try {
                                val cleanCategory = categoryTag.removePrefix("en:").trim()
                                val searchUrl = URL("https://world.openfoodfacts.org/api/v2/search?categories_tags_en=$cleanCategory&fields=code,product_name,brands,ingredients_text,nutriments&page_size=15")

                                Log.d("LabelScanner", "Category Search: Querying URL = $searchUrl")

                                val searchConnection = searchUrl.openConnection() as HttpURLConnection
                                searchConnection.requestMethod = "GET"
                                searchConnection.setRequestProperty("User-Agent", "LabelScanner/1.0")

                                if (searchConnection.responseCode == 200) {
                                    val searchResponse = searchConnection.inputStream.bufferedReader().use { it.readText() }
                                    val searchJson = JSONObject(searchResponse)
                                    val productsArr = searchJson.optJSONArray("products")

                                    Log.d("LabelScanner", "Category Search: Found raw products = ${productsArr?.length() ?: 0}")

                                    if (productsArr != null) {
                                        for (j in 0 until productsArr.length()) {
                                            val altProduct = productsArr.getJSONObject(j)
                                            val altCode = altProduct.optString("code", "")
                                            if (altCode == cleanCode) continue

                                            val altNutriments = altProduct.optJSONObject("nutriments") ?: JSONObject()
                                            val altIngredients = altProduct.optString("ingredients_text", "").split(",").map { it.trim().uppercase() }

                                            val altEvalJson = JSONObject().apply {
                                                fun hasVal(vararg keys: String): Boolean {
                                                    return keys.any { altNutriments.has(it) && !altNutriments.isNull(it) }
                                                }
                                                if (hasVal("energy-kcal_serving")) put("calories", altNutriments.optInt("energy-kcal_serving", 0))
                                                else if (hasVal("energy-kcal_100g")) put("calories", altNutriments.optInt("energy-kcal_100g", 0))

                                                if (hasVal("sodium_serving")) put("sodium", (altNutriments.optDouble("sodium_serving", 0.0) * 1000).toInt())
                                                else if (hasVal("sodium_100g")) put("sodium", (altNutriments.optDouble("sodium_100g", 0.0) * 1000).toInt())

                                                if (hasVal("proteins_serving")) put("protein", altNutriments.optDouble("proteins_serving", 0.0))
                                                if (hasVal("carbohydrates_serving")) put("carbs", altNutriments.optDouble("carbohydrates_serving", 0.0))
                                                if (hasVal("fiber_serving")) put("fiber", altNutriments.optDouble("fiber_serving", 0.0))
                                                if (hasVal("sugars_serving")) put("sugar", altNutriments.optDouble("sugars_serving", 0.0))
                                            }

                                            val altEvalResult = LabelEvaluator.evaluateScanData(
                                                altEvalJson,
                                                altIngredients,
                                                userSettings.getSelectedConditions(),
                                                userSettings,
                                                userSettings.loadTriggersFromAssets("red"),
                                                userSettings.getCustomWatchlist("RED"),
                                                userSettings.getCustomWatchlist("YELLOW")
                                            )

                                            Log.d("LabelScanner", "Category Search: Evaluated alt ${altProduct.optString("product_name")} -> Grade: ${altEvalResult.gradeTitle}")

                                            if (altEvalResult.gradeTitle.startsWith("Green")) {
                                                suggestionsList.add(
                                                    ProductAlternative(
                                                        name = altProduct.optString("product_name", "Alternative Option"),
                                                        brand = altProduct.optString("brands", ""),
                                                        code = altCode,
                                                        gradeTitle = altEvalResult.gradeTitle
                                                    )
                                                )
                                            } else if (altEvalResult.gradeTitle.startsWith("Yellow")) {
                                                tempYellowSuggestions.add(
                                                    ProductAlternative(
                                                        name = altProduct.optString("product_name", "Alternative Option"),
                                                        brand = altProduct.optString("brands", ""),
                                                        code = altCode,
                                                        gradeTitle = altEvalResult.gradeTitle
                                                    )
                                                )
                                            }
                                            if (suggestionsList.size >= 3) break
                                        }

                                        for (yellowOpt in tempYellowSuggestions) {
                                            if (suggestionsList.size >= 3) break
                                            suggestionsList.add(yellowOpt)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("LabelScanner", "Error querying dynamic safer alternatives", e)
                            }
                        }

                        Log.d("LabelScanner", "Category Search: Compiled suggestion list size = ${suggestionsList.size}")

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
                                userSettings.getSelectedConditions().contains("keto"),
                                suggestions = suggestionsList
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
                    displaySummaryCard(evalResult,
                        name,
                        json.optInt("calories"),
                        0f,
                        0,
                        0f,
                        0f,
                        0f,
                        0f,
                        false)
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
        isRestoring: Boolean = false,
        suggestions: ArrayList<ProductAlternative>? = null
    ) {
        if (!isRestoring) {
            cachedEval = evaluation
            cachedSub = subtitle
            cachedMacros = Bundle().apply {
                putInt("cal", calories); putFloat("pro", protein); putInt("sod", sodium)
                putFloat("pot", potassium); putFloat("carb", carbs); putFloat("net_carb", netCarbs)
                putFloat("sug", sugar); putBoolean("is_keto", isKeto)
            }
            cachedSuggestions = suggestions
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

        if (suggestions != null && suggestions.isNotEmpty()) {
            textTapPrompt?.text = "Alternatives Found! Tap for details ➔"

            val pulseAnimation = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply {
                duration = 1000
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            }
            textTapPrompt?.startAnimation(pulseAnimation)
        } else {
            textTapPrompt?.text = "Tap for details ➔"
            textTapPrompt?.clearAnimation()
        }
        textTapPrompt?.setTextColor(evaluation.textColor)

        textExplanation.text = ""

        cardSummary.setOnClickListener {
            isNavigatingToDetail = true
            val bundle = Bundle().apply {
                putSerializable("EVAL", evaluation)
                putString("SUB", subtitle)
                putBundle("MACROS", cachedMacros)
                putSerializable("SUGGESTIONS", cachedSuggestions)
            }
            val extras = FragmentNavigatorExtras(cardSummary to "shared_element_container")
            findNavController().navigate(R.id.action_home_to_detail, bundle, null, extras)
        }
    }

    private fun resetUI() {
        activity?.runOnUiThread {
            cardSummary.visibility = View.GONE
            cachedEval = null
            cachedSuggestions = null
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
        isNavigatingToDetail = false
        updateConditionText()
    }

    private fun runWithCameraPermission(action: PendingCameraAction) {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = action
            executePendingCameraAction()
        } else {
            pendingAction = action
            requestCameraPermissionLauncher.launch(permission)
        }
    }

    private fun executePendingCameraAction() {
        when (pendingAction) {
            PendingCameraAction.CAMERA_SCAN -> {
                resetUI()
                launchCameraExplicit(labelCameraLauncher)
            }
            PendingCameraAction.PRODUCE_SCAN -> {
                resetUI()
                launchCameraExplicit(produceCameraLauncher)
            }
            PendingCameraAction.BARCODE_SCAN -> {
                resetUI()
                barcodeScannerLauncher.launch(Intent(requireContext(), ScannerActivity::class.java))
            }
            else -> {}
        }
        pendingAction = PendingCameraAction.NONE
    }

    override fun onStop() {
        super.onStop()
        if (!isNavigatingToDetail) {
            cachedEval = null
            cachedSub = ""
            cachedMacros = null
            cachedSuggestions = null
        }
    }
}