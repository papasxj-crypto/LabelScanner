package net.meinook.labelscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.graphics.toColorInt
import com.google.ai.client.generativeai.GenerativeModel
import java.io.File

class MainActivity : AppCompatActivity() {

    // 1. Global State Management (Enforces the absolute strictest limit if multiple boxes are checked)
    private var sodiumModMax: Int = 2300
    private var proteinModMax: Int = 80
    private var addedSugarModMax: Int = 5
    private var totalSugarModMax: Int = 10
    private var satFatModMax: Int = 3
    private var totalFatModMax: Int = 10
    private var potassiumModMax: Int = 350
    private var carbsModMax: Int = 45
    private var selectedConditionsString: String = ""

    // 2. View Element Declarations
    private lateinit var mainLayoutContainer: LinearLayout
    private lateinit var textExplanation: TextView
    private lateinit var textCondition: TextView
    private lateinit var buttonScan: Button
    private lateinit var buttonOpenSettings: Button
    private var isAnalyzing: Boolean = false

    // 4. Data Streams and Storage Tracking
    private lateinit var tempPhotoUri: Uri
    private val activeConditions = mutableSetOf<String>()
    private var useFullContainerValues: Boolean = false
    private lateinit var checkDisplayFullContainer: android.widget.CheckBox

    // 6. Last Scan Cache Storage (For instant UI recalculations without re-scanning)
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
    private var hasScanData: Boolean = false

    // 5. High-Resolution Camera Storage Callback (Balanced for Speed & Text Accuracy)
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
                    // UPGRADED: Raise limit to 2000px to secure fine-print text fidelity
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

                    // BALANCED: Write to disk at 75% compression to keep payload small
                    val fileOutputStream = java.io.FileOutputStream(File(filesDir, getString(R.string.scan_capture_jpg)))
                    optimizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
                    fileOutputStream.flush()
                    fileOutputStream.close()

                    runAnalysis(optimizedBitmap)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Native Core Views
        textExplanation = findViewById(R.id.textExplanation)
        buttonScan = findViewById(R.id.buttonScan)
        buttonOpenSettings = findViewById(R.id.buttonOpenSettings)
        textCondition = findViewById(R.id.textCondition)
        checkDisplayFullContainer = findViewById(R.id.checkDisplayFullContainer)

