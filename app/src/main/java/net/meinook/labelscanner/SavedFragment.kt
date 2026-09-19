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
import java.util.Locale

class SavedFragment : Fragment() {

    private lateinit var layoutSavedContainer: LinearLayout
    private lateinit var textEmptySaved: TextView
    private lateinit var txtSwipeToDeleteHint: TextView
    private lateinit var appSettings: AppSettings

    companion object {
        fun saveSavedItem(context: Context, item: SavedItem) {
            SavedPersistenceManager.saveItem(context, item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_saved, container, false)
        layoutSavedContainer = view.findViewById(R.id.layoutSavedContainer)
        textEmptySaved = view.findViewById(R.id.textEmptySaved)
        txtSwipeToDeleteHint = view.findViewById(R.id.txtSwipeToDeleteHint)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appSettings = AppSettings(requireContext())
        populateSavedLog()
    }

    private fun populateSavedLog() {
        val ctx = context ?: return
        layoutSavedContainer.removeAllViews()

        val items = loadSavedFromFile()
        if (items.isEmpty()) {
            textEmptySaved.visibility = View.VISIBLE
            txtSwipeToDeleteHint.visibility = View.GONE
        } else {
            textEmptySaved.visibility = View.GONE
            txtSwipeToDeleteHint.visibility = View.VISIBLE
            for (item in items) {
                val cardView = createSavedCard(ctx, item)
                layoutSavedContainer.addView(cardView)
            }
        }
    }

    private fun deleteSavedItem(item: SavedItem) {
        val success = SavedPersistenceManager.deleteItem(requireContext(), item.id)
        if (success) {
            Toast.makeText(context, "Recipe removed from your cookbook", Toast.LENGTH_SHORT).show()
        }
        populateSavedLog()
    }

    private fun loadSavedFromFile(): List<SavedItem> {
        val items = SavedPersistenceManager.loadAllItems(requireContext())
        return items.filter { it.itemType == "RECIPE" }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSavedCard(context: Context, item: SavedItem): View {
        val density = resources.displayMetrics.density

        val card = com.google.android.material.card.MaterialCardView(context).apply {
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

        val cleanSubtitle = item.adjustedOutput.lines()
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(", ") { it.trim().removePrefix("-").trim() }
            .ifEmpty { "View saved recipe details" }

        val subtitleView = TextView(context).apply {
            text = cleanSubtitle
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

        val activeConditions = appSettings.getSelectedConditions()
        val customReds = appSettings.getCustomWatchlist("RED")

        val hasCustomViolation = customReds.any { trigger ->
            item.adjustedOutput.contains(trigger, ignoreCase = true)
        }

        val normalizedActiveBases = activeConditions.map { id ->
            val base = appSettings.getBaseProfileIdForProfile(id)
            if (base.isNotEmpty()) base else id
        }.toSet()

        val normalizedItemBase = run {
            val base = appSettings.getBaseProfileIdForProfile(item.baseProfileId)
            if (base.isNotEmpty()) base else item.baseProfileId
        }

        val profileMatches = activeConditions.contains(item.baseProfileId) ||
                normalizedActiveBases.contains(normalizedItemBase) ||
                normalizedActiveBases.contains(item.baseProfileId)

        val isOriginallyRed = item.adjustedGrade.lowercase(Locale.ROOT).contains("red") ||
                item.adjustedGrade.lowercase(Locale.ROOT).contains("avoid")
        val isOriginallyCaution = item.adjustedGrade.lowercase(Locale.ROOT).contains("yellow") ||
                item.adjustedGrade.lowercase(Locale.ROOT).contains("caution")
        val isOriginallySafe = item.adjustedGrade.lowercase(Locale.ROOT).contains("green") ||
                item.adjustedGrade.lowercase(Locale.ROOT).contains("safe")

        val (safetyText, safetyColor) = when {
            hasCustomViolation -> Pair("🔴 Avoid (Watchlist)", "#FF6B6B")
            isOriginallyRed -> Pair("🔴 Avoid (Saved)", "#FF6B6B")
            !profileMatches -> Pair("🟡 Re-Verify", "#FFD54F")
            isOriginallyCaution -> Pair("🟡 Caution (Saved)", "#FFD54F")
            isOriginallySafe -> Pair("🟢 Verified Safe", "#81C784")
            else -> Pair("🟡 Re-Verify", "#FFD54F")
        }

        val safetyBadgeView = TextView(context).apply {
            text = safetyText
            setTextColor(Color.parseColor(safetyColor))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        val profileView = TextView(context).apply {
            val displayName = appSettings.getAvailableDietProfiles().find { it.id == item.baseProfileId }?.displayName
                ?: appSettings.getAvailableDietProfiles().find { it.id == normalizedItemBase }?.displayName
                ?: item.baseProfileId
            text = "For: $displayName"
            setTextColor(Color.parseColor("#8C7A6B"))
            textSize = 10f
            setPadding(0, (4 * density).toInt(), 0, 0)
            setTypeface(null, Typeface.BOLD)
        }

        textLayout.addView(titleView)
        textLayout.addView(subtitleView)
        metaLayout.addView(safetyBadgeView)
        metaLayout.addView(profileView)

        horizontalLayout.addView(iconView)
        horizontalLayout.addView(textLayout)
        horizontalLayout.addView(metaLayout)
        card.addView(horizontalLayout)

        card.setOnClickListener {
            val bundle = Bundle().apply {
                putString("RECIPE_INPUT", item.adjustedOutput)
                putString("RECIPE_URL", item.sourceUrl)
                putString("RECIPE_ADJUSTED_OUTPUT", item.adjustedOutput)
                putString("HISTORY_ITEM_ID", item.id)
                putString("RECIPE_TITLE", item.title)
                putString("RECIPE_INSTRUCTIONS", item.instructions)
                putString("BASE_PROFILE_ID", item.baseProfileId)
                putString("ADJUSTED_GRADE", item.adjustedGrade)
                putString("ORIGINAL_GRADE", "")
            }
            // Standard forward navigation preserves SavedFragment on the backstack so '<-' returns to Cookbook
            findNavController().navigate(R.id.recipeDetailFragment, bundle)
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
                                    deleteSavedItem(item)
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
}