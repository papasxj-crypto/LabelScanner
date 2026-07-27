package net.meinook.labelscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ResultDetailSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Reuse your existing card layout
        return inflater.inflate(R.layout.card_result_summary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val eval = arguments?.getSerializable("EVAL") as? EvaluationResult
        val subtitle = arguments?.getString("SUB") ?: ""
        val isKeto = arguments?.getBoolean("KETO") ?: false

        if (eval == null) return

        // Populate Views
        view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardResult).setCardBackgroundColor(eval.bgColor)
        view.findViewById<TextView>(R.id.textGradeTitle).apply {
            text = eval.gradeTitle
            setTextColor(eval.textColor)
        }
        view.findViewById<TextView>(R.id.textItemSubtitle).text = subtitle

        val violations = eval.redViolations + eval.yellowViolations
        val layoutVio = view.findViewById<LinearLayout>(R.id.layoutViolations)
        if (violations.isNotEmpty()) {
            layoutVio.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.textViolationsList).text = violations.joinToString("\n• ", prefix = "• ")
        } else {
            layoutVio.visibility = View.GONE
        }

        // Macros
        view.findViewById<TextView>(R.id.chipCalories).text = "Calories: ${arguments?.getInt("CAL")}"
        view.findViewById<TextView>(R.id.chipProtein).text = "Protein: ${arguments?.getFloat("PRO")?.toInt()}g"
        view.findViewById<TextView>(R.id.chipSodium).text = "Sodium: ${arguments?.getInt("SOD")}mg"
        view.findViewById<TextView>(R.id.chipPotassium).text = "Potassium: ${arguments?.getFloat("POT")?.toInt()}mg"

        val carbs = arguments?.getFloat("CARB") ?: 0f
        val netCarbs = arguments?.getFloat("NET") ?: 0f
        view.findViewById<TextView>(R.id.chipCarbs).text = if (isKeto) "Net Carbs: ${netCarbs.toInt()}g" else "Carbs: ${carbs.toInt()}g"
        view.findViewById<TextView>(R.id.chipSugar).text = "Sugar: ${arguments?.getFloat("SUG")?.toInt()}g"
    }

    companion object {
        fun newInstance(eval: EvaluationResult, sub: String, cal: Int, pro: Float, sod: Int, pot: Float, carb: Float, net: Float, sug: Float, keto: Boolean) = ResultDetailSheet().apply {
            arguments = Bundle().apply {
                putSerializable("EVAL", eval); putString("SUB", sub); putInt("CAL", cal)
                putFloat("PRO", pro); putInt("SOD", sod); putFloat("POT", pot)
                putFloat("CARB", carb); putFloat("NET", net); putFloat("SUG", sug); putBoolean("KETO", keto)
            }
        }
    }
}