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
 * Splits new releases into those by the artists the user listens to ([fromListening], plus any
 * [popular] one by an artist in [artistRank] or [followed]), newest first and then by how much the
 * artist is played ([artistRank], 0 = most played), and the remaining [popular] ones in chart order.
 */
fun splitNewReleases(
    fromListening: List<AlbumItem>,
    popular: List<AlbumItem>,
    artistRank: Map<String, Int>,
    followed: Set<String> = emptySet(),
): Pair<List<AlbumItem>, List<AlbumItem>> {
    fun AlbumItem.artistIds() = artists.orEmpty().mapNotNull { it.id }
    fun AlbumItem.rank() = artistIds().mapNotNull(artistRank::get).minOrNull()

    val personal = (fromListening + popular.filter { album -> album.rank() != null || album.artistIds().any { it in followed } })
        .distinctBy { it.id }
        .sortedWith(compareByDescending<AlbumItem> { it.year ?: 0 }.thenBy { it.rank() ?: Int.MAX_VALUE })
    val personalIds = personal.mapTo(mutableSetOf()) { it.id }
    return personal to popular.filter { it.id !in personalIds }.distinctBy { it.id }
}

/**
 * Builds the home "New releases" row: the user's releases (see [splitNewReleases]) fill up to half
 * the row, then the popular ones; personal releases left over go last when the popular ones run out.
 */
fun rankNewReleases(
    fromListening: List<AlbumItem>,
    popular: List<AlbumItem>,
    artistRank: Map<String, Int>,
    followed: Set<String> = emptySet(),
    limit: Int = NEW_RELEASES_SIZE,
): List<AlbumItem> {
    val (personal, others) = splitNewReleases(fromListening, popular, artistRank, followed)
    val head = personal.take(limit / 2)
    return (head + others + personal.drop(head.size)).take(limit)
}
