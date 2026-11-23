package com.luatools.companion.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.documentfile.provider.DocumentFile

enum class ConnectionMode {
    FULLY_CONNECTED,
    MANUAL_FALLBACK,
    NOT_INSTALLED
}

data class TargetAppStatus(
    val appName: String,
    val packageName: String?,
    val mode: ConnectionMode,
    val bridgeVersion: Int = 0,
    val mtAuthority: String? = null,
    val detail: String
)

data class SafValidationResult(
    val isValid: Boolean,
    val folderName: String,
    val message: String
)

/**
 * Probes BannerHub (and future target apps like GameHub / WinNative / Winlator)
 * to determine whether the modified auto-sync bridge is present or if the
 * Companion App should operate in Manual Fallback mode (SAF / MTDataFilesProvider).
 */
object TargetAppBridge {

    private val bannerHubPackages = listOf(
        "banner.hub",
        "gamehub.lite",
        "app.gamenative",
        "app.pluvia",
        "com.winlator",
        "com.tencent.ig",
        "com.antutu.ABenchMark",
        "com.antutu.benchmark.full",
        "com.tencent.tmgp.cf",
        "com.ludashi.aibench",
        "com.miHoYo.GenshinImpact",
        "com.xiaoji.egggame"
    )

    fun probeAllBannerHub(context: Context): List<TargetAppStatus> {
        val pm = context.packageManager
        return bannerHubPackages
            .filter { isInstalled(pm, it) }
            .map { probePackage(context, it) }
    }

    fun probeBannerHub(context: Context, preferredPackage: String? = null): TargetAppStatus {
        val all = probeAllBannerHub(context)
        if (all.isEmpty()) {
            return TargetAppStatus(
                appName = "BannerHub",
                packageName = null,
                mode = ConnectionMode.NOT_INSTALLED,
                detail = "BannerHub not detected on this device"
            )
        }
        if (!preferredPackage.isNullOrBlank()) {
            all.firstOrNull { it.packageName == preferredPackage }?.let { return it }
        }
        return all.firstOrNull { it.mode == ConnectionMode.FULLY_CONNECTED } ?: all.first()
    }

    private fun probePackage(context: Context, pkg: String): TargetAppStatus {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        try {
            val res: Bundle? = context.contentResolver.call(bridgeUri, "ping", null, null)
            if (res != null && res.getBoolean("ok", false)) {
                val version = res.getInt("bridge_version", 1)
                val mtAuth = res.getString("mt_authority")
                return TargetAppStatus(
                    appName = res.getString("app_name", "BannerHub"),
                    packageName = pkg,
                    mode = ConnectionMode.FULLY_CONNECTED,
                    bridgeVersion = version,
                    mtAuthority = mtAuth,
                    detail = "Bridge v$version connected ($pkg) — Auto Sync active"
                )
            }
        } catch (_: Exception) {
            // Unmodified BannerHub or provider not exported -> fall through to Manual Fallback
        }

        val fallbackMtAuth = "$pkg.app.revanced.extension.gamehub.filemanager.MTDataFilesProvider"
        return TargetAppStatus(
            appName = "BannerHub",
            packageName = pkg,
            mode = ConnectionMode.MANUAL_FALLBACK,
            mtAuthority = fallbackMtAuth,
            detail = "Bridge not present in $pkg — using Manual Fallback (SAF / MTDataFilesProvider)"
        )
    }

    fun validateContainerSafUri(
        context: Context,
        uri: Uri,
        expectedMtAuthority: String?
    ): SafValidationResult {
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: return SafValidationResult(
                isValid = false,
                folderName = uri.lastPathSegment ?: "Unknown",
                message = "Could not open selected folder. Please select it again."
            )

        val name = doc.name ?: uri.lastPathSegment ?: "Selected Folder"
        val children = runCatching { doc.listFiles().mapNotNull { it.name?.lowercase() }.toSet() }
            .getOrDefault(emptySet())

        val authorityMatches = expectedMtAuthority != null &&
            uri.authority.equals(expectedMtAuthority, ignoreCase = true)

        val hasContainerMarkers = children.any {
            it == "files" || it == "drive_c" || it == "usr" || it == "home" ||
                it == "shared_prefs" || it == "containers" || it == "wine" || it == "pfx"
        }

        return when {
            authorityMatches && hasContainerMarkers -> SafValidationResult(
                isValid = true,
                folderName = name,
                message = "Valid BannerHub container folder selected ($name)."
            )
            hasContainerMarkers -> SafValidationResult(
                isValid = true,
                folderName = name,
                message = "Container folder detected ($name)."
            )
            authorityMatches -> SafValidationResult(
                isValid = true,
                folderName = name,
                message = "BannerHub MTDataFilesProvider linked ($name). Tip: selecting 'data' or 'files' is recommended."
            )
            else -> SafValidationResult(
                isValid = false,
                folderName = name,
                message = "'$name' does not look like a BannerHub container (expected 'data', 'files', or 'drive_c' inside MTDataFilesProvider)."
            )
        }
    }

