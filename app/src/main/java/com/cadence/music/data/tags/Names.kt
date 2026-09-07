package com.cadence.music.data.tags

private val KNOWN_ENSEMBLES: Map<String, String> = mapOf(
    "ac/dc" to "AC/DC",
    "simon & garfunkel" to "Simon & Garfunkel",
    "simon and garfunkel" to "Simon & Garfunkel",
    "earth, wind & fire" to "Earth, Wind & Fire",
    "earth, wind and fire" to "Earth, Wind & Fire",
    "earth wind & fire" to "Earth, Wind & Fire",
    "earth wind and fire" to "Earth, Wind & Fire",
    "crosby, stills, nash & young" to "Crosby, Stills, Nash & Young",
    "crosby, stills, nash and young" to "Crosby, Stills, Nash & Young",
    "crosby, stills & nash" to "Crosby, Stills & Nash",
    "crosby, stills and nash" to "Crosby, Stills & Nash",
    "blood, sweat & tears" to "Blood, Sweat & Tears",
    "blood, sweat and tears" to "Blood, Sweat & Tears",
    "emerson, lake & palmer" to "Emerson, Lake & Palmer",
    "emerson, lake and palmer" to "Emerson, Lake & Palmer",
    "hall & oates" to "Hall & Oates",
    "hall and oates" to "Hall & Oates",
    "daryl hall & john oates" to "Hall & Oates",
    "daryl hall and john oates" to "Hall & Oates",
    "kool & the gang" to "Kool & The Gang",
    "kool and the gang" to "Kool & The Gang",
    "brooks & dunn" to "Brooks & Dunn",
    "brooks and dunn" to "Brooks & Dunn",
    "kc & the sunshine band" to "KC & The Sunshine Band",
    "kc and the sunshine band" to "KC & The Sunshine Band",
    "sly & the family stone" to "Sly & The Family Stone",
    "sly and the family stone" to "Sly & The Family Stone",
    "tom petty and the heartbreakers" to "Tom Petty and the Heartbreakers",
    "tom petty & the heartbreakers" to "Tom Petty and the Heartbreakers",
    "bob marley & the wailers" to "Bob Marley & The Wailers",
    "bob marley and the wailers" to "Bob Marley & The Wailers",
    "florence + the machine" to "Florence + The Machine",
    "florence and the machine" to "Florence + The Machine",
    "king gizzard & the lizard wizard" to "King Gizzard & The Lizard Wizard",
    "king gizzard and the lizard wizard" to "King Gizzard & The Lizard Wizard",
    "mumford & sons" to "Mumford & Sons",
    "mumford and sons" to "Mumford & Sons",
    "huey lewis & the news" to "Huey Lewis & The News",
    "huey lewis and the news" to "Huey Lewis & The News",
    "joan jett & the blackhearts" to "Joan Jett & The Blackhearts",
    "joan jett and the blackhearts" to "Joan Jett & The Blackhearts",
    "gladys knight & the pips" to "Gladys Knight & The Pips",
    "gladys knight and the pips" to "Gladys Knight & The Pips",
    "diana ross & the supremes" to "Diana Ross & The Supremes",
    "diana ross and the supremes" to "Diana Ross & The Supremes",
    "toots & the maytals" to "Toots & The Maytals",
    "toots and the maytals" to "Toots & The Maytals",
    "echo & the bunnymen" to "Echo & The Bunnymen",
    "echo and the bunnymen" to "Echo & The Bunnymen",
    "siouxsie and the banshees" to "Siouxsie and the Banshees",
    "siouxsie & the banshees" to "Siouxsie and the Banshees",
    "peter, paul and mary" to "Peter, Paul and Mary",
    "peter, paul & mary" to "Peter, Paul and Mary",
    "katrina and the waves" to "Katrina and the Waves",
    "katrina & the waves" to "Katrina and the Waves",
    "captain & tennille" to "Captain & Tennille",
    "captain and tennille" to "Captain & Tennille",
    "peaches & herb" to "Peaches & Herb",
    "peaches and herb" to "Peaches & Herb",
    "sam & dave" to "Sam & Dave",
    "sam and dave" to "Sam & Dave",
    "ashford & simpson" to "Ashford & Simpson",
    "ashford and simpson" to "Ashford & Simpson",
    "chas & dave" to "Chas & Dave",
    "chas and dave" to "Chas & Dave",
    "above & beyond" to "Above & Beyond",
    "above and beyond" to "Above & Beyond",
    "the mamas & the papas" to "The Mamas & The Papas",
    "the mamas and the papas" to "The Mamas & The Papas",
    "mamas & the papas" to "The Mamas & The Papas",
    "mamas and the papas" to "The Mamas & The Papas",
    "angus & julia stone" to "Angus & Julia Stone",
    "angus and julia stone" to "Angus & Julia Stone",
    "iron & wine" to "Iron & Wine",
    "iron and wine" to "Iron & Wine",
    "arms and sleepers" to "Arms and Sleepers",
    "stars and rabbit" to "Stars and Rabbit",
    "she & him" to "She & Him",
    "she and him" to "She & Him",
    "tears for fears" to "Tears for Fears",
    "twenty one pilots" to "Twenty One Pilots",
    "panic! at the disco" to "Panic! At The Disco",
    "boyz ii men" to "Boyz II Men",
    "bell biv devoe" to "Bell Biv DeVoe",
    "tony! toni! tone!" to "Tony! Toni! Toné!",
    "tony! toni! toné!" to "Tony! Toni! Toné!",
    "me first and the gimme gimmes" to "Me First and the Gimme Gimmes",
    "of monsters and men" to "Of Monsters and Men",
    "bruce springsteen & the e street band" to "Bruce Springsteen & The E Street Band",
    "bruce springsteen and the e street band" to "Bruce Springsteen & The E Street Band",
    "bob dylan & the band" to "Bob Dylan & The Band",
    "bob dylan and the band" to "Bob Dylan & The Band",
    "prince & the revolution" to "Prince & The Revolution",
    "prince and the revolution" to "Prince & The Revolution",
    "prince & the new power generation" to "Prince & The New Power Generation",
    "prince and the new power generation" to "Prince & The New Power Generation",
    "alison krauss & union station" to "Alison Krauss & Union Station",
    "alison krauss and union station" to "Alison Krauss & Union Station",
    "ziggy marley & the melody makers" to "Ziggy Marley & The Melody Makers",
    "ziggy marley and the melody makers" to "Ziggy Marley & The Melody Makers",
)

