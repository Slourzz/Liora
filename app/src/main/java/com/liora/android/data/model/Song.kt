package com.liora.android.data.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    /** Carpeta que contiene el archivo; Ajustes la usa para excluirla. */
    val folder: String,
    /** Alarmas, timbres o avisos del teléfono; Ajustes puede ocultarlos. */
    val systemSound: Boolean = false,
    /**
     * Id de Spotify de la pista, cuando la fuente lo trae. Spicy Lyrics lo usa
     * como primera vía; si falta, la app cae a Apple Music sin fallar.
     */
    val spotifyTrackId: String? = null,
    /**
     * Las letras de la canción, cuando ya están guardadas con ella.
     *
     * Viajan dentro de la propia pista: en cuanto una fuente entrega la letra
     * se guarda con la canción y a partir de ahí ya no hay que buscarla otra
     * vez. Va nulo mientras nadie la ha buscado todavía.
     */
    val lyrics: LyricsResult? = null,
) {

    /** "Artista • Álbum" sin partes vacías, listo para los subtítulos. */
    val subtitle: String
        get() = listOf(artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
}
