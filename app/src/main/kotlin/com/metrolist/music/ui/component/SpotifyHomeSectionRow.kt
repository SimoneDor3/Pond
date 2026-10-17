/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.R
import com.metrolist.music.constants.GridThumbnailHeight
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.models.HomePodcast
import com.metrolist.music.models.CraftedMix
import com.metrolist.music.models.SectionType
import com.metrolist.music.models.SpotifyHomeSection
import com.metrolist.music.utils.SPOTIFY_ID_PREFIX
import com.metrolist.music.utils.toAlbumItem
import com.metrolist.music.utils.toArtistItem
import com.metrolist.music.utils.toPlaylistItem
import com.metrolist.music.utils.toSongItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Resolves the display title for a Spotify home section.
 * Handles special cases like "spotify_because_you_like:ArtistName".
 */
@Composable
fun resolveSpotifySectionTitle(section: SpotifyHomeSection): String {
    val title = section.title
    return when {
        title.startsWith("spotify_because_you_like:") -> {
            val artistName = title.removePrefix("spotify_because_you_like:")
            stringResource(R.string.spotify_because_you_like, artistName)
        }
        title == "spotify_top_artists" -> stringResource(R.string.spotify_top_artists)
        title == "spotify_made_for_you" -> stringResource(R.string.spotify_made_for_you)
        title == "crafted_for_you" -> stringResource(R.string.crafted_for_you)
        title == "spotify_discover" -> stringResource(R.string.spotify_discover)
        title == "spotify_your_playlists" -> stringResource(R.string.spotify_your_playlists)
        title == "spotify_new_releases" -> stringResource(R.string.spotify_new_releases)
        title == "your_shows" -> stringResource(R.string.your_shows)
        else -> title
    }
}

/**
 * Renders a section of tracks as a horizontal scrollable grid (4 rows).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyTrackSectionRow(
    tracks: List<SpotifyTrack>,
    horizontalItemWidth: Dp,
    isPlaying: Boolean,
    currentMediaId: String?,
    onTrackClick: (SpotifyTrack) -> Unit,
    onTrackLongClick: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyHorizontalGrid(
        state = rememberLazyGridState(),
        rows = GridCells.Fixed(4),
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
            .asPaddingValues(),
        modifier = modifier
            .fillMaxWidth()
            .height(ListItemHeight * 4)
    ) {
        items(
            items = tracks,
            key = { "spotify_track_${it.id}" }
        ) { track ->
            val songItem = remember(track.id) { track.toSongItem() }
            YouTubeListItem(
                item = songItem,
                isActive = false,
                isPlaying = isPlaying,
                isSwipeable = false,
                modifier = Modifier
                    .width(horizontalItemWidth)
                    .combinedClickable(
                        onClick = { onTrackClick(track) },
                        onLongClick = { onTrackLongClick(track) },
                    ),
            )
        }
    }
}

/**
 * Renders the "Crafted for you" mixes from both services as one row of playlist cards,
 * styled like [SpotifyPlaylistSectionRow].
 */
@Composable
fun SpotifyCraftedMixRow(
    mixes: List<CraftedMix>,
    onMixClick: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = mixes,
            key = { "crafted_mix_${it.javaClass.simpleName}_${it.id}" }
        ) { mix ->
            val playlistItem = remember(mix) { mix.toPlaylistItem() }
            YouTubeGridItem(
                item = playlistItem,
                isActive = false,
                isPlaying = false,
                modifier = Modifier.clickable { onMixClick(playlistItem) },
            )
        }
    }
}

/** Spotify mixes get the "spotify:" ID prefix so playlist navigation opens the Spotify screen. */
private fun CraftedMix.toPlaylistItem(): PlaylistItem = when (this) {
    is CraftedMix.YouTube -> playlist
    is CraftedMix.Spotify -> PlaylistItem(
        id = "$SPOTIFY_ID_PREFIX${playlist.id}",
        title = playlist.name,
        author = playlist.ownerName?.let { Artist(name = it, id = null) },
        songCountText = null,
        thumbnail = playlist.imageUrl,
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null,
    )
}

