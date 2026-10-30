/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem

const val SHORTCUT_GRID_SIZE = 8
const val NEW_RELEASES_SIZE = 20

/**
 * Picks the music tiles of the home grid from the YouTube Music home [shelves], one item per shelf
 * in turn so the grid mixes "Listen again", recommended albums, artists and so on. Only albums,
 * artists and playlists qualify; personal mixes are left to "Crafted for you".
 */
fun pickYouTubeShortcuts(shelves: List<List<YTItem>>, count: Int = SHORTCUT_GRID_SIZE): List<YTItem> {
    val queues = shelves.map { shelf ->
        shelf.filter {
            it is AlbumItem || it is ArtistItem || (it is PlaylistItem && !it.id.startsWith(YOUTUBE_PERSONAL_MIX_PREFIX))
        }.iterator()
    }
    val picked = LinkedHashMap<String, YTItem>()
    while (picked.size < count && queues.any { it.hasNext() }) {
        for (queue in queues) {
            if (picked.size >= count) break
            if (queue.hasNext()) queue.next().let { picked.putIfAbsent(it.id, it) }
        }
    }
    return picked.values.toList()
}

/**
 * Builds the "New releases" row: releases by the artists the user listens to ([fromListening] plus
 * any [popular] one by such an artist), newest first and then by how much the artist is played
 * ([artistRank], 0 = most played), filling up to half the row; then YouTube Music's [popular]
 * releases. Personal releases left over go last when the popular ones run out.
 */
fun rankNewReleases(
    fromListening: List<AlbumItem>,
    popular: List<AlbumItem>,
    artistRank: Map<String, Int>,
    limit: Int = NEW_RELEASES_SIZE,
): List<AlbumItem> {
    fun AlbumItem.rank() = artists.orEmpty().mapNotNull { artist -> artist.id?.let(artistRank::get) }.minOrNull()

    val personal = (fromListening + popular.filter { it.rank() != null })
        .distinctBy { it.id }
        .sortedWith(compareByDescending<AlbumItem> { it.year ?: 0 }.thenBy { it.rank() ?: Int.MAX_VALUE })
    val personalIds = personal.mapTo(mutableSetOf()) { it.id }
    val head = personal.take(limit / 2)
    return (head + popular.filter { it.id !in personalIds }.distinctBy { it.id } + personal.drop(head.size)).take(limit)
}
