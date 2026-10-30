package com.metrolist.music.models

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHomeBuildTest {

    private fun episode(
        id: String,
        durationSec: Int = 3600,
        show: String = "Show $id",
        album: Album? = null,
        youTubeProgress: Float? = null,
    ) = SongItem(
        id = id,
        title = "Episode $id",
        artists = listOf(Artist(name = show, id = null)),
        album = album,
        duration = durationSec,
        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        isEpisode = true,
        playbackProgress = youTubeProgress,
    )

    private fun played(id: String, positionMs: Long, durationSec: Int = 3600) =
        SongEntity(id = id, title = "Episode $id", duration = durationSec, playbackPosition = positionMs)

    private fun album(n: Int) = AlbumItem(
        browseId = "MPRE$n", playlistId = "OLAK$n", title = "Album $n", artists = null, thumbnail = "https://album/$n.jpg",
    )

    private fun show(id: String, title: String, author: String? = null) = PodcastItem(
        id = id, title = title, author = author?.let { Artist(name = it, id = null) }, episodeCountText = null,
        thumbnail = "https://show/$id.jpg", playEndpoint = null, shuffleEndpoint = null,
    )

    private val show = show("MPSPshow", "Show")

    private fun podcasts(episodes: List<SongItem>, shows: List<PodcastItem> = emptyList(), local: Map<String, SongEntity> = emptyMap()) =
        buildSpotifyHome(emptyList(), episodes, shows, local, SpotifyHomeFilter.PODCASTS).first().podcasts

    private val musicShortcuts = (1..6).map(::album)
    private val spotifyMusic = (1..6).map { SpotifyHomeSection(title = "Music $it", type = SectionType.PLAYLISTS) }

    @Test
    fun `episode state - unplayed is new, partial shows progress, near the end is completed`() {
        val new = episode("a").toHomeEpisode(local = null)
        assertTrue(new.isNew)
        assertNull(new.progress)

        val partial = episode("b").toHomeEpisode(played("b", positionMs = 900_000))
        assertFalse(partial.isNew)
        assertEquals(0.25f, partial.progress!!, 0.001f)

        val done = episode("c").toHomeEpisode(played("c", positionMs = 3_580_000))
        assertFalse(done.isNew)
        assertNull(done.progress)
    }

    @Test
    fun `YouTube-only progress shows the bar and resumes from YouTube's position`() {
        val partial = episode("a", youTubeProgress = 0.4f).toHomeEpisode(local = null)
        assertFalse(partial.isNew)
        assertEquals(0.4f, partial.progress!!, 0.001f)
        assertEquals(1_440_000L, partial.resumePositionMs)
    }

    @Test
    fun `YouTube completed episode is neither new nor in progress`() {
        val done = episode("a", youTubeProgress = 0.98f).toHomeEpisode(local = null)
        assertFalse(done.isNew)
        assertNull(done.progress)
        assertEquals(0L, done.resumePositionMs)

        val doneOnYouTubeOnly = episode("b", youTubeProgress = 1f).toHomeEpisode(played("b", positionMs = 900_000))
        assertNull(doneOnYouTubeOnly.progress)
    }

    @Test
    fun `local position ahead of YouTube wins and playback resumes locally`() {
        val state = episode("a", youTubeProgress = 0.1f).toHomeEpisode(played("a", positionMs = 1_800_000))
        assertFalse(state.isNew)
        assertEquals(0.5f, state.progress!!, 0.001f)
        assertEquals(0L, state.resumePositionMs)
    }

    @Test
    fun `YouTube ahead of local shows and resumes from YouTube's position`() {
        val state = episode("a", youTubeProgress = 0.75f).toHomeEpisode(played("a", positionMs = 900_000))
        assertFalse(state.isNew)
        assertEquals(0.75f, state.progress!!, 0.001f)
        assertEquals(2_700_000L, state.resumePositionMs)
    }

    @Test
    fun `no progress from either source is new`() {
        val state = episode("a", youTubeProgress = null).toHomeEpisode(played("a", positionMs = 0))
        assertTrue(state.isNew)
        assertNull(state.progress)
        assertEquals(0L, state.resumePositionMs)
    }

    @Test
    fun `unknown local duration falls back to the YouTube duration`() {
        val partial = episode("a", durationSec = 1000).toHomeEpisode(played("a", positionMs = 500_000, durationSec = -1))
        assertEquals(0.5f, partial.progress!!, 0.001f)
    }

    @Test
    fun `podcast state - new if any episode is unplayed, progress from the first started one`() {
        val podcast = HomePodcast(
            id = null, title = "P", thumbnail = null,
            episodes = listOf(
                episode("a").toHomeEpisode(played("a", positionMs = 3_590_000)),
                episode("b").toHomeEpisode(played("b", positionMs = 900_000)),
                episode("c").toHomeEpisode(played("c", positionMs = 1_800_000)),
                episode("d").toHomeEpisode(local = null),
            ),
        )
        assertTrue(podcast.hasNew)
        assertEquals(0.25f, podcast.progress!!, 0.001f)
        assertEquals("a", podcast.latestEpisode.song.id)

        val finished = podcast.copy(episodes = podcast.episodes.take(1))
        assertFalse(finished.hasNew)
        assertNull(finished.progress)
    }

    @Test
    fun `episodes are grouped by show ID in feed order`() {
        val moka = Album(name = "La Moka", id = "MPSPmoka")
        val result = podcasts(
            listOf(
                episode("m2", show = "Author", album = moka),
                episode("x", show = "Other", album = Album(name = "Other", id = "MPSPother")),
                episode("m1", show = "Author", album = moka),
            ),
        )
        assertEquals(listOf("MPSPmoka", "MPSPother"), result.map { it.id })
        assertEquals("La Moka", result.first().title)
        assertEquals(listOf("m2", "m1"), result.first().episodes.map { it.song.id })
        assertEquals("https://i.ytimg.com/vi/m2/hqdefault.jpg", result.first().thumbnail)
    }

    @Test
    fun `episodes without a show ID match a followed show by name`() {
        val moka = show("MPSPmoka", "La Moka", author = "Moka Media")
        val result = podcasts(
            listOf(
                episode("a", show = " la moka "),
                episode("b", show = "MOKA MEDIA"),
                episode("c", show = "Author", album = Album(name = "La Moka", id = "MPSPmoka")),
            ),
            shows = listOf(moka),
        )
        assertEquals(1, result.size)
        assertEquals("MPSPmoka", result.single().id)
        assertEquals("La Moka", result.single().title)
        assertEquals("https://show/MPSPmoka.jpg", result.single().thumbnail)
        assertEquals(listOf("a", "b", "c"), result.single().episodes.map { it.song.id })
    }

    @Test
    fun `unmatched episodes are grouped by show name without an ID`() {
        val result = podcasts(listOf(episode("a", show = "Indie"), episode("b", show = "indie "), episode("c", show = "Else")))
        assertEquals(listOf(null, null), result.map { it.id })
        assertEquals(listOf("Indie", "Else"), result.map { it.title })
        assertEquals(listOf("a", "b"), result.first().episodes.map { it.song.id })
    }

    @Test
    fun `podcasts in progress come first, then those with new episodes, then the rest`() {
        val result = podcasts(
            listOf(episode("done", show = "Done"), episode("new", show = "New"), episode("half", show = "Half")),
            local = mapOf(
                "done" to played("done", positionMs = 3_590_000),
                "half" to played("half", positionMs = 1_800_000),
            ),
        )
        assertEquals(listOf("Half", "New", "Done"), result.map { it.title })
    }

    @Test
    fun `all view puts up to four unfinished podcasts before music, eight tiles total`() {
        val episodes = listOf(
            episode("new1"), episode("done"), episode("new2"), episode("half"),
            episode("new3"), episode("new4"), episode("half-older", show = "Show half"),
        )
        val local = mapOf(
            "done" to played("done", positionMs = 3_590_000),
            "half" to played("half", positionMs = 1_800_000),
            "half-older" to played("half-older", positionMs = 900_000),
        )

        val home = buildSpotifyHome(spotifyMusic, episodes, listOf(show), local, SpotifyHomeFilter.ALL, musicShortcuts = musicShortcuts)
            .drop(1)

        val grid = home.first()
        assertEquals(SectionType.SHORTCUTS, grid.type)
        // In progress first, then new in feed order, capped at four; the finished one is left out.
        assertEquals(listOf("Show half", "Show new1", "Show new2", "Show new3"), grid.podcasts.map { it.title })
        assertEquals(listOf("half", "half-older"), grid.podcasts.first().episodes.map { it.song.id })
        assertEquals(0.5f, grid.podcasts.first().progress!!, 0.001f)
        assertEquals(musicShortcuts.take(4), grid.shortcuts)
        assertEquals(listOf(SectionType.SHORTCUTS, SectionType.SHOWS), home.take(2).map { it.type })
        assertEquals(1 + 1 + spotifyMusic.size, home.size)
    }

    @Test
    fun `music view has no podcasts, podcasts view has no music`() {
        val episodes = listOf(episode("a"))

        val music = buildSpotifyHome(spotifyMusic, episodes, listOf(show), emptyMap(), SpotifyHomeFilter.MUSIC, musicShortcuts = musicShortcuts)
        assertEquals(musicShortcuts, music.first().shortcuts)
        assertTrue(music.first().podcasts.isEmpty())
        assertEquals(spotifyMusic, music.drop(1))

        val podcasts = buildSpotifyHome(spotifyMusic, episodes, listOf(show), emptyMap(), SpotifyHomeFilter.PODCASTS, musicShortcuts = musicShortcuts)
        assertEquals(listOf(SectionType.SHORTCUTS, SectionType.SHOWS), podcasts.map { it.type })
        assertTrue(podcasts.first().shortcuts.isEmpty())
    }

    @Test
    fun `without podcasts the grid is all music, then the Spotify feed`() {
        val home = buildSpotifyHome(spotifyMusic, emptyList(), emptyList(), emptyMap(), SpotifyHomeFilter.ALL, musicShortcuts = musicShortcuts)
        assertEquals(SectionType.SPEED_DIAL, home[0].type)
        assertEquals(musicShortcuts, home[1].shortcuts)
        assertEquals(spotifyMusic, home.drop(2))
    }

    @Test
    fun `new releases are a YouTube Music row`() {
        val home = buildSpotifyHome(spotifyMusic, emptyList(), emptyList(), emptyMap(), SpotifyHomeFilter.ALL, newReleases = musicShortcuts)
        val releases = home.single { it.title == NEW_RELEASES_TITLE }
        assertEquals(SectionType.YOUTUBE_ITEMS, releases.type)
        assertEquals(musicShortcuts, releases.items)
    }

    private val topTracks = SpotifyHomeSection(title = "spotify_top_tracks", type = SectionType.TRACKS)

    // Mirrors HomeViewModel: Spotify's feed only; the grid and new releases come from YouTube Music.
    private val loadedSections = listOf(topTracks) + spotifyMusic.take(3)

    private fun layout(vararg sections: HomeSectionId, hidden: Set<HomeSectionId> = emptySet()) =
        sections.map { HomeSectionSetting(it, it !in hidden) }

    private fun home(filter: SpotifyHomeFilter, layout: List<HomeSectionSetting>) =
        buildSpotifyHome(
            loadedSections, listOf(episode("a")), listOf(show), emptyMap(), filter, layout,
            musicShortcuts = musicShortcuts, newReleases = musicShortcuts,
        )
            .map { if (it.type == SectionType.SHORTCUTS) "grid" else it.title.ifEmpty { it.type.name } }

    private val customLayout = layout(
        HomeSectionId.SPOTIFY_FEED,
        HomeSectionId.YOUR_SHOWS,
        HomeSectionId.SHORTCUTS,
        HomeSectionId.SPEED_DIAL,
        HomeSectionId.NEW_RELEASES,
        HomeSectionId.FOR_YOU,
    )

    @Test
    fun `default layout - speed dial, grid, new releases, shows, then the feed in Spotify order`() {
        assertEquals(
            listOf("SPEED_DIAL", "grid", "spotify_new_releases", "your_shows", "spotify_top_tracks", "Music 1", "Music 2", "Music 3"),
            home(SpotifyHomeFilter.ALL, DefaultHomeLayout),
        )
    }

    @Test
    fun `layout order is respected and the feed stays together`() {
        assertEquals(
            listOf("spotify_top_tracks", "Music 1", "Music 2", "Music 3", "your_shows", "grid", "SPEED_DIAL", "spotify_new_releases"),
            home(SpotifyHomeFilter.ALL, customLayout),
        )
    }

    @Test
    fun `hidden sections are dropped`() {
        val hidden = customLayout.map {
            it.copy(visible = it.section !in setOf(HomeSectionId.SPOTIFY_FEED, HomeSectionId.SPEED_DIAL))
        }
        assertEquals(listOf("your_shows", "grid", "spotify_new_releases"), home(SpotifyHomeFilter.ALL, hidden))
    }

    @Test
    fun `filters keep the layout order without the other kind of content or the speed dial`() {
        assertEquals(
            listOf("spotify_top_tracks", "Music 1", "Music 2", "Music 3", "grid", "spotify_new_releases"),
            home(SpotifyHomeFilter.MUSIC, customLayout),
        )
        assertEquals(listOf("your_shows", "grid"), home(SpotifyHomeFilter.PODCASTS, customLayout))

        val showsHidden = customLayout.map { it.copy(visible = it.section != HomeSectionId.YOUR_SHOWS) }
        assertEquals(listOf("grid"), home(SpotifyHomeFilter.PODCASTS, showsHidden))
    }

    @Test
    fun `layout survives a round trip`() {
        val saved = customLayout.map { it.copy(visible = it.section != HomeSectionId.NEW_RELEASES) }
        assertEquals(saved, deserializeHomeLayout(serializeHomeLayout(saved)))
    }

    @Test
    fun `missing sections are appended visible, unknown and duplicate ones dropped`() {
        assertEquals(
            listOf(
                HomeSectionSetting(HomeSectionId.SPOTIFY_FEED, visible = false),
                HomeSectionSetting(HomeSectionId.SPEED_DIAL, visible = true),
                HomeSectionSetting(HomeSectionId.SHORTCUTS, visible = true),
                HomeSectionSetting(HomeSectionId.FOR_YOU, visible = true),
                HomeSectionSetting(HomeSectionId.NEW_RELEASES, visible = true),
                HomeSectionSetting(HomeSectionId.YOUR_SHOWS, visible = true),
            ),
            deserializeHomeLayout("spotify_feed:false,recently_played:true,spotify_feed:true"),
        )
    }

    @Test
    fun `missing or garbage layout falls back to the default`() {
        assertEquals(DefaultHomeLayout, deserializeHomeLayout(null))
        assertEquals(DefaultHomeLayout, deserializeHomeLayout(""))
        assertEquals(DefaultHomeLayout, deserializeHomeLayout("not a layout,::,speed_dial"))
    }
}
