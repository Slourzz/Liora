package com.liora.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song

/**
 * Recuerda la portada de una canción para pintarla en la interfaz.
 *
 * La búsqueda (etiqueta del archivo, teléfono o tienda) se hace en fondo, así
 * que las listas no se frenan: mientras llega devuelve null y quien la use
 * enseña su propio hueco. [sizePx] es el lado que se necesita, para no cargar
 * copias más grandes de lo que se va a dibujar.
 */
@Composable
fun rememberSongArtwork(song: Song, sizePx: Int = 256): ImageBitmap? {
    val context = LocalContext.current
    var artwork by remember(song.id, sizePx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(song.id, sizePx) {
        artwork = ArtworkRepository.displayArtwork(context, song, sizePx)?.asImageBitmap()
    }
    return artwork
}

/**
 * La portada de una canción, con su hueco mientras no llega.
 *
 * Es el mismo componente que usan las listas y las tarjetas del inicio.
 */
@Composable
fun SongArtworkBox(
    song: Song,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = rememberSongArtwork(song, sizePx = 256)
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = tint,
            )
        }
    }
}
