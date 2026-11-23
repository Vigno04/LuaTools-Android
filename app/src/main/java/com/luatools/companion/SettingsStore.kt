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
    WINNATIVE("winnative", "WinNative / GameNative", "Coming soon", false),
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
}
