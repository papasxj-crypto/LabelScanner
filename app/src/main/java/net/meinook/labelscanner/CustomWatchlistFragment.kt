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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CustomWatchlistFragment : Fragment(R.layout.fragment_custom_watchlist) {

    private lateinit var layoutRedItems: LinearLayout
    private lateinit var layoutYellowItems: LinearLayout
    private lateinit var etIngredientInput: EditText
    private lateinit var chipGroupCommonItems: ChipGroup
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
    private lateinit var commonWatchlistItems: List<String>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutRedItems = view.findViewById(R.id.layoutRedItems)
        layoutYellowItems = view.findViewById(R.id.layoutYellowItems)
        etIngredientInput = view.findViewById(R.id.etIngredientInput)
        chipGroupCommonItems = view.findViewById(R.id.chipGroupCommonItems)
        btnAddRed = view.findViewById(R.id.btnAddRed)
        btnAddYellow = view.findViewById(R.id.btnAddYellow)
        btnBackToHealth = view.findViewById(R.id.btnBackToHealth)
        appSettings = AppSettings(requireContext())

        commonWatchlistItems = resources.getStringArray(R.array.common_watchlist_items).toList()

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

    // Runs disk I/O on background thread, preventing frame drops on UI thread
    private fun setupCommonItemsChips() {
        chipGroupCommonItems.removeAllViews()

        val redWatchlist = appSettings.getCustomWatchlist("RED").toSet()
        val yellowWatchlist = appSettings.getCustomWatchlist("YELLOW").toSet()

        val activeProfiles = appSettings.getSelectedConditions()
        val availableDietProfiles = appSettings.getAvailableDietProfiles()

        lifecycleScope.launch {
            // Offload disk reads to Background thread
            val profileTriggersMap = withContext(Dispatchers.Default) {
                val map = mutableMapOf<String, String>()
                for (profileId in activeProfiles) {
                    val profileName = availableDietProfiles.find { it.id == profileId }?.displayName ?: profileId
                    val triggers = appSettings.getIngredientsFromAssetFile(profileId)
                    for (trigger in triggers) {
                        map[trigger.uppercase(Locale.US).trim()] = profileName
                    }
                }
                map
            }

            // Draw chips on UI thread
            for (item in commonWatchlistItems) {
                val chip = Chip(requireContext()).apply {
                    isCloseIconVisible = false
                    isClickable = true

                    val coveredProfileName = profileTriggersMap.keys.find { trigger ->
                        item.contains(trigger) || trigger.contains(item)
                    }?.let { profileTriggersMap[it] }

                    val isProfileCovered = coveredProfileName != null
                    val isRed = redWatchlist.contains(item)
                    val isYellow = yellowWatchlist.contains(item)

                    when {
                        isProfileCovered -> {
                            text = "$item (Profile)"
                            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#1A237E"))
                            setTextColor(Color.WHITE)
                        }
                        isRed -> {
                            text = item
                            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#C62828"))
                            setTextColor(Color.WHITE)
                        }
                        isYellow -> {
                            text = item
                            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#FBC02D"))
                            setTextColor(Color.BLACK)
                        }
                        else -> {
                            text = item
                            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#2C2C2C"))
                            setTextColor(Color.WHITE)
                        }
                    }

                    setOnClickListener {
                        if (isProfileCovered) {
                            val coveringProfile = coveredProfileName ?: "Active Profile"
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Clinical Profile Protection")
                                .setMessage("'$item' is already being monitored automatically because you have the '$coveringProfile' profile active in My Health.\n\nYou do not need to add it to your custom watchlist.")
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            handleChipInteraction(item, isRed, isYellow)
                        }
                    }
                }
                chipGroupCommonItems.addView(chip)
            }
        }
    }

    private fun handleChipInteraction(item: String, isRed: Boolean, isYellow: Boolean) {
        if (isRed) {
            showConfirmationBanner(item, "RED")
        } else if (isYellow) {
            showConfirmationBanner(item, "YELLOW")
        } else {
            val options = arrayOf("Add to RED Watchlist", "Add to YELLOW Watchlist")
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Quick Add Watchlist")
                .setItems(options) { _, which ->
                    val tier = if (which == 0) "RED" else "YELLOW"
                    appSettings.addWatchlistIngredient(item, tier)
                    Toast.makeText(requireContext(), "Added $item to $tier Watchlist", Toast.LENGTH_SHORT).show()
                    refreshWatchlists()
                }
                .show()
        }
    }

    private fun showConfirmationBanner(ingredient: String, tier: String) {
        pendingDeleteIngredient = ingredient
        tvConfirmMessage.text = "Remove '$ingredient'?"

        if (tier == "RED") {
            layoutConfirmBanner.setBackgroundColor(Color.parseColor("#C62828"))
            tvConfirmMessage.setTextColor(Color.WHITE)
            btnCancelDelete.setColorFilter(Color.WHITE)

            btnConfirmDelete.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnConfirmDelete.setTextColor(Color.parseColor("#C62828"))
        } else {
            layoutConfirmBanner.setBackgroundColor(Color.parseColor("#FBC02D"))
            tvConfirmMessage.setTextColor(Color.BLACK)
            btnCancelDelete.setColorFilter(Color.BLACK)

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
                showConfirmationBanner(item, "RED")
            }
            layoutRedItems.addView(row)
        }

        for (item in yellowWatchlist) {
            val row = createWatchlistRow(item) {
                showConfirmationBanner(item, "YELLOW")
            }
            layoutYellowItems.addView(row)
        }

        setupCommonItemsChips()
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