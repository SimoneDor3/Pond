/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.NewReleaseItem
import com.metrolist.music.models.ReleaseSource
import com.metrolist.music.models.splitNewReleases
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.fetchYouTubeReleaseSources
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.metrolist.innertube.models.AlbumItem

enum class NewReleaseTab { FOR_YOU, FOLLOWING, DISCOVER }

@HiltViewModel
class NewReleaseViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val _followingReleases = MutableStateFlow<List<NewReleaseItem>>(emptyList())
    private val _discoverReleases = MutableStateFlow<List<NewReleaseItem>>(emptyList())
    private val _isLoading = MutableStateFlow(true)

    private val _selectedTab = MutableStateFlow(NewReleaseTab.FOR_YOU)
    val selectedTab = _selectedTab.asStateFlow()
    val isLoading = _isLoading.asStateFlow()

    val displayedReleases = combine(_followingReleases, _discoverReleases, _selectedTab) { following, discover, tab ->
        when (tab) {
            NewReleaseTab.FOR_YOU -> following + discover
            NewReleaseTab.FOLLOWING -> following
            NewReleaseTab.DISCOVER -> discover
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val followingCount = _followingReleases
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val discoverCount = _discoverReleases
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            loadReleases()
        }
    }

    fun selectTab(tab: NewReleaseTab) {
        _selectedTab.value = tab
    }

    /**
     * Same sources as the home "New releases" row: Following holds releases by the artists the user
     * plays most or has bookmarked, Discover the rest of YouTube Music's popular new releases.
     */
    private suspend fun loadReleases() {
        try {
            val sources = fetchYouTubeReleaseSources(database, context.dataStore.get(HideExplicitKey, false))
            val (following, discover) = splitNewReleases(sources.fromListening, sources.popular, sources.artistRank, sources.followed)
            _followingReleases.value = following.map { it.toReleaseItem(isFromFollowedArtist = true) }
            _discoverReleases.value = discover.map { it.toReleaseItem(isFromFollowedArtist = false) }
        } catch (e: Exception) {
            reportException(e)
        }
        _isLoading.value = false
    }

    private fun AlbumItem.toReleaseItem(isFromFollowedArtist: Boolean) = NewReleaseItem(
        albumItem = this,
        source = ReleaseSource.YOUTUBE,
        spotifyAlbumId = null,
        artistIds = artists.orEmpty().mapNotNullTo(mutableSetOf()) { it.id },
        releaseDate = year?.toString(),
        isFromFollowedArtist = isFromFollowedArtist,
    )
}
