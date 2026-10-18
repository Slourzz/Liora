package com.liora.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song

/**
 * Recuerda la portada de una canción para pintarla en la interfaz.
 *
 * La búsqueda (etiqueta del archivo, teléfono o tienda) se hace de fondo, así
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
