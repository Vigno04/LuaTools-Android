package com.luatools.companion.luatools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HubcapStats(
    val used: Int,
    val limit: Int,
    val remaining: Int
)

/**
 * Mirrors LuaToolsGui/Services/HubcapService.cs.
 * Talks directly to hubcapmanifest.com with the user's API key.
 */
class HubcapService(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    private val keyRegex = Regex("""^smm_[0-9a-f]{96}$""")

    fun isValidKeyFormat(key: String?): Boolean =
        key != null && keyRegex.matches(key.trim())

    suspend fun getStatsAsync(key: String): HubcapStats? = withContext(Dispatchers.IO) {
        try {
            val clean = key.trim()
            val url = "${AppConfig.HUBCAP_BASE_URL}/api/v1/user/stats?api_key=${URLEncoder.encode(clean, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val json = JSONObject(res.body?.string() ?: return@withContext null)
                val used = json.optInt("used", json.optInt("daily_used", 0))
                val limit = json.optInt("limit", json.optInt("daily_limit", 0))
                val remaining = json.optInt("remaining", (limit - used).coerceAtLeast(0))
                HubcapStats(used = used, limit = limit, remaining = remaining)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkStatusAsync(key: String, appId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${AppConfig.HUBCAP_BASE_URL}/api/v1/status/$appId")
                .header("Authorization", "Bearer ${key.trim()}")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext false
                val json = JSONObject(res.body?.string() ?: return@withContext false)
                json.optBoolean("available", json.optBoolean("exists", true))
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadManifestAsync(
        appId: Long,
        key: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = key.trim()
            val url = "${AppConfig.HUBCAP_BASE_URL}/api/v1/manifest/$appId?api_key=${URLEncoder.encode(clean, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val msg = when (res.code) {
                        401 -> "Hubcap: Invalid API key"
                        429 -> "Hubcap: Daily limit reached"
                        404 -> "Hubcap: No manifest found for AppID $appId"
                        else -> "Hubcap download failed (HTTP ${res.code})"
                    }
                    throw IllegalStateException(msg)
                }
                val bytes = res.body?.bytes() ?: throw IllegalStateException("Hubcap returned empty body")
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
                destFile
            }
        }
    }
}
