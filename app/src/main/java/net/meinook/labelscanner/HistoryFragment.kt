package net.meinook.labelscanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: String,
    val timestamp: Long,
    val title: String,
    val subtitle: String,
    val originalGrade: String,
    val adjustedGrade: String,
    val activeProfiles: String,
    val sourceUrl: String = "",       // Saved scraped URL
    val originalInput: String = "",    // Saved full original ingredients text
    val adjustedOutput: String = "",   // Saved full adjusted outcome text
    val itemType: String = "RECIPE",   // Discriminator: RECIPE or SCAN

    // Reconstruction properties for instant history retrieval
    val originalViolations: String = "",
    val originalStats: String = "",
    val adjustedViolations: String = "",
    val adjustedStats: String = "",
    val originalBgColor: Int = 0,
    val originalTextColor: Int = 0,
    val adjustedBgColor: Int = 0,
    val adjustedTextColor: Int = 0
) : java.io.Serializable

class HistoryFragment : Fragment() {

    private lateinit var layoutHistoryContainer: LinearLayout
    private lateinit var textEmptyHistory: TextView
    private lateinit var txtSwipeToDeleteHint: TextView

    companion object {
        // Universal static save hook to log entries from any screen
        fun saveHistoryItem(context: Context, item: HistoryItem) {
            try {
                val file = File(context.filesDir, "user_history.json")
                val list = mutableListOf<HistoryItem>()
                var overwritten = false

                if (file.exists()) {
                    val jsonStr = file.readText()
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val currentId = obj.getString("id")

                        if (currentId == item.id) {
                            list.add(item)
                            overwritten = true
                        } else {
                            list.add(
                                HistoryItem(
                                    id = currentId,
                                    timestamp = obj.getLong("timestamp"),
                                    title = obj.getString("title"),
                                    subtitle = obj.getString("subtitle"),
                                    originalGrade = obj.getString("original_grade"),
                                    adjustedGrade = obj.getString("adjusted_grade"),
                                    activeProfiles = obj.getString("active_profiles"),
                                    sourceUrl = obj.optString("source_url", ""),
                                    originalInput = obj.optString("original_input", ""),
                                    adjustedOutput = obj.optString("adjusted_output", ""),
                                    itemType = obj.optString("item_type", "RECIPE"),
                                    originalViolations = obj.optString("original_violations", ""),
                                    originalStats = obj.optString("original_stats", ""),
                                    adjustedViolations = obj.optString("adjusted_violations", ""),
                                    adjustedStats = obj.optString("adjusted_stats", ""),
                                    originalBgColor = obj.optInt("original_bg_color", 0),
                                    originalTextColor = obj.optInt("original_text_color", 0),
                                    adjustedBgColor = obj.optInt("adjusted_bg_color", 0),
                                    adjustedTextColor = obj.optInt("adjusted_text_color", 0)
                                )
                            )
                        }
                    }
                }

                if (!overwritten) {
                    list.add(item)
                }

                val outArr = JSONArray()
                for (x in list) {
                    outArr.put(JSONObject().apply {
                        put("id", x.id)
                        put("timestamp", x.timestamp)
                        put("title", x.title)
                        put("subtitle", x.subtitle)
                        put("original_grade", x.originalGrade)
                        put("adjusted_grade", x.adjustedGrade)
                        put("active_profiles", x.activeProfiles)
                        put("source_url", x.sourceUrl)
                        put("original_input", x.originalInput)
                        put("adjusted_output", x.adjustedOutput)
                        put("item_type", x.itemType)
                        put("original_violations", x.originalViolations)
                        put("original_stats", x.originalStats)
                        put("adjusted_violations", x.adjustedViolations)
                        put("adjusted_stats", x.adjustedStats)
                        put("original_bg_color", x.originalBgColor)
                        put("original_text_color", x.originalTextColor)
                        put("adjusted_bg_color", x.adjustedBgColor)
                        put("adjusted_text_color", x.adjustedTextColor)
                    })
                }
                file.writeText(outArr.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        layoutHistoryContainer = view.findViewById(R.id.layoutHistoryContainer)
        textEmptyHistory = view.findViewById(R.id.textEmptyHistory)
        txtSwipeToDeleteHint = view.findViewById(R.id.txtSwipeToDeleteHint)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populateHistoryLog()
    }

    private fun populateHistoryLog() {
        val context = context ?: return
        layoutHistoryContainer.removeAllViews()

        val items = loadHistoryFromFile()
        if (items.isEmpty()) {
            textEmptyHistory.visibility = View.VISIBLE
            txtSwipeToDeleteHint.visibility = View.GONE // Hide hint if no entries are present [2]
        } else {
            textEmptyHistory.visibility = View.GONE
            txtSwipeToDeleteHint.visibility = View.VISIBLE // Show hint persistently at bottom [2]
            for (item in items) {
                val cardView = createHistoryCard(context, item)
                layoutHistoryContainer.addView(cardView)
            }
        }
    }

    private fun deleteHistoryItem(item: HistoryItem) {
        val file = File(requireContext().filesDir, "user_history.json")
        if (!file.exists()) return

        try {
            val jsonStr = file.readText()
            val arr = JSONArray(jsonStr)
            val outArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") != item.id) {
                    outArr.put(obj)
                }
            }
            file.writeText(outArr.toString())
            Toast.makeText(context, "Entry removed from journal", Toast.LENGTH_SHORT).show()
            populateHistoryLog()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveHistoryListToFile(list: List<HistoryItem>) {
        try {
            val file = File(requireContext().filesDir, "user_history.json")
            val outArr = JSONArray()
            for (x in list) {
                outArr.put(JSONObject().apply {
                    put("id", x.id)
                    put("timestamp", x.timestamp)
                    put("title", x.title)
                    put("subtitle", x.subtitle)
                    put("original_grade", x.originalGrade)
                    put("adjusted_grade", x.adjustedGrade)
                    put("active_profiles", x.activeProfiles)
                    put("source_url", x.sourceUrl)
                    put("original_input", x.originalInput)
                    put("adjusted_output", x.adjustedOutput)
                    put("item_type", x.itemType)
                    put("original_violations", x.originalViolations)
                    put("original_stats", x.originalStats)
                    put("adjusted_violations", x.adjustedViolations)
                    put("adjusted_stats", x.adjustedStats)
                    put("original_bg_color", x.originalBgColor)
                    put("original_text_color", x.originalTextColor)
                    put("adjusted_bg_color", x.adjustedBgColor)
                    put("adjusted_text_color", x.adjustedTextColor)
                })
            }
            file.writeText(outArr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadHistoryFromFile(): List<HistoryItem> {
        val file = File(requireContext().filesDir, "user_history.json")
        if (!file.exists()) {
            val mockList = getMockHistoryItems()
            saveHistoryListToFile(mockList)
            return mockList
        }

        return try {
            val jsonStr = file.readText()
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.optString("item_type", "RECIPE")

                // Only load and show recipe items
                if (type == "RECIPE") {
                    list.add(
                        HistoryItem(
                            id = obj.getString("id"),
                            timestamp = obj.getLong("timestamp"),
                            title = obj.getString("title"),
                            subtitle = obj.getString("subtitle"),
                            originalGrade = obj.getString("original_grade"),
                            adjustedGrade = obj.getString("adjusted_grade"),
                            activeProfiles = obj.getString("active_profiles"),
                            sourceUrl = obj.optString("source_url", ""),
                            originalInput = obj.optString("original_input", ""),
                            adjustedOutput = obj.optString("adjusted_output", ""),
                            itemType = type,
                            originalViolations = obj.optString("original_violations", ""),
                            originalStats = obj.optString("original_stats", ""),
                            adjustedViolations = obj.optString("adjusted_violations", ""),
                            adjustedStats = obj.optString("adjusted_stats", ""),
                            originalBgColor = obj.optInt("original_bg_color", 0),
                            originalTextColor = obj.optInt("original_text_color", 0),
                            adjustedBgColor = obj.optInt("adjusted_bg_color", 0),
                            adjustedTextColor = obj.optInt("adjusted_text_color", 0)
                        )
                    )
                }
            }
            list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            getMockHistoryItems()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHistoryCard(context: Context, item: HistoryItem): View {
        val density = resources.displayMetrics.density

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (12 * density).toInt())
            }
            cardElevation = 0f
            radius = (12 * density)
            strokeWidth = (1 * density).toInt()
            strokeColor = Color.parseColor("#313542")
            setCardBackgroundColor(Color.parseColor("#EA14161F"))
        }

        val horizontalLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
            setImageResource(R.drawable.ic_recipe)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFF7E6"))
        }

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins((12 * density).toInt(), 0, 0, 0)
            }
        }

        val titleView = TextView(context).apply {
            text = item.title
            setTextColor(Color.parseColor("#F4F5FC"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val subtitleView = TextView(context).apply {
            text = item.subtitle
            setTextColor(Color.parseColor("#99A1B3"))
            textSize = 12f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }

        val metaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins((12 * density).toInt(), 0, 0, 0)
            }
        }

        val origIcon = when {
            item.originalGrade.contains("Green", ignoreCase = true) || item.originalGrade.contains("Safe", ignoreCase = true) -> "🟢"
            item.originalGrade.contains("Yellow", ignoreCase = true) || item.originalGrade.contains("Caution", ignoreCase = true) -> "🟡"
            else -> "🔴"
        }
        val adjIcon = when {
            item.adjustedGrade.contains("Green", ignoreCase = true) || item.adjustedGrade.contains("Safe", ignoreCase = true) -> "🟢"
            item.adjustedGrade.contains("Yellow", ignoreCase = true) || item.adjustedGrade.contains("Caution", ignoreCase = true) -> "🟡"
            else -> "🔴"
        }

        val progressionView = TextView(context).apply {
            text = "$origIcon ➔ $adjIcon"
            textSize = 15f
        }

        val profileView = TextView(context).apply {
            text = item.activeProfiles
            setTextColor(Color.parseColor("#8C7A6B"))
            textSize = 10f
            setPadding(0, (4 * density).toInt(), 0, 0)
            setTypeface(null, Typeface.BOLD)
        }

        textLayout.addView(titleView)
        textLayout.addView(subtitleView)
        metaLayout.addView(progressionView)
        metaLayout.addView(profileView)

        horizontalLayout.addView(iconView)
        horizontalLayout.addView(textLayout)
        horizontalLayout.addView(metaLayout)
        card.addView(horizontalLayout)

        card.setOnClickListener {
            val navOptions = androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.navigation_history, true)
                .build()

            val bundle = Bundle().apply {
                putString("RECIPE_INPUT", item.originalInput)
                putString("RECIPE_URL", item.sourceUrl)
                putString("RECIPE_ADJUSTED_OUTPUT", item.adjustedOutput)
                putString("HISTORY_ITEM_ID", item.id)
                putString("RECIPE_TITLE", item.title)

                putString("ORIGINAL_GRADE", item.originalGrade)
                putString("ORIGINAL_VIOLATIONS", item.originalViolations)
                putString("ORIGINAL_STATS", item.originalStats)
                putInt("ORIGINAL_BG_COLOR", item.originalBgColor)
                putInt("ORIGINAL_TEXT_COLOR", item.originalTextColor)

                putString("ADJUSTED_GRADE", item.adjustedGrade)
                putString("ADJUSTED_VIOLATIONS", item.adjustedViolations)
                putString("ADJUSTED_STATS", item.adjustedStats)
                putInt("ADJUSTED_BG_COLOR", item.adjustedBgColor)
                putInt("ADJUSTED_TEXT_COLOR", item.adjustedTextColor)
            }
            findNavController().navigate(R.id.recipeFragment, bundle, navOptions)
        }

        card.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var isSwiping = false
            private var isScrolling = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        isSwiping = false
                        isScrolling = false
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isScrolling) return false

