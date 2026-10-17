package com.metrolist.music.models

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyHomeFeedSection
import com.metrolist.spotify.models.SpotifyPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CraftedForYouTest {

    private fun spotify(id: String, madeFor: String? = "me") = SpotifyHomeFeedItem.Playlist(
        uri = "spotify:playlist:$id", id = id, name = "Spotify $id", description = null, format = null,
        totalCount = 50, imageUrl = "https://i.scdn.co/$id", extractedColorHex = null, ownerName = "Spotify",
        madeForUsername = madeFor,
    )

    private fun feed(vararg items: SpotifyHomeFeedItem, typename: String = "HomeGenericSectionData") =
        SpotifyHomeFeedSection(sectionUri = "spotify:section:x", title = "Section", typename = typename, totalCount = items.size, items = items.toList())

    private fun youTube(id: String) = PlaylistItem(
        id = id, title = "YouTube $id", author = Artist(name = "YouTube Music", id = null), songCountText = null,
        thumbnail = "https://yt/$id.jpg", playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
    )

    private fun mixed(share: Float = 0.5f) = CraftedConfig(source = SuggestionSource.MIXED, youTubeShare = share)

    // --- Picking the mixes ---

    @Test
    fun `only Spotify playlists made for the user are picked, once each`() {
        val sections = listOf(
            feed(spotify("daily1"), spotify("editorial", madeFor = null)),
            feed(spotify("daily1"), spotify("discover"), typename = "HomeShortsSectionData"),
        )
        val ids = buildCraftedForYou(sections, emptyList(), CraftedConfig(SuggestionSource.SPOTIFY_ONLY)).map { it.id }
        assertEquals(listOf("daily1", "discover"), ids)
    }

    @Test
    fun `only personal YouTube Music mixes are picked, once each`() {
        val home = listOf(youTube("RDTMAK5uy_supermix"), youTube("RDCLAK5uy_curated"), youTube("PLuser"), youTube("RDTMAK5uy_supermix"))
        val ids = buildCraftedForYou(emptyList(), home, mixed()).map { it.id }
        assertEquals(listOf("RDTMAK5uy_supermix"), ids)
    }

    // --- Mixing the sources ---

    @Test
    fun `mixed source interleaves the services at the YouTube share`() {
        val sections = listOf(feed(*(1..6).map { spotify("s$it") }.toTypedArray()))
        val home = (1..6).map { youTube("RDTMAK$it") }
        val result = buildCraftedForYou(sections, home, mixed(0.5f).copy(maxSize = 6))
        assertEquals(6, result.size)
        assertEquals(3, result.count { it is CraftedMix.YouTube })
        assertTrue(result.take(2).any { it is CraftedMix.Spotify } && result.take(2).any { it is CraftedMix.YouTube })
    }

    @Test
    fun `a service with fewer mixes is backfilled by the other`() {
        val sections = listOf(feed(*(1..6).map { spotify("s$it") }.toTypedArray()))
        val result = buildCraftedForYou(sections, listOf(youTube("RDTMAK1")), mixed(0.5f).copy(maxSize = 5))
        assertEquals(5, result.size)
        assertEquals(1, result.count { it is CraftedMix.YouTube })
    }

    @Test
    fun `spotify-only source leaves out YouTube Music mixes`() {
        val result = buildCraftedForYou(listOf(feed(spotify("s1"))), listOf(youTube("RDTMAK1")), CraftedConfig(SuggestionSource.SPOTIFY_ONLY))
        assertTrue(result.all { it is CraftedMix.Spotify })
        assertEquals(1, result.size)
    }

    @Test
    fun `no mixes from either service means an empty row`() {
        assertTrue(buildCraftedForYou(emptyList(), emptyList(), mixed()).isEmpty())
    }

    // --- Paging the YouTube Music home ---

    /** Fake home: page index → (playlists, next continuation). */
    private fun pager(vararg pages: List<PlaylistItem>): Pair<MutableList<String?>, (String?) -> YouTubeHomePage?> {
        val requested = mutableListOf<String?>()
        return requested to { continuation ->
            requested += continuation
            val index = continuation?.removePrefix("page")?.toInt() ?: 0
            pages.getOrNull(index)?.let { YouTubeHomePage(it, continuation = "page${index + 1}".takeIf { index + 1 < pages.size }) }
        }
    }

    @Test
    fun `home pages are read until the personal mixes show up`() {
        val (requested, fetch) = pager(listOf(youTube("PLa")), listOf(youTube("PLb")), listOf(youTube("RDTMAK1")), listOf(youTube("RDTMAK2")))
        val playlists = collectYouTubeHomePlaylists(maxPages = 5, fetchPage = fetch)
        assertEquals(listOf(null, "page1", "page2"), requested)
        assertEquals(listOf("PLa", "PLb", "RDTMAK1"), playlists.map { it.id })
    }

    @Test
    fun `home paging stops at the page limit, the last page, or a failed page`() {
        val (limited, fetchMany) = pager(*(1..10).map { listOf(youTube("PL$it")) }.toTypedArray())
        collectYouTubeHomePlaylists(maxPages = 3, fetchPage = fetchMany)
        assertEquals(3, limited.size)

        val (short, fetchShort) = pager(listOf(youTube("PLa")))
        assertEquals(listOf("PLa"), collectYouTubeHomePlaylists(maxPages = 5, fetchPage = fetchShort).map { it.id })
        assertEquals(1, short.size)

        assertTrue(collectYouTubeHomePlaylists(maxPages = 5) { null }.isEmpty())
    }

    @Test
    fun `youTube share setting is clamped to 0-100 percent`() {
        assertEquals(0f, youTubeShareFromPercent(-5), 0f)
        assertEquals(1f, youTubeShareFromPercent(250), 0f)
        assertEquals(0.6f, youTubeShareFromPercent(60), 0.001f)
    }

    // --- Home integration ---

    private val crafted = listOf(CraftedMix.Spotify(spotify("daily1")), CraftedMix.YouTube(youTube("RDTMAK1")))
    private val feedSection = SpotifyHomeSection(
        title = "Made For You",
        type = SectionType.PLAYLISTS,
        playlists = listOf(SpotifyPlaylist(id = "daily1", name = "Daily Mix 1"), SpotifyPlaylist(id = "other", name = "Other")),
    )
    private val duplicatesOnly = SpotifyHomeSection(
        title = "Your mixes",
        type = SectionType.PLAYLISTS,
        playlists = listOf(SpotifyPlaylist(id = "daily1", name = "Daily Mix 1")),
    )

    private fun home(filter: SpotifyHomeFilter = SpotifyHomeFilter.ALL, layout: List<HomeSectionSetting> = DefaultHomeLayout) =
        buildSpotifyHome(listOf(feedSection, duplicatesOnly), emptyList(), emptyList(), emptyMap(), filter, layout, crafted = crafted)

    @Test
    fun `crafted for you is one playlist row between the shortcuts and the feed`() {
        val sections = home()
        assertEquals(listOf(SectionType.SPEED_DIAL, SectionType.CRAFTED_MIXES, SectionType.PLAYLISTS), sections.map { it.type })
        val row = sections[1]
        assertEquals(CRAFTED_FOR_YOU_TITLE, row.title)
        assertEquals(crafted, row.crafted)
    }

    @Test
    fun `the feed drops Spotify mixes already in crafted for you, and sections left empty`() {
        val feedRows = home().filter { it.type == SectionType.PLAYLISTS }
        assertEquals(listOf("Made For You"), feedRows.map { it.title })
        assertEquals(listOf("other"), feedRows.single().playlists.map { it.id })
    }

    @Test
    fun `crafted for you shows with music, not podcasts, and can be hidden`() {
        assertTrue(home(SpotifyHomeFilter.MUSIC).any { it.type == SectionType.CRAFTED_MIXES })
        assertFalse(home(SpotifyHomeFilter.PODCASTS).any { it.type == SectionType.CRAFTED_MIXES })
        val hidden = DefaultHomeLayout.map { if (it.section == HomeSectionId.FOR_YOU) it.copy(visible = false) else it }
        assertFalse(home(layout = hidden).any { it.type == SectionType.CRAFTED_MIXES })
    }

    @Test
    fun `hiding crafted for you keeps the Spotify mixes in the feed`() {
        val hidden = DefaultHomeLayout.map { if (it.section == HomeSectionId.FOR_YOU) it.copy(visible = false) else it }
        val feedIds = home(layout = hidden).filter { it.type == SectionType.PLAYLISTS }.flatMap { it.playlists }.map { it.id }
        assertEquals(listOf("daily1", "other", "daily1"), feedIds)
    }

    @Test
    fun `saved layouts from before get the new section appended`() {
        val old = "speed_dial:true,shortcuts:true,new_releases:true,your_shows:true,spotify_feed:true"
        assertTrue(deserializeHomeLayout(old).any { it.section == HomeSectionId.FOR_YOU && it.visible })
    }
}
