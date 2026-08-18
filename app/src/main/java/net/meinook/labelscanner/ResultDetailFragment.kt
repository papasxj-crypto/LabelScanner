package net.meinook.labelscanner

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.transition.MaterialContainerTransform

class ResultDetailFragment : Fragment(R.layout.fragment_result_detail) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.nav_host_fragment
            duration = 400
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.parseColor("#111216"))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Unpack Data from the Arguments Bundle
        val eval = arguments?.getSerializable("EVAL") as? EvaluationResult
        val sub = arguments?.getString("SUB") ?: ""
        val macros = arguments?.getBundle("MACROS") ?: return
        val suggestions = arguments?.getSerializable("SUGGESTIONS") as? ArrayList<ProductAlternative>

        if (eval == null) return

        // 2. Find Views directly from the included layout
        val card = view.findViewById<MaterialCardView>(R.id.cardResult)
        val textTitle = view.findViewById<TextView>(R.id.textGradeTitle)
        val textSub = view.findViewById<TextView>(R.id.textItemSubtitle)
        val layoutVio = view.findViewById<LinearLayout>(R.id.layoutViolations)
        val textVioHeader = view.findViewById<TextView>(R.id.textViolationsHeader)
        val textVioList = view.findViewById<TextView>(R.id.textViolationsList)

        val chipCal = view.findViewById<TextView>(R.id.chipCalories)
        val chipPro = view.findViewById<TextView>(R.id.chipProtein)
        val chipSod = view.findViewById<TextView>(R.id.chipSodium)
        val chipPot = view.findViewById<TextView>(R.id.chipPotassium)
        val chipCarb = view.findViewById<TextView>(R.id.chipCarbs)
        val chipSug = view.findViewById<TextView>(R.id.chipSugar)

        // 3. CRITICAL: Force Visibility (because XML is set to 'gone')
        card?.visibility = View.VISIBLE

        // 4. Apply Evaluation Visuals
        textTitle?.setBackgroundColor(eval.bgColor)
        textTitle?.text = eval.gradeTitle
        textTitle?.setTextColor(eval.textColor)
        textSub?.text = sub

        // 5. Populate Violations (Filtered presentation: 2-3 of the worst category) [1]
        val isRedActive = eval.redViolations.isNotEmpty()
        val displayVioList = if (isRedActive) {
            eval.redViolations.distinct().take(3) // Limit to top 3 RED violations [1]
        } else {
            eval.yellowViolations.distinct().take(3) // Fall back to top 3 YELLOW cautions if RED is empty [1]
        }

        if (displayVioList.isNotEmpty()) {
            layoutVio?.visibility = View.VISIBLE
            textVioList?.text = displayVioList.joinToString("\n• ", prefix = "• ")

            if (isRedActive) {
                textVioHeader?.text = "Profile Violations (Avoid):"
                textVioHeader?.setTextColor(eval.textColor)
                layoutVio?.setBackgroundColor(Color.parseColor("#2A1215")) // Deep red alert tint
            } else {
                textVioHeader?.text = "Profile Cautions (Keep in mind):"
                textVioHeader?.setTextColor(eval.textColor)
                layoutVio?.setBackgroundColor(Color.parseColor("#2A2212")) // Deep yellow caution tint
            }
        } else {
            layoutVio?.visibility = View.GONE
        }

        // 6. Populate Macros using the exact keys from HomeFragment
        chipCal?.text = "Calories: ${macros.getInt("cal")}"
        chipPro?.text = "Protein: ${macros.getFloat("pro").toInt()}g"
        chipSod?.text = "Sodium: ${macros.getInt("sod")}mg"
        chipPot?.text = "Potassium: ${macros.getFloat("pot").toInt()}mg"

        val isKeto = macros.getBoolean("is_keto", false)
        val carbsTotal = macros.getFloat("carb")
        val carbsNet = macros.getFloat("net_carb")

        if (isKeto) {
            chipCarb?.text = "Net Carbs: ${carbsNet.toInt()}g"
        } else {
            chipCarb?.text = "Carbs: ${carbsTotal.toInt()}g"
        }
        chipSug?.text = "Sugar: ${macros.getFloat("sug").toInt()}g"

        // 7. Dynamic Alternatives Binding Block
        val layoutAltSection = view.findViewById<LinearLayout>(R.id.layoutAlternativesSection)

        if (suggestions != null && suggestions.isNotEmpty()) {
            layoutAltSection?.visibility = View.VISIBLE

            val cardsList = listOf(
                view.findViewById<MaterialCardView>(R.id.cardAlt1) to (view.findViewById<TextView>(R.id.textAlt1Name) to view.findViewById<TextView>(R.id.textAlt1Brand)),
                view.findViewById<MaterialCardView>(R.id.cardAlt2) to (view.findViewById<TextView>(R.id.textAlt2Name) to view.findViewById<TextView>(R.id.textAlt2Brand)),
                view.findViewById<MaterialCardView>(R.id.cardAlt3) to (view.findViewById<TextView>(R.id.textAlt3Name) to view.findViewById<TextView>(R.id.textAlt3Brand))
            )

            // Hide cards initially to prevent leftover layouts from drawing
            cardsList.forEach { it.first?.visibility = View.GONE }

            // Display up to 3 parsed safe options
            for (i in 0 until minOf(suggestions.size, cardsList.size)) {
                val suggestion = suggestions[i]
                val (cardView, textViews) = cardsList[i]
                val (nameTextView, brandTextView) = textViews

                cardView?.visibility = View.VISIBLE
                nameTextView?.text = suggestion.name
                brandTextView?.text = if (suggestion.brand.isNotEmpty()) suggestion.brand else "Brand Unlisted"

                val strokeColor = if (suggestion.gradeTitle.startsWith("Yellow")) {
                    Color.parseColor("#FFD54F") // Soft Amber Accent
                } else {
                    Color.parseColor("#81C784") // Soft Green Accent
                }

                cardView?.strokeColor = strokeColor
            }
        } else {
            layoutAltSection?.visibility = View.GONE
        }

        // 8. Back Navigation
        view.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}