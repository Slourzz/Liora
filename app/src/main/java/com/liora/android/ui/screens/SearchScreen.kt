package com.liora.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.SongRow
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.components.lioraGlass
import com.liora.android.ui.theme.isDark
import com.liora.android.ui.viewmodel.MusicViewModel
import com.liora.android.ui.viewmodel.UiState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/** Texto de ayuda del campo vacío. */
private const val SearchHint = "Canciones, artistas o álbumes"

/** Deja sitio a la barra superior (estado + título). */
private val SearchFieldTop = 96.dp

/** Alto del campo de búsqueda. */
private val SearchFieldHeight = 52.dp

/** Lo que hay que bajar la lista para no quedar debajo del campo. */
private val SearchContentTop = SearchFieldTop + SearchFieldHeight + 12.dp

/** El alto de la fila de filtros, debajo del campo. */
private val ScopeBarHeight = 48.dp

/** Qué se está buscando: en el telefono o en todo lo que dan los proveedores. */
private enum class SearchScope(val label: String) {
    LIBRARY("Biblioteca"),
    GLOBAL("Global"),
}

/**
 * Pantalla de búsqueda: filtra la biblioteca por título, artista o álbum
 * mientras se escribe, y además busca en los proveedores de las extensiones.
 *
 * Abajo del campo hay dos filtros: **Biblioteca** (lo que hay en el teléfono)
 * y **Global** (lo que devuelven los proveedores externos, que se puede
 * reproducir aunque la canción no esté descargada).
 */
@Composable
fun SearchScreen(
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
    val providerResults by viewModel.providerResults.collectAsState()
    val providerSearching by viewModel.providerSearching.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(SearchScope.LIBRARY) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val songs = (uiState as? UiState.Success)?.songs.orEmpty()
    val needle = query.trim()
    val results = remember(needle, songs) {
        if (needle.isEmpty()) {
            emptyList()
        } else {
            songs.filter { song ->
                song.title.contains(needle, ignoreCase = true) ||
                    song.artist.contains(needle, ignoreCase = true) ||
                    song.album.contains(needle, ignoreCase = true)
            }
        }
    }

    // El teclado aparece solo: entrar a buscar es querer escribir.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Ademas de la biblioteca, se busca en los proveedores de las extensiones
    // instaladas: lo que el usuario no tiene en el telefono tambien se puede
    // buscar. Se espera un poco antes de preguntarles para no llamar a un
    // proveedor con cada tecla.
    LaunchedEffect(needle, scope) {
        if (scope == SearchScope.GLOBAL && needle.length >= 2) {
            delay(450)
            viewModel.searchProviders(needle)
        } else {
            viewModel.searchProviders("")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(liquidGlass = liquidGlass, backdrop = backdrop, hazeState = hazeState),
        ) {
            when {
                uiState !is UiState.Success -> SearchMessage(
                    title = "Todavía no podemos buscar",
                    message = "Concede el permiso de audio y vuelve a intentarlo.",
                )

                scope == SearchScope.LIBRARY && needle.isEmpty() -> SearchMessage(
                    title = "¿Qué quieres escuchar?",
                    message = "Busca entre ${songs.size} canciones por título, artista o álbum.",
                )

                scope == SearchScope.GLOBAL && needle.isEmpty() -> SearchMessage(
                    title = "Buscar en todo",
                    message = "Busca en los proveedores que hayas instalado: " +
                        "las canciones que no tienes descargadas también se pueden reproducir.",
                )

                scope == SearchScope.LIBRARY && results.isEmpty() -> SearchMessage(
                    title = "Sin resultados",
                    message = "No hay nada que coincida con «$needle».",
                )

                scope == SearchScope.GLOBAL && providerResults.isEmpty() && !providerSearching ->
                    SearchMessage(
                        title = "Sin resultados",
                        message = "Ningún proveedor ha encontrado «$needle».",
                    )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(collapseConnection),
                    contentPadding = PaddingValues(
                        top = SearchContentTop + ScopeBarHeight,
                        bottom = bottomPadding,
                    ),
                ) {
                    if (scope == SearchScope.LIBRARY) {
                        items(
                            items = results,
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

                    if (providerSearching) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Buscando en los proveedores… (con redes lentas puede tardar)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (providerResults.isNotEmpty()) {
                        item {
                            Text(
                                text = if (scope == SearchScope.GLOBAL) "De los proveedores" else "De las extensiones",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                            )
                        }
                        items(
                            items = providerResults,
                            key = { "ext-${it.providerId}-${it.id}" },
                        ) { track ->
                            ProviderRow(
                                title = track.title,
                                subtitle = listOf(track.artist, track.album)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • "),
                                onClick = { viewModel.playProviderTrack(track) },
                            )
                        }
                    }
                }
            }
        }

        SearchField(
            query = query,
            onQueryChange = { query = it },
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            focusRequester = focusRequester,
            onSubmit = { keyboard?.hide() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = SearchFieldTop, start = 16.dp, end = 16.dp)
                .height(SearchFieldHeight),
        )

        GlassTopBar(
            title = "Buscar",
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Los dos filtros, justo debajo del campo.
        ScopeBar(
            scope = scope,
            onSelect = { scope = it },
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = SearchContentTop - 4.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** La fila de filtros: biblioteca o todo. */
@Composable
private fun ScopeBar(
    scope: SearchScope,
    onSelect: (SearchScope) -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .lioraGlass(
                liquidGlass = liquidGlass,
                backdrop = backdrop,
                hazeState = hazeState,
                surfaceColor = if (MaterialTheme.colorScheme.isDark) {
                    Color(0xFF121212).copy(alpha = 0.24f)
                } else {
                    Color(0xFFFAFAFA).copy(alpha = 0.34f)
                },
                shape = RoundedCornerShape(20.dp),
                showShadow = false,
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchScope.entries.forEach { option ->
            val selected = option == scope
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Campo de búsqueda de cristal: lupa, texto y botón para borrar. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldTint = if (MaterialTheme.colorScheme.isDark) {
        Color(0xFF121212).copy(alpha = 0.24f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.34f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .lioraGlass(
                liquidGlass = liquidGlass,
                backdrop = backdrop,
                hazeState = hazeState,
                surfaceColor = fieldTint,
                shape = RoundedCornerShape(26.dp),
                showShadow = false,
            )
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = SearchHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Borrar búsqueda",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onQueryChange("") },
            )
        }
    }
}

/** Una fila de resultado de un proveedor externo. */
@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Mensaje centrado para los estados sin lista (pista, sin resultados…). */
@Composable
private fun SearchMessage(
    title: String,
    message: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = SearchContentTop),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1.4f))
    }
}
