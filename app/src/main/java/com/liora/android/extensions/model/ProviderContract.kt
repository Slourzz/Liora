package com.liora.android.extensions.model

/**
 * El contrato de proveedores: lo que Liora conoce de una extensión.
 *
 * Liora no sabe nada del interior de una extensión: solo conoce su
 * identificador, los tipos de proveedor que declara y los datos que devuelve.
 */

/** Una pista según la entiende un proveedor externo. */
data class ProviderTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val providerId: String = "",
)

/**
 * Qué se puede hacer con una pista concreta.
 *
 * `kinds` distingue lo que el proveedor ofrece de verdad: una muestra de 30
 * segundos no es una fuente reproducible, y una fuente reproducible no es
 * necesariamente un archivo descargable.
 */
data class Availability(
    val available: Boolean,
    val kinds: List<AudioSourceKind> = emptyList(),
    val reason: String? = null,
    val quality: String? = null,
)

/** La naturaleza de una fuente de audio. */
enum class AudioSourceKind(val wire: String) {
    /** Un fragmento corto para escuchar: no es la canción completa. */
    SAMPLE("sample"),

    /** Un flujo que se puede reproducir entero. */
    PLAYABLE("playable"),

    /** Un archivo completo que se puede descargar. */
    DOWNLOADABLE("downloadable"),
    ;

    companion object {
        fun fromWire(value: String): AudioSourceKind? =
            entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * Una fuente de audio que devuelve un proveedor.
 *
 * `url` es lo que Media3 reproduce o lo que el gestor de descargas guarda.
 * `expiresAtMs` marca los enlaces temporales: un enlace caducado da error
 * explícito en vez de una descarga vacía.
 */
data class AudioSource(
    val kind: AudioSourceKind,
    val url: String,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val durationMs: Long? = null,
    val expiresAtMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val requiresAuthentication: Boolean = false,
    val notes: String? = null,
) {
    /** ¿El enlace puede haber caducado ya? */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiresAtMs ?: return false
        return expiry in 1..nowMs
    }
}

/** Una línea de letra con su tiempo. */
data class ProviderLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** La letra que devuelve un proveedor. */
data class ProviderLyrics(
    val lines: List<ProviderLyricLine> = emptyList(),
    val plainText: String = "",
    val provider: String = "",
)

/** El resultado de una descarga hecha por un proveedor. */
data class DownloadResult(
    val success: Boolean,
    val filePath: String? = null,
    val bytesWritten: Long = 0L,
    val error: String? = null,
    val errorType: String? = null,
    /** El tipo del fichero, si el proveedor lo sabe: `audio/mpeg`, `audio/flac`… */
    val mimeType: String? = null,
)

/** Metadatos de una pista. */
interface MetadataProvider {
    /** Los datos de una pista por su identificador. */
    fun getTrack(trackId: String): ProviderTrack?
}

/** Búsqueda de canciones. */
interface TrackSearchProvider {
    /** Las pistas que el proveedor encuentra para una consulta. */
    fun searchTracks(query: String, limit: Int): List<ProviderTrack>
}

/** Fuentes de audio que se pueden reproducir. */
interface AudioSourceProvider {
    /** Si hay fuente para esa pista y de qué tipo. */
    fun checkAvailability(trackId: String): Availability

    /** La fuente con la que se puede reproducir la pista. */
    fun getAudioSource(trackId: String, quality: String?): AudioSource?
}

/** Archivos completos que se pueden descargar. */
interface AudioDownloadProvider {
    /** El origen descargable de una pista. */
    fun getDownloadUrl(trackId: String, quality: String?): AudioSource?

    /**
     * Descarga la pista a [targetPath].
     *
     * @param onProgress se llama con (escritos, total) mientras baja.
     */
    fun download(
        trackId: String,
        quality: String?,
        targetPath: String,
        onProgress: (written: Long, total: Long) -> Unit,
    ): DownloadResult
}

/** Letras. */
interface LyricsProvider {
    /** La letra de una pista, si el proveedor la tiene. */
    fun fetchLyrics(track: ProviderTrack, durationSec: Int): ProviderLyrics?
}
