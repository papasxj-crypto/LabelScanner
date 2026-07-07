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
    private var proteinLowMax: Int = 80
    private var addedSugarModMax: Int = 5
    private var totalSugarModMax: Int = 10
    private var satFatModMax: Int = 3
    private var transFatModMax: Int = 3
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
    private var lastScanTransFatGrams: Float = 0.0f
    private var lastScanCalories: Int = 0
    private var lastScanFiberGrams: Float = 0.0f
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

        // ---> BOOT HOOK TRIGGERED <---
        // Run the XML indexer immediately so our exclusivity group lookups work flawlessly
        AppSettings.indexExclusivityGroups(this)

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

        // 1. Keeps your dashboard target header label perfectly synchronized
        updateConditionText()

        val userSettings = AppSettings(this)
        val savedConditions = userSettings.getSelectedConditions()

        activeConditions.clear()
        activeConditions.addAll(savedConditions)

        val userTargetWeightLbs = userSettings.getUserWeight().let { if (it > 0) it else 195.0 }

        // 2. Keep your background threshold calculations running smoothly
        sodiumModMax = getMaxSodium(activeConditions)
        proteinModMax = getMaxProtein(userTargetWeightLbs, activeConditions)

        // 3. ---> ADDED: Clear out old scan cards on return so the screen resets <---
        textExplanation.text = ""
        textExplanation.setPadding(0, 0, 0, 0)
        textExplanation.setBackgroundColor(Color.TRANSPARENT)

        // Check if we just returned from a successful save
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

        // Reset your scan detection tracking flag
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

                // --- 1. EXTRACT RAW NUTRITION FIELDS ---
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
                val fiberGrams = jsonResult.optDouble(getString(R.string.fiber_g_txt), 0.0).toFloat()
                val potassiumRaw = jsonResult.optDouble(getString(R.string.potassium_g_txt), 0.0).toFloat()

                val potassiumMg = if (potassiumRaw > 0.0 && potassiumRaw < 5.0) {
                    (potassiumRaw * 1000).toInt()
                } else {
                    potassiumRaw.toInt()
                }.toFloat()

                // --- 2. APPLY USER PORTION MODIFIER CEILING ---
                val servingScaleFactor = 1.0f
                val scaledCalories = rawCalories * servingScaleFactor
                val scaledProtein = proteinGrams * servingScaleFactor
                val scaledTotalFat = totalFatGrams * servingScaleFactor
                val scaledAddedSugar = addedSugarGrams * servingScaleFactor

                // Cache all values securely for instant layout redraws later
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
                val xmlYellowTriggers = userSettings.loadTriggersFromAssets(getString(R.string.yellow_txt))
                val customBlacklist = userSettings.getCustomBlacklist()

                var bgColor = (getString(R.string.green)).toColorInt()
                var textColor = Color.WHITE
                var lastScanGradeTitle = ""

                // Extract dynamic clinical properties from your XML data layer
                val sodiumTriple     = userSettings.getNutrientThresholds("sodium")
                val addedSugarTriple = userSettings.getNutrientThresholds("added_sugar")
                val totalSugarTriple = userSettings.getNutrientThresholds("total_sugar")
                val satFatTriple     = userSettings.getNutrientThresholds("saturated_fat")
                val transFatTriple   = userSettings.getNutrientThresholds("trans_fat")
                val totalFatTriple   = userSettings.getNutrientThresholds("total_fat")
                val potassiumTriple  = userSettings.getNutrientThresholds("potassium")
                val carbsTriple      = userSettings.getNutrientThresholds("carbs")

                val (sodiumLowMax, sodiumModMax, sodiumIsBlacklist) = sodiumTriple
                val (addedSugarLowMax, addedSugarModMax, addedSugarIsBlacklist) = addedSugarTriple
                val (totalSugarLowMax, totalSugarModMax, totalSugarIsBlacklist) = totalSugarTriple
                val (satFatLowMax, satFatModMax, satFatIsBlacklist) = satFatTriple
                val (transFatLowMax, transFatModMax, transFatIsBlacklist) = transFatTriple
                val (totalFatLowMax, totalFatModMax, totalFatIsBlacklist) = totalFatTriple
                val (potassiumLowMax, potassiumModMax, potassiumIsBlacklist) = potassiumTriple
                val (carbsLowMax, carbsModMax, carbsIsBlacklist) = carbsTriple

                // Sync global limits to prevent layout-redraw variable shadowing
                this@MainActivity.sodiumModMax = sodiumModMax
                this@MainActivity.totalSugarModMax = totalSugarModMax
                this@MainActivity.addedSugarModMax = addedSugarModMax
                this@MainActivity.satFatModMax = satFatModMax
                this@MainActivity.transFatModMax = transFatModMax
                this@MainActivity.totalFatModMax = totalFatModMax
                this@MainActivity.potassiumModMax = potassiumModMax
                this@MainActivity.carbsModMax = carbsModMax

                val redViolations = mutableListOf<String>()
                val yellowViolations = mutableListOf<String>()

                // --- 3. EXECUTE STANDARD CEILING EVALUATION ENGINE ---

                // Sodium
                if (sodiumMg > sodiumModMax) {
                    if (sodiumIsBlacklist) redViolations.add(getString(R.string.sodium_mg_txt)) else yellowViolations.add(getString(R.string.sodium_mg_txt))
                } else if (sodiumMg > sodiumLowMax) {
                    yellowViolations.add(getString(R.string.sodium_mg_txt))
                }

                // Total Sugar
                if (totalSugarGrams > totalSugarModMax) {
                    if (totalSugarIsBlacklist) redViolations.add(getString(R.string.total_sugar_g_txt)) else yellowViolations.add(getString(R.string.total_sugar_g_txt))
                } else if (totalSugarGrams > totalSugarLowMax) {
                    yellowViolations.add(getString(R.string.total_sugar_g_txt))
                }

                // Added Sugar (With GLP-1 Custom Clinical Warning Logic)
                if (addedSugarGrams > addedSugarModMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "High Sugar / Nausea Warning" else getString(R.string.added_sugar_g_txt)
                    if (addedSugarIsBlacklist) redViolations.add(label) else yellowViolations.add(label)
                } else if (addedSugarGrams > addedSugarLowMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "Sugar Warning" else getString(R.string.added_sugar_g_txt)
                    yellowViolations.add(label)
                }

                // Saturated Fat
                if (satFatGrams > satFatModMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "Healthy Fat Profile Violation" else getString(R.string.saturated_fat_g_txt)
                    if (satFatIsBlacklist) redViolations.add(label) else yellowViolations.add(label)
                } else if (satFatGrams > satFatLowMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "Saturated Fat Warning" else getString(R.string.saturated_fat_g_txt)
                    yellowViolations.add(label)
                }

                // Total Fat (With GLP-1 Custom Clinical Warning Logic)
                if (totalFatGrams > totalFatModMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "Slow Digestion / Reflux Warning" else getString(R.string.total_fat_g_txt)
                    if (totalFatIsBlacklist) redViolations.add(label) else yellowViolations.add(label)
                } else if (totalFatGrams > totalFatLowMax) {
                    val label = if (savedProfileIds.contains("glp_1")) "Fat Limit Warning" else getString(R.string.total_fat_g_txt)
                    yellowViolations.add(label)
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

                // --- 4. UNIFIED PROTEIN AND FIBER EVALUATION ENGINE ---
                val proteinRules = userSettings.getActiveProteinRules()

                if (proteinRules.targetRatio > 0.0f) {
                    val currentRatio = if (scaledCalories > 0) scaledProtein / scaledCalories else 0.0f
                    if (currentRatio < proteinRules.targetRatio) {
                        yellowViolations.add("Low Protein Ratio (${String.format("%.2f", currentRatio)} < ${proteinRules.targetRatio})")
                    }

                    val isMealWindow = scaledCalories >= 250f
                    val activeMin = if (isMealWindow) proteinRules.mealMin else proteinRules.snackMin
                    val activeMax = if (isMealWindow) proteinRules.mealMax else proteinRules.snackMax
                    val contextLabel = if (isMealWindow) "Meal" else "Snack"

                    if (activeMin > 0 && scaledProtein < activeMin) {
                        yellowViolations.add("Low Protein for $contextLabel (<${activeMin}g)")
                    }

                    if (activeMax < 999 && scaledProtein > activeMax) {
                        redViolations.add("High Protein for $contextLabel (>${activeMax}g)")
                    }
                }

                // 3. Autonomous Fiber Enrichment Verification
                val fiberNutrient = userSettings.getActiveNutrientRules().find { rule -> rule.name == "fiber" }
                if (fiberNutrient != null) {
                    if (rawFiber < fiberNutrient.lowMax) {
                        yellowViolations.add("Low Dietary Fiber (<${fiberNutrient.lowMax}g)")
                    }
                }

                // --- 5. COMPILING ARRAYS FOR CARD GENERATION WITH PHOS TEXT WILDCARDS ---
                val matchedBlacklist = customBlacklist.filter { detectedIngredients.contains(it) }

                // Matches explicit strings loaded from XML, OR catches any string containing "PHOS" if "PHOS" is active in rules
                val matchedXmlRed = xmlRedTriggers.filter { trigger ->
                    if (trigger == "PHOS") {
                        detectedIngredients.any { it.contains("PHOS") }
                    } else {
                        detectedIngredients.contains(trigger)
                    }
                }

                when {
                    matchedBlacklist.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        val offenders = matchedBlacklist.joinToString(getString(R.string.comma))
                        lastScanGradeTitle = getString(R.string.red_blacklist_matched_msg, offenders)
                    }
                    matchedXmlRed.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE

                        // Grab the actual matching item from packaging text to build a dynamic report card message
                        val physicalOffender = detectedIngredients.find { it.contains("PHOS") } ?: "PHOSPHATE ADDITIVE"
                        val displayName = if (xmlRedTriggers.contains("PHOS") && physicalOffender.contains("PHOS")) physicalOffender else matchedXmlRed.first()

                        lastScanGradeTitle = getString(R.string.red_high_risk_msg, displayName)
                    }
                    redViolations.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        lastScanGradeTitle = "Red - Avoid (${redViolations.joinToString(", ")})"
                    }
                    yellowViolations.isNotEmpty() -> {
                        bgColor = (getString(R.string.yellow)).toColorInt()
                        textColor = Color.BLACK
                        lastScanGradeTitle = "Limit: ${yellowViolations.joinToString(", ")}"
                    }
                    else -> {
                        bgColor = (getString(R.string.green)).toColorInt()
                        textColor = Color.WHITE
                        lastScanGradeTitle = "Green - Safe Baseline"
                    }
                }

                this@MainActivity.lastScanGradeTitle = lastScanGradeTitle

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
                    fiberGrams,
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

        val proteinMultiplier = if (isCKDActive) {
            0.8
        } else {
            1.2
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