package net.meinook.labelscanner

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

    // Top Profile Name Field (Single Point: Edit)
    private lateinit var etProfileName: EditText

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
    private lateinit var btnResetCustomSliders: MaterialButton

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
        val context = requireContext()

        // Bind Top Profile Name Field
        etProfileName = view.findViewById(R.id.etProfileName)

        // Tab Bindings
        tabLayoutHealth = view.findViewById(R.id.tabLayoutHealth)
        layoutTabProfiles = view.findViewById(R.id.layoutTabProfiles)
        layoutTabCustomWatchlist = view.findViewById(R.id.layoutTabCustomWatchlist)

        // Bind Tab 1 Views
        layoutConditions = view.findViewById(R.id.layoutConditionCheckBoxes)
        layoutAllergens = view.findViewById(R.id.layoutAllergenCheckBoxes)
        txtClinicalTargetsHeader = view.findViewById(R.id.txtClinicalTargetsHeader)
        btnEditActiveProfile = view.findViewById(R.id.btnEditActiveProfile)
        btnResetCustomSliders = view.findViewById(R.id.btnResetCustomSliders)
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

        // Setup Direct Profile Renaming (Single Point: Edit)
        etProfileName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyProfileRename()
                etProfileName.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etProfileName.windowToken, 0)
                true
            } else {
                false
            }
        }

        etProfileName.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etProfileName.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurfaceHighlight))
            } else {
                etProfileName.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
                applyProfileRename()
            }
        }

        // Door 3: Adjust Macros Paywall Gate
        btnEditActiveProfile.setOnClickListener {
            if (!BillingManager.isProUser(requireContext())) {
                startActivity(Intent(requireContext(), PaywallActivity::class.java))
                return@setOnClickListener
            }
            CustomProfileManager.showCreateCustomProfileDialog(requireContext(), appSettings) {
                appSettings.setPendingSaveFlag(true)
                refreshProfileData()
            }
        }

        btnResetCustomSliders.setOnClickListener {
            val activeName = appSettings.getActiveProfile()
            MaterialAlertDialogBuilder(requireContext(), R.style.Theme_LabelScanner)
                .setTitle(getString(R.string.reset_macro_limits))
                .setMessage(
                    getString(
                        R.string.reset_all_custom_nutrient_limits_for_back_to_official_clinical_guidelines,
                        activeName
                    ))
                .setPositiveButton(getString(R.string.reset)) { _, _ ->
                    appSettings.resetCustomPlanForActiveMember()
                    appSettings.setPendingSaveFlag(true)
                    refreshProfileData()
                    Toast.makeText(requireContext(),
                        getString(R.string.reset_macro_limits_to_clinical_defaults_for, activeName), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
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

    private fun applyProfileRename() {
        val currentActive = appSettings.getActiveProfile()
        val newName = etProfileName.text?.toString()?.trim() ?: ""

        if (newName.isNotEmpty() && newName != currentActive) {
            appSettings.renameProfile(currentActive, newName)
            appSettings.setPendingSaveFlag(true)
            refreshProfileData()
            Toast.makeText(requireContext(),
                getString(R.string.profile_renamed_to, newName), Toast.LENGTH_SHORT).show()
        } else if (newName.isEmpty()) {
            etProfileName.setText(currentActive)
        }
    }

    private fun refreshProfileData() {
        isRefreshingUi = true

        val activeMember = appSettings.getActiveProfile()

        // 1. Populate Active Profile Name (unless currently typing in it)
        if (!etProfileName.hasFocus()) {
            etProfileName.setText(activeMember)
        }
        txtClinicalTargetsHeader.text = getString(R.string.clinical_targets, activeMember)

        // 2. Reload scoped biometrics for current active profile
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
            getString(R.string.male) -> 1
            getString(R.string.female) -> 2
            else -> 0
        }
        spinnerGender.setSelection(selectionIndex)

        updateCalculatedWeights()

        // 3. Inspect custom slider calibration state (Clean button label without wrap)
        val hasCustomSliders = appSettings.hasCustomPlanForActiveMember()
        btnEditActiveProfile.text = getString(R.string.adjust_macros)
        btnResetCustomSliders.visibility = if (hasCustomSliders) View.VISIBLE else View.GONE

        // 4. Rebuild conditions & watchlists
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

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTab1Profiles() {
        val context = requireContext()

        spinnerGender.setPopupBackgroundDrawable(
            ContextCompat.getColor(context, R.color.inputSurface).toDrawable()
        )

        // Custom Adapter ensuring white text on dark background in both view and dropdown popup
        val genderAdapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_item,
            arrayOf(getString(R.string.unspecified), getString(R.string.male), getString(R.string.female))
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                v.setBackgroundColor(ContextCompat.getColor(context, R.color.inputSurface))
                return v
            }
        }
        spinnerGender.adapter = genderAdapter

        spinnerGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isRefreshingUi) return
                val genderStr = when (position) {
                    1 -> getString(R.string.male)
                    2 -> getString(R.string.female)
                    else -> getString(R.string.unspecified)
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

        // GUARD: Limit weight input to 4 digits (max 9999)
        etActualWeight.filters = arrayOf(android.text.InputFilter.LengthFilter(4))

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

                val weight = etActualWeight.text.toString().toDoubleOrNull() ?: 0.0
                if (weight > 1000.0) {
                    Toast.makeText(context, "Weight cannot exceed 1,000 lbs. Reset to 0.", Toast.LENGTH_SHORT).show()
                    isRefreshingUi = true
                    etActualWeight.setText("")
                    isRefreshingUi = false
                    appSettings.setUserWeight(0.0)
                    appSettings.setPendingSaveFlag(true)
                    appSettings.setCompletedInitialSetup(true)
                    updateCalculatedWeights()
                }
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
                txtToggleWeight.text = getString(R.string.show)
                txtToggleWeight.setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                isWeightRevealed = false
            } else {
                etActualWeight.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
                txtToggleWeight.text = getString(R.string.hide)
                txtToggleWeight.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                isWeightRevealed = true
            }
            etActualWeight.setSelection(etActualWeight.text?.length ?: 0)
        }

        // GUARD: Height filters (Feet max 1 digit (0-9), Inches max 2 digits (0-99))
        etHeightFeet.filters = arrayOf(android.text.InputFilter.LengthFilter(1))
        etHeightInches.filters = arrayOf(android.text.InputFilter.LengthFilter(2))

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
                var feet = etHeightFeet.text.toString().toIntOrNull() ?: 0
                var inches = s.toString().toIntOrNull() ?: 0

                // GUARD: Automatically roll over excessive inches into feet (e.g. 45" -> 3' 9")
                if (inches >= 12) {
                    val rolloverFeet = inches / 12
                    val remainderInches = inches % 12
                    feet += rolloverFeet
                    inches = remainderInches

                    isRefreshingUi = true
                    etHeightFeet.setText(feet.coerceAtMost(8).toString())
                    etHeightInches.setText(inches.toString())
                    isRefreshingUi = false
                }

                val totalInches = (feet * 12.0) + inches
                appSettings.setUserHeightInches(totalInches)
                appSettings.setPendingSaveFlag(true)
                appSettings.setCompletedInitialSetup(true)
                updateCalculatedWeights()
            }
        })

        // GUARD: Validate total height on focus loss (Max 8 feet = 96 inches)
        val heightFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val feet = etHeightFeet.text.toString().toIntOrNull() ?: 0
                val inches = etHeightInches.text.toString().toIntOrNull() ?: 0
                val totalInches = (feet * 12) + inches

                if (totalInches > 96) {
                    Toast.makeText(context, "Height cannot exceed 8 feet. Reset to 5' 8\".", Toast.LENGTH_SHORT).show()
                    isRefreshingUi = true
                    etHeightFeet.setText("5")
                    etHeightInches.setText("8")
                    isRefreshingUi = false
                    appSettings.setUserHeightInches(68.0)
                    appSettings.setPendingSaveFlag(true)
                    appSettings.setCompletedInitialSetup(true)
                    updateCalculatedWeights()
                }
            }
        }

        etHeightFeet.setOnFocusChangeListener(heightFocusListener)
        etHeightInches.setOnFocusChangeListener(heightFocusListener)

        etHeightFeet.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                etHeightInches.requestFocus()
                true
            } else {
                false
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

        buildDynamicCheckBoxes()
    }

    private fun setupTab2CustomWatchlist() {
        // Door 4: Add to Red Watchlist Gate
        btnAddRed.setOnClickListener {
            if (!BillingManager.isProUser(requireContext())) {
                startActivity(Intent(requireContext(), PaywallActivity::class.java))
                return@setOnClickListener
            }
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                val upperText = text.uppercase(Locale.ROOT)
                val redItems = appSettings.getCustomWatchlist(getString(R.string.red_txt)).map { it.uppercase() }
                val yellowItems = appSettings.getCustomWatchlist(getString(R.string.yellow_txt)).map { it.uppercase() }

                if (redItems.contains(upperText) || yellowItems.contains(upperText)) {
                    Toast.makeText(context, "\"$text\" is already in your watchlists.", Toast.LENGTH_SHORT).show()
                } else {
                    appSettings.addWatchlistIngredient(text, getString(R.string.red_txt))
                    appSettings.setCompletedInitialSetup(true)
                    etCustomIngredient.text = null
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                    Toast.makeText(context, getString(R.string.added_to_avoid_list, text), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, getString(R.string.please_enter_an_ingredient_name), Toast.LENGTH_SHORT).show()
            }
        }

        // Door 4: Add to Yellow Watchlist Gate
        btnAddYellow.setOnClickListener {
            if (!BillingManager.isProUser(requireContext())) {
                startActivity(Intent(requireContext(), PaywallActivity::class.java))
                return@setOnClickListener
            }
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                val upperText = text.uppercase(Locale.ROOT)
                val redItems = appSettings.getCustomWatchlist(getString(R.string.red_txt)).map { it.uppercase() }
                val yellowItems = appSettings.getCustomWatchlist(getString(R.string.yellow_txt)).map { it.uppercase() }

                if (redItems.contains(upperText) || yellowItems.contains(upperText)) {
                    Toast.makeText(context, "\"$text\" is already in your watchlists.", Toast.LENGTH_SHORT).show()
                } else {
                    appSettings.addWatchlistIngredient(text, getString(R.string.yellow_txt))
                    appSettings.setCompletedInitialSetup(true)
                    etCustomIngredient.text = null
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                    Toast.makeText(context, getString(R.string.added_to_caution_list, text), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, R.string.please_enter_an_ingredient_name, Toast.LENGTH_SHORT).show()
            }
        }

        setupCommonQuickAddChips()
        populateActiveWatchlists()
    }

    private fun updateCalculatedWeights() {
        val ibw = appSettings.calculateIdealBodyWeightLbs()
        val ajbw = appSettings.calculateAdjustedBodyWeightLbs()

        if (ibw != null && ibw > 0.0) {
            txtIbwCalculated.text = String.format(Locale.US, getString(R.string.ibw_1f_lbs), ibw)
            txtIbwCalculated.visibility = View.VISIBLE
        } else {
            txtIbwCalculated.visibility = View.GONE
        }

        if (ajbw != null && ajbw > 0.0 && ajbw != appSettings.getUserWeight()) {
            txtAjbwCalculated.text = String.format(Locale.US,
                getString(R.string.ajbw_1f_lbs_obese_adj), ajbw)
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

        val redCustom = appSettings.getCustomWatchlist(getString(R.string.red_txt)).map { it.uppercase().trim() }.toSet()
        val yellowCustom = appSettings.getCustomWatchlist(getString(R.string.yellow_txt)).map { it.uppercase().trim() }.toSet()

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
                getString(R.string.dairy) -> triggers.contains(getString(R.string.milk)) || triggers.contains(getString(R.string.butter)) ||
                        triggers.contains(getString(R.string.cheese)) || triggers.contains(getString(R.string.lactose))
                getString(R.string.gluten) -> triggers.contains(getString(R.string.wheat)) || triggers.contains(getString(R.string.barley)) ||
                        triggers.contains(getString(R.string.rye)) || triggers.contains(getString(R.string.gluten))
                getString(R.string.peanuts) -> triggers.contains(getString(R.string.peanut)) || triggers.contains(getString(R.string.peanuts))
                getString(R.string.tree_nuts) -> triggers.contains(getString(R.string.almond)) || triggers.contains(getString(R.string.cashew)) ||
                        triggers.contains(getString(R.string.cashews)) || triggers.contains(getString(R.string.walnut))
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

    // Door 4: Quick-Add Chip Selection Gate
    private fun showQuickAddSelectionDialog(chipView: View, ingredient: String) {
        if (!BillingManager.isProUser(requireContext())) {
            startActivity(Intent(requireContext(), PaywallActivity::class.java))
            return
        }

        val context = requireContext()
        val popup = PopupMenu(context, chipView).apply {
            menu.add(0, 1, 0, getString(R.string.add_to_avoid_red))
            menu.add(0, 2, 1, getString(R.string.add_to_caution_yellow))
        }

        popup.setOnMenuItemClickListener { item ->
            val upperIngredient = ingredient.uppercase(Locale.ROOT)
            val redItems = appSettings.getCustomWatchlist(getString(R.string.red_txt)).map { it.uppercase() }
            val yellowItems = appSettings.getCustomWatchlist(getString(R.string.yellow_txt)).map { it.uppercase() }
            val isDuplicate = redItems.contains(upperIngredient) || yellowItems.contains(upperIngredient)

            when (item.itemId) {
                1 -> {
                    if (isDuplicate) {
                        Toast.makeText(context, "\"$ingredient\" is already in your watchlists.", Toast.LENGTH_SHORT).show()
                    } else {
                        appSettings.addWatchlistIngredient(ingredient, getString(R.string.red_txt))
                        appSettings.setCompletedInitialSetup(true)
                        setupCommonQuickAddChips()
                        populateActiveWatchlists()
                        Toast.makeText(context, getString(R.string.added_to_avoid_list, ingredient), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                2 -> {
                    if (isDuplicate) {
                        Toast.makeText(context, "\"$ingredient\" is already in your watchlists.", Toast.LENGTH_SHORT).show()
                    } else {
                        appSettings.addWatchlistIngredient(ingredient, getString(R.string.yellow_txt))
                        appSettings.setCompletedInitialSetup(true)
                        setupCommonQuickAddChips()
                        populateActiveWatchlists()
                        Toast.makeText(context, getString(R.string.added_to_caution_list, ingredient), Toast.LENGTH_SHORT).show()
                    }
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

        val redItems = appSettings.getCustomWatchlist(getString(R.string.red_txt))
        val yellowItems = appSettings.getCustomWatchlist(getString(R.string.yellow_txt))
        val density = resources.displayMetrics.density

        fun createActiveItemView(item: String): View {
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
                text = context.getString(R.string.no_custom_items_to_avoid)
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveReds.addView(emptyText)
        } else {
            for (item in redItems) {
                layoutActiveReds.addView(createActiveItemView(item))
            }
        }

        if (yellowItems.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = context.getString(R.string.no_custom_caution_markers)
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveYellows.addView(emptyText)
        } else {
            for (item in yellowItems) {
                layoutActiveYellows.addView(createActiveItemView(item))
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
                    if (profile.id == context.getString(R.string.healthy_baseline) || isChecked || currentSelected.contains(
                            context.getString(
                                R.string.healthy_baseline
                            ))) {
                        buildDynamicCheckBoxes()
                    }
                }

                setOnLongClickListener {
                    showAllergenDetails(profile.id, profile.displayName)
                    true
                }
            }

            if (profile.id.startsWith(getString(R.string.allergen))) {
                layoutAllergens.addView(checkBox)
            } else {
                layoutConditions.addView(checkBox)
            }
        }
    }

    private fun showAllergenDetails(profileId: String, title: String) {
        val triggers = appSettings.getIngredientsFromAssetFile(profileId)

        val message = if (triggers.isNotEmpty()) {
            getString(R.string.labelscanner_will_flag_these_ingredients) + triggers.joinToString("\n• ")
        } else {
            getString(R.string.evaluating_based_on_clinical_macro_thresholds)
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_LabelScanner)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
}