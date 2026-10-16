/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyHomeFeedSection
import kotlin.math.roundToInt

/** A personal mix from either service, shown in the "Crafted for you" row. */
sealed interface CraftedMix {
    val id: String

    data class Spotify(val playlist: SpotifyHomeFeedItem.Playlist) : CraftedMix {
        override val id get() = playlist.id
    }

    data class YouTube(val playlist: PlaylistItem) : CraftedMix {
        override val id get() = playlist.id
    }
}

/** Where the "Crafted for you" mixes come from. */
enum class SuggestionSource {
    SPOTIFY_ONLY,
    MIXED,
}

/** [youTubeShare] is the fraction (0..1) of the row that comes from YouTube Music. */
data class CraftedConfig(
    val source: SuggestionSource,
    val youTubeShare: Float = DEFAULT_YOUTUBE_SHARE_PERCENT / 100f,
    val maxSize: Int = 12,
)

const val CRAFTED_FOR_YOU_TITLE = "crafted_for_you"
const val DEFAULT_YOUTUBE_SHARE_PERCENT = 50

// YouTube Music's personal mixes (My Supermix, Discover Mix, Replay Mix, …) use this playlist ID prefix.
private const val YOUTUBE_PERSONAL_MIX_PREFIX = "RDTMAK"

fun youTubeShareFromPercent(percent: Int): Float = percent.coerceIn(0, 100) / 100f

/**
 * Picks the playlists Spotify made for the user from its home feed and, unless the source is
 * Spotify only, YouTube Music's personal mixes from its home, spreading YouTube mixes evenly
 * at the configured share. When one service runs out, the other fills the rest of the row.
 */
fun buildCraftedForYou(
    spotifyFeed: List<SpotifyHomeFeedSection>,
    youTubeHome: List<PlaylistItem>,
    config: CraftedConfig,
): List<CraftedMix> {
    val spotify = spotifyFeed.asSequence()
        .flatMap { it.items }
        .filterIsInstance<SpotifyHomeFeedItem.Playlist>()
        .filter { it.madeForUsername != null }
        .distinctBy { it.id }
        .map { CraftedMix.Spotify(it) }
        .toList()
    val youTube = if (config.source == SuggestionSource.MIXED) {
        youTubeHome.filter { it.id.startsWith(YOUTUBE_PERSONAL_MIX_PREFIX) }.distinctBy { it.id }.map { CraftedMix.YouTube(it) }
    } else {
        emptyList()
    }
    val share = config.youTubeShare.coerceIn(0f, 1f)

    val spotifyLeft = spotify.iterator()
    val youTubeLeft = youTube.iterator()
    val row = mutableListOf<CraftedMix>()
    var youTubeTaken = 0
    while (row.size < config.maxSize) {
        val wantYouTube = youTubeTaken < ((row.size + 1) * share).roundToInt()
        val next = when {
            wantYouTube && youTubeLeft.hasNext() -> youTubeLeft.next()
            spotifyLeft.hasNext() -> spotifyLeft.next()
            youTubeLeft.hasNext() -> youTubeLeft.next()
            else -> break
        }
        if (next is CraftedMix.YouTube) youTubeTaken++
        row += next
    }
    return row
}
