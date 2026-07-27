package net.meinook.labelscanner

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class HealthFragment : Fragment(R.layout.fragment_health) {

    private lateinit var containerConditions: LinearLayout
    private lateinit var btnSaveHealthProfiles: MaterialButton
    private lateinit var appSettings: AppSettings
    private val selectedConditionIds = mutableSetOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appSettings = AppSettings(requireContext())
        containerConditions = view.findViewById(R.id.containerConditions)
        btnSaveHealthProfiles = view.findViewById(R.id.btnSaveHealthProfiles)

        // Load existing selections
        selectedConditionIds.addAll(appSettings.getSelectedConditions())

        populateConditionCheckboxes()

        btnSaveHealthProfiles.setOnClickListener {
            appSettings.saveSelectedConditions(selectedConditionIds)
            Toast.makeText(requireContext(), "Health Profile Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun populateConditionCheckboxes() {
        val availableProfiles = appSettings.getAvailableDietProfiles()

        for (profile in availableProfiles) {
            val checkBox = CheckBox(requireContext()).apply {
                text = profile.displayName
                setTextColor(android.graphics.Color.WHITE) // ✅ Fixed: using explicit setter
                textSize = 16f
                isChecked = selectedConditionIds.contains(profile.id)
                setPadding(16, 16, 16, 16)

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedConditionIds.add(profile.id)
                    } else {
                        selectedConditionIds.remove(profile.id)
                    }
                }
            }
            containerConditions.addView(checkBox)
        }
    }
}