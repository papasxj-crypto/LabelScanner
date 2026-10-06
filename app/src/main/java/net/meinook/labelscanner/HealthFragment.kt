package net.meinook.labelscanner

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.Locale

class HealthFragment : Fragment(R.layout.fragment_health) {

    private lateinit var tabLayoutHealth: TabLayout
    private lateinit var layoutTabProfiles: LinearLayout
    private lateinit var layoutTabCustomWatchlist: LinearLayout

    // Tab 1 Elements
    private lateinit var etActualWeight: EditText
    private lateinit var txtToggleWeight: TextView
    private lateinit var etHeightFeet: EditText
    private lateinit var etHeightInches: EditText
    private lateinit var spinnerGender: Spinner
    private lateinit var txtIbwCalculated: TextView
    private lateinit var txtAjbwCalculated: TextView
    private lateinit var layoutConditions: LinearLayout
    private lateinit var layoutAllergens: LinearLayout
    private lateinit var txtClinicalTargetsHeader: TextView
    private lateinit var btnEditActiveProfile: MaterialButton

    // Tab 2 Elements
    private lateinit var etCustomIngredient: EditText
    private lateinit var btnAddRed: MaterialButton
    private lateinit var btnAddYellow: MaterialButton
    private lateinit var chipGroupCommonAdds: ChipGroup
    private lateinit var layoutActiveReds: LinearLayout
    private lateinit var layoutActiveYellows: LinearLayout

    private lateinit var appSettings: AppSettings
    private var isRefreshingUi = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appSettings = AppSettings(requireContext())

        // Tab Bindings
        tabLayoutHealth = view.findViewById(R.id.tabLayoutHealth)
        layoutTabProfiles = view.findViewById(R.id.layoutTabProfiles)
        layoutTabCustomWatchlist = view.findViewById(R.id.layoutTabCustomWatchlist)

        // Bind Tab 1 Views
        layoutConditions = view.findViewById(R.id.layoutConditionCheckBoxes)
        layoutAllergens = view.findViewById(R.id.layoutAllergenCheckBoxes)
        txtClinicalTargetsHeader = view.findViewById(R.id.txtClinicalTargetsHeader)
        btnEditActiveProfile = view.findViewById(R.id.btnEditActiveProfile)
        etActualWeight = view.findViewById(R.id.etActualWeight)
        txtToggleWeight = view.findViewById(R.id.txtToggleWeight)
        etHeightFeet = view.findViewById(R.id.etHeightFeet)
        etHeightInches = view.findViewById(R.id.etHeightInches)
        spinnerGender = view.findViewById(R.id.spinnerGender)
        txtIbwCalculated = view.findViewById(R.id.txtIbwCalculated)
        txtAjbwCalculated = view.findViewById(R.id.txtAjbwCalculated)

        // Bind Tab 2 Views
        etCustomIngredient = view.findViewById(R.id.etCustomIngredient)
        btnAddRed = view.findViewById(R.id.btnAddRed)
        btnAddYellow = view.findViewById(R.id.btnAddYellow)
        chipGroupCommonAdds = view.findViewById(R.id.chipGroupCommonAdds)
        layoutActiveReds = view.findViewById(R.id.layoutActiveReds)
        layoutActiveYellows = view.findViewById(R.id.layoutActiveYellows)

        btnEditActiveProfile.setOnClickListener {
            CustomProfileManager.showCreateCustomProfileDialog(requireContext(), appSettings) {
                appSettings.setPendingSaveFlag(true)
                refreshProfileData()
            }
        }

