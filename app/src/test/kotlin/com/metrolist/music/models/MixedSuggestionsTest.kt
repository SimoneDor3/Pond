package com.metrolist.music.models

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedSuggestionsTest {

    private fun yt(id: String, title: String = "Song $id", artist: String = "YT Artist $id", explicit: Boolean = false, videoType: String? = null) =
        SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(name = artist, id = null)),
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            explicit = explicit,
            musicVideoType = videoType,
        )

    private fun sp(id: String, name: String = "Track $id", artist: String = "SP Artist $id", explicit: Boolean = false) =
        SpotifyTrack(id = id, name = name, artists = listOf(SpotifySimpleArtist(id = "art_$artist", name = artist)), explicit = explicit)

    private fun artist(name: String, genres: List<String> = emptyList()) =
        SpotifyArtist(id = "art_$name", name = name, genres = genres)

    private val mixed = SuggestionConfig(source = SuggestionSource.MIXED, youTubeShare = 0.6f)

    private fun madeForYou(inputs: SuggestionInputs, config: SuggestionConfig = mixed) =
        buildSuggestionSections(inputs, config).first { it.title == MADE_FOR_YOU_TITLE }.mixed

    // --- Cross-source matching ---

    @Test
    fun `match key ignores case, accents, featured artists and version suffixes`() {
        val a = suggestionKey("Café del Mar (feat. Someone) - Remastered 2011", "Energy 52")
        val b = suggestionKey("cafe del mar", "ENERGY 52")
        val c = suggestionKey("Café del Mar [Official Video]", "Energy 52")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `different songs or artists do not match`() {
        assertFalse(suggestionKey("Song", "Artist A") == suggestionKey("Song", "Artist B"))
        assertFalse(suggestionKey("Song One", "Artist") == suggestionKey("Song Two", "Artist"))
    }

    @Test
    fun `a song present in both sources appears once, as the directly playable YouTube copy`() {
        val inputs = SuggestionInputs(
            liveYouTube = listOf(yt("y1", title = "Shared Song", artist = "Band")),
            liveSpotify = listOf(sp("s1", name = "Shared Song (Remastered)", artist = "Band")),
        )
        val result = madeForYou(inputs)
        assertEquals(1, result.size)
        assertTrue(result.single() is MixedSuggestion.FromYouTube)
    }

    // --- Ranking ---

    @Test
    fun `songs by artists the user listens to more rank higher`() {
        val inputs = SuggestionInputs(
            topArtists = listOf(artist("Loved"), artist("Liked")),
            liveYouTube = listOf(yt("a", artist = "Unknown"), yt("b", artist = "Liked"), yt("c", artist = "Loved")),
        )
        val ids = madeForYou(inputs).map { it.id }
        assertEquals(listOf("c", "b", "a"), ids)
    }

    @Test
    fun `genre overlap with top artists boosts unknown artists`() {
        val inputs = SuggestionInputs(
            topArtists = listOf(artist("Top", genres = listOf("shoegaze"))),
            artistGenres = mapOf("same genre" to setOf("shoegaze")),
            liveYouTube = listOf(yt("plain", artist = "Other"), yt("genre", artist = "Same Genre")),
        )
        assertEquals("genre", madeForYou(inputs).first().id)
    }

    @Test
    fun `at most two songs per artist in a row`() {
        val inputs = SuggestionInputs(
            liveYouTube = (1..5).map { yt("y$it", title = "Song $it", artist = "Prolific") } + yt("other"),
        )
        val result = madeForYou(inputs)
        assertEquals(2, result.count { it.artistName == "Prolific" })
        assertTrue(result.any { it.id == "other" })
    }

    @Test
    fun `heavily played recent tracks are left out of made for you`() {
        val recent = sp("r1", name = "On Repeat", artist = "Band")
        val inputs = SuggestionInputs(
            recentTopTracks = listOf(recent),
            liveYouTube = listOf(yt("y1", title = "On Repeat", artist = "Band"), yt("y2")),
        )
        assertEquals(listOf("y2"), madeForYou(inputs).map { it.id })
    }

    // --- Interleaving and sources ---

    @Test
    fun `mixed source follows the YouTube share`() {
        val inputs = SuggestionInputs(
            liveYouTube = (1..20).map { yt("y$it") },
            topTracks = (1..20).map { sp("s$it") },
        )
        val result = madeForYou(inputs, mixed.copy(youTubeShare = 0.6f, rowSize = 10))
        assertEquals(10, result.size)
        assertEquals(6, result.count { it is MixedSuggestion.FromYouTube })
        // Interleaved, not one block per source.
        assertTrue(result.take(5).any { it is MixedSuggestion.FromSpotify })
        assertTrue(result.take(5).any { it is MixedSuggestion.FromYouTube })
    }

    @Test
    fun `a short source is backfilled by the other`() {
        val inputs = SuggestionInputs(
            liveYouTube = listOf(yt("y1")),
            topTracks = (1..10).map { sp("s$it") },
        )
        val result = madeForYou(inputs, mixed.copy(rowSize = 8))
        assertEquals(8, result.size)
        assertEquals(1, result.count { it is MixedSuggestion.FromYouTube })
    }

    @Test
    fun `spotify-only source ignores YouTube data`() {
        val inputs = SuggestionInputs(
            liveYouTube = listOf(yt("y1"), yt("y2")),
            relatedByArtist = mapOf("Top" to listOf(yt("y3"))),
            topArtists = listOf(artist("Top")),
            topTracks = listOf(sp("s1")),
            liveSpotify = listOf(sp("s2", artist = "Top")),
        )
        val sections = buildSuggestionSections(inputs, SuggestionConfig(source = SuggestionSource.SPOTIFY_ONLY))
        val all = sections.flatMap { it.mixed }
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it is MixedSuggestion.FromSpotify })
    }

    @Test
    fun `explicit and video songs are filtered when hidden`() {
        val inputs = SuggestionInputs(
            liveYouTube = listOf(yt("clean"), yt("dirty", explicit = true), yt("video", videoType = "MUSIC_VIDEO_TYPE_OMV")),
            topTracks = listOf(sp("explicit", explicit = true)),
        )
        val ids = madeForYou(inputs, mixed.copy(hideExplicit = true, hideVideoSongs = true)).map { it.id }
        assertEquals(listOf("clean"), ids)
    }

    // --- Rows ---

    @Test
    fun `rows are made for you, because you like for top artists with related songs, then jump back in`() {
        val inputs = SuggestionInputs(
            topArtists = listOf(artist("First"), artist("Second"), artist("Third")),
            relatedByArtist = mapOf(
                "First" to listOf(yt("f1"), yt("f2")),
                "Third" to listOf(yt("t1")),
                "Not A Top Artist" to listOf(yt("x1")),
            ),
            liveYouTube = listOf(yt("y1")),
            recentTopTracks = listOf(sp("r1")),
        )
        val titles = buildSuggestionSections(inputs, mixed).map { it.title }
        assertEquals(
            listOf(MADE_FOR_YOU_TITLE, "${BECAUSE_YOU_LIKE_PREFIX}First", "${BECAUSE_YOU_LIKE_PREFIX}Third", JUMP_BACK_IN_TITLE),
            titles,
        )
    }

    @Test
    fun `because you like rows come from Spotify artist top tracks in spotify-only mode`() {
        val inputs = SuggestionInputs(
            topArtists = listOf(artist("First")),
            liveSpotify = listOf(sp("s1", artist = "First"), sp("s2", artist = "Elsewhere")),
            relatedByArtist = mapOf("First" to listOf(yt("f1"))),
        )
        val because = buildSuggestionSections(inputs, SuggestionConfig(source = SuggestionSource.SPOTIFY_ONLY))
            .first { it.title == "${BECAUSE_YOU_LIKE_PREFIX}First" }
        assertEquals(listOf("s1"), because.mixed.map { it.id })
    }

    @Test
    fun `no data means no rows`() {
        assertTrue(buildSuggestionSections(SuggestionInputs(), mixed).isEmpty())
    }

    @Test
    fun `all rows use the Spotify track grid type`() {
        val inputs = SuggestionInputs(
            topArtists = listOf(artist("First")),
            relatedByArtist = mapOf("First" to listOf(yt("f1"))),
            liveYouTube = listOf(yt("y1")),
            recentTopTracks = listOf(sp("r1")),
        )
        assertTrue(buildSuggestionSections(inputs, mixed).all { it.type == SectionType.MIXED_TRACKS })
    }

    // --- Home integration ---

    private val suggestionRow = SpotifyHomeSection(
        title = MADE_FOR_YOU_TITLE,
        type = SectionType.MIXED_TRACKS,
        mixed = listOf(MixedSuggestion.FromYouTube(yt("y1"))),
    )
    private val feed = SpotifyHomeSection(title = "Feed", type = SectionType.PLAYLISTS)

    private fun home(filter: SpotifyHomeFilter, layout: List<HomeSectionSetting> = DefaultHomeLayout) =
        buildSpotifyHome(listOf(feed), emptyList(), emptyList(), emptyMap(), filter, layout, suggestions = listOf(suggestionRow))

    @Test
    fun `suggestions follow the shortcuts and precede the feed in the default layout`() {
        assertEquals(listOf(SectionType.SPEED_DIAL, SectionType.MIXED_TRACKS, SectionType.PLAYLISTS), home(SpotifyHomeFilter.ALL).map { it.type })
    }

    @Test
    fun `suggestions show with music, not podcasts, and can be hidden`() {
        assertTrue(home(SpotifyHomeFilter.MUSIC).contains(suggestionRow))
        assertFalse(home(SpotifyHomeFilter.PODCASTS).contains(suggestionRow))
        val hidden = DefaultHomeLayout.map { if (it.section == HomeSectionId.FOR_YOU) it.copy(visible = false) else it }
        assertFalse(home(SpotifyHomeFilter.ALL, hidden).contains(suggestionRow))
    }

    @Test
    fun `saved layouts from before get the new section appended`() {
        val old = "speed_dial:true,shortcuts:true,new_releases:true,your_shows:true,spotify_feed:true"
        assertTrue(deserializeHomeLayout(old).any { it.section == HomeSectionId.FOR_YOU && it.visible })
    }

    @Test
    fun `youTube share setting is clamped to 0-100 percent`() {
        assertEquals(0f, youTubeShareFromPercent(-5), 0f)
        assertEquals(1f, youTubeShareFromPercent(250), 0f)
        assertEquals(0.6f, youTubeShareFromPercent(60), 0.001f)
    }
}
