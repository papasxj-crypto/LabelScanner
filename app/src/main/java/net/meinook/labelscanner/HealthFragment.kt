package net.meinook.labelscanner

import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class HealthFragment : Fragment(R.layout.fragment_health) {

    private lateinit var layoutConditions: LinearLayout
    private lateinit var layoutAllergens: LinearLayout
    private lateinit var btnManageWatchlist: Button
    private lateinit var appSettings: AppSettings

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutConditions = view.findViewById(R.id.layoutConditionSwitches)
        layoutAllergens = view.findViewById(R.id.layoutAllergenSwitches)
        btnManageWatchlist = view.findViewById(R.id.btnManageCustomWatchlist)
        appSettings = AppSettings(requireContext())

        btnManageWatchlist.setOnClickListener {
            findNavController().navigate(R.id.action_health_to_custom_watchlist)
        }

        // --- Link Input Field directly to pre-existing KEY_USER_WEIGHT ---
        val etIdealWeight = view.findViewById<EditText>(R.id.etIdealWeight)
        val savedWeight = appSettings.getUserWeight()
        if (savedWeight > 0.0) {
            etIdealWeight.setText(savedWeight.toString())
        }

        etIdealWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val weight = s.toString().toDoubleOrNull() ?: 0.0
                appSettings.setUserWeight(weight)
                appSettings.setPendingSaveFlag(true)
            }
        })

        // --- Dynamic Scroll Indicator Arrows Logic ---
        val scrollCondition = view.findViewById<NestedScrollView>(R.id.scrollConditionSwitches)
        val ivScrollUp = view.findViewById<ImageView>(R.id.ivScrollUpIndicator)
        val ivScrollDown = view.findViewById<ImageView>(R.id.ivScrollDownIndicator)

        fun updateScrollIndicators() {
            val child = scrollCondition.getChildAt(0)
            if (child != null) {
                // Total height of the scroll content minus the visible window height
                val scrollRange = child.height - scrollCondition.height
                if (scrollRange > 0) {
                    val scrollY = scrollCondition.scrollY

                    // Show up-arrow if we have scrolled down past a tiny threshold
                    ivScrollUp.visibility = if (scrollY > 15) View.VISIBLE else View.GONE

                    // Show down-arrow if we have not reached the very bottom (with a small buffer)
                    ivScrollDown.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
                } else {
                    // Content fits fully within current window space, hide both indicators
                    ivScrollUp.visibility = View.GONE
                    ivScrollDown.visibility = View.GONE
                }
            }
        }

        // Post the initial calculation to wait for layout & measurement to finish
        scrollCondition.post { updateScrollIndicators() }

        // Track real-time scroll updates
        scrollCondition.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateScrollIndicators()
        })

        buildDynamicSwitches()
    }

    private fun buildDynamicSwitches() {
        val allProfiles = appSettings.getAvailableDietProfiles()
        val selectedIds = appSettings.getSelectedConditions()

        layoutConditions.removeAllViews()
        layoutAllergens.removeAllViews()

        for (profile in allProfiles) {
            if (profile.id.startsWith("allergen")) {
                // Silders/Switches for Allergens
                val switch = SwitchMaterial(requireContext()).apply {
                    text = profile.displayName
                    isChecked = selectedIds.contains(profile.id)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setPadding(0, 32, 0, 32)

                    setOnCheckedChangeListener { _, isChecked ->
                        appSettings.toggleConditionState(profile.id, isChecked)
                        if (profile.id == "healthy_baseline" || isChecked) {
                            buildDynamicSwitches()
                        }
                    }

                    setOnLongClickListener {
                        showAllergenDetails(profile.id, profile.displayName)
                        true
                    }
                }
                layoutAllergens.addView(switch)
            } else {
                // CheckBoxes for Clinical Targets
                val checkbox = MaterialCheckBox(requireContext()).apply {
                    text = profile.displayName
                    isChecked = selectedIds.contains(profile.id)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    setPadding(0, 32, 0, 32)

                    setOnCheckedChangeListener { _, isChecked ->
                        appSettings.toggleConditionState(profile.id, isChecked)
                        if (profile.id == "healthy_baseline" || isChecked) {
                            buildDynamicSwitches()
                        }
                    }

                    setOnLongClickListener {
                        showAllergenDetails(profile.id, profile.displayName)
                        true
                    }
                }
                layoutConditions.addView(checkbox)
            }
        }
    }

    private fun showAllergenDetails(profileId: String, title: String) {
        val triggers = appSettings.getIngredientsFromAssetFile(profileId)

        val message = if (triggers.isNotEmpty()) {
            "LabelScanner will flag these ingredients:\n\n• " + triggers.joinToString("\n• ")
        } else {
            "Evaluating based on clinical macro thresholds."
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_LabelScanner)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}