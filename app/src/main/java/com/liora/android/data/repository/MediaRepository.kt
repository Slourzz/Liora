package com.liora.android.data.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.liora.android.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun getLocalSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
        )

        // Excluye tonos de llamada, alarmas, notificaciones y sonidos del sistema,
        // que en algunas ROM aparecen marcados como música (IS_MUSIC != 0).
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= 10000")
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            // DATA está obsoleto pero sigue informando la ruta real del archivo,
            // que es lo que necesitamos para agrupar y excluir carpetas.
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Desconocido"
                val rawArtist = cursor.getString(artistColumn).orEmpty()
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)

                val path = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                val folder = path.substringBeforeLast('/', "").ifEmpty { UnknownFolder }

                // MediaStore devuelve "<unknown>" cuando falta el dato: el
                // artista se deja vacío y el álbum cae al nombre de la carpeta.
                val artist = if (rawArtist.equals(Anonymous, ignoreCase = true)) "" else rawArtist
                val rawAlbum = cursor.getString(albumColumn).orEmpty()
                val album = if (rawAlbum.equals(Anonymous, ignoreCase = true) || rawAlbum.isBlank()) {
                    folder
                } else {
                    rawAlbum
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val albumArtUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                songList.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        contentUri = contentUri,
                        albumArtUri = albumArtUri,
                        folder = folder,
                        systemSound = looksLikeSystemSound(title, rawArtist),
                    )
                )
            }
        }
        songList
    }

    /**
     * Los packs de sonidos que se descargan al teléfono (los de MIUI, por
     * ejemplo) vienen marcados como música y duran más de lo normal, así que
     * se reconocen por el nombre; solo se descartan si además no hay un
     * artista de verdad, para no ocultar canciones que se llamen parecido.
     */
    private fun looksLikeSystemSound(title: String, artist: String): Boolean {
        val name = title.lowercase()
        if (SystemSoundHints.none { name.contains(it) }) return false
        val author = artist.lowercase()
        return author.isBlank() ||
            author == Anonymous ||
            author.contains("xiaomi") ||
            author.contains("miui")
    }

    private companion object {
        /** Carpeta comodín cuando MediaStore no informa la ruta del archivo. */
        const val UnknownFolder = "Otras"

        /** Valor que MediaStore devuelve cuando no hay metadatos. */
        const val Anonymous = "<unknown>"

        /** Pistas en el nombre que delatan sonidos del sistema. */
        val SystemSoundHints = listOf(
            "alarm",
            "alarma",
            "ringtone",
            "timbre",
            "notification",
            "notificaci",
            "miui",
        )
    }
}
