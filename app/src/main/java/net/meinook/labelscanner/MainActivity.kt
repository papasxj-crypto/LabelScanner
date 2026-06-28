package net.meinook.labelscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.graphics.toColorInt
import com.google.ai.client.generativeai.GenerativeModel
import net.meinook.labelscanner.BuildConfig

class MainActivity : AppCompatActivity() {

    // 1. Global State Management (Enforces the absolute strictest limit if multiple boxes are checked)
    private var sodiumModMax: Int = 2300
    private var selectedConditionsString: String = ""

    // 2. View Element Declarations
    private lateinit var mainLayoutContainer: LinearLayout
    private lateinit var textExplanation: TextView
    private lateinit var textCondition: TextView
    private lateinit var buttonScan: Button
    private lateinit var buttonOpenSettings: Button

    // 3. Multi-Selection Checkboxes
    private lateinit var checkCKD: android.widget.CheckBox
    private lateinit var checkGLP1: android.widget.CheckBox
    private lateinit var checkLowSodium: android.widget.CheckBox

    // 4. Data Streams and Storage Tracking
    private lateinit var tempPhotoUri: android.net.Uri
    private val activeConditions = mutableSetOf<String>()

    // 5. High-Resolution Camera Storage Callback
    private val cameraLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            try {
                val inputStream = contentResolver.openInputStream(tempPhotoUri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    runAnalysis(bitmap)
                } else {
                    textExplanation.text = getString(R.string.error_loading_image)
                }
            } catch (e: Exception) {
                e.printStackTrace() // <-- This uses "e", clearing the warning instantly!
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

        mainLayoutContainer = buttonScan.parent as LinearLayout

        // Navigation Action to Settings Dashboard
        buttonOpenSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Camera Action Shutter Trigger with Dynamic UI Clear-Down
        buttonScan.setOnClickListener {
            textExplanation.text = getString(R.string.camera_msg_1)
            textExplanation.setTextColor(android.graphics.Color.WHITE)

            try {
                val photoFile = java.io.File(this@MainActivity.filesDir, "scan_capture.jpg").apply {
                    if (exists()) delete()
                    createNewFile()
                }

                tempPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "net.meinook.labelscanner.fileprovider",
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

        // 1. Instantly pull the profile state out of storage
        val userSettings = AppSettings(this)
        val savedConditions = userSettings.getSelectedConditions()

        // Synchronize our global active conditions set
        activeConditions.clear()
        activeConditions.addAll(savedConditions)

        // 2. Automatically recalculate your strict sodium limits based on the saved rules
        sodiumModMax = 2300 // Baseline fallback

        if (activeConditions.contains("Low Sodium")) sodiumModMax = minOf(sodiumModMax, 140)
        if (activeConditions.contains("CKD"))        sodiumModMax = minOf(sodiumModMax, 300)
        if (activeConditions.contains("GLP-1"))      sodiumModMax = minOf(sodiumModMax, 400)

        updateConditionText()
    }

    /**
     * Evaluates data from Gemini against your rule assets and custom settings profiles.
     */
    private fun runAnalysis(imageBitmap: Bitmap) {
        textExplanation.text = getString(R.string.analyzing_label_msg)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Access the key securely via BuildConfig
                val secureApiKey = BuildConfig.GEMINI_API_KEY

                // Use it to initialize your Gemini client
                val generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = secureApiKey
                )

                val userSettings = AppSettings(this@MainActivity)
                val savedProfileConditions = userSettings.getSelectedConditions()

                val selectedConditionsString = if (savedProfileConditions.isNotEmpty()) {
                    savedProfileConditions.joinToString(", ")
                } else {
                    getString(R.string.standard_baseline)
                }

                // Query the execution engine
                val rawResponseFromGemini = GeminiAnalyzer.analyzeIngredientsImage(imageBitmap, selectedConditionsString, secureApiKey)

                val jsonResult = JSONObject(rawResponseFromGemini)
                val jsonArray = jsonResult.getJSONArray("detected_ingredients")
                val detectedIngredients = mutableListOf<String>()

                // Pulls the entire array at once, instantly upper-casing them
                val negationPhrases = resources.getStringArray(R.array.negation_phrases).map { it.uppercase() }

                for (i in 0 until jsonArray.length()) {
                    val ingredientRaw = jsonArray.getString(i).uppercase()

                    // SAFETY CHECK: If the text line contains any of our negation phrases,
                    // we SKIP adding it to our evaluation array entirely!
                    val isNegatedStatement = negationPhrases.any { ingredientRaw.contains(it) }

                    if (!isNegatedStatement) {
                        detectedIngredients.add(ingredientRaw)
                    }
                }

                val sodiumMg = jsonResult.optInt("sodium_mg", 0)
                val sodiumDv = jsonResult.optInt("sodium_dv_percent", 0)

                // Load your static rules from assets
                val xmlRedTriggers = userSettings.loadTriggersFromAssets("RED")
                val xmlYellowTriggers = userSettings.loadTriggersFromAssets("YELLOW")
                val customBlacklist = userSettings.getCustomBlacklist()

                // Default Layout Colors and Text Formats (Safe State baseline)
                var bgColor = (getString(R.string.green)).toColorInt()
                var textColor = Color.WHITE
                var finalGrade = getString(R.string.grade_green_compliant, sodiumMg)

                // Pull local low ceiling profile thresholds
                val (sodiumLowMax, _) = userSettings.getNutrientThresholds("SODIUM")

                // 1. Find EXACTLY which blacklisted or XML red ingredients were found
                val matchedBlacklist = customBlacklist.filter { detectedIngredients.contains(it) }
                val matchedXmlRed = xmlRedTriggers.filter { detectedIngredients.contains(it) }

                // Rule Evaluation Pipeline
                when {
                    // Priority 1: Custom User Blacklist
                    matchedBlacklist.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE

                        // Build a dynamic string listing the caught items: "RED: BLACKLISTED MATCH (POTASSIUM)"
                        val offenders = matchedBlacklist.joinToString(", ")
                        finalGrade = getString(R.string.grade_red_blacklist_format, offenders)
                    }
                    // Priority 2: Static XML Red Rules
                    matchedXmlRed.isNotEmpty() -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE

                        val offenders = matchedXmlRed.joinToString(", ")
                        finalGrade = getString(R.string.grade_red_high_risk_format, offenders)
                    }
                    sodiumMg > sodiumModMax || sodiumDv >= 20 -> {
                        bgColor = (getString(R.string.red)).toColorInt()
                        textColor = Color.WHITE
                        finalGrade = getString(R.string.grade_red_high_sodium, sodiumMg, sodiumModMax)
                    }
                    sodiumMg in (sodiumLowMax + 1)..sodiumModMax -> {
                        bgColor = (getString(R.string.yellow)).toColorInt()
                        textColor = Color.BLACK
                        finalGrade = getString(R.string.grade_yellow_moderate, sodiumMg)
                    }
                    xmlYellowTriggers.any { detectedIngredients.contains(it) } -> {
                        bgColor = (getString(R.string.yellow)).toColorInt()
                        textColor = Color.BLACK
                        finalGrade = getString(R.string.grade_yellow_warning)
                    }
                }

                withContext(Dispatchers.Main) {
                    // 1. Set the text size and padding so it feels spacious and readable
                    textExplanation.text = finalGrade
                    textExplanation.setTextColor(textColor)
                    textExplanation.setPadding(32, 32, 32, 32) // Adds nice internal breathing room

                    // 2. Create a premium, muted rounded card background dynamically
                    val premiumCardBackground = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(bgColor) // Dynamically applies your muted color variable
                        cornerRadius = 24f // Smooth, modern rounded corners
                        setStroke(2, getString(R.string.frost_white).toColorInt()) // Subtle translucent border outline
                    }

                    textExplanation.background = premiumCardBackground
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

        // Build the comma-separated string based on what's in storage
        selectedConditionsString = if (savedProfileConditions.isNotEmpty()) {
            savedProfileConditions.joinToString(", ")
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
}