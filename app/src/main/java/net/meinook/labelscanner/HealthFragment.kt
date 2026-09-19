package net.meinook.labelscanner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.Locale

// Aligned constructor cleanly maps to your fragment_health layout resource ID
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

    private var scrollCondition: NestedScrollView? = null
    private var ivScrollUp: ImageView? = null
    private var ivScrollDown: ImageView? = null

    private var scrollAllergen: NestedScrollView? = null
    private var ivAllergenScrollUp: ImageView? = null
    private var ivAllergenScrollDown: ImageView? = null

    // Tab 2 Elements
    private lateinit var etCustomIngredient: EditText
    private lateinit var btnAddRed: MaterialButton
    private lateinit var btnAddYellow: MaterialButton
    private lateinit var chipGroupCommonAdds: ChipGroup
    private lateinit var layoutActiveReds: LinearLayout
    private lateinit var layoutActiveYellows: LinearLayout

    private var scrollCommon: NestedScrollView? = null
    private var ivCommonScrollUp: ImageView? = null
    private var ivCommonScrollDown: ImageView? = null

    private var scrollRed: NestedScrollView? = null
    private var ivRedScrollUp: ImageView? = null
    private var ivRedScrollDown: ImageView? = null

    private var scrollYellow: NestedScrollView? = null
    private var ivYellowScrollUp: ImageView? = null
    private var ivYellowScrollDown: ImageView? = null

    private lateinit var appSettings: AppSettings

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

        // Bind Tab 2 Scroll Overlays
        scrollCommon = view.findViewById(R.id.scrollCommonAdds)
        ivCommonScrollUp = view.findViewById(R.id.ivCommonScrollUpIndicator)
        ivCommonScrollDown = view.findViewById(R.id.ivCommonScrollDownIndicator)

        scrollRed = view.findViewById(R.id.scrollActiveReds)
        ivRedScrollUp = view.findViewById(R.id.ivRedScrollUpIndicator)
        ivRedScrollDown = view.findViewById(R.id.ivRedScrollDownIndicator)

        scrollYellow = view.findViewById(R.id.scrollActiveYellows)
        ivYellowScrollUp = view.findViewById(R.id.ivYellowScrollUpIndicator)
        ivYellowScrollDown = view.findViewById(R.id.ivYellowScrollDownIndicator)

        setupTabLayout()
        setupTab1Profiles()
        setupTab2CustomWatchlist()
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
        spinnerGender.setPopupBackgroundDrawable(Color.parseColor("#2E221D").toDrawable())

        val genderAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            arrayOf("Unspecified", "Male", "Female")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerGender.adapter = genderAdapter

        // Load metrics states
        val savedWeight = appSettings.getUserWeight()
        if (savedWeight > 0.0) {
            etActualWeight.setText(savedWeight.toString())
        }

        val savedTotalInches = appSettings.getUserHeightInches()
        if (savedTotalInches > 0.0) {
            val feet = (savedTotalInches / 12).toInt()
            val inches = (savedTotalInches % 12).toInt()
            etHeightFeet.setText(feet.toString())
            etHeightInches.setText(inches.toString())
        }

        val savedGender = appSettings.getUserGender()
        val selectionIndex = when (savedGender) {
            "MALE" -> 1
            "FEMALE" -> 2
            else -> 0
        }
        spinnerGender.setSelection(selectionIndex)

        // Dynamic change listener securely captures and saves the gender selection
        spinnerGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val genderStr = when (position) {
                    1 -> "MALE"
                    2 -> "FEMALE"
                    else -> "UNSPECIFIED"
                }
                if (appSettings.getUserGender() != genderStr) {
                    appSettings.setUserGender(genderStr)
                    appSettings.setPendingSaveFlag(true)
                    updateCalculatedWeights()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateCalculatedWeights()

        // Setup real-time weight listener
        etActualWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val weight = s.toString().toDoubleOrNull() ?: 0.0
                appSettings.setUserWeight(weight)
                appSettings.setPendingSaveFlag(true)
                updateCalculatedWeights()
            }
        })

        // Setup Weight Focus & Highlight (Now completely aligned to matches Height aesthetics)
        etActualWeight.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etActualWeight.setBackgroundColor(Color.parseColor("#4E3B34")) // Focus Highlight
            } else {
                etActualWeight.setBackgroundColor(Color.parseColor("#2E221D")) // Standard background
            }
        }

        // Weight keyboard "Done" listener
        etActualWeight.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                etActualWeight.clearFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etActualWeight.windowToken, 0)
                true
            } else {
                false
            }
        }

        // Programmatic secure show/hide toggle click logic
        var isWeightRevealed = false
        txtToggleWeight.setOnClickListener {
            if (isWeightRevealed) {
                // Securely mask weight digits to bullet dots (••••)
                etActualWeight.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                txtToggleWeight.text = "SHOW"
                txtToggleWeight.setTextColor(Color.parseColor("#99A1B3"))
                isWeightRevealed = false
            } else {
                // Reveal the actual raw digits (e.g. 154)
                etActualWeight.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
                txtToggleWeight.text = "HIDE"
                txtToggleWeight.setTextColor(Color.parseColor("#F4F5FC"))
                isWeightRevealed = true
            }
            // Keep insertion cursor locked at the end of the text
            etActualWeight.setSelection(etActualWeight.text?.length ?: 0)
        }

        // Separate, isolated Height text change listeners prevent recursive/overlapping overrides
        etHeightFeet.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val feet = s.toString().toDoubleOrNull() ?: 0.0
                val inches = etHeightInches.text.toString().toDoubleOrNull() ?: 0.0
                val totalInches = (feet * 12.0) + inches
                appSettings.setUserHeightInches(totalInches)
                appSettings.setPendingSaveFlag(true)
                updateCalculatedWeights()
            }
        })

        etHeightInches.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val feet = etHeightFeet.text.toString().toDoubleOrNull() ?: 0.0
                val inches = s.toString().toDoubleOrNull() ?: 0.0
                val totalInches = (feet * 12.0) + inches
                appSettings.setUserHeightInches(totalInches)
                appSettings.setPendingSaveFlag(true)
                updateCalculatedWeights()
            }
        })

        // Feet Focus & Highlight
        etHeightFeet.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etHeightFeet.setBackgroundColor(Color.parseColor("#4E3B34")) // Focus Highlight
            } else {
                etHeightFeet.setBackgroundColor(Color.parseColor("#2E221D")) // Standard background
            }
        }

        // Feet keyboard "Next" listener
        etHeightFeet.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                etHeightInches.requestFocus()
                true
            } else {
                false
            }
        }

        // Inches Focus & Highlight
        etHeightInches.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etHeightInches.setBackgroundColor(Color.parseColor("#4E3B34")) // Focus Highlight
            } else {
                etHeightInches.setBackgroundColor(Color.parseColor("#2E221D")) // Standard background
            }
        }

        // Inches keyboard "Done" listener
        etHeightInches.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                etHeightInches.clearFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etHeightInches.windowToken, 0)
                true
            } else {
                false
            }
        }

        // Inches touch interceptor redirects all touches to highlight/focus "Feet" instead [1]
        etHeightInches.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                etHeightFeet.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etHeightFeet, InputMethodManager.SHOW_IMPLICIT)
            }
            true
        }

        // Setup Scroll Bindings for Targets (Card 2)
        scrollCondition = view?.findViewById(R.id.scrollConditionCheckBoxes)
        ivScrollUp = view?.findViewById(R.id.ivScrollUpIndicator)
        ivScrollDown = view?.findViewById(R.id.ivScrollDownIndicator)

        scrollCondition?.post { updateScrollIndicators() }
        scrollCondition?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateScrollIndicators()
        })

        // Setup Scroll Bindings for Allergens (Card 3)
        scrollAllergen = view?.findViewById(R.id.scrollAllergenCheckBoxes)
        ivAllergenScrollUp = view?.findViewById(R.id.ivAllergenScrollUpIndicator)
        ivAllergenScrollDown = view?.findViewById(R.id.ivAllergenScrollDownIndicator)

        scrollAllergen?.post { updateAllergenScrollIndicators() }
        scrollAllergen?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateAllergenScrollIndicators()
        })

        buildDynamicCheckBoxes()
    }

    private fun setupTab2CustomWatchlist() {
        // Red (Avoid) Direct Add Button
        btnAddRed.setOnClickListener {
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                appSettings.addWatchlistIngredient(text, "RED")
                etCustomIngredient.text = null
                setupCommonQuickAddChips()
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Avoid list", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Please enter an ingredient name.", Toast.LENGTH_SHORT).show()
            }
        }

        // Yellow (Caution) Direct Add Button
        btnAddYellow.setOnClickListener {
            val text = etCustomIngredient.text.toString().trim()
            if (text.isNotEmpty()) {
                appSettings.addWatchlistIngredient(text, "YELLOW")
                etCustomIngredient.text = null
                setupCommonQuickAddChips()
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Caution list", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Please enter an ingredient name.", Toast.LENGTH_SHORT).show()
            }
        }

        // Bind Tab 2 Scroll Listeners
        scrollCommon?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateCommonScrollIndicators()
        })
        scrollRed?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateRedScrollIndicators()
        })
        scrollYellow?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            updateYellowScrollIndicators()
        })

        setupCommonQuickAddChips()
        populateActiveWatchlists()
    }

    private fun updateScrollIndicators() {
        val sc = scrollCondition ?: return
        val up = ivScrollUp ?: return
        val down = ivScrollDown ?: return
        val child = sc.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sc.height
            if (scrollRange > 0) {
                val scrollY = sc.scrollY
                up.visibility = if (scrollY > 15) View.VISIBLE else View.GONE
                down.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
            } else {
                up.visibility = View.GONE
                down.visibility = View.GONE
            }
        }
    }

    private fun updateAllergenScrollIndicators() {
        val sa = scrollAllergen ?: return
        val up = ivAllergenScrollUp ?: return
        val child = sa.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sa.height
            if (scrollRange > 0) {
                val scrollY = sa.scrollY
                up.visibility = if (scrollY > 15) View.VISIBLE else View.GONE
                sa.findViewById<ImageView>(R.id.ivAllergenScrollDownIndicator)?.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
            } else {
                up.visibility = View.GONE
                sa.findViewById<ImageView>(R.id.ivAllergenScrollDownIndicator)?.visibility = View.GONE
            }
        }
    }

    private fun updateCommonScrollIndicators() {
        val sc = scrollCommon ?: return
        val up = ivCommonScrollUp ?: return
        val down = ivCommonScrollDown ?: return
        val child = sc.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sc.height
            if (scrollRange > 0) {
                val scrollY = sc.scrollY
                up.visibility = if (scrollY > 15) View.VISIBLE else View.GONE
                down.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
            } else {
                up.visibility = View.GONE
                down.visibility = View.GONE
            }
        }
    }

    private fun updateRedScrollIndicators() {
        val sc = scrollRed ?: return
        val up = ivRedScrollUp ?: return
        val down = ivRedScrollDown ?: return
        val child = sc.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sc.height
            if (scrollRange > 0) {
                val scrollY = sc.scrollY
                up.visibility = if (scrollY > 15) View.VISIBLE else View.GONE
                down.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
            } else {
                up.visibility = View.GONE
                down.visibility = View.GONE
            }
        }
    }

    private fun updateYellowScrollIndicators() {
        val sc = scrollYellow ?: return
        val up = ivYellowScrollUp ?: return
        val down = ivYellowScrollDown ?: return
        val child = sc.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sc.height
            if (scrollRange > 0) {
                val scrollY = sc.scrollY
                up.visibility = if (scrollY > 15) View.VISIBLE else View.GONE
                down.visibility = if (scrollY < scrollRange - 15) View.VISIBLE else View.GONE
            } else {
                up.visibility = View.GONE
                down.visibility = View.GONE
            }
        }
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

            val (chipBgColor, chipTextColor) = when {
                redCustom.contains(ingUpper) || isCommonIngredientActive(ingredient, activeTriggers) -> {
                    Pair("#FF6B6B", "#FFFFFF")
                }
                yellowCustom.contains(ingUpper) -> {
                    Pair("#FFD54F", "#14161F")
                }
                else -> {
                    Pair("#222630", "#F4F5FC")
                }
            }

            val chip = Chip(requireContext()).apply {
                text = ingredient
                isCheckable = false
                isClickable = true
                setTextColor(Color.parseColor(chipTextColor))
                setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Color.parseColor(chipBgColor)))
                setChipStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#3A2D28")))
                chipStrokeWidth = density * 1f

                setOnClickListener {
                    showQuickAddSelectionDialog(this, ingredient)
                }
            }
            chipGroupCommonAdds.addView(chip)
        }

        scrollCommon?.post { updateCommonScrollIndicators() }
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
                    setupCommonQuickAddChips()
                    populateActiveWatchlists()
                    Toast.makeText(context, "'$ingredient' added to Avoid list", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    appSettings.addWatchlistIngredient(ingredient, "YELLOW")
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

        val redItems = appSettings.getCustomWatchlist("RED")
        val yellowItems = appSettings.getCustomWatchlist("YELLOW")

        val density = resources.displayMetrics.density

        fun createActiveItemView(item: String, tier: String): View {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * density).toInt())
                }
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                setBackgroundColor(Color.parseColor("#222630"))
            }

            val label = TextView(requireContext()).apply {
                text = item
                setTextColor(Color.parseColor("#F4F5FC"))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val deleteIcon = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
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
                setTextColor(Color.parseColor("#99A1B3"))
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
                setTextColor(Color.parseColor("#99A1B3"))
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveYellows.addView(emptyText)
        } else {
            for (item in yellowItems) {
                layoutActiveYellows.addView(createActiveItemView(item, "YELLOW"))
            }
        }

        scrollRed?.post { updateRedScrollIndicators() }
        scrollYellow?.post { updateYellowScrollIndicators() }
    }

    private fun buildDynamicCheckBoxes() {
        val allProfiles = appSettings.getAvailableDietProfiles()
        val selectedIds = appSettings.getSelectedConditions()

        layoutConditions.removeAllViews()
        layoutAllergens.removeAllViews()

        val checkboxColorStateList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(
                Color.parseColor("#A89890"),
                Color.parseColor("#4CAF50")
            )
        )

        for (profile in allProfiles) {
            val checkBox = MaterialCheckBox(requireContext()).apply {
                text = profile.displayName
                isChecked = selectedIds.contains(profile.id)
                setTextColor(Color.parseColor("#F4F5FC"))
                textSize = 15f
                setPadding(0, 24, 0, 24)
                buttonTintList = checkboxColorStateList

                setOnCheckedChangeListener { _, isChecked ->
                    appSettings.toggleConditionState(profile.id, isChecked)

                    // Rebuild the UI if:
                    // 1. We toggled the baseline profile
                    // 2. We checked a new condition (handles conflict unchecking)
                    // 3. The final active set fell back to healthy_baseline
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

        scrollCondition?.post { updateScrollIndicators() }
        scrollAllergen?.post { updateAllergenScrollIndicators() }
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