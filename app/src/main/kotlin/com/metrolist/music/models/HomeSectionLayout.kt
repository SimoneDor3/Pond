/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

/** Sections of the Spotify home the user can reorder or hide, in default order. */
enum class HomeSectionId(val id: String) {
    SPEED_DIAL("speed_dial"),
    SHORTCUTS("shortcuts"),
    FOR_YOU("for_you"),
    NEW_RELEASES("new_releases"),
    YOUR_SHOWS("your_shows"),
    SPOTIFY_FEED("spotify_feed"),
}

data class HomeSectionSetting(
    val section: HomeSectionId,
    val visible: Boolean,
)

val DefaultHomeLayout: List<HomeSectionSetting> = HomeSectionId.entries.map { HomeSectionSetting(it, visible = true) }

fun serializeHomeLayout(layout: List<HomeSectionSetting>): String =
    layout.joinToString(",") { "${it.section.id}:${it.visible}" }

/** Unknown or malformed entries are dropped; sections missing from [raw] are appended visible. */
fun deserializeHomeLayout(raw: String?): List<HomeSectionSetting> {
    val parsed = raw.orEmpty().split(",").mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2) return@mapNotNull null
        val section = HomeSectionId.entries.find { it.id == parts[0].trim() } ?: return@mapNotNull null
        HomeSectionSetting(section, parts[1].trim().toBooleanStrictOrNull() ?: true)
    }.distinctBy { it.section }
    val known = parsed.map { it.section }.toSet()
    return parsed + DefaultHomeLayout.filter { it.section !in known }
}
