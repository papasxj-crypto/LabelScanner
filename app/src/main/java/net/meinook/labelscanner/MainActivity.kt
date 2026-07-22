package net.meinook.labelscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // 1. Global State Management
    private var sodiumModMax: Int = 2300
    private var proteinModMax: Int = 80
    private var proteinLowMax: Int = 80
    private var addedSugarModMax: Int = 5
    private var totalSugarModMax: Int = 10
    private var satFatModMax: Int = 3
    private var transFatModMax: Int = 3
    private var totalFatModMax: Int = 10
    private var potassiumModMax: Int = 350
    private var carbsModMax: Int = 45

    // 2. View Element Declarations
    private lateinit var mainLayoutContainer: LinearLayout
    private lateinit var textExplanation: TextView
    private lateinit var textCondition: TextView
    private lateinit var buttonScan: Button
    private lateinit var buttonOpenSettings: Button
    private lateinit var checkDisplayFullContainer: CheckBox

    private var isAnalyzing: Boolean = false

    // 3. Data Streams and Storage Tracking
    private lateinit var tempPhotoUri: Uri
    private val activeConditions = mutableSetOf<String>()
    private var useFullContainerValues: Boolean = false

    // 4. Last Scan Cache Storage
    private var lastScanGradeTitle: String = "Green - Enjoy"
    private var lastScanSodiumMg: Int = 0
    private var lastScanProteinGrams: Float = 0.0f
    private var lastScanCarbsGrams: Float = 0.0f
    private var lastScanTotalSugarGrams: Float = 0.0f
    private var lastScanAddedSugarGrams: Float = 0.0f
    private var lastScanPotassiumMg: Float = 0.0f
    private var lastScanServings: Float = 1.0f
    private var lastScanTotalFatGrams: Float = 0.0f
    private var lastScanSatFatGrams: Float = 0.0f
    private var lastScanTransFatGrams: Float = 0.0f
    private var lastScanCalories: Int = 0
    private var lastScanFiberGrams: Float = 0.0f
    private var hasScanData: Boolean = false

    // 5. Standard Camera Launcher (Barcode + Label OCR)
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            try {
                textExplanation.text = getString(R.string.analyzing_image_msg)
                textExplanation.setTextColor(Color.WHITE)
                textExplanation.setBackgroundColor(Color.TRANSPARENT)

                val inputStream = contentResolver.openInputStream(tempPhotoUri)
                val fullSpaceBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (fullSpaceBitmap != null) {
                    val image = InputImage.fromBitmap(fullSpaceBitmap, 0)
                    val scanner = BarcodeScanning.getClient()

                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            if (barcodes.isNotEmpty()) {
                                val upcCode = barcodes.first().rawValue ?: ""
                                lookupBarcodeOnline(upcCode, fullSpaceBitmap)
                            } else {
                                processAndRunJsonPipeline(fullSpaceBitmap)
                            }
                        }
                        .addOnFailureListener {
                            processAndRunJsonPipeline(fullSpaceBitmap)
                        }
                } else {
                    textExplanation.text = getString(R.string.error_loading_image)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                textExplanation.text = getString(R.string.error_processing_file)
            }
        } else {
            textExplanation.text = getString(R.string.camera_cancelled)
        }
    }

    // 6. Produce Camera Launcher (Bypasses Barcode ML Kit)
    private val produceCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            try {
                textExplanation.text = "Identifying Fresh Produce..."
                textExplanation.setTextColor(Color.WHITE)
                textExplanation.setBackgroundColor(Color.TRANSPARENT)

                val inputStream = contentResolver.openInputStream(tempPhotoUri)
                val fullSpaceBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (fullSpaceBitmap != null) {
                    runProduceAnalysis(fullSpaceBitmap)
                } else {
                    textExplanation.text = getString(R.string.error_loading_image)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                textExplanation.text = getString(R.string.error_processing_file)
            }
        } else {
            textExplanation.text = getString(R.string.camera_cancelled)
        }
    }

    /**
     * 🌐 THE ONLINE PATCH ENGINE
     */
    private fun lookupBarcodeOnline(upcCode: String, fallbackBitmap: Bitmap) {
        textExplanation.text = "UPC Found: $upcCode\nSearching grocery database..."

        kotlin.concurrent.thread {
            try {
                val url = URL("https://world.openfoodfacts.org/api/v2/product/$upcCode.json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "LabelScanner/1.0 (steve@meinook.net)")

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(responseText)

                    if (jsonObject.optInt("status", 0) == 1) {
                        val product = jsonObject.getJSONObject("product")
                        val ingredientsText = product.optString("ingredients_text", "").trim()
                        val nutriments = product.optJSONObject("nutriments")

                        val calories = nutriments?.optDouble("energy-kcal_100g", 0.0) ?: 0.0
                        val sodium = nutriments?.optDouble("sodium_100g", 0.0) ?: 0.0
                        val protein = nutriments?.optDouble("proteins_100g", 0.0) ?: 0.0
                        val fat = nutriments?.optDouble("fat_100g", 0.0) ?: 0.0
                        val carbs = nutriments?.optDouble("carbohydrates_100g", 0.0) ?: 0.0

                        val hasRealNutrientData = (calories + sodium + protein + fat + carbs) > 0.0

                        runOnUiThread {
                            if (ingredientsText.isNotEmpty() || hasRealNutrientData) {
                                textExplanation.text = "Product Verified Online!"
                                processAndRunJsonPipeline(fallbackBitmap)
                            } else {
                                textExplanation.text = "⚠️ Online entry missing nutritional data.\nScanning photo label instead..."
                                textExplanation.setTextColor(Color.YELLOW)
                                processAndRunJsonPipeline(fallbackBitmap)
                            }
                        }
                    }
                } else {
                    runOnUiThread { processAndRunJsonPipeline(fallbackBitmap) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { processAndRunJsonPipeline(fallbackBitmap) }
            }
        }
    }

    private fun processAndRunJsonPipeline(fullSpaceBitmap: Bitmap) {
        val maxDimension = 2000
        val width = fullSpaceBitmap.width
        val height = fullSpaceBitmap.height

        val optimizedBitmap = if (width > maxDimension || height > maxDimension) {
            val srcRatio = width.toFloat() / height.toFloat()
            val (newWidth, newHeight) = if (srcRatio > 1) {
                Pair(maxDimension, (maxDimension / srcRatio).toInt())
            } else {
                Pair((maxDimension * srcRatio).toInt(), maxDimension)
            }
            Bitmap.createScaledBitmap(fullSpaceBitmap, newWidth, newHeight, true)
        } else {
            fullSpaceBitmap
        }

        val fileOutputStream = FileOutputStream(File(filesDir, getString(R.string.scan_capture_jpg)))
        optimizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
        fileOutputStream.flush()
        fileOutputStream.close()

        runAnalysis(optimizedBitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppSettings.indexExclusivityGroups(this)

        textExplanation = findViewById(R.id.textExplanation)
        buttonScan = findViewById(R.id.buttonScan)
        buttonOpenSettings = findViewById(R.id.buttonOpenSettings)
        textCondition = findViewById(R.id.textCondition)
        checkDisplayFullContainer = findViewById(R.id.checkDisplayFullContainer)

        checkDisplayFullContainer.setOnCheckedChangeListener { _, isChecked ->
            useFullContainerValues = isChecked

            if (hasScanData && !isAnalyzing) {
                val freshlyCalculatedGrade = buildMacroSummary(
                    lastScanGradeTitle,
                    lastScanServings,
                    lastScanSodiumMg,
                    lastScanProteinGrams,
                    lastScanCarbsGrams,
                    lastScanTotalSugarGrams,
                    lastScanAddedSugarGrams,
                    lastScanPotassiumMg,
                    lastScanTotalFatGrams,
                    lastScanSatFatGrams,
                    lastScanTransFatGrams,
                    lastScanCalories,
                    lastScanFiberGrams,
                    useFullContainerValues
                )
                textExplanation.text = freshlyCalculatedGrade
            }
        }

        mainLayoutContainer = buttonScan.parent as LinearLayout

        buttonOpenSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Standard Click -> Label / Barcode Scan
        buttonScan.setOnClickListener {
            launchCameraForScan(isProduce = false)
        }

        // Long Click -> Fresh Produce Scan
        buttonScan.setOnLongClickListener {
            Toast.makeText(this, "Scanning Fresh Produce...", Toast.LENGTH_SHORT).show()
            launchCameraForScan(isProduce = true)
            true
        }
    }

    private fun launchCameraForScan(isProduce: Boolean) {
        textExplanation.text = getString(R.string.camera_msg_1)
        textExplanation.setTextColor(Color.WHITE)
        textExplanation.setBackgroundColor(Color.TRANSPARENT)
        try {
            val photoFile = File(this@MainActivity.filesDir, getString(R.string.scan_capture_jpg)).apply {
                if (exists()) delete()
                createNewFile()
            }

            tempPhotoUri = FileProvider.getUriForFile(
                this@MainActivity,
                getString(R.string.fileprovider_id),
                photoFile
            )

            if (isProduce) {
                produceCameraLauncher.launch(tempPhotoUri)
            } else {
                cameraLauncher.launch(tempPhotoUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            textExplanation.text = getString(R.string.storage_error_message, e.localizedMessage)
        }
    }

    override fun onResume() {
        super.onResume()

        updateConditionText()

        val userSettings = AppSettings(this)
        val savedConditions = userSettings.getSelectedConditions()

        activeConditions.clear()
        activeConditions.addAll(savedConditions)

        val userTargetWeightLbs = userSettings.getUserWeight().let { if (it > 0) it else 195.0 }

        sodiumModMax = getMaxSodium(activeConditions)
        proteinModMax = getMaxProtein(userTargetWeightLbs, activeConditions)

        textExplanation.text = ""
        textExplanation.setPadding(0, 0, 0, 0)
        textExplanation.setBackgroundColor(Color.TRANSPARENT)

        if (userSettings.getAndClearPendingSaveFlag()) {
            val rootView = findViewById<android.view.View>(android.view.Window.ID_ANDROID_CONTENT)
            com.google.android.material.snackbar.Snackbar
                .make(
                    rootView,
                    getString(R.string.toast_settings_saved),
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                )
                .show()
        }

        hasScanData = false
    }

    private fun runAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true

        runOnUiThread {
            textExplanation.text = getString(R.string.analyzing_label_msg)
            textExplanation.setTextColor(Color.WHITE)
            textExplanation.setBackgroundColor(Color.TRANSPARENT)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val secureApiKey = BuildConfig.GEMINI_API_KEY
                val modelId = getString(R.string.model_identifier_txt)

                val userSettings = AppSettings(this@MainActivity)
                val savedProfileIds = userSettings.getSelectedConditions()

                val selectedConditionsString = if (savedProfileIds.isNotEmpty()) {
                    val availableProfiles = userSettings.getAvailableDietProfiles()
                    savedProfileIds.map { id ->
                        availableProfiles.find { it.id == id }?.displayName ?: id
                    }.joinToString(", ")
                } else {
                    getString(R.string.standard_baseline)
                }

                val rawResponseFromGemini = GeminiAnalyzer.analyzeIngredientsImage(imageBitmap, selectedConditionsString, secureApiKey, modelId)
                val jsonResult = JSONObject(rawResponseFromGemini)
                val jsonArray = jsonResult.getJSONArray(getString(R.string.detected_ingredients_txt))
                val detectedIngredients = mutableListOf<String>()

                val negationPhrases = resources.getStringArray(R.array.negation_phrases).map { it.uppercase() }

                for (i in 0 until jsonArray.length()) {
                    val ingredientRaw = jsonArray.getString(i).uppercase()
                    val isNegatedStatement = negationPhrases.any { ingredientRaw.contains(it) }

                    if (!isNegatedStatement) {
                        detectedIngredients.add(ingredientRaw)
                    }
                }

                val servingsPerContainer = jsonResult.optDouble(getString(R.string.servings_per_container_txt), 1.0).toFloat()
                val rawCalories = jsonResult.optInt("calories", 0)
                val rawFiber = jsonResult.optDouble("fiber", 0.0).toFloat()

                val sodiumMg = jsonResult.optInt(getString(R.string.sodium_mg_txt), 0)
                val proteinGrams = jsonResult.optDouble(getString(R.string.protein_g_txt), 0.0).toFloat()
                val carbsGrams = jsonResult.optDouble(getString(R.string.total_carbohydrates_g_txt), 0.0).toFloat()
                val totalSugarGrams = jsonResult.optDouble(getString(R.string.total_sugar_g_txt), 0.0).toFloat()
                val addedSugarGrams = jsonResult.optDouble(getString(R.string.added_sugar_g_txt), 0.0).toFloat()
                val totalFatGrams = jsonResult.optDouble(getString(R.string.total_fat_g_txt), 0.0).toFloat()
                val satFatGrams = jsonResult.optDouble(getString(R.string.saturated_fat_g_txt), 0.0).toFloat()
                val transFatGrams = jsonResult.optDouble(getString(R.string.trans_fat_g_txt), 0.0).toFloat()
                val calories = jsonResult.optInt(getString(R.string.calories_txt), 0)
                val potassiumRaw = jsonResult.optDouble(getString(R.string.potassium_g_txt), 0.0).toFloat()

                val potassiumMg = if (potassiumRaw > 0.0 && potassiumRaw < 5.0) {
                    (potassiumRaw * 1000).toInt()
                } else {
                    potassiumRaw.toInt()
                }.toFloat()

                lastScanServings = servingsPerContainer
                lastScanSodiumMg = sodiumMg
                lastScanProteinGrams = proteinGrams
                lastScanCarbsGrams = carbsGrams
                lastScanTotalSugarGrams = totalSugarGrams
                lastScanAddedSugarGrams = addedSugarGrams
                lastScanTotalFatGrams = totalFatGrams
                lastScanSatFatGrams = satFatGrams
                lastScanTransFatGrams = transFatGrams
                lastScanCalories = rawCalories
                lastScanFiberGrams = rawFiber
                lastScanPotassiumMg = potassiumMg
                hasScanData = true

                val xmlRedTriggers = userSettings.loadTriggersFromAssets(getString(R.string.red_txt))
                val customRedWatchlist = userSettings.getCustomWatchlist("RED")
                val customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW")

                val evalResult = LabelEvaluator.evaluateScanData(
                    jsonResult = jsonResult,
                    detectedIngredients = detectedIngredients,
                    savedProfileIds = savedProfileIds,
                    userSettings = userSettings,
                    xmlRedTriggers = xmlRedTriggers,
                    customRedWatchlist = customRedWatchlist,
                    customYellowWatchlist = customYellowWatchlist
                )

                val bgColor = evalResult.bgColor
                val textColor = evalResult.textColor
                lastScanGradeTitle = evalResult.gradeTitle

                val finalGrade = buildMacroSummary(
                    lastScanGradeTitle,
                    servingsPerContainer,
                    sodiumMg,
                    proteinGrams,
                    carbsGrams,
                    totalSugarGrams,
                    addedSugarGrams,
                    potassiumMg,
                    totalFatGrams,
                    satFatGrams,
                    transFatGrams,
                    calories,
                    rawFiber,
                    useFullContainerValues
                )

                isAnalyzing = false
                withContext(Dispatchers.Main) {
                    textExplanation.typeface = android.graphics.Typeface.MONOSPACE
                    textExplanation.text = finalGrade
                    textExplanation.setTextColor(textColor)
                    textExplanation.setPadding(32, 32, 32, 32)

                    val premiumCardBackground = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(bgColor)
                        cornerRadius = 24f
                        setStroke(2, getString(R.string.frost_white).toColorInt())
                    }
                    textExplanation.background = premiumCardBackground
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isAnalyzing = false
                withContext(Dispatchers.Main) {
                    textExplanation.text = getString(R.string.error_processing_label_msg)
                    textExplanation.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun runProduceAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true

        runOnUiThread {
            textExplanation.text = "Identifying Fresh Produce..."
            textExplanation.setTextColor(Color.WHITE)
            textExplanation.setBackgroundColor(Color.TRANSPARENT)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val secureApiKey = BuildConfig.GEMINI_API_KEY
                val modelId = getString(R.string.model_identifier_txt)

                val rawResponse = GeminiAnalyzer.analyzeProduceImage(imageBitmap, secureApiKey, modelId)

                val cleanedResponse = rawResponse.replace("```json", "").replace("```", "").trim()
                val jsonResult = JSONObject(cleanedResponse)

                val itemName = jsonResult.optString("item_name", "Unknown Produce")
                val detectedIngredients = listOf(itemName.uppercase())

                val userSettings = AppSettings(this@MainActivity)
                val savedProfileIds = userSettings.getSelectedConditions()
                val xmlRedTriggers = userSettings.loadTriggersFromAssets(getString(R.string.red_txt))
                val customRedWatchlist = userSettings.getCustomWatchlist("RED")
                val customYellowWatchlist = userSettings.getCustomWatchlist("YELLOW")

                val evalResult = LabelEvaluator.evaluateScanData(
                    jsonResult = jsonResult,
                    detectedIngredients = detectedIngredients,
                    savedProfileIds = savedProfileIds,
                    userSettings = userSettings,
                    xmlRedTriggers = xmlRedTriggers,
                    customRedWatchlist = customRedWatchlist,
                    customYellowWatchlist = customYellowWatchlist
                )

                val calories = jsonResult.optInt("calories", 0)
                val sodiumMg = jsonResult.optInt("sodium_mg", 0)
                val proteinGrams = jsonResult.optDouble("protein_g", 0.0).toFloat()
                val carbsGrams = jsonResult.optDouble("total_carbohydrates_g", 0.0).toFloat()
                val totalSugarGrams = jsonResult.optDouble("total_sugar_g", 0.0).toFloat()
                val potassiumRaw = jsonResult.optDouble("potassium_g", 0.0).toFloat()
                val potassiumMg = if (potassiumRaw in 0.01f..5.0f) (potassiumRaw * 1000).toInt().toFloat() else potassiumRaw

                isAnalyzing = false
                withContext(Dispatchers.Main) {
                    val produceSummary = """
                    $itemName (per 100g)
                    -------------------------
                    Grade: ${evalResult.gradeTitle}
                    
                    Calories: $calories
                    Protein: ${proteinGrams}g
                    Carbs: ${carbsGrams}g
                    Sugars: ${totalSugarGrams}g
                    Sodium: ${sodiumMg}mg
                    Potassium: ${potassiumMg}mg
                """.trimIndent()

                    textExplanation.typeface = android.graphics.Typeface.MONOSPACE
                    textExplanation.text = produceSummary
                    textExplanation.setTextColor(evalResult.textColor)
                    textExplanation.setPadding(32, 32, 32, 32)

                    val premiumCardBackground = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(evalResult.bgColor)
                        cornerRadius = 24f
                        setStroke(2, getString(R.string.frost_white).toColorInt())
                    }
                    textExplanation.background = premiumCardBackground
                }

            } catch (e: Exception) {
                e.printStackTrace()
                isAnalyzing = false
                withContext(Dispatchers.Main) {
                    textExplanation.text = "Error identifying produce. Please try again."
                    textExplanation.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun updateConditionText() {
        val userSettings = AppSettings(this@MainActivity)
        val savedProfileIds = userSettings.getSelectedConditions()

        val selectedConditionsString = if (savedProfileIds.isNotEmpty()) {
            val availableProfiles = userSettings.getAvailableDietProfiles()
            savedProfileIds.map { id ->
                availableProfiles.find { it.id == id }?.displayName ?: id
            }.joinToString(", ")
        } else {
            getString(R.string.standard_baseline)
        }

        val textProfileDisplay = findViewById<TextView>(R.id.textCondition)
        textProfileDisplay?.text = "Selected Target: $selectedConditionsString"
    }

    private fun getMaxSodium(conditions: Set<String>): Int {
        val userSettings = AppSettings(this@MainActivity)
        val (_, dynamicModMax, _) = userSettings.getNutrientThresholds("sodium")
        return dynamicModMax
    }

    private fun getMaxProtein(weightLbs: Double, conditions: Set<String>): Int {
        val userSettings = AppSettings(this@MainActivity)
        val weightKg = weightLbs * 0.45359237

        val activeConditions = userSettings.getSelectedConditions()
        val isCKDActive = activeConditions.any { it.contains("ckd") || it.contains("Kidney") }

        val proteinMultiplier = if (isCKDActive) 0.8 else 1.2
        return (weightKg * proteinMultiplier).toInt()
    }

    private fun buildMacroSummary(
        gradeTitle: String,
        servings: Float,
        sodiumMg: Int,
        proteinGrams: Float,
        carbsGrams: Float,
        totalSugarGrams: Float,
        addedSugarGrams: Float,
        potassiumMg: Float,
        totalFatGrams: Float,
        satFatGrams: Float,
        transFatGrams: Float,
        calories: Int,
        fiber: Float,
        showFullContainer: Boolean
    ): String {
        val multiplier = if (showFullContainer) servings else 1.0f
        val labelSuffix = if (showFullContainer) "(Total Container)" else "(Per Serving)"

        val displaySodium = (sodiumMg * multiplier).toInt()
        val displayProtein = (proteinGrams * multiplier).toInt()
        val displayCarbs = (carbsGrams * multiplier).toInt()
        val displayTotalSugar = (totalSugarGrams * multiplier).toInt()
        val displayAddedSugar = (addedSugarGrams * multiplier).toInt()
        val displayPotassium = (potassiumMg * multiplier).toInt()
        val displayTotalFat = (totalFatGrams * multiplier).toInt()
        val displaySatFat = (satFatGrams * multiplier).toInt()
        val displayTransFat = (transFatGrams * multiplier).toInt()
        val displayCalories = (calories * multiplier).toInt()
        val displayFiber = (fiber * multiplier).toInt()

        fun getStatusStars(key: String, value: Float): String {
            val userSettings = AppSettings(this@MainActivity)
            val (lowMax, modMax, _) = userSettings.getNutrientThresholds(key)
            return when {
                value > modMax -> "xxxx"
                value > lowMax -> "**"
                else           -> ""
            }
        }

        val sodiumStatus     = getStatusStars("sodium", displaySodium.toFloat())
        val proteinStatus    = getStatusStars("protein", displayProtein.toFloat())
        val totalSugarStatus = getStatusStars("total_sugar", displayTotalSugar.toFloat())
        val addedSugarStatus = getStatusStars("added_sugar", displayAddedSugar.toFloat())
        val satFatStatus     = getStatusStars("saturated_fat", displaySatFat.toFloat())
        val totalFatStatus   = getStatusStars("total_fat", displayTotalFat.toFloat())
        val potassiumStatus  = getStatusStars("potassium", displayPotassium.toFloat())
        val carbsStatus      = getStatusStars("carbs", displayCarbs.toFloat())
        val caloriesStatus   = getStatusStars("calories", displayCalories.toFloat())
        val fiberStatus      = getStatusStars("fiber", displayFiber.toFloat())
        val transFatStatus   = getStatusStars("trans_fat", displayTransFat.toFloat())

        val labelFormat = "%-12s"
        val maxLineWidth = 39
        val leftPaddingCount = (maxLineWidth - gradeTitle.length) / 2
        val centeredGradeTitle = " ".repeat(leftPaddingCount.coerceAtLeast(0)) + gradeTitle

        return """
            $centeredGradeTitle 
            ---------------------------------------
            Servings: $labelSuffix
            ---------------------------------------
            ${String.format(labelFormat, "Calories")} : $displayCalories $caloriesStatus
            ${String.format(labelFormat, "Total Fat")} : ${displayTotalFat}g $totalFatStatus
            ${String.format(labelFormat, "Sat Fat")} : ${displaySatFat}g $satFatStatus
            ${String.format(labelFormat, "Trans Fat")} : ${displayTransFat}g $transFatStatus
            ${String.format(labelFormat, "Sodium")} : ${displaySodium}mg $sodiumStatus
            ${String.format(labelFormat, "Total Carbs")} : ${displayCarbs}g $carbsStatus
            ${String.format(labelFormat, "Fiber")} : ${displayFiber}g $fiberStatus
            ${String.format(labelFormat, "Total Sugar")} : ${displayTotalSugar}g $totalSugarStatus
            ${String.format(labelFormat, "Added Sugar")} : ${displayAddedSugar}g $addedSugarStatus
            ${String.format(labelFormat, "Protein")} : ${displayProtein}g $proteinStatus
            ${String.format(labelFormat, "Potassium")} : ${displayPotassium}mg $potassiumStatus
        """.trimIndent()
    }
}