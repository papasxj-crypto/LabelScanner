@file:Suppress("unused")
package net.meinook.labelscanner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object SavedPersistenceManager {

    private const val DIRECTORY_NAME = "saved_recipes"
    private const val TAG = "SavedPersistenceManager"

    private fun getSavedDir(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveItem(context: Context, item: SavedItem) {
        try {
            val directory = getSavedDir(context)

            // 1. Deduplication: Find and delete any existing duplicate file (matched by Title or Source URL)
            val existingFiles = directory.listFiles() ?: emptyArray()
            for (file in existingFiles) {
                if (!file.name.startsWith("saved_") || !file.name.endsWith(".json")) continue
                try {
                    val rawJson = file.inputStream().bufferedReader().use { it.readText() }
                    val json = JSONObject(rawJson)
                    val existingTitle = json.optString("title", "")
                    val existingUrl = json.optString("sourceUrl", "")

                    val isTitleMatch = existingTitle.isNotBlank() && existingTitle.equals(item.title, ignoreCase = true)
                    val isUrlMatch = existingUrl.isNotBlank() && item.sourceUrl.isNotBlank() && existingUrl.equals(item.sourceUrl, ignoreCase = true)

                    if (isTitleMatch || isUrlMatch) {
                        file.delete()
                        Log.d(TAG, "Replaced previous duplicate recipe file: ${file.name}")
                    }
                } catch (_: Exception) {}
            }

            // 2. Save the updated recipe with its fresh timestamp so it lists first
            val file = File(directory, "saved_${item.id}.json")
            val jsonObject = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("title", item.title)
                put("baseProfileId", item.baseProfileId)
                put("adjustedGrade", item.adjustedGrade)
                put("sourceUrl", item.sourceUrl)
                put("originalInput", item.originalInput)
                put("adjustedOutput", item.adjustedOutput)
                put("instructions", item.instructions)
                put("originalNutrition", item.originalNutrition)
                put("adjustedNutrition", item.adjustedNutrition)
                put("servings", item.servings)
                put("itemType", item.itemType)
            }

            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(jsonObject.toString())
                    writer.flush()
                }
            }
            Log.d(TAG, "Successfully saved recipe: ${item.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recipe item cleanly: ${e.localizedMessage}", e)
        }
    }

    // Cache Interceptor Query: Checks local storage for an identical URL or raw text fingerprint
    fun findMatchingRecipe(context: Context, sourceUrl: String, rawText: String): SavedItem? {
        val allItems = loadAllItems(context)
        val cleanUrl = sourceUrl.trim()
        val cleanRaw = rawText.trim()

        // 1. Primary Cache Match: Canonical Scraped URL
        if (cleanUrl.isNotBlank()) {
            val urlMatch = allItems.firstOrNull {
                it.sourceUrl.isNotBlank() && it.sourceUrl.equals(cleanUrl, ignoreCase = true)
            }
            if (urlMatch != null) return urlMatch
        }

        // 2. Secondary Cache Match: Raw text / OCR paste exact match
        if (cleanRaw.isNotBlank()) {
            val textMatch = allItems.firstOrNull {
                it.originalInput.isNotBlank() && it.originalInput.trim().equals(cleanRaw, ignoreCase = true)
            }
            if (textMatch != null) return textMatch
        }

        return null
    }

    // Safely appends cooking instructions/steps to an existing saved recipe
    fun updateItemInstructions(context: Context, id: String, instructionsText: String) {
        try {
            val directory = getSavedDir(context)
            val file = File(directory, "saved_${id}.json")
            if (file.exists()) {
                val rawJson = file.inputStream().bufferedReader().use { it.readText() }
                val json = JSONObject(rawJson)

                json.put("instructions", instructionsText)

                FileOutputStream(file).use { fos ->
                    OutputStreamWriter(fos).use { writer ->
                        writer.write(json.toString())
                        writer.flush()
                    }
                }
                Log.d(TAG, "Successfully updated instructions for recipe: $id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update instructions for recipe $id: ${e.localizedMessage}", e)
        }
    }

    fun loadAllItems(context: Context): List<SavedItem> {
        val list = mutableListOf<SavedItem>()
        try {
            val directory = getSavedDir(context)
            val files = directory.listFiles() ?: return emptyList()

            for (file in files) {
                if (!file.name.startsWith("saved_") || !file.name.endsWith(".json")) continue
                try {
                    val rawJson = file.inputStream().bufferedReader().use { it.readText() }
                    val json = JSONObject(rawJson)

                    val item = SavedItem(
                        id = json.optString("id", ""),
                        timestamp = json.optLong("timestamp", 0L),
                        title = json.optString("title", ""),
                        baseProfileId = json.optString("baseProfileId", "healthy_baseline"),
                        adjustedGrade = json.optString("adjustedGrade", "Green - Safe"),
                        sourceUrl = json.optString("sourceUrl", ""),
                        originalInput = json.optString("originalInput", ""),
                        adjustedOutput = json.optString("adjustedOutput", ""),
                        instructions = json.optString("instructions", ""),
                        originalNutrition = json.optString("originalNutrition", ""),
                        adjustedNutrition = json.optString("adjustedNutrition", ""),
                        servings = json.optInt("servings", 1),
                        itemType = json.optString("itemType", "RECIPE")
                    )
                    list.add(item)
                } catch (e: Exception) {
                    Log.e(TAG, "Skipped reading corrupted recipe file ${file.name}: ${e.localizedMessage}", e)
                }
            }
            list.sortByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved recipes list: ${e.localizedMessage}", e)
        }
        return list
    }

    fun deleteItem(context: Context, id: String): Boolean {
        return try {
            val directory = getSavedDir(context)
            val file = File(directory, "saved_${id}.json")
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted saved recipe file saved_${id}.json: $deleted")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete saved recipe: ${e.localizedMessage}", e)
            false
        }
    }

    fun clearAllItems(context: Context) {
        try {
            val directory = getSavedDir(context)
            val files = directory.listFiles() ?: return
            for (file in files) {
                file.delete()
            }
            Log.d(TAG, "Cleared all local saved recipe files safely.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute global recipe clear: ${e.localizedMessage}", e)
        }
    }
}