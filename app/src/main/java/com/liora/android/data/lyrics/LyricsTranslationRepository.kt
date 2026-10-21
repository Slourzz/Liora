package com.liora.android.data.lyrics

import android.content.Context
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.Song
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * En qué punto va la traducción de la letra.
 *
 * La API de Unison traduce el bloque entero de una vez, así que el progreso
 * solo distingue entre "enviado" y "hecho".
 */
sealed interface LyricsTranslationProgress {
    /** Se está traduciendo: [done] líneas de [total]. */
    data class Translating(val done: Int, val total: Int) : LyricsTranslationProgress
}

/**
 * Traducción de las letras con la API comunitaria de Unison (Better Lyrics).
 *
 * Unison es un servicio público y gratuito: no hace falta clave ni cuenta.
 * Se le manda el bloque de líneas y el idioma de destino, y devuelve cada
 * línea traducida. Si el idioma de origen no se indica, Unison lo reconoce y
 * lo dice en `detectedLang`.
 *
 * Lo que se guarda es solo el texto traducido: la letra original, sus
 * tiempos y su orden no se tocan nunca (el reparto de tiempos lo hace
 * [com.liora.android.data.model.withTranslation], en la capa de arriba).
 *
 * El resultado se guarda con la pista, en `filesDir/letras/traducciones`, para
 * no volver a pedir lo mismo.
 */
class LyricsTranslationRepository(context: Context) {

    private val cacheDirectory = File(context.filesDir, "letras/traducciones")

    /**
     * Traduce [lines] al idioma [target] y devuelve el texto traducido de cada
     * línea, en el mismo orden.
     *
     * Si la pista ya tiene la traducción guardada de antes, se usa esa: no se
     * vuelve a pedir. Si el servicio falla, lanza [LyricsTranslationException]
     * con un motivo que se puede enseñar tal cual.
     */
    suspend fun translate(
        song: Song,
        lines: List<LyricLine>,
        target: String,
        onProgress: (LyricsTranslationProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val texts = lines.map { it.text.trim() }
        if (texts.none { it.isNotEmpty() }) return@withContext emptyList()

        // 1. Lo que ya se tradujo de esta pista, si sigue sirviendo.
        readCache(song, target, texts)?.let { return@withContext it }

        // 2. La llamada. Unison traduce el bloque entero y lo devuelve línea a
        //    línea, en el mismo orden en el que se le mandaron.
        onProgress(LyricsTranslationProgress.Translating(0, texts.size))
        val translated = requestTranslation(texts, target)
        onProgress(LyricsTranslationProgress.Translating(texts.size, texts.size))

        // 3. Se guarda con la pista, para no volver a hacerlo.
        writeCache(song, target, texts, translated)

        translated
    }

    // ------------------------------------------------------------- petición

    /**
     * La llamada a Unison.
     *
     * Se manda el bloque de líneas y el idioma de destino; el de origen lo
     * reconoce el servicio. Cada línea vuelve traducida (o vacía, si ya estaba
     * en el idioma pedido).
     */
    private fun requestTranslation(lines: List<String>, target: String): List<String> {
        val endpoint = URI.create("$BaseUrl/translate").toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val payload = JSONObject()
                .put("lines", JSONArray(lines))
                .put("to", target)
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw LyricsTranslationException(reasonFor(status))
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseTranslation(body, lines)
        } catch (error: LyricsTranslationException) {
            throw error
        } catch (error: Exception) {
            // Una red caída, un tiempo de espera agotado: no es un fallo del
            // servicio, es que no se pudo llegar a él.
            throw LyricsTranslationException(NoConnectionMessage)
        } finally {
            connection.disconnect()
        }
    }

    /** Lee la respuesta de Unison y devuelve el texto de cada línea. */
    private fun parseTranslation(body: String, original: List<String>): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LyricsTranslationException(InvalidResponseMessage)

        // La respuesta viene pelada, pero el mismo envoltorio que usan las
        // búsquedas de Unison ("success" + "data") no hace daño.
        if (root.has("success") && !root.optBoolean("success", true)) {
            throw LyricsTranslationException(
                root.optString("error").takeIf { it.isNotBlank() } ?: InvalidResponseMessage,
            )
        }
        val data = root.optJSONObject("data") ?: root
        val array = data.optJSONArray("lines")
            ?: throw LyricsTranslationException(InvalidResponseMessage)

        val translated = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val text = item?.optString("translation").orEmpty()
            // Si la línea ya estaba en el idioma pedido, Unison la devuelve
            // vacía: se queda la original.
            translated += if (text.isBlank()) original.getOrElse(index) { "" } else text
        }
        while (translated.size < original.size) {
            translated += original[translated.size]
        }
        return translated
    }

    /** El motivo de un fallo, en castellano y sin detalles técnicos. */
    private fun reasonFor(status: Int): String = when (status) {
        400 -> "El servicio no entendió la letra que le mandamos."
        413, 422 -> "La letra es demasiado larga para traducirla de una vez."
        429 -> "El servicio está recibiendo demasiadas peticiones. Inténtalo en un momento."
        502, 503, 504 -> "El servicio de traducción no está disponible ahora mismo."
        else -> "No se pudo traducir la letra (el servicio respondió $status)."
    }

    // ------------------------------------------------------------- guardado

    private fun cacheFile(song: Song, target: String) =
        File(cacheDirectory, "${song.id}_$target.json")

    /** La traducción guardada de esta pista, si sigue sirviendo. */
    private fun readCache(song: Song, target: String, texts: List<String>): List<String>? {
        val file = cacheFile(song, target)
        if (!file.isFile) return null
        val payload = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }
            .getOrNull() ?: return null
        if (payload.optInt("version", 0) != CacheVersion) return null
        // La letra pudo cambiar: si no es la misma, lo guardado no vale.
        if (payload.optInt("fingerprint", 0) != fingerprint(texts)) return null
        val array = payload.optJSONArray("lines") ?: return null
        if (array.length() != texts.size) return null
        return buildList {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }

    private fun writeCache(
        song: Song,
        target: String,
        texts: List<String>,
        translated: List<String>,
    ) {
        runCatching {
            cacheDirectory.mkdirs()
            val array = JSONArray().also { json ->
                translated.forEach { json.put(it) }
            }
            val payload = JSONObject()
                .put("version", CacheVersion)
                .put("songId", song.id)
                .put("language", target)
                .put("fingerprint", fingerprint(texts))
                .put("lines", array)
            cacheFile(song, target).writeText(payload.toString(), StandardCharsets.UTF_8)
        }
    }

    /** Una huella de la letra original: si cambia, lo guardado se descarta. */
    private fun fingerprint(texts: List<String>): Int = texts.fold(7) { acc, text ->
        acc * 31 + text.hashCode()
    }

    private companion object {
        const val CacheVersion = 1

        /** La API pública de Unison, la que usa Better Lyrics. */
        const val BaseUrl = "https://unison.boidu.dev"

        const val ConnectTimeoutMs = 10_000
        const val ReadTimeoutMs = 20_000

        const val NoConnectionMessage =
            "No se pudo contactar con el servicio de traducción. Revisa la conexión."

        const val InvalidResponseMessage =
            "El servicio de traducción devolvió una respuesta que no se entiende."
    }
}

/**
 * Un fallo de la traducción, con el motivo ya escrito para enseñarse.
 */
class LyricsTranslationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
