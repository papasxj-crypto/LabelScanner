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

        // Iris expansion configuration
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.nav_host_fragment
            duration = 400
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(Color.parseColor("#121212"))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Unpack Data from the Arguments Bundle
        val eval = arguments?.getSerializable("EVAL") as? EvaluationResult
        val sub = arguments?.getString("SUB") ?: ""
        val macros = arguments?.getBundle("MACROS") ?: return

        if (eval == null) return

        // 2. Find Views directly from the included layout
        val card = view.findViewById<MaterialCardView>(R.id.cardResult)
        val textTitle = view.findViewById<TextView>(R.id.textGradeTitle)
        val textSub = view.findViewById<TextView>(R.id.textItemSubtitle)
        val layoutVio = view.findViewById<LinearLayout>(R.id.layoutViolations)
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
        card?.setCardBackgroundColor(eval.bgColor)
        textTitle?.text = eval.gradeTitle
        textTitle?.setTextColor(eval.textColor)
        textSub?.text = sub

        // 5. Populate Violations
        val allVio = eval.redViolations + eval.yellowViolations
        if (allVio.isNotEmpty()) {
            layoutVio?.visibility = View.VISIBLE
            textVioList?.text = allVio.joinToString("\n• ", prefix = "• ")
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

        // 7. Back Navigation
        view.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}