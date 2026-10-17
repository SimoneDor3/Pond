/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.HomeSectionsOrderKey
import com.metrolist.music.models.DefaultHomeLayout
import com.metrolist.music.models.HomeSectionId
import com.metrolist.music.models.deserializeHomeLayout
import com.metrolist.music.models.serializeHomeLayout
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun HomeSectionId.label(): String = when (this) {
    HomeSectionId.SPEED_DIAL -> stringResource(R.string.speed_dial)
    HomeSectionId.SHORTCUTS -> stringResource(R.string.home_section_shortcuts)
    HomeSectionId.FOR_YOU -> stringResource(R.string.home_section_for_you)
    HomeSectionId.NEW_RELEASES -> stringResource(R.string.spotify_new_releases)
    HomeSectionId.YOUR_SHOWS -> stringResource(R.string.your_shows)
    HomeSectionId.SPOTIFY_FEED -> stringResource(R.string.home_section_spotify_feed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsSettings(
    navController: NavController,
) {
    val haptic = LocalHapticFeedback.current

    val (layoutRaw, onLayoutChange) = rememberPreference(
        key = HomeSectionsOrderKey,
        defaultValue = serializeHomeLayout(DefaultHomeLayout)
    )

    var layout by remember(layoutRaw) {
        mutableStateOf(deserializeHomeLayout(layoutRaw))
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index in layout.indices && to.index in layout.indices) {
            layout = layout.toMutableList().apply { add(to.index, removeAt(from.index)) }
            onLayoutChange(serializeHomeLayout(layout))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Material3SettingsGroup(
            items = listOf(
                Material3SettingsItem(
                    title = {},
                    description = { Text(stringResource(R.string.home_sections_reorder_hint)) },
                    onClick = null
                )
            )
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .height((layout.size * 80).dp),
            userScrollEnabled = false,
        ) {
            items(layout, key = { it.section.id }) { setting ->
                ReorderableItem(reorderableState, key = setting.section.id) {
                    Material3SettingsGroup(
                        items = listOf(
                            Material3SettingsItem(
                                title = { Text(setting.section.label()) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(R.drawable.drag_handle),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .longPressDraggableHandle(
                                                    onDragStarted = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Switch(
                                            checked = setting.visible,
                                            onCheckedChange = { newValue ->
                                                layout = layout.map {
                                                    if (it.section == setting.section) it.copy(visible = newValue) else it
                                                }
                                                onLayoutChange(serializeHomeLayout(layout))
                                            },
                                            thumbContent = {
                                                Icon(
                                                    painter = painterResource(
                                                        if (setting.visible) R.drawable.check else R.drawable.close
                                                    ),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                                )
                                            }
                                        )
                                    }
                                },
                                onClick = {
                                    layout = layout.map {
                                        if (it.section == setting.section) it.copy(visible = !it.visible) else it
                                    }
                                    onLayoutChange(serializeHomeLayout(layout))
                                },
                            )
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(27.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.home_sections)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        actions = {
            IconButton(
                onClick = { onLayoutChange(serializeHomeLayout(DefaultHomeLayout)) },
                onLongClick = {},
            ) {
                Icon(
                    painterResource(R.drawable.restore),
                    contentDescription = stringResource(R.string.reset),
                )
            }
        },
    )
}
