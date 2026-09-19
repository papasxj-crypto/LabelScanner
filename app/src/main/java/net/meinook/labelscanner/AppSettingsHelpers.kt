package net.meinook.labelscanner


data class DietProfile(val id: String, val displayName: String)

data class ProteinRules(
    val snackMin: Int = 0,
    val snackMax: Int = 999,
    val mealMin: Int = 0,
    val mealMax: Int = 999,
    val targetRatio: Float = 0.0f
)

data class NutrientRule(
    val name: String,
    val lowMax: Int,
    val moderateMax: Int,
    val isBlacklist: Boolean
)