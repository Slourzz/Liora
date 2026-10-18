package com.liora.android.data.model

/** Carpeta del teléfono que aporta audio a la biblioteca de Liora. */
data class AudioFolder(
    /** Ruta completa; es la clave con la que se guarda la exclusión. */
    val path: String,
    /** Último tramo de la ruta, lo que se muestra como nombre. */
    val name: String,
    val songCount: Int,
)

/** Agrupa las canciones por carpeta para el selector de Ajustes. */
fun List<Song>.toAudioFolders(): List<AudioFolder> =
    groupBy { it.folder }
        .map { (path, songs) ->
            AudioFolder(
                path = path,
                name = path.substringAfterLast('/').ifEmpty { path },
                songCount = songs.size,
            )
        }
        .sortedWith(compareByDescending<AudioFolder> { it.songCount }.thenBy { it.name.lowercase() })
