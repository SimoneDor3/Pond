/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Represents a section in the Spotify-powered home screen.
 * Each section has a title and contains one type of content, except the untitled
 * [SectionType.SHORTCUTS] grid, which mixes Spotify music [shortcuts] with
 * YouTube Music [podcasts].
 */
data class SpotifyHomeSection(
    val title: String,
    val type: SectionType,
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val shortcuts: List<SpotifyHomeFeedItem> = emptyList(),
    val podcasts: List<HomePodcast> = emptyList(),
    val shows: List<PodcastItem> = emptyList(),
)

enum class SectionType {
    TRACKS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
    SHORTCUTS,
    SHOWS,

    /** Placeholder for the speed dial, which the home screen renders from its own data. */
    SPEED_DIAL,
}

/**
 * A YouTube Music podcast episode with the listening state shown on its home tile.
 * [resumePositionMs] is where to start playback from YouTube's progress when it is ahead of Meld's
 * own saved position; 0 otherwise (MusicService restores the local position).
 */
data class HomeEpisode(
    val song: SongItem,
    val progress: Float?,
    val isNew: Boolean,
    val resumePositionMs: Long = 0L,
)

/** A podcast show on the home grid, built from its recent [episodes] (newest first). */
data class HomePodcast(
    val id: String?,
    val title: String,
    val thumbnail: String?,
    val episodes: List<HomeEpisode>,
) {
    val hasNew: Boolean get() = episodes.any { it.isNew }
    val progress: Float? get() = episodes.firstNotNullOfOrNull { it.progress }
    val latestEpisode: HomeEpisode get() = episodes.first()
}

enum class SpotifyHomeFilter {
    ALL,
    MUSIC,
    PODCASTS,
}

private const val ALL_GRID_SIZE = 8
private const val ALL_GRID_MAX_PODCASTS = 4

private const val NEW_RELEASES_TITLE = "spotify_new_releases"

/**
 * Builds the visible Spotify home. Spotify provides the music [spotifySections];
 * podcasts come from YouTube Music: [newEpisodes] of followed [shows]. [localPlayback]
 * (video ID → song row) holds Meld's saved positions, which drive the dot and bar.
 * Sections follow the visible entries of [layout]; the music and podcasts filters keep
 * that order but drop the other kind of content (and the speed dial).
 */
fun buildSpotifyHome(
    spotifySections: List<SpotifyHomeSection>,
    newEpisodes: List<SongItem>,
    shows: List<PodcastItem>,
    localPlayback: Map<String, SongEntity>,
    filter: SpotifyHomeFilter,
    layout: List<HomeSectionSetting> = DefaultHomeLayout,
): List<SpotifyHomeSection> {
    val episodes = newEpisodes
        .distinctBy { it.id }
        .map { it.toHomeEpisode(localPlayback[it.id]) }
    // In progress first, then with a new episode, then the rest; feed order within each.
    val podcasts = episodes.groupIntoPodcasts(shows)
        .sortedBy { if (it.progress != null) 0 else if (it.hasNew) 1 else 2 }
    val showsSection = shows.takeIf { it.isNotEmpty() }
        ?.let { SpotifyHomeSection(title = "your_shows", type = SectionType.SHOWS, shows = it.distinctBy { show -> show.id }) }
    val musicShortcuts = spotifySections.firstOrNull { it.type == SectionType.SHORTCUTS }

    val shortcuts = when (filter) {
        SpotifyHomeFilter.MUSIC -> musicShortcuts
        SpotifyHomeFilter.PODCASTS -> podcasts.takeIf { it.isNotEmpty() }
            ?.let { SpotifyHomeSection(title = "", type = SectionType.SHORTCUTS, podcasts = it) }
        SpotifyHomeFilter.ALL -> {
            val gridPodcasts = podcasts.filter { it.progress != null || it.hasNew }.take(ALL_GRID_MAX_PODCASTS)
            SpotifyHomeSection(
                title = "",
                type = SectionType.SHORTCUTS,
                shortcuts = musicShortcuts?.shortcuts.orEmpty().take(ALL_GRID_SIZE - gridPodcasts.size),
                podcasts = gridPodcasts,
            ).takeIf { it.shortcuts.isNotEmpty() || it.podcasts.isNotEmpty() }
        }
    }
    val showMusic = filter != SpotifyHomeFilter.PODCASTS
    val showPodcasts = filter != SpotifyHomeFilter.MUSIC

    return layout.filter { it.visible }.flatMap { setting ->
        when (setting.section) {
            HomeSectionId.SPEED_DIAL -> listOfNotNull(
                SpotifyHomeSection(title = "", type = SectionType.SPEED_DIAL).takeIf { filter == SpotifyHomeFilter.ALL },
            )
            HomeSectionId.SHORTCUTS -> listOfNotNull(shortcuts)
            HomeSectionId.YOUR_SHOWS -> listOfNotNull(showsSection?.takeIf { showPodcasts })
            HomeSectionId.NEW_RELEASES ->
                if (showMusic) spotifySections.filter { it.title == NEW_RELEASES_TITLE } else emptyList()
            HomeSectionId.SPOTIFY_FEED ->
                if (showMusic) {
                    spotifySections.filter { it.type != SectionType.SHORTCUTS && it.title != NEW_RELEASES_TITLE }
                } else {
                    emptyList()
                }
        }
    }
}