/**
 * Renders a section of artists as a horizontal scrollable row with circular images.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyArtistSectionRow(
    artists: List<SpotifyArtist>,
    onArtistClick: (SpotifyArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = artists,
            key = { "spotify_artist_${it.id}" }
        ) { artist ->
            val thumbnail = remember(artist.id) {
                artist.images.firstOrNull { it.width in 200..400 }?.url
                    ?: artist.images.firstOrNull()?.url
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(GridThumbnailHeight + 24.dp)
                    .padding(horizontal = 6.dp)
                    .combinedClickable(
                        onClick = { onArtistClick(artist) },
                    ),
            ) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(GridThumbnailHeight)
                        .clip(CircleShape),
                )
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Renders a section of albums as a horizontal row with cover art.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyAlbumSectionRow(
    albums: List<SpotifyAlbum>,
    onAlbumClick: (SpotifyAlbum) -> Unit,
    onAlbumPlay: ((SpotifyAlbum) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = albums,
            key = { "spotify_album_${it.id}" }
        ) { album ->
            val albumItem = remember(album.id) { album.toAlbumItem() }
            YouTubeGridItem(
                item = albumItem,
                isActive = false,
                isPlaying = false,
                onPlayClick = onAlbumPlay?.let { cb -> { cb(album) } },
                modifier = Modifier
                    .width(GridThumbnailHeight + 24.dp)
                    .padding(horizontal = 6.dp)
                    .combinedClickable(
                        onClick = { onAlbumClick(album) },
                    ),
            )
        }
    }
}

/**
 * Renders a section of playlists as a horizontal row.
 * Optional [onPlaylistLongClick] enables "Pin to speed dial" from the long-press menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlaylistSectionRow(
    playlists: List<SpotifyPlaylist>,
    onPlaylistClick: (SpotifyPlaylist) -> Unit,
    onPlaylistLongClick: ((SpotifyPlaylist) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier,
    ) {
        items(
            items = playlists,
            key = { "spotify_playlist_${it.id}" }
        ) { playlist ->
            val playlistItem = remember(playlist.id) { playlist.toPlaylistItem() }
            YouTubeGridItem(
                item = playlistItem,
                isActive = false,
                isPlaying = false,
                modifier = Modifier
                    .pointerInput(playlist.id) {
                        detectTapGestures(
                            onTap = { onPlaylistClick(playlist) },
                            onLongPress = { onPlaylistLongClick?.invoke(playlist) },
                        )
                    },
            )
        }
    }
}

private class ShortcutTile(
    val title: String,
    val coverUrl: String?,
    val isCircle: Boolean = false,
    val isNew: Boolean = false,
    val progress: Float? = null,
    val onClick: () -> Unit,
)

/**
 * Spotify-style untitled shortcuts grid: two columns of compact cover + title tiles.
 * YouTube Music podcasts come first (dot for a new episode, bar when one is in progress), then
 * Spotify's music shortcuts. Not lazy on purpose: it sits inside the home LazyColumn
 * and holds a handful of items.
 */
@Composable
fun SpotifyShortcutGrid(
    section: SpotifyHomeSection,
    onShortcutClick: (SpotifyHomeFeedItem) -> Unit,
    onPodcastClick: (HomePodcast) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles = section.podcasts.map { podcast ->
        ShortcutTile(
            title = podcast.title,
            coverUrl = podcast.thumbnail,
            isNew = podcast.hasNew,
            progress = podcast.progress,
            onClick = { onPodcastClick(podcast) },
        )
    } + section.shortcuts.map { item ->
        when (item) {
            is SpotifyHomeFeedItem.Playlist -> ShortcutTile(item.name, item.imageUrl, onClick = { onShortcutClick(item) })
            is SpotifyHomeFeedItem.Album -> ShortcutTile(item.name, item.imageUrl, onClick = { onShortcutClick(item) })
            is SpotifyHomeFeedItem.Artist -> ShortcutTile(item.name, item.imageUrl, isCircle = true, onClick = { onShortcutClick(item) })
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiles.forEach { tile ->
                    SpotifyShortcutTile(tile = tile, modifier = Modifier.weight(1f))
                }
                if (rowTiles.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpotifyShortcutTile(
    tile: ShortcutTile,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = tile.onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = tile.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .then(if (tile.isCircle) Modifier.clip(CircleShape) else Modifier),
            )
            Text(
                text = tile.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            if (tile.isNew) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        tile.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}
