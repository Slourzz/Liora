package com.liora.android.extensions.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.liora.android.extensions.model.AudioSource
import com.liora.android.extensions.model.AudioSourceKind
import com.liora.android.extensions.runtime.JsExtensionRuntime

/**
 * Convierte lo que devuelve un proveedor en algo que Media3 pueda reproducir.
 *
 * La app no cambia su motor de reproducción: lo que hace es fabricar el
 * `MediaItem` que ExoPlayer ya sabe tocar. Una fuente que es solo una muestra
 * se marca como tal, para que la interfaz no prometa una canción completa.
 */
class AudioSourceAdapter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Resuelve el esquema propio de Liora.
     *
     * `liora-asset://nombre` apunta a un recurso que trae la propia
     * aplicación (una pista de prueba, por ejemplo). Se traduce a la URI de
     * recursos de Android, que Media3 sabe leer sin intermediarios.
     */
    fun resolve(source: AudioSource): AudioSource {
        val url = source.url.trim()
        if (!url.startsWith("${JsExtensionRuntime.ASSET_SCHEME}://", ignoreCase = true)) {
            return source
        }
        val name = url.substringAfter("://").substringBefore('/').trim()
        if (name.isEmpty()) return source
        val resource = appContext.resources.getIdentifier(name, "raw", appContext.packageName)
        if (resource == 0) return source
        val uri = Uri.parse("android.resource://${appContext.packageName}/$resource")
        return source.copy(url = uri.toString())
    }

    /**
     * El `MediaItem` con el que se reproduce una fuente.
     *
     * @param trackId el identificador de la pista en el proveedor.
     */
    fun toMediaItem(source: AudioSource, trackId: String, title: String, artist: String): MediaItem {
        val resolved = resolve(source)
        return MediaItem.Builder()
            .setUri(resolved.url)
            .setMediaId(trackId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsPlayable(resolved.kind != AudioSourceKind.SAMPLE)
                    .build(),
            )
            .build()
    }

    /**
     * ¿Esta fuente sirve para reproducir la canción entera?
     *
     * Una muestra no: sirve para escuchar un trozo, no para decir que la
     * canción está disponible.
     */
    fun isFullLength(source: AudioSource): Boolean = source.kind != AudioSourceKind.SAMPLE
}
