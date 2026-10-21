package com.liora.android.data.lyrics

import android.content.Context
import com.liora.android.data.identification.SearchNameSimilarity
import com.liora.android.data.model.LyricBackgroundLine
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricWord
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.LyricsContributor
import com.liora.android.data.model.LyricsResult
import com.liora.android.data.model.Song
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Las letras de cada pista, guardadas con la propia pista.
 *
 * Esto no es una caché de sesión: es el sitio donde la letra de una canción
 * vive ya resuelta. Cuando una fuente entrega las líneas —con sus tiempos, sus
 * coros de fondo y su atribución— se escriben aquí, en
 * `filesDir/letras/pistas/<id>.json`, y la pista se lleva consigo ese
 * resultado. La próxima vez que suena, sus letras ya vienen encima: no se
 * busca nada, ni se identifica la canción, ni se pregunta a Spicy o a Apple
 * Music.
 *
 * Junto a las líneas se guarda la huella de la pista (título, artista y
 * duración). Si el archivo se sustituye por otro con el mismo identificador,
 * lo guardado se descarta en vez de mostrar letras que no son las suyas.
 *
 * Las letras encontradas no caducan. Los resultados sin letra —o los que no
 * se pudieron confirmar porque la red falló— sí: a los tres días se vuelven a
 * buscar, por si la letra aparece luego o el fallo fue de la red.
 */
class LyricsStore(context: Context) {

    private val directory = File(context.filesDir, DirectoryName)

    /**
     * Lo que hay guardado con [song], o null si todavía no se ha buscado (o si
     * lo guardado ya no sirve).
     */
    suspend fun read(song: Song): LyricsResult? = withContext(Dispatchers.IO) {
        val file = fileOf(song)
        if (!file.isFile) return@withContext null

        val payload = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
        if (payload == null) {
            file.delete()
            return@withContext null
        }

        val root = runCatching { JSONObject(payload) }.getOrNull()
        if (root == null || root.optInt(FormatVersionKey, 0) != FormatVersion) {
            file.delete()
            return@withContext null
        }

        // El archivo de esta pista ya no es el mismo: sus letras no valen.
        if (root.optString(FingerprintKey) != fingerprint(song)) {
            file.delete()
            return@withContext null
        }

        val lines = root.optJSONArray(LinesKey).toLyricLines()
        if (lines.isEmpty() && System.currentTimeMillis() - file.lastModified() > EmptyTtlMs) {
            file.delete()
            return@withContext null
        }

        LyricsResult(
            lines = lines,
            attribution = root.optJSONObject(AttributionKey).toAttribution(),
        )
    }

    /** Guarda [result] con la pista, para no volver a buscarlo. */
    suspend fun write(song: Song, result: LyricsResult) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val root = JSONObject()
                .put(FormatVersionKey, FormatVersion)
                .put(FingerprintKey, fingerprint(song))
                .put(LinesKey, result.lines.linesToJson())
            result.attribution?.let { root.put(AttributionKey, it.toJson()) }
            fileOf(song).writeText(root.toString(), StandardCharsets.UTF_8)
        }
        Unit
    }

    /** Borra lo guardado de todas las pistas. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { directory.deleteRecursively() }
        Unit
    }

    /** Cuántas pistas tienen ya sus letras guardadas. */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        runCatching { directory.listFiles()?.count { it.isFile } ?: 0 }.getOrDefault(0)
    }

    private fun fileOf(song: Song) = File(directory, "${song.id}.json")

    /**
     * La huella de la pista: con ella se reconoce que el archivo guardado es el
     * de esta canción y no el de otra que reutilizó el identificador.
     */
    private fun fingerprint(song: Song): String = listOf(
        SearchNameSimilarity.normalize(song.title),
        SearchNameSimilarity.normalize(song.artist),
        song.duration.toString(),
    ).joinToString("|")

    private companion object {
        const val DirectoryName = "letras/pistas"

        const val FormatVersion = 1
        const val FormatVersionKey = "version"
        const val FingerprintKey = "fingerprint"
        const val LinesKey = "lines"
        const val AttributionKey = "attribution"

        /**
         * Cuánto se guarda un resultado sin letra. Es lo que evita repetir la
         * búsqueda cada vez que suena, sin que un fallo puntual de la red
         * condene a la pista sin letra para siempre.
         */
        const val EmptyTtlMs = 3L * 24L * 60L * 60L * 1_000L
    }
}

// ------------------------------------------------------------------- serialización

