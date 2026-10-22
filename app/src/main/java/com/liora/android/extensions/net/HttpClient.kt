package com.liora.android.extensions.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Una respuesta HTTP leída por completo. */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/** El resultado de una descarga: cuánto se escribió y de dónde venía. */
data class DownloadOutcome(
    val bytesWritten: Long,
    val totalBytes: Long,
    val contentType: String?,
)

/**
 * El cliente HTTP del sistema de extensiones.
 *
 * Es una interfaz para que las pruebas puedan sustituirlo por uno falso, y
 * para que el control de dominios se aplique en un solo sitio.
 */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    /**
     * Descarga a [target] sin cargar el fichero entero en memoria.
     *
     * @param onProgress se llama con (escritos, total) mientras baja.
     * @param shouldCancel se consulta entre trozos: si devuelve true, se
     *   aborta la descarga.
     */
    suspend fun download(
        url: String,
        target: OutputStream,
        headers: Map<String, String> = emptyMap(),
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): DownloadOutcome
}

/**
 * El cliente HTTP real, sobre `HttpURLConnection`.
 *
 * Es el mismo patrón que usa el resto de Liora (sin librerías de red
 * externas), con la diferencia de que aquí las descargas van a un flujo, no a
 * memoria, y se pueden cancelar.
 */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        request("GET", url, null, headers)

    override suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResponse = request("POST", url, body, headers)

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = open(method, url, headers)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream: InputStream? =
                if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(
                statusCode = status,
                body = text,
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            )
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        target: OutputStream,
        headers: Map<String, String>,
        onProgress: (written: Long, total: Long) -> Unit,
        shouldCancel: () -> Boolean,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val connection = open("GET", url, headers)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    if (shouldCancel()) throw IOException("descarga cancelada")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    target.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
            }
            DownloadOutcome(
                bytesWritten = written,
                totalBytes = total,
                contentType = connection.contentType,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, url: String, headers: Map<String, String>): HttpURLConnection {
        val parsed = try {
            URI(url).toURL()
        } catch (error: Exception) {
            throw IOException("URL inválida: $url", error)
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        for ((key, value) in headers) connection.setRequestProperty(key, value)
        return connection
    }
}

/** El dominio de una URL, o vacío si no se puede leer. */
fun hostOf(url: String): String = try {
    URI(url).host.orEmpty().lowercase()
} catch (_: Exception) {
    ""
}

/** ¿La URL es una dirección https válida? */
fun isHttpsUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return false
    return hostOf(trimmed).isNotEmpty()
}
