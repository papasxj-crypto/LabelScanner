package net.meinook.labelscanner

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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

class HealthFragment : Fragment(R.layout.fragment_health) {

    private lateinit var tabLayoutHealth: TabLayout
    private lateinit var layoutTabProfiles: LinearLayout
    private lateinit var layoutTabCustomWatchlist: LinearLayout

    // Tab 1 Elements
    private lateinit var layoutConditions: LinearLayout
    private lateinit var layoutAllergens: LinearLayout
    private lateinit var etActualWeight: EditText
    private lateinit var etHeightFeet: EditText
    private lateinit var etHeightInches: EditText
    private lateinit var spinnerGender: Spinner
    private lateinit var txtIbwCalculated: TextView
    private lateinit var txtAjbwCalculated: TextView

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

    private lateinit var appSettings: AppSettings

    // Gating flag to prevent programmatic focus change clearing the target fields
    private var isProgrammaticFocusTransition = false

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

        // Custom programmatic adapter to guarantee readable text in dark mode
        val genders = arrayOf("Unspecified", "Male", "Female")
        val genderAdapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            genders
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(Color.parseColor("#F4F5FC"))
                    v.textSize = 14f
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                v.setBackgroundColor(Color.parseColor("#2E221D"))
                if (v is TextView) {
                    v.setTextColor(Color.parseColor("#F4F5FC"))
                    v.textSize = 14f
                }
                return v
            }
        }.apply {
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

        updateCalculatedWeights()

        // Bind standard clear behaviors using safety flags
        setupClearOnTouch(etHeightFeet)
        setupClearOnTouch(etHeightInches)

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

        // Feet text listener (updates metrics and auto-advances to inches)
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

                if (s?.isNotEmpty() == true && etHeightFeet.hasFocus()) {
                    isProgrammaticFocusTransition = true
                    etHeightInches.requestFocus()
                    isProgrammaticFocusTransition = false
                }
            }
        })

        // Inches text listener
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

        // Handle IME "Done" on Inches to clear focus and dismiss keyboard cleanly
        etHeightInches.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                v.clearFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        spinnerGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val genderStr = when (position) {
                    1 -> "MALE"
                    2 -> "FEMALE"
                    else -> "UNSPECIFIED"
                }
                appSettings.setUserGender(genderStr)
                appSettings.setPendingSaveFlag(true)
                updateCalculatedWeights()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Avoid list", Toast.LENGTH_SHORT).show()
                updateAllergenScrollIndicators()
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
                populateActiveWatchlists()
                Toast.makeText(context, "'$text' added to Caution list", Toast.LENGTH_SHORT).show()
                updateAllergenScrollIndicators()
            } else {
                Toast.makeText(context, "Please enter an ingredient name.", Toast.LENGTH_SHORT).show()
            }
        }

        populateActiveWatchlists() // Triggers programmatic chip alignment and display
    }

    // Standard focus/click listeners that clear input safely without triggering accessibility warnings
    private fun setupClearOnTouch(editText: EditText) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isProgrammaticFocusTransition) {
                editText.text.clear()
            }
        }
        editText.setOnClickListener {
            if (!isProgrammaticFocusTransition) {
                editText.text.clear()
            }
        }
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
        val down = ivAllergenScrollDown ?: return
        val child = sa.getChildAt(0)
        if (child != null) {
            val scrollRange = child.height - sa.height
            if (scrollRange > 0) {
                val scrollY = sa.scrollY
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

        // Load active watchlist contents to determine highlight state
        val redItems = appSettings.getCustomWatchlist("RED").map { it.lowercase().trim() }
        val yellowItems = appSettings.getCustomWatchlist("YELLOW").map { it.lowercase().trim() }

        val commonIngredients = listOf(
            "Carrageenan",
            "Guar Gum",
            "Maltodextrin",
            "Potassium Chloride",
            "Potassium Sorbate",
            "Red 40",
            "Sodium Phosphate",
            "Xanthan Gum",
            "Yellow 5"
        )
        val density = resources.displayMetrics.density
        for (ingredient in commonIngredients) {
            val normalized = ingredient.lowercase().trim()
            val isRed = redItems.contains(normalized)
            val isYellow = yellowItems.contains(normalized)

            val chip = Chip(requireContext()).apply {
                text = ingredient
                isCheckable = false
                isClickable = true

                // Color configuration mapping directly to the Earthy Espresso theme scheme
                when {
                    isRed -> {
                        setTextColor(Color.parseColor("#FFD2D2")) // Soft red-pink
                        setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#3D2423"))) // Deep Cocoa-Red
                        setChipStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B"))) // Red Border
                    }
                    isYellow -> {
                        setTextColor(Color.parseColor("#FFECA1")) // Soft gold-yellow
                        setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#3D351E"))) // Deep Cocoa-Yellow
                        setChipStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD54F"))) // Yellow Border
                    }
                    else -> {
                        setTextColor(Color.parseColor("#F4F5FC")) // Standard off-white
                        setChipBackgroundColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#2E221D"))) // Neutral warm dark brown
                        setChipStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#3A2D28"))) // Dark Brass Border
                    }
                }
                chipStrokeWidth = density * 1f

                setOnClickListener {
                    showQuickAddSelectionDialog(ingredient, isRed, isYellow)
                }
            }
            chipGroupCommonAdds.addView(chip)
        }
    }

    // Displays a stateful, compact dialog based on whether the item is already watchlisted
    private fun showQuickAddSelectionDialog(ingredient: String, isAlreadyRed: Boolean, isAlreadyYellow: Boolean) {
        val density = resources.displayMetrics.density

        // Create programmatic Earthy Espresso CardView
        val cardView = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            setCardBackgroundColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#251B18"))) // Warm Charcoal
            strokeColor = Color.parseColor("#3A2D28") // Dark Brass
            strokeWidth = (1 * density).toInt()
            radius = 12 * density
            layoutParams = ViewGroup.LayoutParams(
                (260 * density).toInt(), // Compact size
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Establish the header text reflecting the active status of the ingredient
        val headerText = when {
            isAlreadyRed -> "CARRAGEENAN (AVOIDING)"
            isAlreadyYellow -> "CARRAGEENAN (CAUTIONED)"
            else -> "ADD ${ingredient.uppercase()}"
        }

        val titleView = TextView(requireContext()).apply {
            text = headerText.replace("CARRAGEENAN", ingredient.uppercase())
            setTextColor(Color.parseColor("#A89890")) // Sand-Gray
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding(0, 0, 0, (14 * density).toInt())
            gravity = Gravity.CENTER
        }
        contentLayout.addView(titleView)

        val buttonsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var dialog: android.app.Dialog? = null

        // Render controls corresponding to active states
        when {
            isAlreadyRed -> {
                // Already in Avoid List: [ Remove ] [ Caution ]
                val btnRemove = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Remove"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#423936")) // Warm brown-gray
                    setTextColor(Color.parseColor("#F4F5FC"))
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnClickListener {
                        appSettings.removeWatchlistIngredient(ingredient)
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' removed from watchlist.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }

                val btnCaution = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Caution"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD54F")) // Yellow
                    setTextColor(Color.parseColor("#14161F"))
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appSettings.removeWatchlistIngredient(ingredient)
                        appSettings.addWatchlistIngredient(ingredient, "YELLOW")
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' shifted to Caution.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }
                buttonsContainer.addView(btnRemove)
                buttonsContainer.addView(btnCaution)
            }
            isAlreadyYellow -> {
                // Already in Caution List: [ Remove ] [ Avoid ]
                val btnRemove = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Remove"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#423936")) // Warm brown-gray
                    setTextColor(Color.parseColor("#F4F5FC"))
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnClickListener {
                        appSettings.removeWatchlistIngredient(ingredient)
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' removed from watchlist.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }

                val btnAvoid = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Avoid"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B")) // Red
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appSettings.removeWatchlistIngredient(ingredient)
                        appSettings.addWatchlistIngredient(ingredient, "RED")
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' shifted to Avoid.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }
                buttonsContainer.addView(btnRemove)
                buttonsContainer.addView(btnAvoid)
            }
            else -> {
                // Neutral State: [ Avoid ] [ Caution ]
                val btnAvoid = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Avoid"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B")) // Red
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnClickListener {
                        appSettings.addWatchlistIngredient(ingredient, "RED")
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' added to Avoid list.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }

                val btnCaution = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Caution"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD54F")) // Yellow
                    setTextColor(Color.parseColor("#14161F"))
                    textSize = 12f
                    cornerRadius = (8 * density).toInt()
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appSettings.addWatchlistIngredient(ingredient, "YELLOW")
                        populateActiveWatchlists()
                        Toast.makeText(context, "'$ingredient' added to Caution list.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                    }
                }
                buttonsContainer.addView(btnAvoid)
                buttonsContainer.addView(btnCaution)
            }
        }

        contentLayout.addView(buttonsContainer)
        cardView.addView(contentLayout)

        dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_LabelScanner)
            .setView(cardView)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
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
                // Aligned to Warm Dark Brown to blend with the inputs
                setBackgroundColor(Color.parseColor("#2E221D"))
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
                setTextColor(Color.parseColor("#A89890")) // Aligned to Sand-Gray
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
                setTextColor(Color.parseColor("#A89890")) // Aligned to Sand-Gray
                textSize = 12f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            layoutActiveYellows.addView(emptyText)
        } else {
            for (item in yellowItems) {
                layoutActiveYellows.addView(createActiveItemView(item, "YELLOW"))
            }
        }

        // Synchronize and re-render the highlighting state of the quick-add chips
        setupCommonQuickAddChips()
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
                    if (profile.id == "healthy_baseline" || isChecked) {
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