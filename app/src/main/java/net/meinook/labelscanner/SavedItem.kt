package net.meinook.labelscanner

import java.io.Serializable

data class SavedItem(
    val id: String,
    val timestamp: Long,
    val title: String,
    val baseProfileId: String,          // Preserves clinical ID for "Verify Safety" checks
    val adjustedGrade: String,          // Stores compiled grade on save
    val sourceUrl: String = "",         // Scraped website link (cache key for URLs)
    val originalInput: String = "",     // Original raw text (cache key for text/OCR pastes)
    val adjustedOutput: String = "",     // Clean adjusted ingredients
    val instructions: String = "",       // Saved cooking steps (populated via Get Steps)
    val originalNutrition: String = "", // Cached raw original nutrition metrics
    val adjustedNutrition: String = "", // Cached raw adjusted nutrition metrics
    val servings: Int = 1,              // Servings count for per-serving calculations
    val itemType: String = "RECIPE"      // Discriminator
) : Serializable