package com.cadence.music.data.tags

private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\b.*""", RegexOption.IGNORE_CASE)
private val TRAILING_PAREN = Regex("""\s*\([^()]*\)\s*$""")
private val ARTICLES = Regex("""^(a|an|the)\s+""", RegexOption.IGNORE_CASE)

/** Drops a "feat. ..." clause and a trailing "(...)" edition tag.
 *  Never splits on separators — "Simon & Garfunkel" and "Earth, Wind & Fire" are
 *  single artists and no heuristic can tell them from a collab ("A & B"). */
fun primaryArtist(raw: String): String {
    val s = raw.trim().replace(TRAILING_PAREN, "").trim()
    val stripped = FEAT.replace(s, "").trim()
    return stripped.ifEmpty { raw.trim() }
}

/** Grouping key: lowercase, collapsed spaces, no leading article, no trailing (...) edition tag. */
fun albumNormKey(album: String, artist: String): String {
    var a = album.trim().replace(TRAILING_PAREN, "").trim()
    a = a.replace(Regex("""\s+"""), " ").lowercase().replace(ARTICLES, "")
    return "$a::${primaryArtist(artist).lowercase()}"
}
