package com.liora.android.data.lyrics

import android.content.Context
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.Song
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * El resultado de Apple Music: las líneas de la letra y si la consulta llegó
 * al catálogo. "El catálogo respondió y no tiene esta letra" es una
 * respuesta; "no hay red" no lo es.
 */
data class AppleLyricsAttempt(
    val lines: List<LyricLine> = emptyList(),
    val reachable: Boolean = false,
)

/** La candidata de iTunes y si se pudo consultar el catálogo. */
private data class AppleTrack(
    val id: String? = null,
    val reachable: Boolean = false,
)

/** El TTML que devuelve Paxsenix y si se pudo pedir. */
private data class ApplePayload(
    val text: String? = null,
    val reachable: Boolean = false,
)

/**
 * Letras de Apple Music a través de la API pública de Paxsenix.
 *
 * La canción local se busca primero en el catálogo de iTunes para dar con su
 * `trackId`; con ese id se pide el TTML a `lyrics.paxsenix.org`, que se parsea
 * con [TtmlParser] y se guarda en `filesDir/letras/<id>.ttml` para no volver a
 * pedirlo en cada cambio de pista. Todo va con las APIs del sistema —sin
 * dependencias nuevas— y fuera del hilo principal.
 */
class AppleMusicLyricsRepository(context: Context) {

    private val lyricsDirectory = File(context.filesDir, "letras")

    /**
     * Devuelve las líneas sincronizadas de [song], o una lista vacía si no hay
     * coincidencia, no hay red o el TTML no se pudo leer. Nunca lanza.
     *
     * [preferredTrackId] permite reutilizar el `trackId` de iTunes que ya
     * verificó la identificación de la canción y ahorrarse otra búsqueda.
     *
     * Además dice si la consulta llegó a la red: un catálogo que responde y no
     * tiene la letra es una respuesta; un fallo de red no lo es.
     */
    suspend fun findLyrics(
        song: Song,
        preferredTrackId: String? = null,
    ): AppleLyricsAttempt = withContext(Dispatchers.IO) {
        readCachedLyrics(song)?.let { return@withContext AppleLyricsAttempt(lines = it, reachable = true) }

        val track = preferredTrackId?.let { AppleTrack(it, reachable = true) }
            ?: findAppleMusicTrackId(song)
        // Sin red no se puede affirmar que la pista no tiene letra.
        if (!track.reachable) return@withContext AppleLyricsAttempt()
        // El catálogo respondió y no hay candidata: es un "no hay", confirmado.
        val trackId = track.id ?: return@withContext AppleLyricsAttempt(reachable = true)
        val ttml = findAppleMusicTtml(trackId)
        if (!ttml.reachable) return@withContext AppleLyricsAttempt()
        val body = ttml.text ?: return@withContext AppleLyricsAttempt(reachable = true)
        val lines = parseTtml(body)
        if (lines.isNotEmpty()) writeCachedLyrics(song, body)
        AppleLyricsAttempt(lines = lines, reachable = true)
    }

    // ----------------------------------------------------------------- caché

    private fun cacheFile(song: Song) = File(lyricsDirectory, "${song.id}.ttml")

    private fun readCachedLyrics(song: Song): List<LyricLine>? {
        val file = cacheFile(song)
        if (!file.isFile) return null
        return runCatching { file.inputStream().use(TtmlParser::parse) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                file.delete()
                null
            }
    }

    private fun writeCachedLyrics(song: Song, ttml: String) {
        runCatching {
            lyricsDirectory.mkdirs()
            cacheFile(song).writeText(ttml, StandardCharsets.UTF_8)
        }
    }

    // --------------------------------------------------------------- iTunes