private fun findKnownEnsemble(name: String): String? {
    val norm = name.trim().lowercase()
    if (norm.isEmpty()) return null
    KNOWN_ENSEMBLES[norm]?.let { return it }
    val withoutThe = norm.removePrefix("the ").trim()
    return KNOWN_ENSEMBLES[withoutThe]
}

// Matches [feat. ...], (feat. ...), [ft. ...], (ft ...), (featuring ...), (with ...), (w/ ...)
private val BRACKETED_FEAT = Regex(
    """\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|with|w/)\b[^\)\]]*[\)\]]""",
    RegexOption.IGNORE_CASE
)

// Matches trailing edition or meta tags like "(Deluxe Edition)", "[Remastered 2021]", etc.
private val TRAILING_BRACKET_TAG = Regex("""\s*[\(\[][^\)\]]*[\)\]]\s*$""")

// Matches inline " feat. ...", " ft. ...", " featuring ..."
private val INLINE_FEAT = Regex(
    """\s+(?:feat\.?|ft\.?|featuring)\b.*""",
    RegexOption.IGNORE_CASE
)

// Collaborator delimiters when NOT a known ensemble:
// - \u0000 (ID3 null-separated list)
// - Semicolon ';' (standard multi-artist separator)
// - Forward slash '/' (e.g. "Artist 1 / Artist 2" or "Artist 1/Artist 2")
// - Backslash '\'
// - Comma followed by space ', ' (e.g. "Artist 1, Artist 2")
// - Ampersand with spaces ' & ' or standalone surrounded by spaces
// - Plus with spaces ' + '
// - ' x ' or ' X ' or ' × ' with spaces
// - ' with ' or ' w/ ' or ' vs ' or ' vs. ' with spaces
// - ' and ' with spaces
private val COLLAB_SPLIT = Regex(
    """\u0000|\s*;\s*|\s*/\s*|\s*\\\s*|\s*,\s+|\s+&\s+|\s+\+\s+|\s+[xX×]\s+|\s+(?:with|w/|vs\.?)\s+|\s+and\s+""",
    RegexOption.IGNORE_CASE
)

private val ARTICLES = Regex("""^(a|an|the)\s+""", RegexOption.IGNORE_CASE)

/**
 * Resolves the primary/lead artist from a raw tag string.
 *
 * Strips features ("feat. B", "(ft. B)", "[with B]") and folds collaborations
 * ("A & B", "A, B", "A / B", "A x B") under the first artist so songs don't create
 * separate artist entries or split albums, while preserving genuine ensembles
 * like "Earth, Wind & Fire", "Simon & Garfunkel", and "AC/DC".
 */
fun primaryArtist(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    findKnownEnsemble(trimmed)?.let { return it }

    // 1. Strip bracketed or parenthesized feature clauses
    var s = BRACKETED_FEAT.replace(trimmed, "").trim()

    // 2. Strip trailing edition/meta brackets (if not emptying the string)
    val withoutTrailing = TRAILING_BRACKET_TAG.replace(s, "").trim()
    if (withoutTrailing.isNotEmpty()) {
        s = withoutTrailing
    }

    // 3. Strip inline feature clauses (" feat. ...", " ft ...")
    s = INLINE_FEAT.replace(s, "").trim()

    // 4. Check again if stripped form matches a known ensemble
    findKnownEnsemble(s)?.let { return it }

    // 5. Split on collaborator delimiters and take the first non-empty segment
    val parts = COLLAB_SPLIT.split(s)
    val first = parts.firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
    val candidate = first.ifEmpty { s }

    val finalCleaned = TRAILING_BRACKET_TAG.replace(candidate, "").trim()
    val result = finalCleaned.ifEmpty { candidate }

    return result.ifEmpty { trimmed }
}

/** Grouping key: lowercase, collapsed spaces, no leading article, no trailing (...) edition tag. */
fun albumNormKey(album: String, artist: String): String {
    var a = album.trim().replace(TRAILING_BRACKET_TAG, "").trim()
    a = a.replace(Regex("""\s+"""), " ").lowercase().replace(ARTICLES, "")
    return "$a::${primaryArtist(artist).lowercase()}"
}