/**
 * Groups episodes by show: by the episode's show ID when YouTube provides it, else by
 * matching its show or author name against the followed [shows], else by that name alone.
 */
private fun List<HomeEpisode>.groupIntoPodcasts(shows: List<PodcastItem>): List<HomePodcast> {
    fun String.normalized() = trim().lowercase()
    val showsById = shows.associateBy { it.id }
    // Titles take precedence over author names when both could match.
    val showsByName = (shows.map { it.title to it } + shows.mapNotNull { show -> show.author?.name?.let { it to show } })
        .reversed()
        .associate { (name, show) -> name.normalized() to show }

    // Key: (show ID, null) when known, else (null, normalized show name).
    return groupBy { episode ->
        val song = episode.song
        val names = listOfNotNull(song.album?.name, song.artists.firstOrNull()?.name)
        val id = song.album?.id?.takeIf { it.isNotBlank() }
            ?: names.firstNotNullOfOrNull { showsByName[it.normalized()]?.id }
        if (id != null) id to null else null to (names.firstOrNull() ?: song.title).normalized()
    }.map { (key, podcastEpisodes) ->
        val show = key.first?.let(showsById::get)
        val first = podcastEpisodes.first().song
        HomePodcast(
            id = key.first,
            title = show?.title ?: first.album?.name ?: first.artists.firstOrNull()?.name ?: first.title,
            thumbnail = show?.thumbnail ?: first.thumbnail,
            episodes = podcastEpisodes,
        )
    }
}

// YouTube marks an episode as played around the end without always reaching 100%.
internal const val YOUTUBE_COMPLETED_FRACTION = 0.97f

/** Merges Meld's [local] saved position with the progress YouTube reports for the episode. */
internal fun SongItem.toHomeEpisode(local: SongEntity?): HomeEpisode {
    val position = local?.playbackPosition?.takeIf { it > 0 }
    val totalMs = (local?.duration?.takeIf { it > 0 } ?: duration ?: 0) * 1000L
    // MusicService saves the position every 15 s, so the last save can stop short of the end.
    val localCompleted = position != null && totalMs > 0 && position >= totalMs - 30_000
    val localFraction = if (position != null && totalMs > 0) position.toFloat() / totalMs else null
    val youTubeFraction = playbackProgress?.takeIf { it > 0f }
    val completed = localCompleted || (youTubeFraction != null && youTubeFraction >= YOUTUBE_COMPLETED_FRACTION)
    val progress = if (completed) null else listOfNotNull(localFraction, youTubeFraction).maxOrNull()
    return HomeEpisode(
        song = this,
        progress = progress,
        isNew = position == null && youTubeFraction == null,
        resumePositionMs = youTubeFraction
            ?.takeIf { !completed && totalMs > 0 }
            ?.let { (it * totalMs).toLong() }
            ?.takeIf { it > (position ?: 0L) }
            ?: 0L,
    )
}