    /**
     * Busca la pista en iTunes y elige la mejor candidata por título, artista y
     * duración. El umbral evita quedarse con una versión que solo comparte una
     * palabra del nombre.
     *
     * Si la consulta falla (no hay red, la API contesta otra cosa) se devuelve
     * una respuesta sin `reachable`: eso no es lo mismo que "el catálogo
     * respondió y no hay coincidencia".
     */
    private fun findAppleMusicTrackId(song: Song): AppleTrack {
        val query = encode(
            listOf(song.artist, song.title)
                .filter(String::isNotBlank)
                .joinToString(" "),
        )
        if (query.isBlank()) return AppleTrack()

        val endpoint = URI.create(
            "https://itunes.apple.com/search" +
                "?term=$query&entity=musicTrack&media=music&limit=8",
        ).toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return AppleTrack()
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(payload).optJSONArray("results")
                ?: return AppleTrack(reachable = true)
            val wantedArtist = normalizedLookup(song.artist)
            val wantedTitle = normalizedLookup(song.title)

            var bestId: String? = null
            var bestScore = 0.0
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val trackId = item.optLong("trackId").takeIf { it > 0L } ?: continue
                val trackTitle = normalizedLookup(item.optString("trackName"))
                val trackArtist = normalizedLookup(item.optString("artistName"))

                var score = 0.0
                if (trackTitle == wantedTitle) {
                    score += 80.0
                } else if (trackTitle.contains(wantedTitle) || wantedTitle.contains(trackTitle)) {
                    score += 42.0
                }
                if (
                    trackArtist == wantedArtist ||
                    trackArtist.contains(wantedArtist) ||
                    wantedArtist.contains(trackArtist)
                ) {
                    score += 42.0
                }
                score += durationScore(song.duration, item.optLong("trackTimeMillis"))

                if (score > bestScore) {
                    bestScore = score
                    bestId = trackId.toString()
                }
            }

            // El catálogo respondió: si no hay candidata buena, es un "no hay".
            AppleTrack(
                id = bestId?.takeIf { bestScore >= 58.0 },
                reachable = true,
            )
        } catch (_: Exception) {
            AppleTrack()
        } finally {
            connection.disconnect()
        }
    }

    // -------------------------------------------------------------- Paxsenix

    /** Pide el TTML de una pista y lo extrae del JSON con el que responde. */
    private fun findAppleMusicTtml(trackId: String): ApplePayload {
        val endpoint = URI.create(
            "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&ttml=true",
        ).toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json, application/xml, text/xml")
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return ApplePayload()
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            ApplePayload(text = extractTtmlPayload(payload), reachable = true)
        } catch (_: Exception) {
            ApplePayload()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTtmlPayload(payload: String): String? {
        val trimmed = payload.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") -> extractTtmlFromJson(JSONObject(trimmed))
                trimmed.startsWith("[") -> extractTtmlFromJson(JSONArray(trimmed))
                trimmed.contains(Regex("<tt[\\s>]|<p[\\s>]", RegexOption.IGNORE_CASE)) -> trimmed
                else -> null
            }
        }.getOrNull()
    }

    private fun extractTtmlFromJson(value: Any?): String? {
        if (value is String && value.contains(Regex("<tt[\\s>]|<p[\\s>]", RegexOption.IGNORE_CASE))) {
            return value
        }
        if (value is JSONObject) {
            val keys = value.keys()
            while (keys.hasNext()) {
                extractTtmlFromJson(value.opt(keys.next()))?.let { return it }
            }
        }
        if (value is JSONArray) {
            for (index in 0 until value.length()) {
                extractTtmlFromJson(value.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun parseTtml(ttml: String): List<LyricLine> = runCatching {
        ByteArrayInputStream(ttml.toByteArray(StandardCharsets.UTF_8)).use(TtmlParser::parse)
    }.getOrDefault(emptyList())

    // -------------------------------------------------------------- matching

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())

    private fun normalizedLookup(value: String): String = cleanLyricsQuery(
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\b(feat|ft|with|prod)\\b.*$", RegexOption.IGNORE_CASE), " "),
    ).lowercase()

    private fun cleanLyricsQuery(value: String): String =
        value.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")

    private fun durationScore(songDurationMs: Long, trackDurationMs: Long): Double {
        if (songDurationMs <= 0L || trackDurationMs <= 0L) return 0.0
        val differenceSeconds = kotlin.math.abs(songDurationMs - trackDurationMs) / 1_000.0
        return (28.0 - differenceSeconds).coerceAtLeast(0.0)
    }
}
