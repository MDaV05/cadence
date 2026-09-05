package com.cadence.music.data.tags

private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\b.*""", RegexOption.IGNORE_CASE)
private val SPLIT = Regex("""\s*(?:&|\band\b|/|,|;|\bwith\b|×)\s*""", RegexOption.IGNORE_CASE)
private val TRAILING_PAREN = Regex("""\s*\([^()]*\)\s*$""")
private val ARTICLES = Regex("""^(a|an|the)\s+""", RegexOption.IGNORE_CASE)

/** First-mentioned artist wins: "Future and Drake" → "Future". Trims parens + whitespace. */
fun primaryArtist(raw: String): String {
    var s = raw.trim().replace(TRAILING_PAREN, "").trim()
    s = FEAT.replace(s, "")
    // No whitespace = one token ("AC/DC"); splitting it mangles real band names.
    if (s.none { it.isWhitespace() }) return s.ifEmpty { raw.trim() }
    val first = SPLIT.split(s).firstOrNull()?.trim().orEmpty()
    return first.ifEmpty { raw.trim() }
}

/** Grouping key: lowercase, collapsed spaces, no leading article, no trailing (...) edition tag. */
fun albumNormKey(album: String, artist: String): String {
    var a = album.trim().replace(TRAILING_PAREN, "").trim()
    a = a.replace(Regex("""\s+"""), " ").lowercase().replace(ARTICLES, "")
    return "$a::${primaryArtist(artist).lowercase()}"
}
