package com.luatools.companion.luatools

/**
 * Mirrors LuaToolsGui/Models/ApiModels.cs & FixModels.cs.
 */
data class SteamSearchResult(
    val appId: Long,
    val name: String,
    val iconUrl: String
)

data class SourceAvailability(
    val source: String,
    val status: String
) {
    val isAvailable: Boolean
        get() = status.equals("available", ignoreCase = true)
}

data class ParsedLuaEntry(
    val appId: Long,
    val depotKey: String? = null,
    val manifestId: String? = null
)

data class DenuvoGameListing(
    val appId: String,
    val name: String,
    val headerImage: String?,
    val fixCount: Int,
    val tags: List<String> = emptyList()
)

data class DenuvoFix(
    val id: String,
    val title: String,
    val description: String?,
    val hasManifest: Boolean,
    val hasFix: Boolean,
    val manifestFilename: String?,
    val fixFilename: String?
)

data class InstallResult(
    val luaInstalled: Boolean,
    val manifestCount: Int,
    val targetPaths: List<String>,
    val error: String? = null
)
