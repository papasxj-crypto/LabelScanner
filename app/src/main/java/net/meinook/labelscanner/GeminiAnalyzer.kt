package net.meinook.labelscanner

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun resizeBitmapForTransit(source: Bitmap, maxDimension: Int = 768): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDimension && height <= maxDimension) return source

        val aspectRatio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / aspectRatio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * aspectRatio).toInt()
        }

        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    suspend fun analyzeIngredientsText(
        extractedOcrText: String,
        backendUrl: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply {
                put("action", "scan_text")
                put("extracted_ocr_text", extractedOcrText)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(backendUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP Error: ${response.code}")
                }
                response.body?.string() ?: "{}"
            }
        } catch (e: Exception) {
            Log.e("GeminiAnalyzer", "analyzeIngredientsText proxy failed:", e)
            throw e
        }
    }

    suspend fun analyzeIngredientsImage(
        imageBitmap: Bitmap,
        backendUrl: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val optimizedBitmap = resizeBitmapForTransit(imageBitmap, 768)
            val base64Image = encodeBitmapToBase64(optimizedBitmap)

            val jsonRequest = JSONObject().apply {
                put("action", "scan_image")
                put("image_b64", base64Image)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(backendUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP Error: ${response.code}")
                }
                response.body?.string() ?: "{}"
            }
        } catch (e: Exception) {
            Log.e("GeminiAnalyzer", "analyzeIngredientsImage proxy failed:", e)
            throw e
        }
    }

    suspend fun analyzeProduceImage(
        bitmap: Bitmap,
        backendUrl: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val optimizedBitmap = resizeBitmapForTransit(bitmap, 768)
            val base64Image = encodeBitmapToBase64(optimizedBitmap)

            val jsonRequest = JSONObject().apply {
                put("action", "scan_produce")
                put("image_b64", base64Image)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(backendUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP Error: ${response.code}")
                }
                response.body?.string() ?: "{}"
            }
        } catch (e: Exception) {
            Log.e("GeminiAnalyzer", "analyzeProduceImage proxy failed:", e)
            throw e
        }
    }
}