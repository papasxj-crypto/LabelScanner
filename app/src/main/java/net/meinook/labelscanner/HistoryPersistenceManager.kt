package net.meinook.labelscanner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object HistoryPersistenceManager {

    private const val DIRECTORY_NAME = "history"
    private const val TAG = "HistoryManager"

    private fun getHistoryDir(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveHistoryItem(context: Context, item: HistoryItem) {
        try {
            val directory = getHistoryDir(context)
            val file = File(directory, "history_${item.id}.json")

            val jsonObject = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("title", item.title)
                put("subtitle", item.subtitle)
                put("originalGrade", item.originalGrade)
                put("adjustedGrade", item.adjustedGrade)
                put("activeProfiles", item.activeProfiles)
                put("sourceUrl", item.sourceUrl)
                put("originalInput", item.originalInput)
                put("adjustedOutput", item.adjustedOutput)
                put("itemType", item.itemType)
                put("originalViolations", item.originalViolations)
                put("originalStats", item.originalStats)
                put("adjustedViolations", item.adjustedViolations)
                put("adjustedStats", item.adjustedStats)
                put("originalBgColor", item.originalBgColor)
                put("originalTextColor", item.originalTextColor)
                put("adjustedBgColor", item.adjustedBgColor)
                put("adjustedTextColor", item.adjustedTextColor)
            }

            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(jsonObject.toString())
                    writer.flush()
                }
            }
            Log.d(TAG, "Successfully saved history item: ${item.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history item cleanly: ${e.localizedMessage}")
        }
    }

    fun loadAllHistory(context: Context): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            val directory = getHistoryDir(context)
            val files = directory.listFiles() ?: return emptyList()

            for (file in files) {
                if (!file.name.startsWith("history_") || !file.name.endsWith(".json")) continue
                try {
                    val rawJson = file.inputStream().bufferedReader().use { it.readText() }
                    val json = JSONObject(rawJson)

                    val item = HistoryItem(
                        id = json.optString("id", ""),
                        timestamp = json.optLong("timestamp", 0L),
                        title = json.optString("title", ""),
                        subtitle = json.optString("subtitle", ""),
                        originalGrade = json.optString("originalGrade", "Unknown"),
                        adjustedGrade = json.optString("adjustedGrade", "Unknown"),
                        activeProfiles = json.optString("activeProfiles", ""),
                        sourceUrl = json.optString("sourceUrl", ""),
                        originalInput = json.optString("originalInput", ""),
                        adjustedOutput = json.optString("adjustedOutput", ""),
                        itemType = json.optString("itemType", "RECIPE"),
                        originalViolations = json.optString("originalViolations", ""),
                        originalStats = json.optString("originalStats", ""),
                        adjustedViolations = json.optString("adjustedViolations", ""),
                        adjustedStats = json.optString("adjustedStats", ""),
                        originalBgColor = json.optInt("originalBgColor", 0),
                        originalTextColor = json.optInt("originalTextColor", 0),
                        adjustedBgColor = json.optInt("adjustedBgColor", 0),
                        adjustedTextColor = json.optInt("adjustedTextColor", 0)
                    )
                    list.add(item)
                } catch (e: Exception) {
                    Log.e(TAG, "Skipped reading corrupted history file ${file.name}: ${e.localizedMessage}")
                }
            }
            list.sortByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history list: ${e.localizedMessage}")
        }
        return list
    }

    fun deleteHistoryItem(context: Context, id: String): Boolean {
        return try {
            val directory = getHistoryDir(context)
            val file = File(directory, "history_${id}.json")
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted history item file history_${id}.json: $deleted")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete history item: ${e.localizedMessage}")
            false
        }
    }

    fun clearAllHistory(context: Context) {
        try {
            val directory = getHistoryDir(context)
            val files = directory.listFiles() ?: return
            for (file in files) {
                file.delete()
            }
            Log.d(TAG, "Cleared all local history files safely.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute global history clear: ${e.localizedMessage}")
        }
    }
}