        setupTabLayout()
        setupTab1Profiles()
        setupTab2CustomWatchlist()
    }

    override fun onResume() {
        super.onResume()
        refreshProfileData()
    }

    override fun onPause() {
        super.onPause()
        appSettings.setCompletedInitialSetup(true)
    }

    private fun refreshProfileData() {
        isRefreshingUi = true

        val context = requireContext()

        // 1. Reload scoped biometrics for current active member
        val savedWeight = appSettings.getUserWeight()
        etActualWeight.setText(if (savedWeight > 0.0) savedWeight.toString() else "")

        val savedTotalInches = appSettings.getUserHeightInches()
        if (savedTotalInches > 0.0) {
            val feet = (savedTotalInches / 12).toInt()
            val inches = (savedTotalInches % 12).toInt()
            etHeightFeet.setText(feet.toString())
            etHeightInches.setText(inches.toString())
        } else {
            etHeightFeet.setText("")
            etHeightInches.setText("")
        }

        val savedGender = appSettings.getUserGender()
        val selectionIndex = when (savedGender) {
            "MALE" -> 1
            "FEMALE" -> 2
            else -> 0
        }
        spinnerGender.setSelection(selectionIndex)

        updateCalculatedWeights()

        // 2. Update visual member identity banner
        val activeMember = appSettings.getActiveProfile()
        txtClinicalTargetsHeader.text = "CLINICAL TARGETS ($activeMember)"

        // 3. Rebuild conditions & watchlists
        buildDynamicCheckBoxes()
        if (tabLayoutHealth.selectedTabPosition == 1) {
            setupCommonQuickAddChips()
            populateActiveWatchlists()
        }

        isRefreshingUi = false
    }

    private fun setupTabLayout() {
        tabLayoutHealth.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutTabProfiles.visibility = View.VISIBLE
                        layoutTabCustomWatchlist.visibility = View.GONE
                        buildDynamicCheckBoxes()
                    }
                    1 -> {
                        layoutTabProfiles.visibility = View.GONE
                        layoutTabCustomWatchlist.visibility = View.VISIBLE
                        setupCommonQuickAddChips()
                        populateActiveWatchlists()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupTab1Profiles() {
        val context = requireContext()

        spinnerGender.setPopupBackgroundDrawable(
            ContextCompat.getColor(context, R.color.inputSurface).toDrawable()
        )

        val genderAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            arrayOf("Unspecified", "Male", "Female")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerGender.adapter = genderAdapter

        spinnerGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isRefreshingUi) return
                val genderStr = when (position) {
                    1 -> "MALE"
                    2 -> "FEMALE"
                    else -> "UNSPECIFIED"
                }
                if (appSettings.getUserGender() != genderStr) {
                    appSettings.setUserGender(genderStr)
                    appSettings.setPendingSaveFlag(true)
                    appSettings.setCompletedInitialSetup(true)
                    updateCalculatedWeights()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etActualWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRefreshingUi) return
                val weight = s.toString().toDoubleOrNull() ?: 0.0
                appSettings.setUserWeight(weight)
                appSettings.setPendingSaveFlag(true)
                appSettings.setCompletedInitialSetup(true)
                updateCalculatedWeights()
            }
        })

        etActualWeight.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etActualWeight.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurfaceHighlight))
            } else {
                etActualWeight.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
            }
        }

        etActualWeight.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                etActualWeight.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etActualWeight.windowToken, 0)
                true
            } else {
                false
            }
        }

        var isWeightRevealed = false
        txtToggleWeight.setOnClickListener {
            if (isWeightRevealed) {
                etActualWeight.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                txtToggleWeight.text = "SHOW"
                txtToggleWeight.setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                isWeightRevealed = false
            } else {
                etActualWeight.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
                txtToggleWeight.text = "HIDE"
                txtToggleWeight.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                isWeightRevealed = true
            }
            etActualWeight.setSelection(etActualWeight.text?.length ?: 0)
        }

        etHeightFeet.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRefreshingUi) return
                val feet = s.toString().toDoubleOrNull() ?: 0.0
                val inches = etHeightInches.text.toString().toDoubleOrNull() ?: 0.0
                val totalInches = (feet * 12.0) + inches
                appSettings.setUserHeightInches(totalInches)
                appSettings.setPendingSaveFlag(true)
                appSettings.setCompletedInitialSetup(true)
                updateCalculatedWeights()
            }
        })

        etHeightInches.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRefreshingUi) return
                val feet = etHeightFeet.text.toString().toDoubleOrNull() ?: 0.0
                val inches = s.toString().toDoubleOrNull() ?: 0.0
                val totalInches = (feet * 12.0) + inches
                appSettings.setUserHeightInches(totalInches)
                appSettings.setPendingSaveFlag(true)
                appSettings.setCompletedInitialSetup(true)
                updateCalculatedWeights()
            }
        })

        etHeightFeet.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etHeightFeet.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurfaceHighlight))
            } else {
                etHeightFeet.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
            }
        }

        etHeightFeet.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                etHeightInches.requestFocus()
                true
            } else {
                false
            }
        }

        etHeightInches.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etHeightInches.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurfaceHighlight))
            } else {
                etHeightInches.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
            }
        }

        etHeightInches.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                etHeightInches.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etHeightInches.windowToken, 0)
                true
            } else {
                false
            }
        }

        etHeightInches.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                etHeightFeet.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etHeightFeet, InputMethodManager.SHOW_IMPLICIT)
            }
            true
        }

        buildDynamicCheckBoxes()
    }

    private fun setupTab2CustomWatchlist() {
        btnAddRed.setOnClickListener {
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                appSettings.addWatchlistIngredient(text, "RED")
                appSettings.setCompletedInitialSetup(true)
                etCustomIngredient.text = null
                setupCommonQuickAddChips()
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Avoid list", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Please enter an ingredient name.", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddYellow.setOnClickListener {
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                appSettings.addWatchlistIngredient(text, "YELLOW")
                appSettings.setCompletedInitialSetup(true)
                etCustomIngredient.text = null
                setupCommonQuickAddChips()
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Caution list", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Please enter an ingredient name.", Toast.LENGTH_SHORT).show()
            }
        }

        setupCommonQuickAddChips()
        populateActiveWatchlists()
    }

    private fun updateCalculatedWeights() {
        val ibw = appSettings.calculateIdealBodyWeightLbs()
        val ajbw = appSettings.calculateAdjustedBodyWeightLbs()

        if (ibw != null && ibw > 0.0) {
            txtIbwCalculated.text = String.format(Locale.US, "IBW: %.1f lbs", ibw)
            txtIbwCalculated.visibility = View.VISIBLE
        } else {
            txtIbwCalculated.visibility = View.GONE
        }

        if (ajbw != null && ajbw > 0.0 && ajbw != appSettings.getUserWeight()) {
            txtAjbwCalculated.text = String.format(Locale.US, "AjBW: %.1f lbs (Obese Adj.)", ajbw)
            txtAjbwCalculated.visibility = View.VISIBLE
        } else {
            txtAjbwCalculated.visibility = View.GONE
        }
    }

    private fun setupCommonQuickAddChips() {
        chipGroupCommonAdds.removeAllViews()
        val context = requireContext()
        val commonIngredients = resources.getStringArray(R.array.common_watchlist_items)
        val density = resources.displayMetrics.density

        val redCustom = appSettings.getCustomWatchlist("RED").map { it.uppercase().trim() }.toSet()
        val yellowCustom = appSettings.getCustomWatchlist("YELLOW").map { it.uppercase().trim() }.toSet()

        val activeConditions = appSettings.getSelectedConditions()
        val activeTriggers = mutableSetOf<String>()
        for (profileId in activeConditions) {
            val triggers = appSettings.getIngredientsFromAssetFile(profileId)
            for (t in triggers) {
                activeTriggers.add(t.uppercase().trim())
            }
        }

        fun isCommonIngredientActive(ingredient: String, triggers: Set<String>): Boolean {
            val upper = ingredient.uppercase().trim()
            if (triggers.contains(upper)) return true
            return when (upper) {
                "DAIRY" -> triggers.contains("MILK") || triggers.contains("BUTTER") || triggers.contains("CHEESE") || triggers.contains("LACTOSE")
                "GLUTEN" -> triggers.contains("WHEAT") || triggers.contains("BARLEY") || triggers.contains("RYE") || triggers.contains("GLUTEN")
                "PEANUTS" -> triggers.contains("PEANUT") || triggers.contains("PEANUTS")
                "TREE NUTS" -> triggers.contains("ALMOND") || triggers.contains("CASHEW") || triggers.contains("CASHEWS") || triggers.contains("WALNUT")
                else -> false
            }
        }

        for (ingredient in commonIngredients) {
            val ingUpper = ingredient.uppercase().trim()

            val (chipBgRes, chipTextRes) = when {
                redCustom.contains(ingUpper) || isCommonIngredientActive(ingredient, activeTriggers) -> {
                    Pair(R.color.gradeAvoid, R.color.white)
                }
                yellowCustom.contains(ingUpper) -> {
                    Pair(R.color.gradeCaution, R.color.black)
                }
                else -> {
                    Pair(R.color.inputSurface, R.color.textPrimary)
                }
            }

            val chip = Chip(context).apply {
                text = ingredient
                isCheckable = false
                isClickable = true
                setTextColor(ContextCompat.getColor(context, chipTextRes))
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, chipBgRes))
                chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surfaceStroke))
                chipStrokeWidth = density * 1f

                setOnClickListener {
                    showQuickAddSelectionDialog(this, ingredient)
                }
            }
            chipGroupCommonAdds.addView(chip)
        }
    }

    private fun showQuickAddSelectionDialog(chipView: View, ingredient: String) {
        val context = requireContext()

        val popup = PopupMenu(context, chipView).apply {
            menu.add(0, 1, 0, "Add to Avoid (Red)")
            menu.add(0, 2, 1, "Add to Caution (Yellow)")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    appSettings.addWatchlistIngredient(ingredient, "RED")
                    appSettings.setCompletedInitialSetup(true)
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                    Toast.makeText(context, "'$ingredient' added to Avoid list", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    appSettings.addWatchlistIngredient(ingredient, "YELLOW")
                    appSettings.setCompletedInitialSetup(true)
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                    Toast.makeText(context, "'$ingredient' added to Caution list", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun populateActiveWatchlists() {
        layoutActiveReds.removeAllViews()
        layoutActiveYellows.removeAllViews()
        val context = requireContext()

        val redItems = appSettings.getCustomWatchlist("RED")
        val yellowItems = appSettings.getCustomWatchlist("YELLOW")
        val density = resources.displayMetrics.density

        fun createActiveItemView(item: String, tier: String): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * density).toInt())
                }
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
            }

            val label = TextView(context).apply {
                text = item
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val deleteIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.gradeAvoid))
                setOnClickListener {
                    appSettings.removeWatchlistIngredient(item)
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                }
            }

            row.addView(label)
            row.addView(deleteIcon)
            return row
        }

        if (redItems.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "No custom items to avoid."
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveReds.addView(emptyText)
        } else {
            for (item in redItems) {
                layoutActiveReds.addView(createActiveItemView(item, "RED"))
            }
        }

        if (yellowItems.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "No custom caution markers."
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveYellows.addView(emptyText)
        } else {
            for (item in yellowItems) {
                layoutActiveYellows.addView(createActiveItemView(item, "YELLOW"))
            }
        }
    }

    private fun buildDynamicCheckBoxes() {
        val allProfiles = appSettings.getAvailableDietProfiles()
        val selectedIds = appSettings.getSelectedConditions()
        val context = requireContext()

        layoutConditions.removeAllViews()
        layoutAllergens.removeAllViews()

        val checkboxColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(
                ContextCompat.getColor(context, R.color.textUnselected),
                ContextCompat.getColor(context, R.color.accentVivid)
            )
        )

        for (profile in allProfiles) {
            val checkBox = MaterialCheckBox(context).apply {
                text = profile.displayName
                isChecked = selectedIds.contains(profile.id)
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                textSize = 15f
                setPadding(0, 20, 0, 20)
                buttonTintList = checkboxColorStateList

                setOnCheckedChangeListener { _, isChecked ->
                    if (isRefreshingUi) return@setOnCheckedChangeListener
                    appSettings.toggleConditionState(profile.id, isChecked)
                    appSettings.setCompletedInitialSetup(true)

                    val currentSelected = appSettings.getSelectedConditions()
                    if (profile.id == "healthy_baseline" || isChecked || currentSelected.contains("healthy_baseline")) {
                        buildDynamicCheckBoxes()
                    }
                }

                setOnLongClickListener {
                    showAllergenDetails(profile.id, profile.displayName)
                    true
                }
            }

            if (profile.id.startsWith("allergen")) {
                layoutAllergens.addView(checkBox)
            } else {
                layoutConditions.addView(checkBox)
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