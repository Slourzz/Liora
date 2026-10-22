package com.liora.android.extensions.provider

import com.liora.android.data.identification.SearchNameSimilarity
import com.liora.android.data.model.Song
import com.liora.android.extensions.model.AudioSource
import com.liora.android.extensions.model.Availability
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ProviderLyrics
import com.liora.android.extensions.model.ProviderTrack
import com.liora.android.extensions.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * El uso de los proveedores externos.
 *
 * Aquí está el aislamiento de errores: una extensión que falla no tira el
 * proceso ni impide que las demás sigan trabajando. Cada proveedor se
 * consulta con su propio tiempo límite y, si falla, se pasa al siguiente.
 */
class ProviderManager(private val registry: ProviderRegistry) {

    /**
     * Busca en todos los proveedores de búsqueda.
     *
     * Los resultados se intercalan: primero los del proveedor con mejor
     * coincidencia, sin repetir pistas.
     */
    suspend fun searchTracks(query: String, limit: Int = 20): List<ProviderTrack> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<ProviderTrack>()
            val seen = mutableSetOf<String>()
            for (entry in registry.providers(ProviderType.TRACK_SEARCH)) {
                val tracks = runCatching { entry.runtime.searchTracks(query, limit) }
                    .getOrElse { error ->
                        registry.setError(entry.extensionId, error.message)
                        emptyList()
                    }
                for (track in tracks) {
                    val key = "${track.providerId}:${track.id}"
                    if (seen.add(key)) found += track
                }
            }
            found.take(limit)
        }

    /**
     * Busca la pista que corresponde a una canción local.
     *
     * Se pregunta a los proveedores de búsqueda y se puntúa con el mismo
     * criterio que usa Liora para identificar canciones (`SearchNameSimilarity`),
     * de modo que la comparación con la canción local sea coherente con el
     * resto de la app.
     */
    suspend fun resolveSong(song: Song, limit: Int = 20): ProviderTrack? =
        withContext(Dispatchers.IO) {
            val candidates = searchTracks("${song.title} ${song.artist}", limit)
            bestMatch(song.title, song.artist, song.duration, candidates)
        }

    /**
     * La mejor coincidencia entre una canción local y una lista de candidatas.
     *
     * Se puntúa con el mismo criterio que usa Liora para identificar canciones
     * (`SearchNameSimilarity`), de modo que la comparación con la canción
     * local sea coherente con el resto de la app.
     */
    fun bestMatch(
        title: String,
        artist: String,
        durationMs: Long,
        candidates: List<ProviderTrack>,
    ): ProviderTrack? {
        var best: ProviderTrack? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val score = SearchNameSimilarity.score(
                title = title,
                artist = artist,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artist,
            )
            val total = score - durationMismatch(durationMs, candidate.durationMs)
            if (total > bestScore) {
                bestScore = total
                best = candidate
            }
        }
        return best?.takeIf { bestScore >= SearchNameSimilarity.MatchThreshold }
    }

    /**
     * Cuánto penaliza que las duraciones no cuadren.
     *
     * Dos canciones con el mismo nombre pero de distinta duración rara vez son
     * la misma grabación: se resta una parte proporcional a la diferencia.
     */
    private fun durationMismatch(localMs: Long, candidateMs: Long): Double {
        if (localMs <= 0L || candidateMs <= 0L) return 0.0
        val difference = kotlin.math.abs(localMs - candidateMs).toDouble()
        val ratio = difference / maxOf(localMs, candidateMs).toDouble()
        return (ratio * 0.5).coerceAtMost(0.5)
    }

    /**
     * La fuente con la que se puede reproducir una pista.
     *
     * Se pregunta a los proveedores de audio por orden; el primero que
     * devuelve una fuente válida sirve. Los que fallan se saltan.
     */
    suspend fun audioSource(trackId: String, quality: String? = null): AudioSource? =
        withContext(Dispatchers.IO) {
            for (entry in registry.providers(ProviderType.AUDIO_SOURCE)) {
                val source = runCatching { entry.runtime.getAudioSource(trackId, quality) }
                    .getOrElse { error ->
                        registry.setError(entry.extensionId, error.message)
                        null
                    }
                if (source != null) return@withContext source
            }
            null
        }

    /** La disponibilidad de una pista, según los proveedores de audio. */
    suspend fun availability(trackId: String): Availability = withContext(Dispatchers.IO) {
        for (entry in registry.providers(ProviderType.AUDIO_SOURCE) +
            registry.providers(ProviderType.AUDIO_DOWNLOAD)
        ) {
            val availability = runCatching { entry.runtime.checkAvailability(trackId) }
                .getOrElse { error ->
                    registry.setError(entry.extensionId, error.message)
                    null
                }
            if (availability != null && availability.available) return@withContext availability
        }
        Availability(false, reason = "Ningún proveedor tiene esta pista")
    }

    /** El origen descargable de una pista, si algún proveedor lo ofrece. */
    suspend fun downloadSource(trackId: String, quality: String? = null): AudioSource? =
        withContext(Dispatchers.IO) {
            for (entry in registry.providers(ProviderType.AUDIO_DOWNLOAD)) {
                val source = runCatching { entry.runtime.getDownloadUrl(trackId, quality) }
                    .getOrElse { error ->
                        registry.setError(entry.extensionId, error.message)
                        null
                    }
                if (source != null) return@withContext source
            }
            null
        }

    /**
     * La letra de una pista, según los proveedores de letras.
     *
     * Los proveedores externos se consultan antes que los integrados: si
     * alguien instala una fuente de letras, esa manda.
     */
    suspend fun lyrics(track: ProviderTrack, durationSec: Int): ProviderLyrics? =
        withContext(Dispatchers.IO) {
            for (entry in registry.providers(ProviderType.LYRICS)) {
                val lyrics = runCatching {
                    entry.runtime.fetchLyrics(track, durationSec)
                }.getOrElse { error ->
                    registry.setError(entry.extensionId, error.message)
                    null
                }
                if (lyrics != null && (lyrics.lines.isNotEmpty() || lyrics.plainText.isNotBlank())) {
                    return@withContext lyrics
                }
            }
            null
        }

    /** Los proveedores de un tipo, para mostrarlos en la interfaz. */
    fun providers(type: ProviderType): List<ProviderEntry> = registry.providers(type)

    /** Lanza si no hay ningún proveedor para la operación. */
    fun requireProvider(type: ProviderType, operation: String): ProviderEntry =
        registry.providers(type).firstOrNull()
            ?: throw ExtensionException.NotSupported("liora", operation)
}
