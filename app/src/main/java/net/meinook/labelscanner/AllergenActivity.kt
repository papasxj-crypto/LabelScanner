package net.meinook.labelscanner

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.LinearLayout

class AllergenActivity : AppCompatActivity() {

    private lateinit var userSettings: AppSettings
    private lateinit var editCustomIngredient: EditText

    // Core Clinical Checkboxes
    private lateinit var checkGluten: CheckBox
    private lateinit var checkDairy: CheckBox
    private lateinit var checkSoy: CheckBox
    private lateinit var checkNuts: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allergens)

        userSettings = AppSettings(this)

        // Bind Custom Input Views
        editCustomIngredient = findViewById(R.id.editCustomIngredient)
        val buttonAddYellow = findViewById<Button>(R.id.buttonAddYellow)
        val buttonAddRed = findViewById<Button>(R.id.buttonAddRed)

        // Bind Clinical Checkboxes
        checkGluten = findViewById(R.id.checkAllergenGluten)
        checkDairy = findViewById(R.id.checkAllergenDairy)
        checkSoy = findViewById(R.id.checkAllergenSoy)
        checkNuts = findViewById(R.id.checkAllergenNuts)

        // Load existing profiles & custom watchlists
        loadSavedAllergenState()


        // Handle Add Sensitivity (YELLOW Tier)
        buttonAddYellow.setOnClickListener {
            addCustomTrigger("YELLOW")
        }

        // Handle Add Avoidance (RED Tier)
        buttonAddRed.setOnClickListener {
            addCustomTrigger("RED")
        }

        // Setup immediate saving listeners for clinical profile switches
        setupCheckboxListeners()

        // 1. Bind your expandable UI components
        val textToggleGlutenPreview = findViewById<TextView>(R.id.textToggleGlutenPreview)
        val textGlutenIngredientsList = findViewById<TextView>(R.id.textGlutenIngredientsList)

        // 2. Read the ingredients dynamically straight from your XML asset file
        val glutenIngredients = userSettings.getIngredientsFromAssetFile("allergen_gluten")
        // Format them beautifully as a clean comma-separated list or bullet points
        textGlutenIngredientsList.text = "Covers: " + glutenIngredients.joinToString(", ")

        // 3. Set a smooth tap listener to toggle the visibility window
        textToggleGlutenPreview.setOnClickListener {
            if (textGlutenIngredientsList.visibility == View.GONE) {
                textGlutenIngredientsList.visibility = View.VISIBLE
                textToggleGlutenPreview.text = "▲ Hide"
                textToggleGlutenPreview.setTextColor(Color.parseColor("#6200EE")) // Premium accent highlight
            } else {
                textGlutenIngredientsList.visibility = View.GONE
                textToggleGlutenPreview.text = "▼ View Included"
                textToggleGlutenPreview.setTextColor(Color.parseColor("#888888"))
            }
        }

        // 1. Bind your expandable UI components
        val textToggleDairyPreview = findViewById<TextView>(R.id.textToggleDairyPreview)
        val textDairyIngredientsList = findViewById<TextView>(R.id.textDairyIngredientsList)

        // 2. Read the ingredients dynamically straight from your XML asset file
        val dairyIngredients = userSettings.getIngredientsFromAssetFile("allergen_dairy")
        // Format them beautifully as a clean comma-separated list or bullet points
        textDairyIngredientsList.text = "Covers: " + dairyIngredients.joinToString(", ")

        // 3. Set a smooth tap listener to toggle the visibility window
        textToggleDairyPreview.setOnClickListener {
            if (textDairyIngredientsList.visibility == View.GONE) {
                textDairyIngredientsList.visibility = View.VISIBLE
                textToggleDairyPreview.text = "▲ Hide"
                textToggleDairyPreview.setTextColor(Color.parseColor("#6200EE")) // Premium accent highlight
            } else {
                textDairyIngredientsList.visibility = View.GONE
                textToggleDairyPreview.text = "▼ View Included"
                textToggleDairyPreview.setTextColor(Color.parseColor("#888888"))
            }
        }

        // 1. Bind your expandable UI components
        val textToggleSoyPreview = findViewById<TextView>(R.id.textToggleSoyPreview)
        val textSoyIngredientsList = findViewById<TextView>(R.id.textSoyIngredientsList)

        // 2. Read the ingredients dynamically straight from your XML asset file
        val soyIngredients = userSettings.getIngredientsFromAssetFile("allergen_soy")
        // Format them beautifully as a clean comma-separated list or bullet points
        textSoyIngredientsList.text = "Covers: " + soyIngredients.joinToString(", ")

        // 3. Set a smooth tap listener to toggle the visibility window
        textToggleSoyPreview.setOnClickListener {
            if (textSoyIngredientsList.visibility == View.GONE) {
                textSoyIngredientsList.visibility = View.VISIBLE
                textToggleSoyPreview.text = "▲ Hide"
                textToggleSoyPreview.setTextColor(Color.parseColor("#6200EE")) // Premium accent highlight
            } else {
                textSoyIngredientsList.visibility = View.GONE
                textToggleSoyPreview.text = "▼ View Included"
                textToggleSoyPreview.setTextColor(Color.parseColor("#888888"))
            }
        }

        // 1. Bind your expandable UI components
        val textToggleNutsPreview = findViewById<TextView>(R.id.textToggleNutsPreview)
        val textNutsIngredientsList = findViewById<TextView>(R.id.textNutsIngredientsList)

        // 2. Read the ingredients dynamically straight from your XML asset file
        val nutsIngredients = userSettings.getIngredientsFromAssetFile("allergen_nuts")
        // Format them beautifully as a clean comma-separated list or bullet points
        textNutsIngredientsList.text = "Covers: " + nutsIngredients.joinToString(", ")

        // 3. Set a smooth tap listener to toggle the visibility window
        textToggleNutsPreview.setOnClickListener {
            if (textNutsIngredientsList.visibility == View.GONE) {
                textNutsIngredientsList.visibility = View.VISIBLE
                textToggleNutsPreview.text = "▲ Hide"
                textToggleNutsPreview.setTextColor(Color.parseColor("#6200EE")) // Premium accent highlight
            } else {
                textNutsIngredientsList.visibility = View.GONE
                textToggleNutsPreview.text = "▼ View Included"
                textToggleNutsPreview.setTextColor(Color.parseColor("#888888"))
            }
        }

        val buttonReturnToSettings = findViewById<Button>(R.id.buttonReturnToSettings)
        buttonReturnToSettings.setOnClickListener {
            // Smoothly closes this layout card and returns focus back to settings
            finish()
        }
    }

    private fun addCustomTrigger(tier: String) {
        val rawInput = editCustomIngredient.text.toString().trim().uppercase()
        if (rawInput.isEmpty()) {
            Toast.makeText(this, "Please enter an ingredient name first", Toast.LENGTH_SHORT).show()
            return
        }

        // Save entry directly to your upgraded dual-tier AppSettings infrastructure
        userSettings.saveCustomWatchlistItem(rawInput, tier)
        editCustomIngredient.text.clear()

        // Instant visual local layout refresh
        updateWatchlistDisplay()
        Toast.makeText(this, "$rawInput added to $tier watchlist", Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedAllergenState() {
        val savedProfiles = userSettings.getSelectedConditions()
        checkGluten.isChecked = savedProfiles.contains("allergen_gluten")
        checkDairy.isChecked = savedProfiles.contains("allergen_dairy")
        checkSoy.isChecked = savedProfiles.contains("allergen_soy")
        checkNuts.isChecked = savedProfiles.contains("allergen_nuts")

        // 🟢 Make sure this calls the new dynamic renderer:
        updateWatchlistDisplay()
    }

    private fun updateWatchlistDisplay() {
        val container = findViewById<LinearLayout>(R.id.containerWatchlistItems)
        container.removeAllViews() // Clear old views before redraw

        val redItems = userSettings.getCustomWatchlist("RED")
        val yellowItems = userSettings.getCustomWatchlist("YELLOW")

        if (redItems.isEmpty() && yellowItems.isEmpty()) {
            val noItemsText = TextView(this).apply {
                text = "No custom filters active."
                setTextColor(Color.parseColor("#555555"))
                textSize = 14f
            }
            container.addView(noItemsText)
            return
        }

        // Render Red Items
        if (redItems.isNotEmpty()) {
            val header = TextView(this).apply {
                text = "🔴 AVOID (RED) - Tap to remove:"
                setTextColor(Color.parseColor("#A33B3B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 8, 0, 4)
            }
            container.addView(header)

            redItems.forEach { ingredient ->
                val itemView = TextView(this).apply {
                    text = "  ✖  $ingredient"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        userSettings.removeWatchlistIngredient(ingredient)
                        updateWatchlistDisplay()
                        Toast.makeText(this@AllergenActivity, "$ingredient removed", Toast.LENGTH_SHORT).show()
                    }
                }
                container.addView(itemView)
            }
        }

        // Render Yellow Items
        if (yellowItems.isNotEmpty()) {
            val header = TextView(this).apply {
                text = "🟡 SENSITIVITY (YELLOW) - Tap to remove:"
                setTextColor(Color.parseColor("#CAA100"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 16, 0, 4)
            }
            container.addView(header)

            yellowItems.forEach { ingredient ->
                val itemView = TextView(this).apply {
                    text = "  ✖  $ingredient"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        userSettings.removeWatchlistIngredient(ingredient)
                        updateWatchlistDisplay()
                        Toast.makeText(this@AllergenActivity, "$ingredient removed", Toast.LENGTH_SHORT).show()
                    }
                }
                container.addView(itemView)
            }
        }
    }

    private fun setupCheckboxListeners() {
        val checkboxMap = mapOf(
            checkGluten to "allergen_gluten",
            checkDairy to "allergen_dairy",
            checkSoy to "allergen_soy",
            checkNuts to "allergen_nuts"
        )

        for ((checkbox, profileId) in checkboxMap) {
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                userSettings.toggleConditionState(profileId, isChecked)
            }
        }
    }
}