        checkDisplayFullContainer.setOnCheckedChangeListener { _, isChecked ->
            useFullContainerValues = isChecked

            // If we have data from a previous scan on screen, recalculate and redraw immediately!
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

        buttonScan.setOnClickListener {
            textExplanation.text = getString(R.string.camera_msg_1)
            textExplanation.setTextColor(Color.WHITE)
            textExplanation.setBackgroundColor(Color.TRANSPARENT)
            try {
                val photoFile = File(this@MainActivity.filesDir,
                    getString(R.string.scan_capture_jpg)).apply {
                    if (exists()) delete()
                    createNewFile()
                }

                tempPhotoUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    getString(R.string.fileprovider_id),
                    photoFile
                )

                cameraLauncher.launch(tempPhotoUri)
            } catch (e: Exception) {
                e.printStackTrace()
                textExplanation.text = getString(R.string.storage_error_message, e.localizedMessage)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val userSettings = AppSettings(this)
        val savedConditions = userSettings.getSelectedConditions()

        activeConditions.clear()
        activeConditions.addAll(savedConditions)

        val userTargetWeightLbs = userSettings.getUserWeight().let { if (it > 0) it else 195.0 }

        sodiumModMax = getMaxSodium(activeConditions)
        proteinModMax = getMaxProtein(userTargetWeightLbs, activeConditions)

        if (!isAnalyzing) {
            updateConditionText()
        }
    }

    private fun runAnalysis(imageBitmap: Bitmap) {
        isAnalyzing = true
        textExplanation.text = getString(R.string.analyzing_label_msg)
        textExplanation.setTextColor(Color.WHITE)
        textExplanation.setBackgroundColor(Color.TRANSPARENT)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val secureApiKey = BuildConfig.GEMINI_API_KEY
                val modelId = getString(R.string.model_identifier_txt)

                val userSettings = AppSettings(this@MainActivity)
                val savedProfileConditions = userSettings.getSelectedConditions()

                val selectedConditionsString = if (savedProfileConditions.isNotEmpty()) {
                    savedProfileConditions.joinToString(", ")
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
                val sodiumMg = jsonResult.optInt(getString(R.string.sodium_mg_txt), 0)
                val proteinGrams = jsonResult.optDouble(getString(R.string.protein_g_txt), 0.0).toFloat()
                val carbsGrams = jsonResult.optDouble(getString(R.string.total_carbohydrates_g_txt), 0.0).toFloat()
                val totalSugarGrams = jsonResult.optDouble(getString(R.string.total_sugar_g_txt), 0.0).toFloat()
                val addedSugarGrams = jsonResult.optDouble(getString(R.string.added_sugar_g_txt), 0.0).toFloat()
                val totalFatGrams = jsonResult.optDouble(getString(R.string.total_fat_g_txt), 0.0).toFloat()
                val satFatGrams = jsonResult.optDouble(getString(R.string.saturated_fat_g_txt), 0.0).toFloat()
                val transFatGrams = jsonResult.optDouble(getString(R.string.trans_fat_g_txt), 0.0).toFloat()
                val potassiumRaw = jsonResult.optDouble(getString(R.string.potassium_g_txt), 0.0).toFloat()

                val potassiumMg = if (potassiumRaw > 0.0 && potassiumRaw < 5.0) {
                    (potassiumRaw * 1000).toInt()
                } else {
                    potassiumRaw.toInt()
                }.toFloat()

                // Cache all values securely for instant layout redraws later
                lastScanServings = servingsPerContainer
                lastScanSodiumMg = sodiumMg
                lastScanProteinGrams = proteinGrams
                lastScanCarbsGrams = carbsGrams
                lastScanTotalSugarGrams = totalSugarGrams
                lastScanAddedSugarGrams = addedSugarGrams
                lastScanTotalFatGrams = totalFatGrams
                lastScanSatFatGrams = satFatGrams
                lastScanPotassiumMg = potassiumMg
                hasScanData = true

                val xmlRedTriggers = userSettings.loadTriggersFromAssets(getString(R.string.red_txt))
                val xmlYellowTriggers = userSettings.loadTriggersFromAssets(getString(R.string.yellow_txt))
                val customBlacklist = userSettings.getCustomBlacklist()

                var bgColor = (getString(R.string.green)).toColorInt()
                var textColor = Color.WHITE
                var lastScanGradeTitle = ""

                // 1. Extract dynamic clinical properties from your XML data layer
                val sodiumTriple     = userSettings.getNutrientThresholds(getString(R.string.sodium_mg_txt))
                val proteinTriple    = userSettings.getNutrientThresholds(getString(R.string.protein_g_txt))
                val addedSugarTriple = userSettings.getNutrientThresholds(getString(R.string.added_sugar_g_txt))
                val totalSugarTriple = userSettings.getNutrientThresholds(getString(R.string.total_sugar_g_txt))
                val satFatTriple     = userSettings.getNutrientThresholds(getString(R.string.saturated_fat_g_txt))
                val totalFatTriple   = userSettings.getNutrientThresholds(getString(R.string.total_fat_g_txt))
                val potassiumTriple  = userSettings.getNutrientThresholds(getString(R.string.potassium_g_txt))
                val carbsTriple      = userSettings.getNutrientThresholds(getString(R.string.total_carbohydrates_g_txt))

                // Unpack limits for local rule checks
                val (sodiumLowMax, sodiumModMax, sodiumIsBlacklist) = sodiumTriple
                val (proteinLowMax, proteinModMax, proteinIsBlacklist) = proteinTriple
                val (addedSugarLowMax, addedSugarModMax, addedSugarIsBlacklist) = addedSugarTriple
                val (totalSugarLowMax, totalSugarModMax, totalSugarIsBlacklist) = totalSugarTriple
                val (satFatLowMax, satFatModMax, satFatIsBlacklist) = satFatTriple
                val (totalFatLowMax, totalFatModMax, totalFatIsBlacklist) = totalFatTriple
                val (potassiumLowMax, potassiumModMax, potassiumIsBlacklist) = potassiumTriple
                val (carbsLowMax, carbsModMax, carbsIsBlacklist) = carbsTriple

                // Sync global limits to prevent layout-redraw variable shadowing
                this@MainActivity.sodiumModMax = sodiumModMax
                this@MainActivity.proteinModMax = proteinModMax
                this@MainActivity.totalSugarModMax = totalSugarModMax
                this@MainActivity.addedSugarModMax = addedSugarModMax
                this@MainActivity.satFatModMax = satFatModMax
                this@MainActivity.totalFatModMax = totalFatModMax
                this@MainActivity.potassiumModMax = potassiumModMax
                this@MainActivity.carbsModMax = carbsModMax

                // Edge Case Override 1: The Plant Protein Exception Check
                val plantProteinKeywords = listOf("LENTIL", "CHICKPEA", "TOFU", "PEA PROTEIN", "SOY", "BEAN")
                val isPurelyPlantProtein = detectedIngredients.any { ingredient ->
                    plantProteinKeywords.any { keyword -> ingredient.contains(keyword) }
                }

                var proteinViolatesRed = proteinGrams > proteinModMax
                var proteinViolatesYellow = proteinGrams > proteinLowMax

                if (proteinViolatesRed && isPurelyPlantProtein) {
                    proteinViolatesRed = false
                    proteinViolatesYellow = true
                }

                val redViolations = mutableListOf<String>()
                val yellowViolations = mutableListOf<String>()

                // 2. Clear Evaluation Engine with Dynamic Profile Isolation via if/else chains

                // Sodium
                if (sodiumMg > sodiumModMax) {
                    if (sodiumIsBlacklist) redViolations.add(getString(R.string.sodium_mg_txt)) else yellowViolations.add(getString(R.string.sodium_mg_txt))
                } else if (sodiumMg > sodiumLowMax) {
                    yellowViolations.add(getString(R.string.sodium_mg_txt))
                }

                // Protein
                if (proteinViolatesRed) {
                    if (proteinIsBlacklist) redViolations.add(getString(R.string.protein_g_txt)) else yellowViolations.add(getString(R.string.protein_g_txt))
                } else if (proteinViolatesYellow) {
                    yellowViolations.add(getString(R.string.protein_g_txt))
                }

                // Total Sugar
                if (totalSugarGrams > totalSugarModMax) {
                    if (totalSugarIsBlacklist) redViolations.add(getString(R.string.total_sugar_g_txt)) else yellowViolations.add(getString(R.string.total_sugar_g_txt))
                } else if (totalSugarGrams > totalSugarLowMax) {
                    yellowViolations.add(getString(R.string.total_sugar_g_txt))
                }

                // Added Sugar
                if (addedSugarGrams > addedSugarModMax) {
                    if (addedSugarIsBlacklist) redViolations.add(getString(R.string.added_sugar_g_txt)) else yellowViolations.add(getString(R.string.added_sugar_g_txt))
                } else if (addedSugarGrams > addedSugarLowMax) {
                    yellowViolations.add(getString(R.string.added_sugar_g_txt))
                }

                // Saturated Fat
                if (satFatGrams > satFatModMax) {
                    if (satFatIsBlacklist) redViolations.add(getString(R.string.saturated_fat_g_txt)) else yellowViolations.add(getString(R.string.saturated_fat_g_txt))
                } else if (satFatGrams > satFatLowMax) {
                    yellowViolations.add(getString(R.string.saturated_fat_g_txt))
                }

                // Total Fat
                if (totalFatGrams > totalFatModMax) {
                    if (totalFatIsBlacklist) redViolations.add(getString(R.string.total_fat_g_txt)) else yellowViolations.add(getString(R.string.total_fat_g_txt))
                } else if (totalFatGrams > totalFatLowMax) {
                    yellowViolations.add(getString(R.string.total_fat_g_txt))
                }

                // Potassium
                if (potassiumMg > potassiumModMax) {
                    if (potassiumIsBlacklist) redViolations.add(getString(R.string.potassium_g_txt)) else yellowViolations.add(getString(R.string.potassium_g_txt))
                } else if (potassiumMg > potassiumLowMax) {
                    yellowViolations.add(getString(R.string.potassium_g_txt))
                }

                // Carbs
                if (carbsGrams > carbsModMax) {
                    if (carbsIsBlacklist) redViolations.add(getString(R.string.total_carbohydrates_g_txt)) else yellowViolations.add(getString(R.string.total_carbohydrates_g_txt))
                } else if (carbsGrams > carbsLowMax) {
                    yellowViolations.add(getString(R.string.total_carbohydrates_g_txt))
                }

                // Permanent Binary Red Override
                if (transFatGrams > 0.0f) {
                    redViolations.add(getString(R.string.trans_fat_g_txt))
                }

                val matchedBlacklist = customBlacklist.filter { detectedIngredients.contains(it) }
                val matchedXmlRed = xmlRedTriggers.filter { detectedIngredients.contains(it) }

                // 3. Core Decision Layout Engine
                when {
                    // Priority 1: Custom User Blacklist Match
                    matchedBlacklist.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        val offenders = matchedBlacklist.joinToString(getString(R.string.comma))
                        lastScanGradeTitle = getString(R.string.red_blacklist_matched_msg, offenders)
                    }

                    // Priority 2: Static Rule Warning Triggers
                    matchedXmlRed.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        val offenders = matchedXmlRed.joinToString(getString(R.string.comma))
                        lastScanGradeTitle = getString(R.string.red_high_risk_msg, offenders)
                    }

                    // Priority 3: CRITICAL HIGH RISK TIERS (Only runs if a verified blacklist item fails)
                    redViolations.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        lastScanGradeTitle = "Red - Avoid (High ${redViolations.joinToString(", ")})"
                    }

                    // Priority 4: MODERATE RISK TIERS (Any item in the yellowViolations list)
                    yellowViolations.isNotEmpty() -> {
                        bgColor = (getString(R.string.yellow)).toColorInt()
                        textColor = Color.BLACK
                        lastScanGradeTitle = "Limit: ${yellowViolations.joinToString(", ")}"
                    }

                    // Priority 5: COMPLIANT TARGET BASELINE
                    else -> {
                        bgColor = (getString(R.string.green)).toColorInt()
                        textColor = Color.WHITE
                        lastScanGradeTitle = "Green - Safe Baseline"
                    }
                }

