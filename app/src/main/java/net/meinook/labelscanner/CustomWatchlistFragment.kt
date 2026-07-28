package net.meinook.labelscanner

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import net.meinook.labelscanner.R

class CustomWatchlistFragment : Fragment(R.layout.fragment_custom_watchlist) {

    private lateinit var layoutRedItems: LinearLayout
    private lateinit var layoutYellowItems: LinearLayout
    private lateinit var etIngredientInput: EditText
    private lateinit var btnAddRed: Button
    private lateinit var btnAddYellow: Button
    private lateinit var btnBackToHealth: Button
    private lateinit var appSettings: AppSettings

    // Safety Banner Elements
    private lateinit var layoutConfirmBanner: LinearLayout
    private lateinit var tvConfirmMessage: TextView
    private lateinit var btnConfirmDelete: Button
    private lateinit var btnCancelDelete: ImageView

    private var pendingDeleteIngredient: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutRedItems = view.findViewById(R.id.layoutRedItems)
        layoutYellowItems = view.findViewById(R.id.layoutYellowItems)
        etIngredientInput = view.findViewById(R.id.etIngredientInput)
        btnAddRed = view.findViewById(R.id.btnAddRed)
        btnAddYellow = view.findViewById(R.id.btnAddYellow)
        btnBackToHealth = view.findViewById(R.id.btnBackToHealth)
        appSettings = AppSettings(requireContext())

        // Bind Deletion Interlocks
        layoutConfirmBanner = view.findViewById(R.id.layoutConfirmBanner)
        tvConfirmMessage = view.findViewById(R.id.tvConfirmMessage)
        btnConfirmDelete = view.findViewById(R.id.btnConfirmDelete)
        btnCancelDelete = view.findViewById(R.id.btnCancelDelete)

        btnAddRed.setOnClickListener { addIngredientToTier("RED") }
        btnAddYellow.setOnClickListener { addIngredientToTier("YELLOW") }

        btnConfirmDelete.setOnClickListener {
            pendingDeleteIngredient?.let { item ->
                appSettings.removeWatchlistIngredient(item)
                Toast.makeText(requireContext(), "Removed $item", Toast.LENGTH_SHORT).show()
            }
            hideConfirmationBanner()
            refreshWatchlists()
        }

        btnCancelDelete.setOnClickListener {
            hideConfirmationBanner()
        }

        // Return Navigation
        btnBackToHealth.setOnClickListener {
            findNavController().popBackStack()
        }

        refreshWatchlists()
    }

    private fun addIngredientToTier(tier: String) {
        val ingredient = etIngredientInput.text.toString().trim()
        if (ingredient.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter an ingredient name", Toast.LENGTH_SHORT).show()
            return
        }

        appSettings.addWatchlistIngredient(ingredient, tier)
        etIngredientInput.text.clear()
        hideConfirmationBanner()
        refreshWatchlists()
    }

    private fun showConfirmationBanner(ingredient: String, tier: String) {
        pendingDeleteIngredient = ingredient
        tvConfirmMessage.text = "Remove '$ingredient'?"

        if (tier == "RED") {
            layoutConfirmBanner.setBackgroundColor(Color.parseColor("#C62828")) // Dark Red
            tvConfirmMessage.setTextColor(Color.WHITE)
            btnCancelDelete.setColorFilter(Color.WHITE)

            // Button: White background with red text
            btnConfirmDelete.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnConfirmDelete.setTextColor(Color.parseColor("#C62828"))
        } else {
            layoutConfirmBanner.setBackgroundColor(Color.parseColor("#FBC02D")) // High-contrast Amber Yellow
            tvConfirmMessage.setTextColor(Color.BLACK)
            btnCancelDelete.setColorFilter(Color.BLACK)

            // Button: Dark gray background with white text
            btnConfirmDelete.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#212121"))
            btnConfirmDelete.setTextColor(Color.WHITE)
        }

        layoutConfirmBanner.visibility = View.VISIBLE
    }

    private fun hideConfirmationBanner() {
        pendingDeleteIngredient = null
        layoutConfirmBanner.visibility = View.GONE
    }

    private fun refreshWatchlists() {
        layoutRedItems.removeAllViews()
        layoutYellowItems.removeAllViews()

        val redWatchlist = appSettings.getCustomWatchlist("RED")
        val yellowWatchlist = appSettings.getCustomWatchlist("YELLOW")

        for (item in redWatchlist) {
            val row = createWatchlistRow(item) {
                showConfirmationBanner(item, "RED") // Passes "RED"
            }
            layoutRedItems.addView(row)
        }

        for (item in yellowWatchlist) {
            val row = createWatchlistRow(item) {
                showConfirmationBanner(item, "YELLOW") // Passes "YELLOW"
            }
            layoutYellowItems.addView(row)
        }
    }

    private fun createWatchlistRow(ingredient: String, onDeleteRequest: () -> Unit): View {
        val rowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 16, 8, 16)
        }

        val textView = TextView(requireContext()).apply {
            text = ingredient
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deleteButton = ImageView(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#E57373"))
            setPadding(16, 0, 16, 0)
            setOnClickListener { onDeleteRequest() }
        }

        rowLayout.addView(textView)
        rowLayout.addView(deleteButton)

        return rowLayout
    }
}