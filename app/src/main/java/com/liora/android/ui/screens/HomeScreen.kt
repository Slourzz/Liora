package com.liora.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.SongRow
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.viewmodel.MusicViewModel
import com.liora.android.ui.viewmodel.UiState
import dev.chrisbanes.haze.HazeState

@Composable
fun HomeScreen(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
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
            when {
                !hasPermission -> PermissionPrompt(
                    onRequestPermissions = onRequestPermissions,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> when (val state = uiState) {
                    is UiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is UiState.Error -> EmptyState(
                        title = "No se pudo leer tu música",
                        message = state.message,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is UiState.Success -> {
                        if (state.songs.isEmpty()) {
                            EmptyState(
                                title = "Todavía no hay canciones",
                                message = "Copia música a tu teléfono y vuelve a abrir Liora.",
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
                                items(
                                    items = state.songs,
                                    key = { it.id },
                                ) { song ->
                                    SongRow(
                                        song = song,
                                        onClick = { viewModel.playSong(song) },
                                        subtitle = song.subtitle,
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
            title = "Liora Music",
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun PermissionPrompt(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Liora necesita permiso para leer tu música",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Conceder permiso de audio")
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
