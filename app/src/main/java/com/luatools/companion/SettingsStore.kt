package com.luatools.companion

import android.content.Context
import android.net.Uri
import com.luatools.companion.bridge.CompanionBridgeProvider

enum class TargetPlatform(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val supported: Boolean
) {
    BANNERHUB("bannerhub", "BannerHub", "GameHub 5.3.5 ReVanced (Bridge + SAF Fallback)", true),
    GAMEHUB("gamehub", "GameHub", "Coming soon", false),
    WINNATIVE("winnative", "GameNative", "JavaSteam Bridge + CloudRedirect", true),
    WINLATOR("winlator", "Winlator", "Coming soon", false)
}

/**
 * Unified settings store merging LuaTools options and CloudRedirect options
 * into a single configuration surface, with per-emulator SAF persistence.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(CompanionBridgeProvider.PREFS_NAME, Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()

    var selectedPlatform: TargetPlatform
        get() {
            val id = prefs.getString("selected_platform", TargetPlatform.BANNERHUB.id)
            return TargetPlatform.entries.firstOrNull { it.id == id } ?: TargetPlatform.BANNERHUB
        }
        set(v) = prefs.edit().putString("selected_platform", v.id).apply()

    var selectedPackageName: String?
        get() = prefs.getString("selected_package_name", null)
        set(v) = prefs.edit().putString("selected_package_name", v).apply()

    fun getSafUri(appKey: String): Uri? =
        prefs.getString("saf_uri_$appKey", null)?.let { Uri.parse(it) }

    fun setSafUri(appKey: String, uri: Uri?) {
        prefs.edit().putString("saf_uri_$appKey", uri?.toString()).apply()
    }

    // ── LuaTools Options ───────────────────────────────────────────────
    var autoUpdateApps: Boolean
        get() = prefs.getBoolean("lt_auto_update_apps", true)
        set(v) = prefs.edit().putBoolean("lt_auto_update_apps", v).apply()

    var downloadFolder: String
        get() = prefs.getString("lt_download_folder", "/storage/emulated/0/Games/LuaTools")
            ?: "/storage/emulated/0/Games/LuaTools"
        set(v) = prefs.edit().putString("lt_download_folder", v).apply()

    var hubcapApiKey: String
        get() = prefs.getString("lt_hubcap_key", "") ?: ""
        set(v) = prefs.edit().putString("lt_hubcap_key", v).apply()

    // ── CloudRedirect Options ──────────────────────────────────────────
    var cloudProvider: String
        get() = prefs.getString(CompanionBridgeProvider.KEY_PROVIDER, "gdrive") ?: "gdrive"
        set(v) = prefs.edit().putString(CompanionBridgeProvider.KEY_PROVIDER, v).apply()

    var cloudAccountId: String
        get() = prefs.getString(CompanionBridgeProvider.KEY_ACCOUNT_ID, "0") ?: "0"
        set(v) = prefs.edit().putString(CompanionBridgeProvider.KEY_ACCOUNT_ID, v).apply()

    var autoSyncOnLaunch: Boolean
        get() = prefs.getBoolean("cr_auto_sync", true)
        set(v) = prefs.edit().putBoolean("cr_auto_sync", v).apply()

    // ── Multi-Platform & Cross-App Sync ─────────────────────────────────
    var syncAllConnectedApps: Boolean
        get() = prefs.getBoolean("lt_sync_all_apps", false)
        set(v) = prefs.edit().putBoolean("lt_sync_all_apps", v).apply()

    var addedPlatformIds: Set<String>
        get() = prefs.getStringSet("lt_added_platforms", null)
            ?: setOf(selectedPlatform.id)
        set(v) = prefs.edit().putStringSet("lt_added_platforms", v).apply()

    fun addPlatform(platformId: String) {
        addedPlatformIds = addedPlatformIds + platformId
    }

    fun removePlatform(platformId: String) {
        addedPlatformIds = addedPlatformIds - platformId
    }

    fun getAppSyncedIds(pkg: String): Set<Long> =
        prefs.getStringSet("lt_app_games_$pkg", emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun addAppSyncedId(pkg: String, appId: Long) {
        val current = prefs.getStringSet("lt_app_games_$pkg", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("lt_app_games_$pkg", current + appId.toString()).apply()
    }

    fun removeAppSyncedId(pkg: String, appId: Long) {
        val current = prefs.getStringSet("lt_app_games_$pkg", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("lt_app_games_$pkg", current - appId.toString()).apply()
    }

    // ── Cloud Games Cache ───────────────────────────────────────────────
    fun hasCachedCloudScan(providerId: String): Boolean =
        prefs.contains("cr_cached_games_$providerId")

    fun getCachedCloudGames(providerId: String): List<Pair<String, String>> {
        val raw = prefs.getString("cr_cached_games_$providerId", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val acc = o.optString("account_id")
                    val app = o.optString("app_id")
                    if (app.isNotBlank()) add(acc to app)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setCachedCloudGames(providerId: String, games: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        games.forEach { (acc, app) ->
            arr.put(org.json.JSONObject().put("account_id", acc).put("app_id", app))
        }
        prefs.edit().putString("cr_cached_games_$providerId", arr.toString()).apply()
    }

    fun upsertCachedCloudGame(providerId: String, accountId: String, appId: String) {
        val existing = getCachedCloudGames(providerId).toMutableList()
        if (existing.none { it.second == appId && it.first == accountId }) {
            existing.add(accountId to appId)
            setCachedCloudGames(providerId, existing)
        }
    }
}
