package com.liora.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liora.android.R
import com.liora.android.data.model.Song
import com.liora.android.ui.components.SongArtworkBox
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.viewmodel.MusicViewModel
import com.liora.android.ui.viewmodel.UiState
import com.styropyr0.prismal.sources.PrismalGlassLayer
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La pantalla de inicio.
 *
 * El diseño es el de una app de musica conocida: un logo arriba a la
 * izquierda, un avatar a la derecha, un saludo grande y después las secciones
 * de canciones y de albums. Todo aquí es Compose nativo, sin nada copiado.
 */
@Composable
fun HomeScreen(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
    bottomPadding: Dp,
    collapseConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
    onOpenSearch: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val signingIn by viewModel.signingIn.collectAsState()
    val avatarUri by viewModel.accountAvatarUri.collectAsState()

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

                    is UiState.Success -> HomeContent(
                        songs = state.songs,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        avatarUri = avatarUri,
                        onOpenAccount = { viewModel.startSignIn("youtube") },
                        onPlay = viewModel::playSong,
                        onOpenSearch = onOpenSearch,
                        bottomPadding = bottomPadding,
                    )
                }
            }
        }

        // El inicio de sesion se hace en la pagina real del servicio: el
        // usuario escribe sus datos alli, no en Liora. Cubre la pantalla
        // entera, con su propio fondo, para que no se vea el inicio debajo.
        val service = signingIn
        if (service != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                WebLoginScreen(
                    url = sessionUrl(service),
                    title = "Iniciar sesión",
                    onCaptured = { cookies, sapisid, avatar ->
                        viewModel.onSessionCaptured(service, cookies, sapisid, avatar)
                    },
                    onCancel = viewModel::cancelSignIn,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * La página de inicio de sesión de cada servicio.
 *
 * Es la página real del servicio: desde ahí se entra a la cuenta de Google
 * y se vuelve al servicio, que es donde Liora recoge las cookies.
 */
private fun sessionUrl(service: String): String = when (service) {
    "youtube" -> "https://music.youtube.com/"
    else -> "https://accounts.google.com/"
}

/**
 * El contenido del inicio.
 *
 * Una sola lista perezosa: cada sección es un elemento y sus carruseles van
 * dentro. Así el inicio no se dibuja entero cuando hay muchas canciones.
 */
@Composable
private fun HomeContent(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    avatarUri: String?,
    onOpenAccount: () -> Unit,
    onPlay: (Song) -> Unit,
    onOpenSearch: () -> Unit,
    bottomPadding: Dp,
) {
    val recent = remember(songs) { songs.take(20) }
    val quick = remember(songs) {
        songs.drop(5).plus(songs.take(5)).distinctBy { it.id }.take(8)
    }
    val albums = remember(songs) { songs.groupBy { it.album }.entries.take(14).toList() }
    var showLarge by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
    ) {
        item("cabecera") {
            HomeTopBar(
                avatarUri = avatarUri,
                onOpenAccount = onOpenAccount,
            )
        }

        item("saludo") {
            Text(
                text = "Escuchar ahora",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 29.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        if (songs.isEmpty()) {
            item("vacio") {
                EmptyState(
                    title = "Todavía no hay canciones",
                    message = "Copia música a tu teléfono y vuelve a abrir Liora.",
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            return@LazyColumn
        }

        item("recientes-cabecera") {
            RecentsHeader(
                largeCards = showLarge,
                onToggleLayout = { showLarge = !showLarge },
            )
        }

        item("recientes") {
            if (showLarge) {
                LargeRecentRow(songs = recent.take(15), onPlay = onPlay)
            } else {
                RecentRow(
                    songs = recent,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onPlay = onPlay,
                )
            }
        }

        item("selecciones-cabecera") {
            SectionTitle(title = "Selecciones rápidas", onClick = onOpenSearch)
        }
        item("selecciones") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(quick, key = { "quick-${it.id}" }) { song ->
                    SongSquareCard(song = song, subtitle = song.artist, onClick = { onPlay(song) })
                }
            }
        }

        item("escuchadas-cabecera") { SectionTitle(title = "Canciones más escuchadas") }
        item("escuchadas") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(recent.take(14), key = { "played-${it.id}" }) { song ->
                    SongSquareCard(song = song, subtitle = song.artist, onClick = { onPlay(song) })
                }
            }
        }

        if (albums.isNotEmpty()) {
            item("albumes-cabecera") { SectionTitle(title = "Álbumes") }
            item("albumes") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(albums, key = { "album-${it.key}" }) { (album, songs) ->
                        SongSquareCard(
                            song = songs.first(),
                            title = album,
                            subtitle = "${songs.size} canciones",
                            onClick = { onPlay(songs.first()) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * La foto de la cuenta, si la hay.
 *
 * Se baja en segundo plano: mientras llega se ve el icono de perfil.
 */
@Composable
private fun rememberAccountPhoto(avatarUri: String?): ImageBitmap? {
    val context = LocalContext.current
    var photo by remember(avatarUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarUri) {
        photo = avatarUri?.let { url ->
            withContext(Dispatchers.IO) {
                ProviderArtworkLoader.load(context, url, 96)?.asImageBitmap()
            }
        }
    }
    return photo
}

/**
 * La barra de arriba: el logo de Liora a la izquierda, el avatar a la derecha.
 *
 * El avatar es el botón de la cuenta: al tocarlo se abre el inicio de
 * sesión. Cuando hay sesión, se ve la foto de la cuenta.
 */
@Composable
private fun HomeTopBar(
    avatarUri: String?,
    onOpenAccount: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(46.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.liora_home_logo),
            contentDescription = "Liora",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(42.dp)
                .height(32.dp),
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.58f),
                    shape = CircleShape,
                )
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                .clickable(onClick = onOpenAccount),
            contentAlignment = Alignment.Center,
        ) {
            val photo = rememberAccountPhoto(avatarUri)
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = "Foto de la cuenta",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Abrir cuenta",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** La cabecera de "Recents", con el botón de cambiar de disposición. */
@Composable
private fun RecentsHeader(
    largeCards: Boolean,
    onToggleLayout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp)
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Recents",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleLayout, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (largeCards) Icons.Rounded.List else Icons.Rounded.GridView,
                contentDescription = if (largeCards) {
                    "Cambiar a bloques de lista"
                } else {
                    "Cambiar a tarjetas"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Las canciones recientes, en bloques de cuatro.
 *
 * Cada bloque es una columna: así el carrusel se lee como una rejilla.
 */
@Composable
private fun RecentRow(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = songs.take(20).chunked(4),
            key = { block -> "recent-block-${block.first().id}" },
        ) { block ->
            Column(
                modifier = Modifier.fillParentMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                block.forEach { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SongArtworkBox(
                            song = song,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { onPlay(song) },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onPlay(song) }
                                .padding(start = 10.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = song.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Opciones de ${song.title}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Las mismas canciones recientes, en tarjetas grandes. */
@Composable
private fun LargeRecentRow(
    songs: List<Song>,
    onPlay: (Song) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(songs, key = { "large-recent-${it.id}" }) { song ->
            Box(
                modifier = Modifier
                    .width(286.dp)
                    .height(315.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPlay(song) },
            ) {
                SongArtworkBox(song = song, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.54f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(14.dp),
                ) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artist,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Una tarjeta cuadrada con su título y su subtítulo.
 *
 * La usan las tres secciones de carrusel: así hay un solo componente.
 */
@Composable
private fun SongSquareCard(
    song: Song,
    subtitle: String,
    onClick: () -> Unit,
    title: String = song.title,
) {
    Column(
        modifier = Modifier
            .width(138.dp)
            .clickable(onClick = onClick),
    ) {
        SongArtworkBox(
            song = song,
            modifier = Modifier
                .size(138.dp)
                .clip(RoundedCornerShape(11.dp)),
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** El título de una sección. */
@Composable
private fun SectionTitle(
    title: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp)
            .padding(top = 20.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 21.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
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
