package net.meinook.labelscanner

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class ResultDetailFragment : Fragment(R.layout.fragment_result_detail) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Unpack Arguments safely
        @Suppress("DEPRECATION")
        val evaluation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable("EVAL", EvaluationResult::class.java)
        } else {
            arguments?.getSerializable("EVAL") as? EvaluationResult
        }

        val subtitle = arguments?.getString("SUB") ?: "Product Scan"
        val macros = arguments?.getBundle("MACROS")

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val suggestions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable("SUGGESTIONS", ArrayList::class.java) as? ArrayList<ProductAlternative>
        } else {
            arguments?.getSerializable("SUGGESTIONS") as? ArrayList<ProductAlternative>
        }

        // 2. Navigation Actions
        val btnBackTop = view.findViewById<ImageView>(R.id.btnBackTop)
        val btnBack = view.findViewById<MaterialButton>(R.id.btnBack)

        btnBackTop.setOnClickListener {
            findNavController().navigateUp()
        }

        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // 3. Bind card_result_summary views
        val cardResult = view.findViewById<MaterialCardView>(R.id.cardResult)
        val textGradeTitle = view.findViewById<TextView>(R.id.textGradeTitle)
        val textItemSubtitle = view.findViewById<TextView>(R.id.textItemSubtitle)
        val layoutViolations = view.findViewById<LinearLayout>(R.id.layoutViolations)
        val textViolationsHeader = view.findViewById<TextView>(R.id.textViolationsHeader)
        val textViolationsList = view.findViewById<TextView>(R.id.textViolationsList)

        val chipCalories = view.findViewById<TextView>(R.id.chipCalories)
        val chipProtein = view.findViewById<TextView>(R.id.chipProtein)
        val chipSodium = view.findViewById<TextView>(R.id.chipSodium)
        val chipPotassium = view.findViewById<TextView>(R.id.chipPotassium)
        val chipCarbs = view.findViewById<TextView>(R.id.chipCarbs)
        val chipSugar = view.findViewById<TextView>(R.id.chipSugar)

        // 4. Populate Evaluation Header & Guidance
        if (evaluation != null) {
            cardResult.visibility = View.VISIBLE
            cardResult.setCardBackgroundColor(evaluation.bgColor)
            cardResult.strokeColor = evaluation.textColor

            textGradeTitle.text = evaluation.gradeTitle
            textGradeTitle.setTextColor(evaluation.textColor)
            textItemSubtitle.text = subtitle

            val allViolations = mutableListOf<String>()
            allViolations.addAll(evaluation.redViolations)
            allViolations.addAll(evaluation.yellowViolations)

            if (allViolations.isNotEmpty()) {
                layoutViolations.visibility = View.VISIBLE
                if (evaluation.redViolations.isNotEmpty()) {
                    textViolationsHeader.text = "Avoidance Criteria Triggered:"
                    textViolationsHeader.setTextColor(Color.parseColor("#E57373"))
                } else {
                    textViolationsHeader.text = "Cautionary Notes:"
                    textViolationsHeader.setTextColor(Color.parseColor("#FFD54F"))
                }
                textViolationsList.text = allViolations.joinToString("\n") { "• $it" }
            } else {
                layoutViolations.visibility = View.GONE
            }
        } else {
            cardResult.visibility = View.GONE
        }

        // 5. Populate Macro Breakdown Chips
        if (macros != null) {
            val cal = macros.getInt("cal", 0)
            val pro = macros.getFloat("pro", 0f)
            val sod = macros.getInt("sod", 0)
            val pot = macros.getFloat("pot", 0f)
            val carb = macros.getFloat("carb", 0f)
            val netCarb = macros.getFloat("net_carb", 0f)
            val sug = macros.getFloat("sug", 0f)
            val isKeto = macros.getBoolean("is_keto", false)

            chipCalories.text = "Calories: $cal kcal"
            chipProtein.text = String.format(Locale.US, "Protein: %.1f g", pro)
            chipSodium.text = "Sodium: $sod mg"
            chipPotassium.text = String.format(Locale.US, "Potassium: %.0f mg", pot)
            chipCarbs.text = if (isKeto) {
                String.format(Locale.US, "Net Carbs: %.1f g", netCarb)
            } else {
                String.format(Locale.US, "Carbs: %.1f g", carb)
            }
            chipSugar.text = String.format(Locale.US, "Sugar: %.1f g", sug)
        }

        // 6. Populate Safer Alternatives (if available)
        val layoutAlternatives = view.findViewById<LinearLayout>(R.id.layoutAlternativesSection)
        val cardAlt1 = view.findViewById<MaterialCardView>(R.id.cardAlt1)
        val textAlt1Name = view.findViewById<TextView>(R.id.textAlt1Name)
        val textAlt1Brand = view.findViewById<TextView>(R.id.textAlt1Brand)

        val cardAlt2 = view.findViewById<MaterialCardView>(R.id.cardAlt2)
        val textAlt2Name = view.findViewById<TextView>(R.id.textAlt2Name)
        val textAlt2Brand = view.findViewById<TextView>(R.id.textAlt2Brand)

        val cardAlt3 = view.findViewById<MaterialCardView>(R.id.cardAlt3)
        val textAlt3Name = view.findViewById<TextView>(R.id.textAlt3Name)
        val textAlt3Brand = view.findViewById<TextView>(R.id.textAlt3Brand)

        if (!suggestions.isNullOrEmpty()) {
            layoutAlternatives.visibility = View.VISIBLE

            fun bindAlternative(card: MaterialCardView, nameView: TextView, brandView: TextView, alt: ProductAlternative?) {
                if (alt != null) {
                    card.visibility = View.VISIBLE
                    nameView.text = alt.name
                    brandView.text = if (alt.brand.isNotBlank()) alt.brand else alt.gradeTitle
                    card.setOnClickListener {
                        if (alt.code.isNotBlank()) {
                            val bundle = Bundle().apply {
                                putString("AUTO_LOOKUP_BARCODE", alt.code)
                            }
                            findNavController().navigate(R.id.navigation_home, bundle)
                        }
                    }
                } else {
                    card.visibility = View.GONE
                }
            }

            bindAlternative(cardAlt1, textAlt1Name, textAlt1Brand, suggestions.getOrNull(0))
            bindAlternative(cardAlt2, textAlt2Name, textAlt2Brand, suggestions.getOrNull(1))
            bindAlternative(cardAlt3, textAlt3Name, textAlt3Brand, suggestions.getOrNull(2))
        } else {
            layoutAlternatives.visibility = View.GONE
        }
    }
}