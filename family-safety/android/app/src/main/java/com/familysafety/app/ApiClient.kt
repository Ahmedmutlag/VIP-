package com.familysafety.app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    var serverUrl: String = ""
    var deviceId: String = ""
    var childName: String = ""

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base: String get() = serverUrl.trimEnd('/')

    fun getCommand(): String? {
        return try {
            val request = Request.Builder()
                .url("$base/api/command")
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-Device-Name", childName)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { Log.w(TAG, "getCommand failed: HTTP ${response.code}"); return null }
                val json = response.body?.string() ?: return null
                val obj = gson.fromJson(json, JsonObject::class.java)
                val cmd = obj.get("command")
                if (cmd == null || cmd.isJsonNull) null else cmd.asString
            }
        } catch (e: Exception) { Log.e(TAG, "getCommand: ${e.message}"); null }
    }

    fun postPhoto(photoFile: File): Boolean {
        return try {
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("photo", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                .build()
            val request = Request.Builder()
                .url("$base/api/photo")
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-Device-Name", childName)
                .post(requestBody).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { Log.e(TAG, "postPhoto: ${e.message}"); false }
    }

    fun postFrame(base64Jpeg: String): Boolean {
        val body = JsonObject().apply { addProperty("frame", base64Jpeg) }
        return postJson("/api/frame", gson.toJson(body))
    }

    fun postJson(endpoint: String, json: String): Boolean {
        return try {
            val url = if (endpoint.startsWith("http")) endpoint else "$base$endpoint"
            val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-Device-Name", childName)
                .post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "POST $url failed: HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) { Log.e(TAG, "POST $endpoint: ${e.message}"); false }
    }
}
