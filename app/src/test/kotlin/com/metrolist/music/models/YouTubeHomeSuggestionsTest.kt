package com.metrolist.music.models

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeHomeSuggestionsTest {

    private fun album(id: String, artistId: String? = null, year: Int? = null) = AlbumItem(
        browseId = id, playlistId = "OLAK$id", title = id,
        artists = artistId?.let { listOf(Artist(name = it, id = it)) }, year = year, thumbnail = "",
    )

    private fun playlist(id: String) = PlaylistItem(
        id = id, title = id, author = null, songCountText = null, thumbnail = null,
        playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
    )

    private fun artist(id: String) = ArtistItem(id = id, title = id, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")

    @Test
    fun `shortcuts take one item per shelf in turn, skipping songs, mixes and duplicates`() {
        val shelves = listOf(
            listOf(song("s1"), album("a1"), artist("ar1"), album("a2")),
            listOf(playlist("RDTMAKmix"), playlist("p1"), album("a1"), playlist("p2")),
            listOf(song("s2")),
        )
        assertEquals(
            listOf("a1", "p1", "ar1", "a2", "p2"),
            pickYouTubeShortcuts(shelves, count = 8).map { it.id },
        )
        assertEquals(listOf("a1", "p1", "ar1"), pickYouTubeShortcuts(shelves, count = 3).map { it.id })
    }

    @Test
    fun `new releases put the listened artists' newest first, then popular ones`() {
        val rank = mapOf("top" to 0, "second" to 1)
        val result = rankNewReleases(
            fromListening = listOf(album("old", "top", 2025), album("new2", "second", 2026), album("new1", "top", 2026)),
            popular = listOf(album("hit1", "x"), album("hit-second", "second"), album("hit2", "y"), album("new1", "top", 2026)),
            artistRank = rank,
            limit = 6,
        )
        assertEquals(listOf("new1", "new2", "old", "hit1", "hit2", "hit-second"), result.map { it.id })
    }

    @Test
    fun `popular releases fill the row when nothing comes from listening`() {
        val popular = (1..5).map { album("hit$it", "x") }
        assertEquals(popular.take(3), rankNewReleases(emptyList(), popular, emptyMap(), limit = 3))
    }
}
