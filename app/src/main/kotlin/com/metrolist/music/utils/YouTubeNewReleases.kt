/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.LocalDateTime

private const val SEED_ARTISTS = 6
private const val WINDOW_MONTHS = 3L

/**
 * YouTube Music new releases: [fromListening] are recent releases by the artists the user plays
 * most (ranked in [artistRank], 0 = most played), [popular] are YouTube Music's new releases, and
 * [followed] holds the IDs of bookmarked artists.
 */
data class YouTubeReleaseSources(
    val fromListening: List<AlbumItem>,
    val popular: List<AlbumItem>,
    val artistRank: Map<String, Int>,
    val followed: Set<String>,
)

suspend fun fetchYouTubeReleaseSources(database: MusicDatabase, hideExplicit: Boolean): YouTubeReleaseSources {
    val since = LocalDateTime.now().minusMonths(WINDOW_MONTHS)
    val topArtists = database.mostPlayedArtists(since, limit = 20).first().filter { it.artist.isYouTubeArtist }
    val artistRank = topArtists.withIndex().associate { (index, artist) -> artist.id to index }
    val followed = database.allArtistsByPlayTime().first()
        .filter { it.artist.bookmarkedAt != null }
        .mapTo(mutableSetOf()) { it.id }
    // Artist pages only give a release year, so "recent" means released this year (or last year early on).
    val minYear = since.year

    return coroutineScope {
        val popular = async {
            YouTube.newReleaseAlbums().onFailure { Timber.w(it, "newReleases: YouTube newReleaseAlbums() FAILED") }
                .getOrNull().orEmpty()
        }
        val fromArtists = topArtists.take(SEED_ARTISTS).map { seed ->
            async {
                YouTube.artist(seed.id).getOrNull()?.sections.orEmpty()
                    .flatMap { it.items }
                    .filterIsInstance<AlbumItem>()
                    .filter { (it.year ?: 0) >= minYear }
                    // Artist pages leave the artist out of their own releases.
                    .map { it.copy(artists = it.artists ?: listOf(Artist(name = seed.artist.name, id = seed.id))) }
            }
        }
        YouTubeReleaseSources(
            fromListening = fromArtists.awaitAll().flatten().filterExplicit(hideExplicit),
            // filterIsInstance also drops the nulls JSON parsing can leave in the list.
            popular = popular.await().filterIsInstance<AlbumItem>().filterExplicit(hideExplicit),
            artistRank = artistRank,
            followed = followed,
        )
    }
}
