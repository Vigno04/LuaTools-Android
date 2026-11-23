package com.luatools.companion.luatools

/**
 * Mirrors LuaToolsGui/AppConfig.cs so upstream LuaTools endpoint or constant
 * updates can be ported 1:1.
 */
object AppConfig {
    const val SUPABASE_URL = "https://db.lua.tools"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3NzYwMzkzNzYsImV4cCI6MTg5MzQ1NjAwMCwicm9sZSI6ImFub24iLCJpc3MiOiJzdXBhYmFzZSJ9.f_-K38u3odjltP-g_67FVmG32Vg-_-k-lNBvIaVUVBM"

    const val API_BASE_URL = "https://lua.tools"
    const val HUBCAP_BASE_URL = "https://hubcapmanifest.com"
    const val DAILY_DOWNLOAD_LIMIT = 25

    const val STEAM_STORE_SEARCH_URL = "https://store.steampowered.com/api/storesearch/"
    const val STEAM_FEATURED_URL = "https://store.steampowered.com/api/featuredcategories"

    const val MANIFEST_BACKEND_URL = "http://167.235.229.108"
    const val MANIFEST_BACKEND_USER_AGENT = "secretgoonpoon"
    const val DONATE_KEYS_USER_AGENT = "discord(dot)gg/luatools"

    const val CLOUD_REDIRECT_REPO = "Vigno04/CloudRedirect"
}
