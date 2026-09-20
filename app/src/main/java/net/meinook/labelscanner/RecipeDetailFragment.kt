package net.meinook.labelscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class RecipeDetailFragment : Fragment(R.layout.fragment_recipe_detail) {

    // DUAL-URL INJECTION COMPLIANT - NO HARDCODED "HTTPS://"
    private val backendAnalysisUrl by lazy {
        if (BuildConfig.DEBUG) {
            getString(R.string.dev_URL)
        } else {
            getString(R.string.production_URL)
        }
    }

    private lateinit var appSettings: AppSettings

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appSettings = AppSettings(requireContext())

        // 1. Unpack arguments from the Saved Cookbook bundle
        val savedId = arguments?.getString("HISTORY_ITEM_ID") ?: ""
        val title = arguments?.getString("RECIPE_TITLE") ?: "Saved Recipe"
        val adjustedOutput = arguments?.getString("RECIPE_ADJUSTED_OUTPUT") ?: ""
        var savedInstructions = arguments?.getString("RECIPE_INSTRUCTIONS") ?: ""
        val sourceUrl = arguments?.getString("RECIPE_URL") ?: ""
        val baseProfileId = arguments?.getString("BASE_PROFILE_ID") ?: "healthy_baseline"
        val savedGrade = arguments?.getString("ADJUSTED_GRADE") ?: "Green - Safe"

        // 2. Bind View Elements
        val btnBackTop = view.findViewById<ImageView>(R.id.btnBackTop)
        val txtTitle = view.findViewById<TextView>(R.id.txtRecipeDetailTitle)
        val txtBadge = view.findViewById<TextView>(R.id.txtRecipeDetailBadge)
        val txtProfile = view.findViewById<TextView>(R.id.txtRecipeDetailProfile)
        val txtSource = view.findViewById<TextView>(R.id.txtRecipeDetailSource)
        val txtIngredientsHeader = view.findViewById<TextView>(R.id.txtDetailIngredientsHeader)
        val btnReProfile = view.findViewById<MaterialButton>(R.id.btnReProfile)
        val txtIngredients = view.findViewById<TextView>(R.id.txtDetailIngredients)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopyIngredients)
        val txtInstructions = view.findViewById<TextView>(R.id.txtDetailInstructions)
        val btnFetchSteps = view.findViewById<MaterialButton>(R.id.btnFetchSteps)

        // 3. Top-Left Back Navigation
        btnBackTop.setOnClickListener {
            findNavController().navigateUp()
        }

        // 4. Populate Header & Real-Time Safety Status
        txtTitle.text = title
        txtIngredients.text = adjustedOutput

        val activeConditions = appSettings.getSelectedConditions()
        val customReds = appSettings.getCustomWatchlist("RED").map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
        val adjustedLines = adjustedOutput.lines().map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }

        val hasCustomViolation = customReds.any { trigger ->
            val boundaryRegex = "\\b${Regex.escape(trigger)}\\b".toRegex()
            adjustedLines.any { it.contains(boundaryRegex) || it == trigger }
        }

        val normalizedActiveBases = activeConditions.map { id ->
            val base = appSettings.getBaseProfileIdForProfile(id)
            if (base.isNotEmpty()) base else id
        }.toSet()

        val normalizedItemBase = run {
            val base = appSettings.getBaseProfileIdForProfile(baseProfileId)
            if (base.isNotEmpty()) base else baseProfileId
        }

        val profileMatches = activeConditions.contains(baseProfileId) ||
                normalizedActiveBases.contains(normalizedItemBase) ||
                normalizedActiveBases.contains(baseProfileId)

        val isOriginallyRed = savedGrade.lowercase(Locale.ROOT).contains("red") ||
                savedGrade.lowercase(Locale.ROOT).contains("avoid")
        val isOriginallyCaution = savedGrade.lowercase(Locale.ROOT).contains("yellow") ||
                savedGrade.lowercase(Locale.ROOT).contains("caution")
        val isOriginallySafe = savedGrade.lowercase(Locale.ROOT).contains("green") ||
                savedGrade.lowercase(Locale.ROOT).contains("safe")

        val (safetyText, safetyColor) = when {
            hasCustomViolation -> Pair("🔴 Avoid (Watchlist)", "#FF6B6B")
            isOriginallyRed -> Pair("🔴 Avoid (Saved)", "#FF6B6B")
            !profileMatches -> Pair("🟡 Re-Verify", "#FFD54F")
            isOriginallyCaution -> Pair("🟡 Caution (Saved)", "#FFD54F")
            isOriginallySafe -> Pair("🟢 Verified Safe", "#81C784")
            else -> Pair("🟡 Re-Verify", "#FFD54F")
        }

        txtBadge.text = safetyText
        txtBadge.setTextColor(Color.parseColor(safetyColor))

        val headerTitle = when {
            safetyText.contains("Avoid") -> "INGREDIENTS (AVOID)"
            safetyText.contains("Caution") || safetyText.contains("Re-Verify") -> "INGREDIENTS (CAUTION)"
            else -> "INGREDIENTS (SAFE ADJUSTED)"
        }
        txtIngredientsHeader.text = headerTitle
        txtIngredientsHeader.setTextColor(Color.parseColor(safetyColor))

        val displayName = appSettings.getAvailableDietProfiles().find { it.id == baseProfileId }?.displayName
            ?: appSettings.getAvailableDietProfiles().find { it.id == normalizedItemBase }?.displayName
            ?: baseProfileId
        txtProfile.text = "For: $displayName"

        // Re-Profile Trigger: Pops backstack so SavedFragment remains clean
        fun triggerReProfile() {
            val bundle = Bundle().apply {
                putBoolean("FORCE_REPROFILE", true)
                if (sourceUrl.isNotBlank()) {
                    putString("recipe_url", sourceUrl)
                    putString("RECIPE_URL", sourceUrl)
                } else {
                    putString("RECIPE_INPUT", adjustedOutput)
                }
            }
            val navOptions = NavOptions.Builder()
                .setPopUpTo(R.id.navigation_history, false)
                .setLaunchSingleTop(true)
                .build()
            findNavController().navigate(R.id.recipeFragment, bundle, navOptions)
        }

        btnReProfile.setOnClickListener {
            triggerReProfile()
        }

        if (sourceUrl.isNotBlank()) {
            txtSource.text = "Source: $sourceUrl (Tap to Re-Profile)"
            txtSource.visibility = View.VISIBLE
            txtSource.setOnClickListener {
                triggerReProfile()
            }
        } else {
            txtSource.visibility = View.GONE
        }

        // 5. Populate or On-Demand Fetch Instructions
        fun renderInstructions(steps: String) {
            if (steps.isNotBlank()) {
                txtInstructions.text = steps
                txtInstructions.setTextColor(Color.parseColor("#F4F5FC"))
                btnFetchSteps.visibility = View.GONE
            } else {
                txtInstructions.text = "No instructions loaded yet. Tap 'Get Steps' below to generate cooking steps."
                txtInstructions.setTextColor(Color.parseColor("#99A1B3"))
                btnFetchSteps.visibility = View.VISIBLE
            }
        }
        renderInstructions(savedInstructions)

        btnFetchSteps.setOnClickListener {
            if (adjustedOutput.isBlank()) return@setOnClickListener

            btnFetchSteps.isEnabled = false
            btnFetchSteps.text = "Fetching cooking steps..."

            lifecycleScope.launch {
                val instructionsResult = fetchInstructionsFromBackend(adjustedOutput)
                if (instructionsResult != null && instructionsResult.isNotBlank()) {
                    savedInstructions = instructionsResult
                    renderInstructions(savedInstructions)

                    if (savedId.isNotBlank()) {
                        SavedPersistenceManager.updateItemInstructions(requireContext(), savedId, savedInstructions)
                    }
                    Toast.makeText(context, "Instructions saved offline!", Toast.LENGTH_SHORT).show()
                } else {
                    btnFetchSteps.isEnabled = true
                    btnFetchSteps.text = "Get Steps"
                    Toast.makeText(context, "Failed to retrieve steps. Check network connection.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 6. Setup Copy Button
        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = if (savedInstructions.isNotBlank()) {
                "$title\n\nINGREDIENTS:\n$adjustedOutput\n\nPREPARATION:\n$savedInstructions"
            } else {
                "$title\n\nINGREDIENTS:\n$adjustedOutput"
            }
            val clip = ClipData.newPlainText("Recipe", clipText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Recipe copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fetchInstructionsFromBackend(adjustedIngredients: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(backendAnalysisUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("action", "steps")
                put("adjusted_ingredients", adjustedIngredients)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == 200) {
                val rawJson = conn.inputStream.bufferedReader().use { it.readText().trim() }
                val jsonResponse = JSONObject(rawJson)
                jsonResponse.optString("instructions", "").ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}