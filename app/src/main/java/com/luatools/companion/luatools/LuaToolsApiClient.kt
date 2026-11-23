package com.luatools.companion.luatools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Mirrors LuaToolsGui/Services/LuaToolsApiClient.cs.
 * Keep method names and endpoint behavior aligned with upstream LuaTools for easy porting.
 */
class LuaToolsApiClient(
    private val auth: AuthService? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {

    suspend fun searchAsync(query: String): List<SteamSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "${AppConfig.STEAM_STORE_SEARCH_URL}?term=$encoded&l=english&cc=US"
        val req = Request.Builder().url(url).get().build()

        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val body = res.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: return@withContext emptyList()

            buildList {
                val limit = minOf(items.length(), 15)
                for (i in 0 until limit) {
                    val item = items.getJSONObject(i)
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    val name = item.optString("name", "App $id")
                    val icon = item.optString("tiny_image")
                        .ifEmpty { "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/capsule_sm_120.jpg" }
                    add(SteamSearchResult(appId = id, name = name, iconUrl = icon))
                }
            }
        }
    }

    suspend fun checkSourcesAsync(appId: Long): List<SourceAvailability> = withContext(Dispatchers.IO) {
        val url = "${AppConfig.MANIFEST_BACKEND_URL}/check_apis?appid=$appId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.MANIFEST_BACKEND_USER_AGENT)
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val body = res.body?.string() ?: return@withContext emptyList()
            val obj = JSONObject(body)
            buildList {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    add(SourceAvailability(source = k, status = obj.optString(k, "unknown")))
                }
            }
        }
    }

    suspend fun downloadManifestZipAsync(
        appId: Long,
        source: String,
        gameName: String?,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth?.getValidAccessTokenAsync()
                ?: throw IllegalStateException("Sign in to LuaTools first to download manifests.")

            var url = "${AppConfig.API_BASE_URL}/api/manifest/download?appid=$appId&source=${URLEncoder.encode(source, "UTF-8")}"
            if (!gameName.isNullOrBlank()) {
                url += "&game_name=${URLEncoder.encode(gameName, "UTF-8")}"
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val errBody = res.body?.string().orEmpty()
                    throw IllegalStateException("Download failed (HTTP ${res.code}): $errBody")
                }
                val bytes = res.body?.bytes() ?: throw IllegalStateException("Empty download body")
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
                destFile
            }
        }
    }

    /**
     * Mirrors LuaToolsApiClient.DownloadDepotManifestAsync:
     * GET /api/givemethemanifestpunk/{depotId}/{manifestId}
     * Does not consume daily download cap; fetches raw .manifest bytes for a depot.
     */
    suspend fun downloadDepotManifestAsync(
        depotId: Long,
        manifestId: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth?.getValidAccessTokenAsync()
                ?: throw IllegalStateException("Sign in to LuaTools first to download depot manifests.")

            val url = "${AppConfig.API_BASE_URL}/api/givemethemanifestpunk/$depotId/$manifestId"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val errBody = res.body?.string().orEmpty()
                    throw IllegalStateException("Depot manifest fetch failed (HTTP ${res.code}): $errBody")
                }
                val bytes = res.body?.bytes() ?: throw IllegalStateException("Empty depot manifest body")
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
                destFile
            }
        }
    }

    // ── Denuvo / Game Fixes ─────────────────────────────────────────

    suspend fun getDenuvoListingsAsync(): List<DenuvoGameListing> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${AppConfig.API_BASE_URL}/api/denuvo/listings").get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val root = JSONObject(res.body?.string() ?: return@withContext emptyList())
            val games = root.optJSONArray("games") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until games.length()) {
                    val g = games.getJSONObject(i)
                    val tagsArr = g.optJSONArray("tags")
                    val tagNames = buildList {
                        if (tagsArr != null) {
                            for (t in 0 until tagsArr.length()) {
                                add(tagsArr.getJSONObject(t).optString("name"))
                            }
                        }
                    }
                    add(
                        DenuvoGameListing(
                            appId = g.optString("appid"),
                            name = g.optString("name"),
                            headerImage = g.optString("header_image").takeIf { it.isNotEmpty() },
                            fixCount = g.optInt("fixCount", 0),
                            tags = tagNames
                        )
                    )
                }
            }
        }
    }

    suspend fun getDenuvoFixesAsync(appId: String): List<DenuvoFix> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${AppConfig.API_BASE_URL}/api/denuvo/fixes?appid=${URLEncoder.encode(appId, "UTF-8")}")
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val root = JSONObject(res.body?.string() ?: return@withContext emptyList())
            val fixes = root.optJSONArray("fixes") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until fixes.length()) {
                    val f = fixes.getJSONObject(i)
                    add(
                        DenuvoFix(
                            id = f.optString("id"),
                            title = f.optString("title"),
                            description = f.optString("description").takeIf { it.isNotEmpty() },
                            hasManifest = f.optBoolean("hasManifest", false),
                            hasFix = f.optBoolean("hasFix", false),
                            manifestFilename = f.optString("manifestFilename").takeIf { it.isNotEmpty() },
                            fixFilename = f.optString("fixFilename").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }
        }
    }

    suspend fun downloadDenuvoAsync(
        fixId: String,
        slot: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth?.getValidAccessTokenAsync()
                ?: throw IllegalStateException("Sign in to LuaTools first to download fixes.")

            val url = "${AppConfig.API_BASE_URL}/api/denuvo/download?fix=${URLEncoder.encode(fixId, "UTF-8")}&slot=${URLEncoder.encode(slot, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val signedUrl = http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("Fix request failed (HTTP ${res.code}): $body")
                }
                JSONObject(body).optString("url")
            }
            require(signedUrl.isNotEmpty()) { "Empty signed download URL" }

            // Fetch from R2 signed URL without Authorization header
            val dlReq = Request.Builder().url(signedUrl).get().build()
            http.newCall(dlReq).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Fix download failed (HTTP ${res.code})")
                }
                val bytes = res.body?.bytes() ?: throw IllegalStateException("Empty fix file")
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
                destFile
            }
        }
    }
}
