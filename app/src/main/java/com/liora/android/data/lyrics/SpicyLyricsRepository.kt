package com.liora.android.data.lyrics

import android.content.Context
import com.liora.android.data.model.LyricBackgroundLine
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricWord
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.LyricsContributor
import com.liora.android.data.model.LyricsResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * El resultado de Spicy: la letra si la hay y, además, si la consulta llegó a
 * Servirse.
 *
 * "No hay letra" es una respuesta —la pista no tiene letra—; un fallo de la
 * red no lo es. Esa diferencia es la que permite guardar el resultado con la
 * pista sin condenar a las canciones que un día tendrán letra.
 */
data class SpicyLyricsAttempt(
    val result: LyricsResult? = null,
    val reachable: Boolean = false,
)

/** Lo que contesta la API: el cuerpo y si se pudo preguntar. */
private data class SpicyPayload(
    val text: String? = null,
    val reachable: Boolean = false,
)

/**
 * Letras de Spicy Lyrics para pistas identificadas por su id de Spotify.
 *
 * El id se valida contra el patrón de la API antes de llamar. La respuesta se
 * interpreta tal como la documenta Spicy Lyrics: `Body.Type` decide la
 * precisión (`Syllable`, `Line` o `Static`), `Body.Content`/`Body.Lines` traen
 * los tiempos —en segundos—, y las traducciones y romanizaciones disponibles se
 * guardan junto a cada línea. El proveedor y sus créditos viajan en
 * [LyricsResult] para que la interfaz pueda cumplir la atribución oficial.
 *
 * La clave es publicable (`sl_pk_…`), la propia de una app cliente; este código
 * nunca usa ni pide una clave secreta (`sl_sk_…`). El JSON que responde 200 se
 * guarda en `filesDir/letras/spicy` para no repetir la petición —ni gastar la
 * ventana de peticiones— cada vez que suena la misma pista. Nunca lanza.
 */
class SpicyLyricsRepository(context: Context) {

    private val cacheDirectory = File(context.filesDir, "letras/spicy")
    private val apiKey = SpicyLyricsConfig.PUBLISHABLE_KEY

    /** Verdadero cuando hay una clave publicable configurada; si no, se salta Spicy. */
    val isConfigured: Boolean
        get() = hasPublishableKey()

    /** Hasta cuándo conviene no insistir tras un 401/429/503; 0 si no hay espera. */
    @Volatile
    private var cooldownUntilMs = 0L

    /**
     * Devuelve las letras de [spotifyTrackId] o nulo si el id no es válido, no
     * hay clave configurada, la pista no tiene letra o la API falla. Nunca
     * lanza: quien encadena las fuentes decide el respaldo.
     *
     * Además dice si la consulta llegó a Servirse: un "no hay letra" confirmado
     * es una respuesta, mientras que un fallo de la red no es una respuesta.
     * Esa diferencia decide si el resultado se guarda con la pista.
     */
    suspend fun findLyrics(spotifyTrackId: String, songDurationMs: Long): SpicyLyricsAttempt =
        withContext(Dispatchers.IO) {
            if (!isValidTrackId(spotifyTrackId)) return@withContext SpicyLyricsAttempt()
            if (!hasPublishableKey()) return@withContext SpicyLyricsAttempt()
            if (System.currentTimeMillis() < cooldownUntilMs) return@withContext SpicyLyricsAttempt()

            val cached = readCachedPayload(spotifyTrackId)
            if (cached != null) {
                val cachedResult = parsePayload(cached, songDurationMs)
                if (cachedResult != null) {
                    return@withContext SpicyLyricsAttempt(result = cachedResult, reachable = true)
                }
                cacheFile(spotifyTrackId).delete()
            }

            val payload = requestPayload(spotifyTrackId)
            if (!payload.reachable) return@withContext SpicyLyricsAttempt()
            val body = payload.text ?: return@withContext SpicyLyricsAttempt(reachable = true)
            val result = parsePayload(body, songDurationMs)
                ?: return@withContext SpicyLyricsAttempt(reachable = true)
            writeCachedPayload(spotifyTrackId, body)
            SpicyLyricsAttempt(result = result, reachable = true)
        }

    // ----------------------------------------------------------------- clave

    private fun isValidTrackId(trackId: String): Boolean = TrackIdPattern.matches(trackId)