                        val diffX = event.x - startX
                        val diffY = event.y - startY

                        if (!isSwiping && !isScrolling) {
                            val absX = kotlin.math.abs(diffX)
                            val absY = kotlin.math.abs(diffY)

                            if (absY > absX && absY > 10) {
                                isScrolling = true
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                return false
                            } else if (absX > absY && absX > 10) {
                                isSwiping = true
                            }
                        }

                        if (isSwiping) {
                            if (diffX < 0) {
                                v.translationX = diffX
                                v.alpha = 1f + (diffX / v.width.toFloat())
                            }
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isScrolling) {
                            return false
                        }
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        val diffX = event.x - startX

                        if (isSwiping && diffX < -150) {
                            v.animate()
                                .translationX(-v.width.toFloat())
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction {
                                    deleteHistoryItem(item)
                                }
                                .start()
                        } else {
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(200)
                                .start()

                            if (!isSwiping) {
                                v.performClick()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        return card
    }

    private fun getMockHistoryItems(): List<HistoryItem> {
        val now = System.currentTimeMillis()
        return listOf(
            HistoryItem(
                id = "1",
                timestamp = now - 3600000,
                title = "Apples & Squash Blend",
                subtitle = "Tweak: Oats ➔ Barley | Honey ➔ Syrup",
                originalGrade = "Red - Avoid",
                adjustedGrade = "Green - Safe",
                activeProfiles = "CKD Pre-Dialysis",
                sourceUrl = "https://davita.com/diet-nutrition/recipes/beef-lamb-pork/broccoli-and-beef-stir-fry/",
                originalInput = "2 cups raw broccoli\n1 lb lean beef sirloin\n2 tbsp soy sauce\n1 tbsp sesame oil\n1 tsp cornstarch",
                adjustedOutput = "2 cups raw broccoli\n1 lb lean beef sirloin\n2 tbsp coconut aminos\n1 tbsp sesame oil\n1 tsp arrowroot starch",
                itemType = "RECIPE",
                originalViolations = "• High Sodium (Soy Sauce)\n• High Potassium",
                originalStats = "Per Serving:\nCal: 220 | Sod: 920mg | Prot: 24g",
                adjustedViolations = "None (Compliant)",
                adjustedStats = "Per Serving:\nCal: 210 | Sod: 110mg | Prot: 24g",
                originalBgColor = Color.parseColor("#4D5C1D1D"),
                originalTextColor = Color.WHITE,
                adjustedBgColor = Color.parseColor("#4D1D5C1D"),
                adjustedTextColor = Color.WHITE
            )
        )
    }
}