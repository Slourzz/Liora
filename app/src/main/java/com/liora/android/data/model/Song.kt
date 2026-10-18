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
) {

    /** "Artista • Álbum" sin partes vacías, listo para los subtítulos. */
    val subtitle: String
        get() = listOf(artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
}