private fun List<LyricLine>.linesToJson(): JSONArray = JSONArray().also { array ->
    forEach { line ->
        array.put(
            JSONObject()
                .put("text", line.text)
                .put("startMs", line.startMs)
                .put("endMs", line.endMs)
                .put("oppositeAligned", line.oppositeAligned)
                .put("translation", line.translation)
                .put("transliteration", line.transliteration)
                .put("words", line.words.wordsToJson())
                .put("background", line.background.backgroundToJson()),
        )
    }
}

private fun List<LyricWord>.wordsToJson(): JSONArray = JSONArray().also { array ->
    forEach { word ->
        array.put(
            JSONObject()
                .put("text", word.text)
                .put("startMs", word.startMs)
                .put("endMs", word.endMs)
                .put("partOfWord", word.partOfWord)
                .put("joinWithPrevious", word.joinWithPrevious)
                .put("joinWithNext", word.joinWithNext)
                .put("transliteration", word.transliteration),
        )
    }
}

private fun List<LyricBackgroundLine>.backgroundToJson(): JSONArray = JSONArray().also { array ->
    forEach { line ->
        array.put(
            JSONObject()
                .put("text", line.text)
                .put("startMs", line.startMs)
                .put("endMs", line.endMs)
                .put("oppositeAligned", line.oppositeAligned)
                .put("words", line.words.wordsToJson()),
        )
    }
}

private fun LyricsAttribution.toJson(): JSONObject = JSONObject()
    .put("providerName", providerName)
    .put("originName", originName)
    .put("uploader", uploader?.toJson())
    .put("maker", maker?.toJson())
    .put("songwriters", JSONArray(songwriters))

private fun LyricsContributor.toJson(): JSONObject = JSONObject()
    .put("username", username)
    .put("url", url)

private fun JSONArray?.toLyricLines(): List<LyricLine> {
    if (this == null) return emptyList()
    val lines = mutableListOf<LyricLine>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        lines += LyricLine(
            text = item.optString("text"),
            startMs = item.optLong("startMs", 0L),
            endMs = item.optLong("endMs", 0L),
            words = item.optJSONArray("words").toLyricWords(),
            background = item.optJSONArray("background").toBackgroundLines(),
            oppositeAligned = item.optBoolean("oppositeAligned", false),
            translation = item.optString("translation").takeIf(String::isNotEmpty),
            transliteration = item.optString("transliteration").takeIf(String::isNotEmpty),
        )
    }
    return lines
}

private fun JSONArray?.toLyricWords(): List<LyricWord> {
    if (this == null) return emptyList()
    val words = mutableListOf<LyricWord>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val text = item.optString("text")
        if (text.isEmpty()) continue
        words += LyricWord(
            text = text,
            startMs = item.optLong("startMs", 0L),
            endMs = item.optLong("endMs", 0L),
            partOfWord = item.optBoolean("partOfWord", false),
            joinWithPrevious = item.optBoolean("joinWithPrevious", false),
            joinWithNext = item.optBoolean("joinWithNext", false),
            transliteration = item.optString("transliteration").takeIf(String::isNotEmpty),
        )
    }
    return words
}

private fun JSONArray?.toBackgroundLines(): List<LyricBackgroundLine> {
    if (this == null) return emptyList()
    val lines = mutableListOf<LyricBackgroundLine>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        lines += LyricBackgroundLine(
            text = item.optString("text"),
            startMs = item.optLong("startMs", 0L),
            endMs = item.optLong("endMs", 0L),
            words = item.optJSONArray("words").toLyricWords(),
            oppositeAligned = item.optBoolean("oppositeAligned", false),
        )
    }
    return lines
}

private fun JSONObject?.toAttribution(): LyricsAttribution? {
    if (this == null) return null
    val provider = optString("providerName").trim()
    if (provider.isEmpty()) return null
    return LyricsAttribution(
        providerName = provider,
        originName = optString("originName").takeIf(String::isNotEmpty),
        uploader = optJSONObject("uploader").toContributor(),
        maker = optJSONObject("maker").toContributor(),
        songwriters = optJSONArray("songwriters").toStringList(),
    )
}

/** Los nombres de la lista, sin los vacíos. */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val names = mutableListOf<String>()
    for (index in 0 until length()) {
        val name = optString(index).trim()
        if (name.isNotEmpty()) names += name
    }
    return names
}

private fun JSONObject?.toContributor(): LyricsContributor? {
    if (this == null) return null
    val username = optString("username").trim()
    if (username.isEmpty()) return null
    return LyricsContributor(
        username = username,
        url = optString("url").takeIf(String::isNotEmpty),
    )
}
