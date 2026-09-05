package com.cadence.music.data

/** Entry id is the segment before the FIRST ':'; null when absent. */
fun entryIdOf(serverId: String): String? =
    serverId.substringBefore(':').takeIf { serverId.contains(':') }

/** Namespaces a source-level remote key under an entry id. */
fun namespacedKey(entryId: String, remoteKey: String): String = "$entryId:$remoteKey"

/**
 * Resume ids to try in order: the raw id, plus the "primary:"-prefixed form
 * when the raw prefix isn't a known entry (legacy pre-v9 ids).
 */
fun resumeLookupIds(rawId: String, knownIds: Set<String>): List<String> {
    val prefix = rawId.substringBefore(':')
    return if (rawId.contains(':') && prefix in knownIds) listOf(rawId)
    else listOf(rawId, "primary:$rawId")
}

/** The exact v9 prefix UPDATEs (single-server legacy rows gain "primary:"). */
fun legacyPrefixSql(): List<String> = listOf(
    "UPDATE tracks SET serverId = 'primary:' || serverId WHERE sourceId != 'local'",
    "UPDATE tracks SET albumKey = 'primary:' || albumKey WHERE albumKey IS NOT NULL AND sourceId != 'local'",
    "UPDATE albums SET serverId = 'primary:' || serverId",
    "UPDATE downloads SET trackServerId = 'primary:' || trackServerId",
)

/** Display predicate: local rows always pass; server rows need their entry active. */
fun isEntryActive(sourceId: String, serverId: String, activeIds: Set<String>): Boolean {
    if (sourceId == "local") return true
    return serverId.substringBefore(':') in activeIds
}
