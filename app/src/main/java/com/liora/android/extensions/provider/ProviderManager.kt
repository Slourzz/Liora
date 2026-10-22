package com.liora.android.extensions.provider

import com.liora.android.data.identification.SearchNameSimilarity
import com.liora.android.data.model.Song
import com.liora.android.extensions.model.AudioSource
import com.liora.android.extensions.model.Availability
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ProviderLyrics
import com.liora.android.extensions.model.ProviderTrack
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.net.hostOf
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
            val candidates = registry.providers(ProviderType.TRACK_SEARCH)
            com.liora.android.extensions.ExtLog.i(
                "Buscando '$query' en ${candidates.size} proveedores: " +
                    candidates.joinToString { it.extensionId },
            )
            for (entry in candidates) {
                val tracks = runCatching { entry.runtime.searchTracks(query, limit) }
                    .getOrElse { error ->
                        com.liora.android.extensions.ExtLog.w(
                            "El proveedor '${entry.extensionId}' fallo en la busqueda: ${error.message}",
                            error,
                        )
                        registry.setError(entry.extensionId, error.message)
                        emptyList()
                    }
                if (tracks.isEmpty() && entry.error == null) {
                    com.liora.android.extensions.ExtLog.i(
                        "El proveedor '${entry.extensionId}' no devolvio nada para '$query'",
                    )
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
     * La ficha completa de una pista, tal y como la ve el proveedor.
     *
     * La busqueda no siempre trae el artista ni el album: la ficha completa
     * sale del proveedor, que es quien la sabe.
     */
    suspend fun trackOf(trackId: String): ProviderTrack? = withContext(Dispatchers.IO) {
        for (entry in registry.providers(ProviderType.METADATA)) {
            val track = runCatching { entry.runtime.getTrack(trackId) }
                .getOrElse { error ->
                    registry.setError(entry.extensionId, error.message)
                    null
                }
            if (track != null) return@withContext track
        }
        null
    }

    /**
     * La fuente con la que se puede reproducir una pista.
     *
     * Se pregunta a los proveedores de audio por orden; el primero que
     * devuelve una fuente válida sirve. Los que fallan se saltan.
     *
     * Antes de devolverla se comprueba que el proveedor tenga permiso para
     * hablar con el dominio de la fuente: una extensión no puede entregar una
     * URL de un sitio que no le está permitido.
     */
    suspend fun audioSource(trackId: String, quality: String? = null): AudioSource? =
        withContext(Dispatchers.IO) {
            for (entry in registry.providers(ProviderType.AUDIO_SOURCE)) {
                val source = runCatching { entry.runtime.getAudioSource(trackId, quality) }
                    .getOrElse { error ->
                        registry.setError(entry.extensionId, error.message)
                        null
                    }
                if (source == null) continue
                val host = hostOf(source.url)
                if (!entry.allowsDomain(host)) {
                    registry.setError(
                        entry.extensionId,
                        "La fuente apunta a '$host', un dominio sin permiso",
                    )
                    continue
                }
                return@withContext source
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
                if (source == null) continue
                val host = hostOf(source.url)
                if (!entry.allowsDomain(host)) {
                    registry.setError(
                        entry.extensionId,
                        "La descarga apunta a '$host', un dominio sin permiso",
                    )
                    continue
                }
                return@withContext source
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
