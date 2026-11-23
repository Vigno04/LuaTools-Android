package com.luatools.companion.luatools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads BetterSteamTools ("bst") or OpenSteamTools Nightly ("ost") packs
 * from GitHub into app-private storage so they can be pushed over the
 * ContentProvider bridge to any target emulator (BannerHub, GameNative, Winlator).
 */
object SteamEmuDownloader {

    private const val BST_MANIFEST_URL =
        "https://raw.githubusercontent.com/madoiscool/BetterSteamTools/refs/heads/updates/opensteamtool/latest.toml"
    private const val OST_RELEASES_URL =
        "https://api.github.com/repos/madoiscool/OST-Nightly/releases/latest"

    private val REQUIRED_FILES = setOf("OpenSteamTool.dll", "dwmapi.dll", "xinput1_4.dll")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun packDir(context: Context, mode: String): File {
        val clean = if (mode.equals("ost", ignoreCase = true)) "ost" else "bst"
        return File(context.filesDir, "steam_emu/$clean").apply { if (!exists()) mkdirs() }
    }

    fun isPackCached(context: Context, mode: String): Boolean {
        val dir = packDir(context, mode)
        return REQUIRED_FILES.all { File(dir, it).isFile }
    }

    suspend fun downloadPack(context: Context, mode: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = if (mode.equals("ost", ignoreCase = true)) "ost" else "bst"
            val zipUrl = if (clean == "bst") resolveBstZipUrl() else resolveOstZipUrl()
            val outDir = packDir(context, clean)

            val req = Request.Builder()
                .url(zipUrl)
                .header("User-Agent", "LuaTools-Companion")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} downloading $zipUrl")
                val body = resp.body ?: error("Empty response downloading pack")
                var extracted = 0
                ZipInputStream(body.byteStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val base = File(entry.name).name
                            val match = REQUIRED_FILES.firstOrNull { it.equals(base, ignoreCase = true) }
                            if (match != null) {
                                val outFile = File(outDir, match)
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                extracted++
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (extracted < REQUIRED_FILES.size) {
                    error("Pack zip missing required DLLs (extracted $extracted/${REQUIRED_FILES.size})")
                }
            }
            outDir
        }
    }

    private fun resolveBstZipUrl(): String {
        val req = Request.Builder()
            .url(BST_MANIFEST_URL)
            .header("User-Agent", "LuaTools-Companion")
            .build()
        val toml = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching BST manifest")
            resp.body?.string() ?: error("Empty BST manifest")
        }
        val version = Regex("""version\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
            ?: error("Could not parse BST version from latest.toml")
        return "https://github.com/madoiscool/BetterSteamTools/releases/download/$version/OpenSteamTool-$version-Release.zip"
    }

    private fun resolveOstZipUrl(): String {
        val req = Request.Builder()
            .url(OST_RELEASES_URL)
            .header("User-Agent", "LuaTools-Companion")
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching OST-Nightly release")
            JSONObject(resp.body?.string() ?: error("Empty OST release"))
        }
        val assets = json.optJSONArray("assets") ?: error("No assets in OST release")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.endsWith(".zip", ignoreCase = true) && name.contains("OpenSteamTool", ignoreCase = true)) {
                return a.getString("browser_download_url")
            }
        }
        error("No OpenSteamTool zip asset found in OST-Nightly")
    }
}
