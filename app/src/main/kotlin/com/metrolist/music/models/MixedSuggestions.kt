/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import java.text.Normalizer
import kotlin.math.roundToInt

/** A suggested song from either service, shown in the Spotify-style track grid. */
sealed interface MixedSuggestion {
    val id: String
    val title: String
    val artistName: String?
    val explicit: Boolean
    val isVideo: Boolean

    /** Cross-service identity of the song, see [suggestionKey]. */
    val key: String get() = suggestionKey(title, artistName.orEmpty())

    data class FromYouTube(val song: SongItem) : MixedSuggestion {
        override val id get() = song.id
        override val title get() = song.title
        override val artistName get() = song.artists.firstOrNull()?.name
        override val explicit get() = song.explicit
        override val isVideo get() = song.isVideoSong
    }

    data class FromSpotify(val track: SpotifyTrack) : MixedSuggestion {
        override val id get() = track.id
        override val title get() = track.name
        override val artistName get() = track.artists.firstOrNull()?.name
        override val explicit get() = track.explicit
        override val isVideo get() = false
    }
}

/** Where the suggestion rows draw their songs from. */
enum class SuggestionSource {
    SPOTIFY_ONLY,
    MIXED,
}

/**
 * Data the suggestion rows are built from.
 * Spotify history: [topTracks] and [topArtists] (most listened first), [recentTopTracks]
 * (short-term top tracks) and [artistGenres] (artist name → genres).
 * Live data: [liveSpotify] (top tracks of the user's top artists), [liveYouTube]
 * (YouTube Music's current recommendations) and [relatedByArtist] (artist name → songs
 * YouTube Music relates to that artist).
 */
data class SuggestionInputs(
    val topTracks: List<SpotifyTrack> = emptyList(),
    val recentTopTracks: List<SpotifyTrack> = emptyList(),
    val topArtists: List<SpotifyArtist> = emptyList(),
    val artistGenres: Map<String, Set<String>> = emptyMap(),
    val liveSpotify: List<SpotifyTrack> = emptyList(),
    val liveYouTube: List<SongItem> = emptyList(),
    val relatedByArtist: Map<String, List<SongItem>> = emptyMap(),
)

/** [youTubeShare] is the fraction (0..1) of each mixed row that comes from YouTube Music. */
data class SuggestionConfig(
    val source: SuggestionSource,
    val youTubeShare: Float = DEFAULT_YOUTUBE_SHARE_PERCENT / 100f,
    val hideExplicit: Boolean = false,
    val hideVideoSongs: Boolean = false,
    val rowSize: Int = 20,
)

const val MADE_FOR_YOU_TITLE = "spotify_mix_made_for_you"
const val JUMP_BACK_IN_TITLE = "spotify_jump_back_in"
const val BECAUSE_YOU_LIKE_PREFIX = "spotify_because_you_like:"
const val DEFAULT_YOUTUBE_SHARE_PERCENT = 60

private const val MAX_PER_ARTIST = 2
private const val MAX_ARTIST_ROWS = 2

private const val W_AFFINITY = 0.5f
private const val W_GENRE = 0.3f
private const val W_SOURCE = 0.2f
private const val YOUTUBE_LIVE_SOURCE = 1f
private const val SPOTIFY_SOURCE = 0.8f

fun youTubeShareFromPercent(percent: Int): Float = percent.coerceIn(0, 100) / 100f

private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
private val FEATURING = Regex("""\s(feat|ft|featuring)\.?\s.*$""")
private val VERSION_SUFFIX = Regex("""\s-\s.*\b(remaster(ed)?|version|edit|live|mono|stereo|mix|radio)\b.*$""")
private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
private val DIACRITICS = Regex("""\p{M}+""")

private fun String.normalizedName(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_ALPHANUMERIC, " ")
        .trim()

/**
 * Identity of a song across Spotify and YouTube Music, whose titles differ in casing, accents,
 * featured artists and version tags ("Remastered", "Official Video").
 */
fun suggestionKey(title: String, artist: String): String {
    val cleanTitle = title.lowercase()
        .replace(BRACKETED, " ")
        .replace(VERSION_SUFFIX, "")
        .replace(FEATURING, "")
        .normalizedName()
    return "$cleanTitle|${artist.normalizedName()}"
}

/**
 * Builds the suggestion rows: "Made for you" ranked by the user's Spotify taste,
 * "Because you like {artist}" for the first top artists with songs, and "Jump back in"
 * from what the user has played most lately. A song appears in one row only; artist rows
 * pick their songs first. With [SuggestionSource.SPOTIFY_ONLY] all YouTube Music data is ignored.
 */
