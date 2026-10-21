package com.liora.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.SongRow
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.viewmodel.MusicViewModel
import com.liora.android.ui.viewmodel.UiState
import dev.chrisbanes.haze.HazeState

/**
 * Biblioteca: la música agrupada por álbum.
 */
@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
    bottomPadding: Dp,
    collapseConnection: NestedScrollConnection,
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(liquidGlass = liquidGlass, backdrop = backdrop, hazeState = hazeState),
        ) {
            when (val state = uiState) {
                is UiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                is UiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                is UiState.Success -> {
                    val albums = remember(state.songs) {
                        state.songs
                            .groupBy { it.album.ifBlank { "Sin álbum" } }
                            .toSortedMap()
                    }

                    if (albums.isEmpty()) {
                        Text(
                            text = "No se encontraron canciones en el dispositivo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(collapseConnection),
                            contentPadding = PaddingValues(
                                top = 92.dp,
                                bottom = bottomPadding,
                            ),
                        ) {
                            albums.forEach { (album, songs) ->
                                item(key = "album-$album") {
                                    AlbumHeader(
                                        album = album,
                                        songCount = songs.size,
                                    )
                                }
                                items(
                                    items = songs,
                                    key = { "song-${it.id}" },
                                ) { song ->
                                    SongRow(
                                        song = song,
                                        onClick = { viewModel.playSong(song) },
                                        isCurrent = song.id == currentSong?.id,
                                        isPlaying = isPlaying,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(
            title = "Biblioteca",
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun AlbumHeader(
    album: String,
    songCount: Int,
) {
    Text(
        text = "$album · $songCount",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}
