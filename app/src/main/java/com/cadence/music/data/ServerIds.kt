package com.cadence.music.data

/** Entry id is the segment before the FIRST ':'; null when absent. */
fun entryIdOf(serverId: String): String? =
    serverId.substringBefore(':').takeIf { serverId.contains(':') }

/** Display predicate: local rows always pass; server rows need their entry active. */
fun isEntryActive(sourceId: String, serverId: String, activeIds: Set<String>): Boolean {
    if (sourceId == "local") return true
    return serverId.substringBefore(':') in activeIds
}