fun buildSuggestionSections(inputs: SuggestionInputs, config: SuggestionConfig): List<SpotifyHomeSection> {
    val useYouTube = config.source == SuggestionSource.MIXED
    val share = if (useYouTube) config.youTubeShare.coerceIn(0f, 1f) else 0f
    val taste = Taste(inputs)

    fun List<MixedSuggestion>.allowed() = filter {
        it.id.isNotEmpty() && !(config.hideExplicit && it.explicit) && !(config.hideVideoSongs && it.isVideo)
    }
    fun List<SongItem>.fromYouTube() = if (useYouTube) map { MixedSuggestion.FromYouTube(it) }.allowed() else emptyList()
    fun List<SpotifyTrack>.fromSpotify() = map { MixedSuggestion.FromSpotify(it) }.allowed()

    val recent = inputs.recentTopTracks.fromSpotify().distinctBy { it.key }
    // Keys already placed in a row; recently played songs are reserved for "Jump back in".
    val claimed = recent.mapTo(mutableSetOf()) { it.key }

    val relatedByName = inputs.relatedByArtist.mapKeys { it.key.normalizedName() }
    val artistRows = inputs.topArtists.asSequence()
        .mapNotNull { artist ->
            val name = artist.name.normalizedName()
            val spotify = inputs.liveSpotify.filter { track -> track.artists.any { it.name.normalizedName() == name } }
            val row = interleave(
                youTube = relatedByName[name].orEmpty().fromYouTube().unclaimed(claimed),
                spotify = spotify.fromSpotify().unclaimed(claimed),
                share = share,
                size = config.rowSize,
                maxPerArtist = Int.MAX_VALUE,
            )
            if (row.isEmpty()) return@mapNotNull null
            row.forEach { claimed += it.key }
            SpotifyHomeSection(title = BECAUSE_YOU_LIKE_PREFIX + artist.name, type = SectionType.MIXED_TRACKS, mixed = row)
        }
        .take(MAX_ARTIST_ROWS)
        .toList()

    val youTubeCandidates = inputs.liveYouTube.fromYouTube().unclaimed(claimed)
    // A song on both services keeps its YouTube copy, which plays without a lookup.
    val youTubeKeys = youTubeCandidates.mapTo(mutableSetOf()) { it.key }
    val madeForYou = interleave(
        youTube = taste.rank(youTubeCandidates, YOUTUBE_LIVE_SOURCE),
        spotify = taste.rank((inputs.topTracks + inputs.liveSpotify).fromSpotify().unclaimed(claimed + youTubeKeys), SPOTIFY_SOURCE),
        share = share,
        size = config.rowSize,
        maxPerArtist = MAX_PER_ARTIST,
    )

    return listOfNotNull(
        madeForYou.takeIf { it.isNotEmpty() }
            ?.let { SpotifyHomeSection(title = MADE_FOR_YOU_TITLE, type = SectionType.MIXED_TRACKS, mixed = it) },
    ) + artistRows + listOfNotNull(
        recent.take(config.rowSize).takeIf { it.isNotEmpty() }
            ?.let { SpotifyHomeSection(title = JUMP_BACK_IN_TITLE, type = SectionType.MIXED_TRACKS, mixed = it) },
    )
}

private fun List<MixedSuggestion>.unclaimed(claimed: Set<String>) =
    filter { it.key !in claimed }.distinctBy { it.key }

/** The user's Spotify taste: how much they listen to each artist and their genres. */
private class Taste(inputs: SuggestionInputs) {
    // The most listened artist scores 1, decreasing linearly with rank.
    private val affinity: Map<String, Float> = inputs.topArtists
        .map { it.name.normalizedName() }
        .distinct()
        .let { names -> names.withIndex().associate { (rank, name) -> name to 1f - rank.toFloat() / names.size } }
    private val genres: Map<String, Set<String>> = inputs.artistGenres.mapKeys { it.key.normalizedName() } +
        inputs.topArtists.associate { it.name.normalizedName() to it.genres.toSet() }
    private val topGenres: Set<String> = inputs.topArtists.flatMapTo(mutableSetOf()) { it.genres }

    private fun score(suggestion: MixedSuggestion, sourceWeight: Float): Float {
        val artist = suggestion.artistName.orEmpty().normalizedName()
        val artistGenres = genres[artist].orEmpty()
        val genreOverlap = if (artistGenres.isEmpty()) 0f else artistGenres.count { it in topGenres }.toFloat() / artistGenres.size
        return W_AFFINITY * (affinity[artist] ?: 0f) + W_GENRE * genreOverlap + W_SOURCE * sourceWeight
    }

    /** Best first; ties keep the source's own order. */
    fun rank(suggestions: List<MixedSuggestion>, sourceWeight: Float) =
        suggestions.sortedByDescending { score(it, sourceWeight) }
}

/**
 * Fills a row of [size], spreading YouTube songs evenly at the given [share]; when one source
 * runs out, the other fills the rest. Skips songs past [maxPerArtist] for the same artist.
 */
private fun interleave(
    youTube: List<MixedSuggestion>,
    spotify: List<MixedSuggestion>,
    share: Float,
    size: Int,
    maxPerArtist: Int,
): List<MixedSuggestion> {
    val perArtist = mutableMapOf<String, Int>()
    val seen = mutableSetOf<String>()
    fun Iterator<MixedSuggestion>.nextAllowed(): MixedSuggestion? {
        while (hasNext()) {
            val candidate = next()
            val artist = candidate.artistName.orEmpty().normalizedName()
            val count = perArtist[artist] ?: 0
            if (candidate.key in seen || count >= maxPerArtist) continue
            seen += candidate.key
            perArtist[artist] = count + 1
            return candidate
        }
        return null
    }

    val youTubeLeft = youTube.iterator()
    val spotifyLeft = spotify.iterator()
    val row = mutableListOf<MixedSuggestion>()
    var youTubeTaken = 0
    while (row.size < size) {
        val wantYouTube = youTubeTaken < ((row.size + 1) * share).roundToInt()
        val next = if (wantYouTube) {
            youTubeLeft.nextAllowed() ?: spotifyLeft.nextAllowed()
        } else {
            spotifyLeft.nextAllowed() ?: youTubeLeft.nextAllowed()
        } ?: break
        if (next is MixedSuggestion.FromYouTube) youTubeTaken++
        row += next
    }
    return row
}