    private fun hasPublishableKey(): Boolean =
        apiKey.length >= MinPublishableKeyLength && apiKey.startsWith(PublishableKeyPrefix)

    // --------------------------------------------------------------- petición

    private fun requestPayload(trackId: String): SpicyPayload {
        val endpoint = URI.create("${SpicyLyricsConfig.BASE_URL}/v1/lyrics/$trackId").toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    SpicyPayload(
                        text = connection.inputStream.bufferedReader().use { it.readText() },
                        reachable = true,
                    )

                // La pista no tiene letra: no es un error, es un "no hay".
                HttpURLConnection.HTTP_NOT_FOUND -> SpicyPayload(reachable = true)

                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    // Clave inválida o sin permiso: no insistir en cada pista.
                    enterCooldown(AuthCooldownMs)
                    SpicyPayload()
                }

                HTTP_TOO_MANY_REQUESTS,
                HttpURLConnection.HTTP_UNAVAILABLE -> {
                    // La API dice cuándo se repone la ventana de peticiones.
                    enterCooldown(retryAfterMs(connection))
                    SpicyPayload()
                }

                else -> SpicyPayload()
            }
        } catch (_: Exception) {
            SpicyPayload()
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfterMs(connection: HttpURLConnection): Long {
        val header = connection.getHeaderField("Retry-After")
            ?: connection.getHeaderField("RateLimit-Reset")
        val seconds = header?.trim()?.toLongOrNull() ?: return DefaultRateLimitCooldownMs
        return seconds.coerceIn(1L, MaxCooldownSeconds) * 1_000L
    }

    private fun enterCooldown(millis: Long) {
        cooldownUntilMs = System.currentTimeMillis() + millis.coerceAtLeast(1_000L)
    }

    // ---------------------------------------------------------------- caché

    private fun cacheFile(trackId: String) = File(cacheDirectory, "$trackId.json")

    private fun readCachedPayload(trackId: String): String? {
        val file = cacheFile(trackId)
        if (!file.isFile) return null

        // Los términos de Spicy exigen revalidar o descartar lo almacenado
        // dentro de 30 días; pasado ese plazo se vuelve a pedir.
        if (System.currentTimeMillis() - file.lastModified() > CacheMaxAgeMs) {
            file.delete()
            return null
        }

        return runCatching { file.readText(StandardCharsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun writeCachedPayload(trackId: String, payload: String) {
        runCatching {
            cacheDirectory.mkdirs()
            cacheFile(trackId).writeText(payload, StandardCharsets.UTF_8)
        }
    }

    // ------------------------------------------------------------- parseo

    private fun parsePayload(payload: String, songDurationMs: Long): LyricsResult? = runCatching {
        val envelope = JSONObject(payload)
        if (envelope.optInt("Status", -1) != EnvelopeOkStatus) return@runCatching null
        val body = envelope.optJSONObject("Body") ?: return@runCatching null

        val lines = when (body.optString("Type")) {
            TypeSyllable -> parseSyllableLines(body)
            TypeLine -> parseLineLines(body)
            TypeStatic -> parseStaticLines(body, songDurationMs)
            else -> return@runCatching null
        }.let(::resolveEnds)
        if (lines.isEmpty()) return@runCatching null

        LyricsResult(lines = lines, attribution = attributionFor(body))
    }.getOrNull()

    /** El proveedor siempre es Spicy Lyrics; `source` es el catálogo que contestó. */
    private fun attributionFor(body: JSONObject): LyricsAttribution {
        val source = body.optString("source").trim().lowercase()
        val upload = body.optJSONObject("UploadAttribution")
        val isCommunity = source == SourceSpicyLyrics
        return LyricsAttribution(
            providerName = "Spicy Lyrics",
            originName = originName(source),
            // La API solo acredita uploader/maker en las letras de la comunidad.
            uploader = if (isCommunity) upload?.contributor("Uploader") else null,
            maker = if (isCommunity) upload?.contributor("Maker") else null,
            // Quién escribió la canción, si la fuente lo cuenta.
            songwriters = body.optJSONArray("SongWriters")
                ?.toSongwriterList()
                .orEmpty(),
        )
    }

    /** Los autores de la canción, tal como los cuenta la fuente. */
    private fun JSONArray.toSongwriterList(): List<String> = buildList {
        for (index in 0 until length()) {
            val name = optString(index).trim()
            if (name.isNotEmpty()) add(name)
        }
    }

    private fun JSONObject.contributor(key: String): LyricsContributor? {
        val person = optJSONObject(key) ?: return null
        val username = person.optString("username").trim().ifBlank { null } ?: return null
        val url = person.optString("url").trim().takeIf { it.startsWith("https://") }
        return LyricsContributor(username = username, url = url)
    }

    private fun originName(source: String): String? = when (source) {
        "" -> null
        SourceSpicyLyrics -> "Spicy Lyrics"
        "apple_music" -> "Apple Music"
        "spotify" -> "Spotify"
        else -> source.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    // --------------------------------------------------- precisión Syllable

    private fun parseSyllableLines(body: JSONObject): List<LyricLine> {
        val content = body.optJSONArray("Content") ?: return emptyList()
        val lines = mutableListOf<LyricLine>()

        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("Type").equals(TypeInterlude, ignoreCase = true)) continue

            val lead = item.optJSONObject("Lead")
            var leadWords = lead?.optJSONArray("Syllables").toLyricWords()
            var background = item.optJSONArray("Background").toBackgroundLines()
            if (leadWords.isEmpty() && background.isEmpty()) continue

            // Una línea puede ser solo coro de fondo (Industry Baby abre así):
            // el primer grupo visible pasa a ser la voz principal para no
            // dejar la línea en blanco en pantalla.
            if (leadWords.isEmpty()) {
                val first = background.firstOrNull()
                if (first != null) {
                    leadWords = first.words
                    background = background.drop(1)
                }
            }

            val startMs = lead.groupStartMs() ?: leadWords.minOfOrNull(LyricWord::startMs) ?: 0L
            val endMs = lead.groupEndMs() ?: leadWords.maxOfOrNull(LyricWord::endMs) ?: startMs
            lines += LyricLine(
                text = wordsToText(leadWords).ifBlank { lead?.optString("TransliteratedText").cleanOrNull().orEmpty() },
                startMs = startMs,
                endMs = endMs,
                words = leadWords,
                background = background,
                oppositeAligned = item.optBoolean("OppositeAligned", false),
                translation = lead?.optString("TranslatedText").cleanOrNull(),
                transliteration = lead?.optString("TransliteratedText").cleanOrNull(),
            )
        }
        return lines
    }

    /**
     * Convierte las sílabas de un grupo en palabras. `IsPartOfWord` marca que
     * la sílaba se pega a la siguiente (así lo documenta Spicy Lyrics), que es
     * justo lo que necesitan `joinWithPrevious`/`joinWithNext` de Lyra.
     */
    private fun JSONArray?.toLyricWords(): List<LyricWord> {
        if (this == null) return emptyList()
        val raw = buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val text = item.optString("Text").trim()
                if (text.isEmpty()) continue
                val startMs = item.secondsToMs("StartTime") ?: 0L
                val endMs = item.secondsToMs("EndTime") ?: (startMs + DefaultWordDurationMs)
                add(
                    RawSyllable(
                        text = text,
                        startMs = startMs,
                        endMs = endMs.coerceAtLeast(startMs + 1L),
                        joinsNext = item.optBoolean("IsPartOfWord", false),
                        transliteration = item.optString("TransliteratedText").cleanOrNull(),
                    ),
                )
            }
        }

        return raw.mapIndexed { index, syllable ->
            val joinPrevious = index > 0 && raw[index - 1].joinsNext
            val joinNext = syllable.joinsNext && index < raw.lastIndex
            LyricWord(
                text = syllable.text,
                startMs = syllable.startMs,
                endMs = syllable.endMs,
                partOfWord = joinPrevious || joinNext,
                joinWithPrevious = joinPrevious,
                joinWithNext = joinNext,
                transliteration = syllable.transliteration,
            )
        }
    }

    private fun JSONArray?.toBackgroundLines(): List<LyricBackgroundLine> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val group = optJSONObject(index) ?: continue
                val words = group.optJSONArray("Syllables").toLyricWords()
                if (words.isEmpty()) continue
                add(
                    LyricBackgroundLine(
                        text = wordsToText(words),
                        startMs = group.groupStartMs() ?: words.minOf(LyricWord::startMs),
                        endMs = group.groupEndMs() ?: words.maxOf(LyricWord::endMs),
                        words = words,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------- precisión Line

    private fun parseLineLines(body: JSONObject): List<LyricLine> {
        val content = body.optJSONArray("Content") ?: return emptyList()
        val lines = mutableListOf<LyricLine>()

        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("Type").equals(TypeInterlude, ignoreCase = true)) continue
            val text = item.optString("Text").cleanOrNull() ?: continue
            val startMs = item.secondsToMs("StartTime") ?: 0L
            val endMs = item.secondsToMs("EndTime") ?: startMs
            lines += LyricLine(
                text = text,
                startMs = startMs,
                endMs = endMs,
                // Line no trae tiempos por palabra: la precisión se respeta
                // dejando `words` vacío y usando solo la ventana de la línea.
                oppositeAligned = item.optBoolean("OppositeAligned", false),
                translation = item.optString("TranslatedText").cleanOrNull(),
                transliteration = item.optString("TransliteratedText").cleanOrNull(),
            )
        }
        return lines
    }

    // ----------------------------------------------------- precisión Static

    private fun parseStaticLines(body: JSONObject, songDurationMs: Long): List<LyricLine> {
        val array = body.optJSONArray("Lines") ?: return emptyList()
        val texts = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.optString("Text").cleanOrNull()?.let(::add)
            }
        }
        if (texts.isEmpty()) return emptyList()

        // Static no trae tiempos: se reparten sobre la duración de la pista
        // para que la lista se pueda recorrer y buscar, sin inventar
        // sincronía palabra por palabra.
        val totalMs = songDurationMs.takeIf { it > 0L } ?: (texts.size * DefaultLineDurationMs)
        return texts.mapIndexed { index, text ->
            val startMs = totalMs * index / texts.size
            val endMs = totalMs * (index + 1) / texts.size
            LyricLine(text = text, startMs = startMs, endMs = endMs.coerceAtLeast(startMs + 1L))
        }
    }

    // ------------------------------------------------------------ utilidades

    /** Asegura que cada línea tenga una ventana real, como hace TtmlParser. */
    private fun resolveEnds(lines: List<LyricLine>): List<LyricLine> {
        val sorted = lines.sortedBy(LyricLine::startMs)
        return sorted.mapIndexed { index, line ->
            if (line.endMs > line.startMs) {
                line
            } else {
                line.copy(
                    endMs = sorted.getOrNull(index + 1)?.startMs?.takeIf { it > line.startMs }
                        ?: (line.startMs + DefaultLineDurationMs),
                )
            }
        }
    }

    private fun wordsToText(words: List<LyricWord>): String = buildString {
        words.forEachIndexed { index, word ->
            val joinsPrevious = word.joinWithPrevious || words.getOrNull(index - 1)?.joinWithNext == true
            if (index > 0 && !joinsPrevious && word.text.firstOrNull() !in NoLeadingSpace) append(' ')
            append(word.text)
        }
    }.trim()

    private fun JSONObject?.groupStartMs(): Long? = this?.secondsToMs("StartTime")

    private fun JSONObject?.groupEndMs(): Long? = this?.secondsToMs("EndTime")

    private fun JSONObject.secondsToMs(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val seconds = optDouble(key, Double.NaN)
        if (seconds.isNaN()) return null
        return (seconds * 1_000.0).toLong()
    }

    private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private data class RawSyllable(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val joinsNext: Boolean,
        val transliteration: String?,
    )

    private companion object {
        val TrackIdPattern = Regex("^[A-Za-z0-9]{22}$")

        const val ConnectTimeoutMs = 8_000
        const val ReadTimeoutMs = 12_000
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val EnvelopeOkStatus = 200

        const val PublishableKeyPrefix = "sl_pk_"
        const val MinPublishableKeyLength = 14
        const val AuthCooldownMs = 10 * 60 * 1_000L
        const val DefaultRateLimitCooldownMs = 60_000L
        const val MaxCooldownSeconds = 3_600L

        /** Los términos de Spicy limitan a 30 días cualquier copia almacenada. */
        const val CacheMaxAgeMs = 30L * 24 * 60 * 60 * 1_000L

        const val TypeSyllable = "Syllable"
        const val TypeLine = "Line"
        const val TypeStatic = "Static"
        const val TypeInterlude = "Interlude"
        const val SourceSpicyLyrics = "spicy_lyrics"

        const val DefaultWordDurationMs = 450L
        const val DefaultLineDurationMs = 4_000L

        /** Signos que no llevan espacio delante al recomponer la línea. */
        val NoLeadingSpace = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }
}