    fun listPrefixes(context: Context, pkg: String): List<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            val res = context.contentResolver.call(bridgeUri, "list_prefixes", null, null)
            res?.getStringArrayList("prefixes") ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pushLuaGame(
        context: Context,
        pkg: String,
        appId: Long,
        name: String,
        luaContent: String,
        depotCacheDir: java.io.File? = null
    ): Result<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            // Stream manifests over ContentProvider openFile() to avoid Android's 1 MB Binder limit
            if (depotCacheDir != null && depotCacheDir.isDirectory) {
                val entries = com.luatools.companion.luatools.LuaFileParser.parse(luaContent)
                for (entry in entries) {
                    val manId = entry.manifestId ?: continue
                    val manFile = java.io.File(depotCacheDir, "${entry.appId}_$manId.manifest")
                    if (manFile.isFile && manFile.length() > 0) {
                        val manUri = Uri.parse("content://$pkg.bh_lua_bridge/manifest_in/${entry.appId}_$manId")
                        runCatching {
                            context.contentResolver.openOutputStream(manUri, "wt")?.use { os ->
                                manFile.inputStream().use { ins -> ins.copyTo(os) }
                            }
                        }
                    }
                }
            }
            val extras = Bundle().apply {
                putInt("app_id", appId.toInt())
                putString("name", name)
                putString("lua_content", luaContent)
            }
            val res = context.contentResolver.call(bridgeUri, "add_steam_game", appId.toString(), extras)
                ?: return Result.failure(IllegalStateException("No response from BannerHub bridge"))
            if (res.getBoolean("ok", false)) {
                val manifestsCached = res.getInt("manifests_cached", 0)
                val path = res.getString("path", "Sent to BannerHub")
                Result.success("$path (manifests cached: $manifestsCached)")
            } else {
                Result.failure(IllegalStateException(res.getString("error", "BannerHub bridge error")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun applyFix(
        context: Context,
        pkg: String,
        appId: Long,
        gameName: String,
        fixZipFile: java.io.File,
        fixVersion: String = "1.0.0"
    ): Result<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        val uploadUri = Uri.parse("content://$pkg.bh_lua_bridge/fix_in/$appId")
        return try {
            val out = context.contentResolver.openOutputStream(uploadUri, "wt")
                ?: return Result.failure(IllegalStateException("Target bridge does not support fix streaming"))
            out.use { os ->
                fixZipFile.inputStream().use { ins -> ins.copyTo(os) }
            }
            val extras = Bundle().apply {
                putInt("app_id", appId.toInt())
                putString("name", gameName)
                putString("fix_version", fixVersion)
            }
            val res = context.contentResolver.call(bridgeUri, "apply_fix", appId.toString(), extras)
                ?: return Result.failure(IllegalStateException("No response from BannerHub bridge"))
            if (res.getBoolean("ok", false)) {
                val files = res.getInt("extracted_files", 0)
                val overrides = res.getString("dll_overrides", "").orEmpty()
                val dir = res.getString("game_dir", "")
                val ovPart = if (overrides.isNotBlank()) " | DLL overrides: $overrides" else ""
                Result.success("Applied fix ($files files -> $dir$ovPart)")
            } else {
                Result.failure(IllegalStateException(res.getString("error", "apply_fix failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removeFix(
        context: Context,
        pkg: String,
        appId: Long
    ): Result<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            val extras = Bundle().apply { putInt("app_id", appId.toInt()) }
            val res = context.contentResolver.call(bridgeUri, "remove_fix", appId.toString(), extras)
                ?: return Result.failure(IllegalStateException("No response from bridge"))
            if (res.getBoolean("ok", false)) {
                val removed = res.getInt("removed_files", 0)
                Result.success("Removed fix ($removed file(s) removed)")
            } else {
                Result.failure(IllegalStateException(res.getString("error", "remove_fix failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getInstalledFixes(context: Context, pkg: String): Map<String, String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            val res = context.contentResolver.call(bridgeUri, "fix_status", null, null) ?: return emptyMap()
            if (!res.getBoolean("ok", false)) return emptyMap()
            val obj = org.json.JSONObject(res.getString("installed_json", "{}"))
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "1.0.0")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getBridgeStatus(context: Context, pkg: String): Result<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            val res = context.contentResolver.call(bridgeUri, "bridge_status", null, null)
                ?: return Result.failure(IllegalStateException("No response from bridge"))
            if (res.getBoolean("ok", false)) {
                Result.success(res.getString("result_json", "{}"))
            } else {
                Result.failure(IllegalStateException(res.getString("error", "bridge_status failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setSteamEmu(
        context: Context,
        pkg: String,
        mode: String? = null,
        realSteam: Boolean? = null,
        autoCloseWine: Boolean? = null
    ): Result<String> {
        val bridgeUri = Uri.parse("content://$pkg.bh_lua_bridge")
        return try {
            val extras = Bundle().apply {
                if (mode != null) putString("mode", mode)
                if (realSteam != null) putBoolean("real_steam", realSteam)
                if (autoCloseWine != null) putBoolean("autoclose_wine", autoCloseWine)
            }
            val res = context.contentResolver.call(bridgeUri, "set_steam_emu", null, extras)
                ?: return Result.failure(IllegalStateException("No response from bridge"))
            if (res.getBoolean("ok", false)) {
                Result.success(res.getString("result_json", "{}"))
            } else {
                Result.failure(IllegalStateException(res.getString("error", "set_steam_emu failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pushSteamEmuPack(
        context: Context,
        pkg: String,
        mode: String,
        packDir: java.io.File
    ): Result<Int> {
        val required = listOf("OpenSteamTool.dll", "dwmapi.dll", "xinput1_4.dll")
        return try {
            var count = 0
            for (name in required) {
                val f = java.io.File(packDir, name)
                if (!f.isFile) {
                    return Result.failure(IllegalStateException("Missing $name in ${packDir.absolutePath}"))
                }
                val uri = Uri.parse("content://$pkg.bh_lua_bridge/pack_in/$mode/$name")
                val out = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return Result.failure(IllegalStateException("Target bridge does not support pack_in streaming"))
                out.use { os -> f.inputStream().use { ins -> ins.copyTo(os) } }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
}
