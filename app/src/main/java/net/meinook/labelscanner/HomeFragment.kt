package net.meinook.labelscanner

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HomeFragment : Fragment(R.layout.fragment_home) {

    // 1. DUAL-URL COMPILATION INJECTION RULE COMPLIANT
    private val backendAnalysisUrl by lazy {
        if (BuildConfig.DEBUG) {
            getString(R.string.dev_URL)
        } else {
            getString(R.string.production_URL)
        }
    }

    private lateinit var textExplanation: TextView
    private lateinit var btnProfileSelector: MaterialButton
    private lateinit var cardSummary: MaterialCardView
    private lateinit var textSummaryGrade: TextView
    private lateinit var textSummaryExplanation: TextView
    private lateinit var composeView: ComposeView

    private var tempPhotoUri: Uri? = null
    private var isAnalyzing: Boolean = false

    private var cachedEval: EvaluationResult? = null
    private var cachedSub: String = ""
    private var cachedMacros: Bundle? = null
    private var cachedSuggestions: ArrayList<ProductAlternative>? = null
    private var isNavigatingToDetail: Boolean = false

    // Alternatives Search State & IPC Debounce Guards
    private var isSearchingAlternatives: Boolean = false
    private var activeSearchToast: Toast? = null
    private var lastToastTimestamp: Long = 0L

    private val labelCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val ctx = context ?: return@registerForActivityResult
            val uri = tempPhotoUri ?: getTempPhotoUri(ctx)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { runAnalysis(it, uri) }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to open captured image stream", e)
            }
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
            val ctx = context ?: return@registerForActivityResult
            val uri = tempPhotoUri ?: getTempPhotoUri(ctx)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { runProduceAnalysis(it) }
                }
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to open captured produce image stream", e)
            }
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

    private fun getTempPhotoUri(context: Context): Uri {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val photoFile = File(cacheDir, "temp_capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tempPhotoUri?.let { outState.putParcelable("SAVED_TEMP_PHOTO_URI", it) }
    }

    private fun optDoubleResilient(json: JSONObject, vararg keys: String): Double {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val rawObj = json.get(key)
            if (rawObj is Number) {
                return rawObj.toDouble()
            }
            val strVal = rawObj.toString().trim()
            if (strVal.isNotEmpty()) {
                val numericPart = strVal.replace(Regex("[^0-9\\.]"), "")
                return numericPart.toDoubleOrNull() ?: 0.0
            }
        }
        return 0.0
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore photo URI safely across low-memory process recreations
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            tempPhotoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable("SAVED_TEMP_PHOTO_URI", Uri::class.java)
            } else {
                savedInstanceState.getParcelable("SAVED_TEMP_PHOTO_URI")
            }
        }

        textExplanation = view.findViewById(R.id.textExplanation)
        btnProfileSelector = view.findViewById(R.id.btnProfileSelector)
        cardSummary = view.findViewById(R.id.cardSummary)
        textSummaryGrade = view.findViewById(R.id.textSummaryGrade)
        textSummaryExplanation = view.findViewById(R.id.textSummaryExplanation)

        composeView = view.findViewById(R.id.composeViewMenu)
        rebuildComposeMenu()

        // Single Point SELECT: Only switches existing profiles
        btnProfileSelector.setOnClickListener {
            showProfileSelectorDialog()
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
            val cal = cachedMacros?.getInt("cal") ?: 0
            val pro = cachedMacros?.getFloat("pro") ?: 0f
            val sod = cachedMacros?.getInt("sod") ?: 0
            val pot = cachedMacros?.getFloat("pot") ?: 0f
            val carb = cachedMacros?.getFloat("carb") ?: 0f
            val netCarb = cachedMacros?.getFloat("net_carb") ?: 0f
            val sug = cachedMacros?.getFloat("sug") ?: 0f
            val keto = cachedMacros?.getBoolean("is_keto") ?: false

            displaySummaryCard(
                evaluation = eval,
                subtitle = cachedSub,
                calories = cal,
                protein = pro,
                sodium = sod,
                potassium = pot,
                carbs = carb,
                netCarbs = netCarb,
                sugar = sug,
                isKeto = keto,
                isRestoring = true,
                suggestions = cachedSuggestions
            )
        }
        updateConditionText()
    }

    private fun rebuildComposeMenu() {
        val userSettings = AppSettings(requireContext())
        val isRightHanded = userSettings.isRightHanded()

        composeView.setContent {
            VerticalThumbArchMenu(
                isRightHanded = isRightHanded,
                onLabelClick = { runWithCameraPermission(PendingCameraAction.CAMERA_SCAN) },
                onBarcodeClick = { runWithCameraPermission(PendingCameraAction.BARCODE_SCAN) },
                onProduceClick = { runWithCameraPermission(PendingCameraAction.PRODUCE_SCAN) }
            )
        }
    }

    // Single Point: SELECT ONLY (No Add Button)
    private fun showProfileSelectorDialog() {
        val context = context ?: return
        val userSettings = AppSettings(context)
        val currentProfiles = userSettings.getProfilesList()
        val activeProfile = userSettings.getActiveProfile()

        val activeIndex = currentProfiles.indexOf(activeProfile)

        AlertDialog.Builder(context)
            .setTitle("Select Active Profile")
            .setSingleChoiceItems(currentProfiles.toTypedArray(), activeIndex) { dialog, which ->
                dialog.dismiss()
                val selectedProfile = currentProfiles[which]
                if (selectedProfile != activeProfile) {
                    userSettings.setActiveProfile(selectedProfile)
                    updateConditionText()
                    rebuildComposeMenu()
                    resetUI()
                    Toast.makeText(context, "Switched to $selectedProfile", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Composable
    fun VerticalThumbArchMenu(
        isRightHanded: Boolean,
        onLabelClick: () -> Unit,
        onBarcodeClick: () -> Unit,
        onProduceClick: () -> Unit
    ) {
        val unifiedButtonColor = colorResource(id = R.color.cardSurface)

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
        val ctx = requireContext()
        val uri = getTempPhotoUri(ctx)
        tempPhotoUri = uri
        launcher.launch(uri)
    }

    // Lifecycle-Aware & Safe: Replaced raw thread with lifecycleScope, resolved OFF User-Agent policy
    private fun lookupBarcodeOnline(upcCode: String) {
        textExplanation.text = "Searching..."

        val cleanCode = upcCode.trim()

        val isVariableWeight = (cleanCode.length == 12 && cleanCode.startsWith("2")) ||
                (cleanCode.length == 13 && (cleanCode.startsWith("02") || (cleanCode.substring(0, 2).toIntOrNull() in 20..29)))

        if (isVariableWeight) {
            resetUI()
            textExplanation.text = "This is a store-packaged variable weight item. Please use 'Camera Scan' to evaluate its ingredient label directly!"
            context?.let { textExplanation.setTextColor(ContextCompat.getColor(it, R.color.textPrimary)) }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val appContext = ctx.applicationContext
            val userSettings = AppSettings(appContext)

            try {
                val url = URL("https://world.openfoodfacts.org/api/v2/product/$cleanCode.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "FilterPoint/1.0 (support@filterpoint.app - Android)")
                connection.connectTimeout = 8000
                connection.readTimeout = 12000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    if (json.optInt("status", 0) == 1) {
                        val product = json.getJSONObject("product")
                        val nutriments = product.optJSONObject("nutriments") ?: JSONObject()
                        val ingredients = product.optString("ingredients_text", "").split(",").map { it.trim().uppercase() }

                        val categoriesTags = product.optJSONArray("categories_tags")
                        val candidateCategories = mutableListOf<String>()
                        if (categoriesTags != null && categoriesTags.length() > 0) {
                            for (i in categoriesTags.length() - 1 downTo 0) {
                                val tag = categoriesTags.optString(i, "")
                                if (tag.startsWith("en:") && !tag.contains("cjips", ignoreCase = true)) {
                                    candidateCategories.add(tag)
                                }
                            }
                        }
                        if (candidateCategories.isEmpty()) {
                            candidateCategories.add("en:chips-and-fries")
                            candidateCategories.add("en:salty-snacks")
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

                            if (hasVal("potassium_serving")) {
                                put("potassium", (nutriments.optDouble("potassium_serving", 0.0) * 1000).toInt())
                            } else if (hasVal("potassium_100g")) {
                                put("potassium", (nutriments.optDouble("potassium_100g", 0.0) * 1000).toInt())
                            } else if (hasVal("potassium")) {
                                put("potassium", (nutriments.optDouble("potassium", 0.0) * 1000).toInt())
                            }
                        }

                        val evalResult = LabelEvaluator.evaluateScanData(
                            evalJson,
                            ingredients,
                            userSettings.getSelectedConditions(),
                            userSettings,
                            userSettings.loadTriggersFromAssets("red"),
                            userSettings.getCustomWatchlist("RED"),
                            userSettings.getCustomWatchlist("YELLOW")
                        )

                        val productName = product.optString("product_name", "Product")
                        val calories = evalJson.optInt("calories", 0)
                        val protein = evalJson.optDouble("protein", 0.0).toFloat()
                        val sodium = evalJson.optInt("sodium", 0)
                        val potassium = evalJson.optDouble("potassium", 0.0).toFloat()
                        val carbs = evalJson.optDouble("carbs", 0.0).toFloat()
                        val netCarbs = (evalJson.optDouble("carbs", 0.0) - evalJson.optDouble("fiber", 0.0)).toFloat()
                        val sugar = evalJson.optDouble("sugar", 0.0).toFloat()
                        val isKeto = userSettings.getSelectedConditions().contains("keto")

                        val needsAlternatives = evalResult.gradeTitle.startsWith("Red") || evalResult.gradeTitle.startsWith("Yellow")
                        isSearchingAlternatives = needsAlternatives

                        withContext(Dispatchers.Main) {
                            displaySummaryCard(
                                evalResult,
                                productName,
                                calories,
                                protein,
                                sodium,
                                potassium,
                                carbs,
                                netCarbs,
                                sugar,
                                isKeto,
                                suggestions = null,
                                isSearchingAlternatives = needsAlternatives
                            )
                        }

                        if (needsAlternatives) {
                            try {
                                val suggestionsList = ArrayList<ProductAlternative>()
                                val tempYellowSuggestions = ArrayList<ProductAlternative>()

                                for (targetTag in candidateCategories.take(2)) {
                                    val searchUrlStr = "https://world.openfoodfacts.org/api/v2/search?categories_tags=$targetTag&countries_tags=en:united-states&sort_by=unique_scans_n&fields=code,product_name,brands,ingredients_text,nutriments,unique_scans_n&page_size=30"
                                    val searchConnection = URL(searchUrlStr).openConnection() as HttpURLConnection
                                    searchConnection.instanceFollowRedirects = true
                                    searchConnection.requestMethod = "GET"
                                    searchConnection.setRequestProperty("User-Agent", "FilterPoint/1.0 (support@filterpoint.app - Android)")
                                    searchConnection.connectTimeout = 8000
                                    searchConnection.readTimeout = 12000

                                    if (searchConnection.responseCode == 200) {
                                        val searchResponse = searchConnection.inputStream.bufferedReader().use { it.readText() }
                                        val searchJson = JSONObject(searchResponse)
                                        val productsArr = searchJson.optJSONArray("products")

                                        if (productsArr != null && productsArr.length() > 0) {
                                            for (j in 0 until productsArr.length()) {
                                                val altProduct = productsArr.getJSONObject(j)
                                                val altCode = altProduct.optString("code", "")
                                                if (altCode == cleanCode) continue

                                                val brand = altProduct.optString("brands", "").trim()
                                                val scans = altProduct.optInt("unique_scans_n", 0)
                                                if (brand.isBlank() || (scans < 5 && j < 25)) continue

                                                val altName = altProduct.optString("product_name", "").ifBlank {
                                                    altProduct.optString("product_name_en", "")
                                                }
                                                if (altName.isBlank()) continue

                                                val altNutriments = altProduct.optJSONObject("nutriments") ?: JSONObject()
                                                val altIngredients = altProduct.optString("ingredients_text", "").split(",").map { it.trim().uppercase() }

                                                val altEvals = JSONObject().apply {
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
                                                    altEvals,
                                                    altIngredients,
                                                    userSettings.getSelectedConditions(),
                                                    userSettings,
                                                    userSettings.loadTriggersFromAssets("red"),
                                                    userSettings.getCustomWatchlist("RED"),
                                                    userSettings.getCustomWatchlist("YELLOW")
                                                )

                                                if (altEvalResult.gradeTitle.startsWith("Green")) {
                                                    suggestionsList.add(
                                                        ProductAlternative(
                                                            name = altName,
                                                            brand = brand,
                                                            code = altCode,
                                                            gradeTitle = altEvalResult.gradeTitle
                                                        )
                                                    )
                                                } else if (altEvalResult.gradeTitle.startsWith("Yellow")) {
                                                    tempYellowSuggestions.add(
                                                        ProductAlternative(
                                                            name = altName,
                                                            brand = brand,
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

                                            if (suggestionsList.isNotEmpty()) break
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    isSearchingAlternatives = false
                                    if (cardSummary.visibility == View.VISIBLE && cachedEval == evalResult) {
                                        cachedSuggestions = suggestionsList
                                        val textTapPrompt = cardSummary.findViewById<TextView>(R.id.textTapPrompt)
                                        if (suggestionsList.isNotEmpty()) {
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
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("LabelScanner", "Async safer alternatives query error", e)
                                withContext(Dispatchers.Main) {
                                    isSearchingAlternatives = false
                                    val textTapPrompt = cardSummary.findViewById<TextView>(R.id.textTapPrompt)
                                    textTapPrompt?.text = "Tap for details ➔"
                                    textTapPrompt?.clearAnimation()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LabelScanner", "Barcode lookup background error", e)
                withContext(Dispatchers.Main) {
                    isSearchingAlternatives = false
                    textExplanation.text = "Error: ${e.localizedMessage ?: "Unknown connection failure"}"
                }
            }
        }
    }

    private fun tryParseNutritionLocally(rawText: String): Pair<JSONObject, List<String>>? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lowerText = rawText.lowercase(Locale.US)

        fun extractFirstMatch(vararg patterns: Regex): Double? {
            for (p in patterns) {
                val match = p.find(rawText)
                if (match != null) {
                    val numStr = match.groups[1]?.value?.replace(",", ".")
                    val parsed = numStr?.toDoubleOrNull()
                    if (parsed != null) return parsed
                }
            }
            return null
        }

        val cal = extractFirstMatch(
            Regex("""(?i)\bCalories\s*[:]?\s*(\d{1,4})\b"""),
            Regex("""(?i)\bEnergy\s*[:]?\s*(\d{1,4})\s*k?cal\b""")
        )?.toInt()

        val sod = extractFirstMatch(
            Regex("""(?i)\bSodium\s*[:]?\s*(\d{1,5})\s*mg\b"""),
            Regex("""(?i)\bSodium\s+(\d{1,5})\b""")
        )?.toInt()

        val pro = extractFirstMatch(
            Regex("""(?i)\bProtein\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b""")
        )

        val carb = extractFirstMatch(
            Regex("""(?i)\bTotal\s+Carbohydrate\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b"""),
            Regex("""(?i)\bCarbohydrate\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b"""),
            Regex("""(?i)\bCarbs\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b""")
        )

        val fiber = extractFirstMatch(
            Regex("""(?i)\bDietary\s+Fiber\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b"""),
            Regex("""(?i)\bFiber\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b""")
        ) ?: 0.0

        val sug = extractFirstMatch(
            Regex("""(?i)\bTotal\s+Sugars?\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b"""),
            Regex("""(?i)\bSugars?\s*[:]?\s*(\d+(?:\.\d+)?)\s*g\b""")
        )

        val pot = extractFirstMatch(
            Regex("""(?i)\bPotassium\s*[:]?\s*(\d{1,5})\s*mg\b""")
        )

        val ingredientsList = mutableListOf<String>()
        val ingIndex = lowerText.indexOf("ingredients")
        if (ingIndex != -1) {
            val ingSubstring = rawText.substring(ingIndex)
                .replace(Regex("""(?i)^ingredients\s*[:]?\s*"""), "")
                .takeWhile { it != '.' && it != '\n' || it == ',' }

            ingredientsList.addAll(
                ingSubstring.split(",")
                    .map { it.trim().uppercase(Locale.US) }
                    .filter { it.length > 1 }
            )
        } else {
            if (cal == null && sod == null && carb == null) {
                val nonMacroLines = lines.filter { line ->
                    val l = line.lowercase(Locale.US)
                    !l.contains("facts") && !l.contains("serving") && !l.contains("daily value")
                }
                if (nonMacroLines.isNotEmpty()) {
                    for (line in nonMacroLines) {
                        ingredientsList.addAll(
                            line.split(",")
                                .map { it.trim().uppercase(Locale.US) }
                                .filter { it.length > 1 }
                        )
                    }
                }
            }
        }

        val hasClearNutritionBox = (cal != null || sod != null || carb != null || sug != null)
        val hasClearIngredients = ingredientsList.isNotEmpty()

        if (!hasClearNutritionBox && !hasClearIngredients) {
            return null
        }

        val json = JSONObject().apply {
            put("nutrition_facts_found", hasClearNutritionBox)
            if (cal != null) put("calories", cal)
            if (sod != null) put("sodium", sod)
            if (pro != null) put("protein", pro)
            if (carb != null) put("carbs", carb)
            put("fiber", fiber)
            if (sug != null) put("sugar", sug)
            if (pot != null) put("potassium", pot)
            put("ingredients", org.json.JSONArray(ingredientsList))
        }

        return Pair(json, ingredientsList)
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

    private fun resizeBitmapToMax(source: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDimension && height <= maxDimension) return source

        val aspectRatio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / aspectRatio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * aspectRatio).toInt()
        }

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun runAnalysis(imageBitmap: Bitmap, sourceUri: Uri? = null) {
        isAnalyzing = true
        textExplanation.text = "Reading label..."

        val ctx = context ?: return
        val image = try {
            val uri = sourceUri ?: tempPhotoUri ?: getTempPhotoUri(ctx)
            InputImage.fromFilePath(ctx, uri)
        } catch (e: Exception) {
            InputImage.fromBitmap(imageBitmap, 0)
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                if (extractedText.isNotBlank()) {
                    val localResult = tryParseNutritionLocally(extractedText)
                    if (localResult != null) {
                        val (json, ingredients) = localResult
                        val userSettings = AppSettings(requireContext())
                        val loadedRedTriggers = userSettings.loadTriggersFromAssets("red")

                        val evalResult = LabelEvaluator.evaluateScanData(
                            json,
                            ingredients,
                            userSettings.getSelectedConditions(),
                            userSettings,
                            loadedRedTriggers,
                            userSettings.getCustomWatchlist("RED"),
                            userSettings.getCustomWatchlist("YELLOW")
                        )

                        val fiber = json.optDouble("fiber", 0.0)
                        val carbs = json.optDouble("carbs", 0.0)
                        val potVal = optDoubleResilient(json, "potassium_mg", "potassium")
                        val sugVal = optDoubleResilient(json, "total_sugar_g", "sugar")

                        isAnalyzing = false
                        displaySummaryCard(
                            evalResult,
                            "Scan Result",
                            json.optInt("calories", 0),
                            json.optDouble("protein", 0.0).toFloat(),
                            json.optInt("sodium", 0),
                            potVal.toFloat(),
                            carbs.toFloat(),
                            (carbs - fiber).coerceAtLeast(0.0).toFloat(),
                            sugVal.toFloat(),
                            userSettings.getSelectedConditions().contains("keto")
                        )
                    } else {
                        textExplanation.text = "Analyzing text..."
                        executeTextBasedAnalysis(extractedText)
                    }
                } else {
                    textExplanation.text = "Using visual fallback..."
                    executeVisionBasedAnalysis(imageBitmap)
                }
            }
            .addOnFailureListener { e ->
                Log.e("LabelScanner", "Local OCR Failed, falling back to Vision API", e)
                textExplanation.text = "OCR Failed. Using visual fallback..."
                executeVisionBasedAnalysis(imageBitmap)
            }
    }

    private fun executeTextBasedAnalysis(extractedText: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                val userSettings = AppSettings(ctx.applicationContext)
                val rawResponse = GeminiAnalyzer.analyzeIngredientsText(
                    extractedText,
                    backendAnalysisUrl
                )

                Log.d("LabelScanner", "HomeFragment [Hybrid Text]: Raw Response = $rawResponse")
                parseAndDisplayAnalysis(rawResponse, userSettings)

            } catch (e: Exception) {
                Log.e("LabelScanner", "Text hybrid scan execution failed", e)
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    textExplanation.text = "Failed: ${e.localizedMessage ?: "Analysis error"}"
                }
            }
        }
    }

    private fun executeVisionBasedAnalysis(imageBitmap: Bitmap) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                val userSettings = AppSettings(ctx.applicationContext)
                val optimizedBitmap = resizeBitmapToMax(imageBitmap, 1024)

                val rawResponse = GeminiAnalyzer.analyzeIngredientsImage(
                    optimizedBitmap,
                    backendAnalysisUrl
                )

                Log.d("LabelScanner", "HomeFragment [Fallback Vision]: Raw Response = $rawResponse")
                parseAndDisplayAnalysis(rawResponse, userSettings)

            } catch (e: Exception) {
                Log.e("LabelScanner", "Vision fallback scan execution failed", e)
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    textExplanation.text = "Failed: ${e.localizedMessage ?: "Analysis error"}"
                }
            }
        }
    }

    private suspend fun parseAndDisplayAnalysis(rawResponse: String, userSettings: AppSettings) {
        val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())

        val ingredients = extractIngredientsFromJson(json)
        val loadedRedTriggers = userSettings.loadTriggersFromAssets("red")

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
            val fiber = if (json.has("fiber_g")) json.optDouble("fiber_g") else json.optDouble("fiber", 0.0)
            val carbs = if (json.has("total_carbohydrates_g")) json.optDouble("total_carbohydrates_g") else json.optDouble("carbs", 0.0)

            val potassiumVal = optDoubleResilient(json, "potassium_mg", "potassium")
            val sugarVal = optDoubleResilient(json, "total_sugar_g", "sugar")

            displaySummaryCard(
                evalResult,
                "Scan Result",
                json.optInt("calories"),
                json.optDouble("protein", 0.0).toFloat().let { if (it == 0f) json.optDouble("protein_g", 0.0).toFloat() else it },
                json.optInt("sodium", 0).let { if (it == 0) json.optInt("sodium_mg", 0) else it },
                potassiumVal.toFloat(),
                carbs.toFloat(),
                (carbs - fiber).toFloat(),
                sugarVal.toFloat(),
                userSettings.getSelectedConditions().contains("keto")
            )
        }
    }

    private fun runProduceAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                val optimizedBitmap = resizeBitmapToMax(imageBitmap, 1024)

                withContext(Dispatchers.Main) { textExplanation.text = "Analyzing Image ..." }

                val rawResponse = GeminiAnalyzer.analyzeProduceImage(
                    optimizedBitmap,
                    backendAnalysisUrl
                )
                Log.d("LabelScanner", "HomeFragment: Raw Produce Response = $rawResponse")

                val json = JSONObject(rawResponse.replace("```json", "").replace("```", "").trim())
                val name = json.optString("item_name", "Produce")

                val isNonFood = !json.optBoolean("is_food", true) ||
                        name.contains("non-food", ignoreCase = true) ||
                        name.startsWith("none", ignoreCase = true)

                if (isNonFood) {
                    withContext(Dispatchers.Main) {
                        isAnalyzing = false
                        val nonFoodResult = EvaluationResult(
                            gradeTitle = "Not Edible Produce",
                            textColor = ContextCompat.getColor(ctx, R.color.textPrimary),
                            subtextColor = ContextCompat.getColor(ctx, R.color.textSecondary),
                            bgColor = ContextCompat.getColor(ctx, R.color.cardSurface),
                            redViolations = emptyList(),
                            yellowViolations = emptyList()
                        )
                        displaySummaryCard(
                            evaluation = nonFoodResult,
                            subtitle = name.replace(Regex("""(?i)^none\s*\(?"""), "").removeSuffix(")").trim().ifEmpty { "Non-food item" },
                            calories = 0,
                            protein = 0f,
                            sodium = 0,
                            potassium = 0f,
                            carbs = 0f,
                            netCarbs = 0f,
                            sugar = 0f,
                            isKeto = false
                        )
                    }
                    return@launch
                }

                val evalResult = LabelEvaluator.evaluateScanData(
                    json,
                    listOf(name.uppercase().trim()),
                    AppSettings(ctx.applicationContext).getSelectedConditions(),
                    AppSettings(ctx.applicationContext),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    true
                )

                withContext(Dispatchers.Main) {
                    isAnalyzing = false

                    val proteinVal = optDoubleResilient(json, "protein_g", "protein").toFloat()
                    val sodiumVal = optDoubleResilient(json, "sodium_mg", "sodium").toInt()
                    val potassiumVal = optDoubleResilient(json, "potassium_mg", "potassium").toFloat()
                    val carbsVal = optDoubleResilient(json, "total_carbohydrates_g", "carbs").toFloat()
                    val fiberVal = optDoubleResilient(json, "fiber_g", "fiber").toFloat()
                    val sugarVal = optDoubleResilient(json, "total_sugar_g", "sugar").toFloat()
                    val netCarbsVal = (carbsVal - fiberVal).coerceAtLeast(0f)

                    displaySummaryCard(
                        evalResult,
                        name,
                        json.optInt("calories"),
                        proteinVal,
                        sodiumVal,
                        potassiumVal,
                        carbsVal,
                        netCarbsVal,
                        sugarVal,
                        false
                    )
                }
            } catch (e: Exception) {
                Log.e("LabelScanner", "Produce analysis error", e)
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
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
        suggestions: ArrayList<ProductAlternative>? = null,
        isSearchingAlternatives: Boolean = false
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
        composeView.visibility = View.GONE

        cardSummary.setCardBackgroundColor(evaluation.bgColor)
        cardSummary.strokeColor = evaluation.textColor
        cardSummary.strokeWidth = (2 * resources.displayMetrics.density).toInt()

        val context = cardSummary.context
        val titleResId = context.resources.getIdentifier("textProductTitle", "id", context.packageName).let { id ->
            if (id != 0) id else context.resources.getIdentifier("textSummaryTitle", "id", context.packageName).let { id2 ->
                if (id2 != 0) id2 else context.resources.getIdentifier("textProduct", "id", context.packageName)
            }
        }

        val textProductTitle = if (titleResId != 0) cardSummary.findViewById<TextView>(titleResId) else null

        if (textProductTitle != null) {
            textProductTitle.text = subtitle
            textProductTitle.setTextColor(evaluation.textColor)
            textProductTitle.gravity = android.view.Gravity.CENTER
            textSummaryGrade.text = evaluation.gradeTitle
        } else {
            textSummaryGrade.text = "$subtitle\n${evaluation.gradeTitle}"
        }
        textSummaryGrade.gravity = android.view.Gravity.CENTER
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
        } else if (isSearchingAlternatives) {
            textTapPrompt?.text = "Searching for safer alternatives... ⏳"
            val pulseAnimation = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply {
                duration = 800
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            }
            textTapPrompt?.startAnimation(pulseAnimation)
        } else {
            textTapPrompt?.text = "Tap for details ➔"
            textTapPrompt?.clearAnimation()
        }

        textExplanation.text = ""

        cardSummary.setOnClickListener {
            if (this.isSearchingAlternatives) {
                val now = System.currentTimeMillis()
                if (now - lastToastTimestamp > 3000L) {
                    lastToastTimestamp = now
                    activeSearchToast?.cancel()
                    activeSearchToast = Toast.makeText(context, "Searching for alternatives, please wait...", Toast.LENGTH_SHORT)
                    activeSearchToast?.show()
                }
                return@setOnClickListener
            }
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

    fun resetToReadyState() {
        resetUI()
    }

    private fun resetUI() {
        activity?.runOnUiThread {
            isSearchingAlternatives = false
            activeSearchToast?.cancel()
            cardSummary.visibility = View.GONE
            composeView.visibility = View.VISIBLE
            cachedEval = null
            cachedSub = ""
            cachedMacros = null
            cachedSuggestions = null
            textExplanation.text = "Ready..."
            context?.let { textExplanation.setTextColor(ContextCompat.getColor(it, R.color.textPrimary)) }
        }
    }

    private fun updateConditionText() {
        btnProfileSelector.text = "Profile: ${AppSettings(requireContext()).getActiveProfile()} ▾"
    }

    override fun onResume() {
        super.onResume()
        if (isNavigatingToDetail) {
            resetUI()
            isNavigatingToDetail = false
        }
        updateConditionText()
        rebuildComposeMenu()
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
            resetUI()
        }
    }
}