                this@MainActivity.lastScanGradeTitle = lastScanGradeTitle

                // Dynamically construct the layout output using the Monospace template rules
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
    private fun updateConditionText() {
        val userSettings = AppSettings(this)
        val savedProfileConditions = userSettings.getSelectedConditions()

        selectedConditionsString = if (savedProfileConditions.isNotEmpty()) {
            savedProfileConditions.joinToString(getString(R.string.comma))
        } else {
            getString(R.string.standard_baseline)
        }

        textCondition.text = getString(R.string.selected_target_label, selectedConditionsString)

        if (activeConditions.isEmpty()) {
            textExplanation.text = getString(R.string.no_conditions_active)
        } else {
            textExplanation.text = getString(R.string.active_profile_tracking, selectedConditionsString, sodiumModMax)
        }
    }

    private fun getMaxSodium(conditions: Set<String>): Int {
        val userSettings = AppSettings(this@MainActivity)

        // Snag the explicit dynamic string key matching your strings.xml setup
        val sodiumKey = getString(R.string.sodium_mg_txt)

        // Pull the Triple from your XML processor. The second item (.second) is your Moderate Max.
        val (_, dynamicModMax, _) = userSettings.getNutrientThresholds(sodiumKey)

        return dynamicModMax
    }

    private fun getMaxProtein(weightLbs: Double, conditions: Set<String>): Int {
        val userSettings = AppSettings(this@MainActivity)
        val weightKg = weightLbs * 0.45359237

        val activeConditions = userSettings.getSelectedConditions()
        val isCKDActive = activeConditions.any { it.contains("CKD") || it.contains("Kidney") }

        // If CKD is selected, we calculate based on the strict clinician multi-factor rule (0.6 - 0.8 g/kg)
        val proteinMultiplier = if (isCKDActive) {
            0.8 // Upper bound target for Stage 3A
        } else {
            1.2 // Healthy adult target weight maintenance default baseline multiplier
        }

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

        // Local helper to fetch thresholds and assign stars using resource lookups
        fun getStatusStars(resId: Int, value: Float): String {
            val stringKey = getString(resId)
            val userSettings = AppSettings(this@MainActivity)

            // Unpack the triple cleanly, discarding the boolean with an underscore since stars don't care about blacklist cards
            val (lowMax, modMax, _) = userSettings.getNutrientThresholds(stringKey)
            return when {
                value > modMax -> "xxxx" // Red Tier
                value > lowMax -> "**"   // Yellow Tier (Swapped to double equal lines to look like small warning bars!)
                else           -> ""     // Green Tier
            }
        }

        // Calculate stars cleanly by passing the string resource IDs directly
        val sodiumStatus     = getStatusStars(R.string.sodium_mg_txt, displaySodium.toFloat())
        val proteinStatus    = getStatusStars(R.string.protein_g_txt, displayProtein.toFloat())
        val totalSugarStatus = getStatusStars(R.string.total_sugar_g_txt, displayTotalSugar.toFloat())
        val addedSugarStatus = getStatusStars(R.string.added_sugar_g_txt, displayAddedSugar.toFloat())
        val satFatStatus     = getStatusStars(R.string.saturated_fat_g_txt, displaySatFat.toFloat())
        val totalFatStatus   = getStatusStars(R.string.total_fat_g_txt, displayTotalFat.toFloat())
        val potassiumStatus  = getStatusStars(R.string.potassium_g_txt, displayPotassium.toFloat())
        val carbsStatus      = getStatusStars(R.string.total_carbohydrates_g_txt, displayCarbs.toFloat())

        // %-12s allocates exactly 12 characters of space, left-aligned
        val labelFormat = "%-12s"

        // 1. Establish the maximum width of your card line (matching your 39 dashes)
        val maxLineWidth = 39

        // 2. Calculate how much total blank space is left over, dividing by 2 for the left margin
        val leftPaddingCount = (maxLineWidth - gradeTitle.length) / 2

        // 3. Generate a string of blank spaces matching that count (safely ensuring it doesn't drop below 0)
        val centeredGradeTitle = " ".repeat(leftPaddingCount.coerceAtLeast(0)) + gradeTitle

        return """
            $centeredGradeTitle 
            ---------------------------------------
            Servings: $labelSuffix
            ---------------------------------------
            ${String.format(labelFormat, "Sodium")} : ${displaySodium}mg $sodiumStatus
            ${String.format(labelFormat, "Protein")} : ${displayProtein}g $proteinStatus
            ${String.format(labelFormat, "Total Carbs")} : ${displayCarbs}g $carbsStatus
            ${String.format(labelFormat, "Total Sugar")} : ${displayTotalSugar}g $totalSugarStatus
            ${String.format(labelFormat, "Added Sugar")} : ${displayAddedSugar}g $addedSugarStatus
            ${String.format(labelFormat, "Total Fat")} : ${displayTotalFat}g $totalFatStatus
            ${String.format(labelFormat, "Sat Fat")} : ${displaySatFat}g $satFatStatus
            ${String.format(labelFormat, "Potassium")} : ${displayPotassium}mg $potassiumStatus
        """.trimIndent()

    }
}