package com.luatools.companion.luatools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.luatools.companion.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class InstalledLuaGame(
    val appId: Long,
    val name: String,
    val cachedManifests: Int,
    val expectedWindowsManifests: Int
)

data class GameUpdateStatus(
    val appId: Long,
    val hasUpdate: Boolean,
    val missingManifests: Int,
    val latestWindowsGids: Map<Long, String>,
    val summary: String
)

/**
 * Mirrors LuaToolsGui/Services/LuaInstaller.cs.
 * Extracts downloaded .lua / .manifest zips into <downloadFolder>/stplug-in & depotcache,
 * and optionally mirrors them into a linked BannerHub SAF container if Steam's
 * config/stplug-in directory is present inside the container.
 */
class LuaInstaller(
    private val context: Context,
    private val settings: SettingsStore
) {
    private val setManifestRegex = Regex("""^(\s*)(setManifestid\s*\()""", RegexOption.IGNORE_CASE)

    fun listInstalledLuaApps(): List<Long> {
        val dir = File(settings.downloadFolder, "stplug-in")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("lua", ignoreCase = true) }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    fun listInstalledLuaGames(): List<InstalledLuaGame> {
        val baseDir = File(settings.downloadFolder)
        val stDir = File(baseDir, "stplug-in")
        val depotCacheDir = File(baseDir, "depotcache")
        if (!stDir.isDirectory) return emptyList()
        return listInstalledLuaApps().map { appId ->
            val luaFile = File(stDir, "$appId.lua")
            val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val name = parseGameName(appId, content)
            val entries = LuaFileParser.parse(content).filter {
                it.manifestId != null && !isNonWindowsDepot(content, it.appId)
            }
            val cached = entries.count { entry ->
                val f = File(depotCacheDir, "${entry.appId}_${entry.manifestId}.manifest")
                f.isFile && f.length() > 0L
            }
            InstalledLuaGame(
                appId = appId,
                name = name,
                cachedManifests = cached,
                expectedWindowsManifests = entries.size
            )
        }
    }

    fun parseGameName(appId: Long, luaContent: String): String {
        val m = Regex("""^--\s*$appId\s*-\s*(.+)$""", RegexOption.MULTILINE).find(luaContent)
        val found = m?.groupValues?.getOrNull(1)?.trim()
        return if (!found.isNullOrEmpty()) found else "AppID $appId"
    }

    fun isNonWindowsDepot(luaContent: String, depotId: Long): Boolean {
        val m = Regex("""addappid\(\s*$depotId\b[^\n]*?--\s*\(([^)]*)\)""").find(luaContent)
            ?: return false
        val tag = m.groupValues[1].lowercase()
        return !tag.contains("windows") && (tag.contains("linux") || tag.contains("mac"))
    }

    /**
     * Free update check using SteamCMD's public PICS endpoint (api.steamcmd.net),
     * matching desktop LuaTools' SteamDepotInfo.cs — consumes ZERO LuaTools API quota.
     */
    suspend fun checkGameUpdateAsync(appId: Long): GameUpdateStatus = withContext(Dispatchers.IO) {
        val baseDir = File(settings.downloadFolder)
        val luaFile = File(baseDir, "stplug-in/$appId.lua")
        val depotCacheDir = File(baseDir, "depotcache")
        if (!luaFile.isFile) {
            return@withContext GameUpdateStatus(appId, false, 0, emptyMap(), "Not installed locally")
        }
        val content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val entries = LuaFileParser.parse(content).filter { !isNonWindowsDepot(content, it.appId) }
        val localManifests = entries.mapNotNull { e -> e.manifestId?.let { e.appId to it } }.toMap()
        val missingLocal = localManifests.count { (depotId, gid) ->
            val f = File(depotCacheDir, "${depotId}_$gid.manifest")
            !f.isFile || f.length() <= 0L
        }

        val latestGids = mutableMapOf<Long, String>()
        var outdatedCount = 0
        try {
            val conn = (URL("https://api.steamcmd.net/v1/info/$appId").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val appObj = JSONObject(body).optJSONObject("data")?.optJSONObject(appId.toString())
                    val depots = appObj?.optJSONObject("depots")
                    if (depots != null) {
                        for ((depotId, localGid) in localManifests) {
                            val d = depots.optJSONObject(depotId.toString()) ?: continue
                            val oslist = d.optJSONObject("config")?.optString("oslist", "").orEmpty().lowercase()
                            if (oslist.isNotEmpty() && !oslist.contains("windows")) continue
                            val pubGid = d.optJSONObject("manifests")?.optJSONObject("public")?.optString("gid", "").orEmpty()
                            if (pubGid.isNotEmpty()) {
                                latestGids[depotId] = pubGid
                                if (pubGid != localGid) outdatedCount++
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            return@withContext GameUpdateStatus(
                appId = appId,
                hasUpdate = missingLocal > 0,
                missingManifests = missingLocal,
                latestWindowsGids = emptyMap(),
                summary = if (missingLocal > 0) "$missingLocal manifest(s) missing locally" else "Up to date (offline check)"
            )
        }

        val hasUpdate = outdatedCount > 0 || missingLocal > 0
        val summary = when {
            outdatedCount > 0 && missingLocal > 0 -> "$outdatedCount depot update(s), $missingLocal missing"
            outdatedCount > 0 -> "$outdatedCount depot update(s) available"
            missingLocal > 0 -> "$missingLocal manifest(s) missing locally"
            else -> "Up to date"
        }
        GameUpdateStatus(appId, hasUpdate, missingLocal, latestGids, summary)
    }

    /**
     * Updates setManifestid(depotId, "oldGid") pins in stplug-in/<appId>.lua to latestGids.
     */
    fun applyUpdatedManifestPins(appId: Long, latestGids: Map<Long, String>): Boolean {
        if (latestGids.isEmpty()) return false
        val luaFile = File(settings.downloadFolder, "stplug-in/$appId.lua")
        if (!luaFile.isFile) return false
        var content = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
        var changed = false
        for ((depotId, newGid) in latestGids) {
            val pat = Regex("""(setManifestid\(\s*$depotId\s*,\s*")(\d+)(")""")
            val replaced = pat.replace(content) { m ->
                if (m.groupValues[2] != newGid) {
                    changed = true
                    "${m.groupValues[1]}$newGid${m.groupValues[3]}"
                } else {
                    m.value
                }
            }
            content = replaced
        }
        if (changed) luaFile.writeText(content, Charsets.UTF_8)
        return changed
    }

    fun removeInstalledLua(appId: Long): Boolean {
        val file = File(settings.downloadFolder, "stplug-in/$appId.lua")
        return !file.exists() || file.delete()
    }

    suspend fun installZip(
        zipFile: File,
        appId: Long,
        forceLocked: Boolean = false,
        safUri: Uri? = null
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(settings.downloadFolder)
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                return@withContext InstallResult(
                    luaInstalled = false,
                    manifestCount = 0,
                    targetPaths = emptyList(),
                    error = "Cannot create ${baseDir.absolutePath} (check Storage Permission)"
                )
            }
            val stPlugInDir = File(baseDir, "stplug-in").apply { mkdirs() }
            val depotCacheDir = File(baseDir, "depotcache").apply { mkdirs() }

            var luaInstalled = false
            var manifestCount = 0
            val targets = mutableListOf<String>()
            val extractedFiles = mutableListOf<Pair<String, ByteArray>>()

            val fileBytes = zipFile.readBytes()
            val isZip = fileBytes.size >= 2 &&
                fileBytes[0] == 0x50.toByte() &&
                fileBytes[1] == 0x4B.toByte()

            if (isZip) {
                ZipInputStream(fileBytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = File(entry.name).name
                            val bytes = zis.readBytes()
                            when {
                                fileName.endsWith(".lua", ignoreCase = true) -> {
                                    val content = String(bytes, Charsets.UTF_8)
                                    val processed = if (settings.autoUpdateApps && !forceLocked) {
                                        commentOutManifestPins(content)
                                    } else {
                                        content
                                    }
                                    val outFile = File(stPlugInDir, "$appId.lua")
                                    outFile.writeText(processed, Charsets.UTF_8)
                                    targets += outFile.absolutePath
                                    extractedFiles += "stplug-in/$appId.lua" to processed.toByteArray(Charsets.UTF_8)
                                    luaInstalled = true
                                }
                                fileName.endsWith(".manifest", ignoreCase = true) -> {
                                    val outFile = File(depotCacheDir, fileName)
                                    outFile.writeBytes(bytes)
                                    targets += outFile.absolutePath
                                    extractedFiles += "depotcache/$fileName" to bytes
                                    manifestCount++
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                // Plain .lua response (e.g. Luie / direct lua source)
                val content = String(fileBytes, Charsets.UTF_8)
                if (content.contains("addappid", ignoreCase = true) ||
                    content.trimStart().startsWith("--")
                ) {
                    val processed = if (settings.autoUpdateApps && !forceLocked) {
                        commentOutManifestPins(content)
                    } else {
                        content
                    }
                    val outFile = File(stPlugInDir, "$appId.lua")
                    outFile.writeText(processed, Charsets.UTF_8)
                    targets += outFile.absolutePath
                    extractedFiles += "stplug-in/$appId.lua" to processed.toByteArray(Charsets.UTF_8)
                    luaInstalled = true
                }
            }

            if (!luaInstalled && manifestCount == 0) {
                return@withContext InstallResult(
                    luaInstalled = false,
                    manifestCount = 0,
                    targetPaths = emptyList(),
                    error = "Downloaded file (${fileBytes.size} bytes) contained no .lua or .manifest entries"
                )
            }

            if (safUri != null && extractedFiles.isNotEmpty()) {
                mirrorToBannerHubSaf(safUri, extractedFiles)?.let { targets += it }
            }

            InstallResult(
                luaInstalled = luaInstalled,
                manifestCount = manifestCount,
                targetPaths = targets
            )
        } catch (e: Exception) {
            InstallResult(
                luaInstalled = false,
                manifestCount = 0,
                targetPaths = emptyList(),
                error = e.message ?: "Install failed"
            )
        }
    }

    /**
     * Ensures every (depotId, manifestId) referenced in <downloadFolder>/stplug-in/<appId>.lua
     * has its .manifest file in <downloadFolder>/depotcache/<depotId>_<manifestId>.manifest.
     * Fetches missing manifests via LuaToolsApiClient.downloadDepotManifestAsync (/api/givemethemanifestpunk).
     */
    suspend fun ensureDepotManifests(
        appId: Long,
        apiClient: LuaToolsApiClient,
        safUri: Uri? = null
    ): Int = withContext(Dispatchers.IO) {
        val baseDir = File(settings.downloadFolder)
        val luaFile = File(baseDir, "stplug-in/$appId.lua")
        if (!luaFile.isFile) return@withContext 0

        val luaContent = runCatching { luaFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (luaContent.isBlank()) return@withContext 0

        val depotCacheDir = File(baseDir, "depotcache").apply { mkdirs() }
        val entries = LuaFileParser.parse(luaContent)
        var totalReady = 0
        val newlyFetched = mutableListOf<Pair<String, ByteArray>>()

        for (entry in entries) {
            val manifestId = entry.manifestId ?: continue
            if (isNonWindowsDepot(luaContent, entry.appId)) continue
            val fileName = "${entry.appId}_$manifestId.manifest"
            val manifestFile = File(depotCacheDir, fileName)
            if (manifestFile.isFile && manifestFile.length() > 0L) {
                totalReady++
                continue
            }
            val dl = apiClient.downloadDepotManifestAsync(entry.appId, manifestId, manifestFile)
            if (dl.isSuccess && manifestFile.isFile && manifestFile.length() > 0L) {
                totalReady++
                newlyFetched += "depotcache/$fileName" to manifestFile.readBytes()
            }
        }

        if (safUri != null && newlyFetched.isNotEmpty()) {
            runCatching { mirrorToBannerHubSaf(safUri, newlyFetched) }
        }
        totalReady
    }

    suspend fun extractFixZip(zipFile: File, appId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(settings.downloadFolder, "fixes/$appId").apply { mkdirs() }
            var count = 0
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Blocked zip path traversal: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.writeBytes(zis.readBytes())
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            "Extracted $count fix file(s) to ${destDir.absolutePath}"
        }
    }

    private fun commentOutManifestPins(lua: String): String {
        return lua.lineSequence().joinToString("\n") { line ->
            if (line.trimStart().startsWith("--")) line
            else setManifestRegex.replace(line, "$1-- $2")
        }
    }

    private fun mirrorToBannerHubSaf(
        safUri: Uri,
        files: List<Pair<String, ByteArray>>
    ): String? {
        val root = DocumentFile.fromTreeUri(context, safUri) ?: return null
        val steamDir = findSteamRoot(root, 0) ?: return null

        for ((relPath, bytes) in files) {
            val parts = relPath.split("/")
            var cur = steamDir
            for (i in 0 until parts.size - 1) {
                cur = cur.findFile(parts[i]) ?: cur.createDirectory(parts[i]) ?: return null
            }
            val fileName = parts.last()
            cur.findFile(fileName)?.delete()
            val doc = cur.createFile("application/octet-stream", fileName) ?: continue
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
        }
        return "Mirrored to BannerHub Steam dir (${steamDir.name})"
    }

    private fun findSteamRoot(dir: DocumentFile, depth: Int): DocumentFile? {
        if (depth > 4) return null
        val name = dir.name?.lowercase().orEmpty()
        if (name == "steam" && (dir.findFile("steamapps") != null || dir.findFile("config") != null)) {
            return dir
        }
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                findSteamRoot(child, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
