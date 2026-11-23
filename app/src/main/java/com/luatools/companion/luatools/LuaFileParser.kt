package com.luatools.companion.luatools

/**
 * Mirrors LuaToolsGui/Services/LuaFileParser.cs for extracting addappid() and
 * setManifestid() entries from stplug-in .lua files.
 */
object LuaFileParser {
    private val addAppIdRegex = Regex("""addappid\(\s*(\d+)\s*(?:,\s*\d+\s*,\s*"([0-9a-fA-F]+)")?""")
    private val setManifestRegex = Regex("""setManifestid\(\s*(\d+)\s*,\s*"(\d+)"""")

    fun parse(luaContent: String): List<ParsedLuaEntry> {
        val depotKeys = mutableMapOf<Long, String?>()
        val manifests = mutableMapOf<Long, String>()

        for (rawLine in luaContent.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("--") && !line.contains("setManifestid", ignoreCase = true)) continue

            if (!line.startsWith("--")) {
                addAppIdRegex.find(line)?.let { m ->
                    val id = m.groupValues[1].toLongOrNull() ?: return@let
                    val key = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    depotKeys[id] = key
                }
            }

            setManifestRegex.find(line)?.let { m ->
                val depotId = m.groupValues[1].toLongOrNull() ?: return@let
                val manifestId = m.groupValues[2]
                manifests[depotId] = manifestId
            }
        }

        val allIds = (depotKeys.keys + manifests.keys).toSortedSet()
        return allIds.map { id ->
            ParsedLuaEntry(
                appId = id,
                depotKey = depotKeys[id],
                manifestId = manifests[id]
            )
        }
    }
}
