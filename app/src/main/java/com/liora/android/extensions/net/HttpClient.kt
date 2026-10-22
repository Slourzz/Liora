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
    /** Las direcciones por las que se pasó, en orden. */
    val redirects: List<String> = emptyList(),
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/** El resultado de una descarga: cuánto se escribió y de dónde venía. */
data class DownloadOutcome(
    val bytesWritten: Long,
    val totalBytes: Long,
    val contentType: String?,
    val statusCode: Int,
    /** El tamaño que decía el servidor, si lo decía de forma fiable. */
    val expectedBytes: Long?,
    val finalUrl: String,
) {
    /**
     * ¿Se ha quedado a medias?
     *
     * Solo se puede saber si el servidor dijo cuántos bytes venían y no
     * coinciden con los recibidos. Sin ese dato no se puede asegurar que la
     * descarga esté completa: quien la use tiene que comprobarlo.
     */
    val looksTruncated: Boolean
        get() = expectedBytes != null && expectedBytes > 0 && bytesWritten != expectedBytes
}

/**
 * Qué se puede hacer con una petición.
 *
 * Es la política de red de Liora: decide qué destinos son válidos, cuántas
 * redirecciones se aceptan y qué cabeceras viajan a cada salto. La comprueba
 * quien llama, **antes** de abrir la conexión, no el script.
 */
data class NetworkPolicy(
    /**
     * Los dominios a los que se puede hablar.
     *
     * Vacío significa "cualquiera": solo se usa para las peticiones de la
     * propia aplicación (un repositorio, un paquete), donde el usuario ya ha
     * autorizado la dirección. Las peticiones de una extensión siempre van
     * con la lista de su manifiesto.
     */
    val allowedDomains: List<String> = emptyList(),
    /** Cuántas redirecciones se siguen como máximo. */
    val maxRedirects: Int = 5,
    /** Que no se pueda bajar de https a http. */
    val requireHttps: Boolean = true,
    /**
     * Cabeceras que no viajan a un dominio distinto del de origen.
     *
     * Una cabecera de autorización es un secreto: si la respuesta redirige a
     * otro sitio, esa cabecera no puede viajar detrás.
     */
    val sensitiveHeaders: Set<String> = setOf("authorization", "cookie", "proxy-authorization"),
) {
    /** ¿Este destino está permitido? */
    fun allows(url: String): Boolean {
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (parsed.scheme == null) return false
        if (requireHttps && !parsed.scheme.equals("https", ignoreCase = true)) return false
        if (allowedDomains.isEmpty()) return true
        val host = parsed.host?.lowercase().orEmpty()
        if (host.isEmpty()) return false
        return allowedDomains.any { allowed ->
            val rule = allowed.trim().lowercase()
            rule == host || (rule.startsWith("*.") && host.endsWith(rule.substring(1)))
        }
    }

    /** El motivo por el que un destino no vale, o null si vale. */
    fun rejectionReason(url: String): String? {
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            return "La dirección no es una URL válida: $url"
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme == null) return "La dirección no tiene esquema: $url"
        if (requireHttps && scheme != "https") {
            return "Solo se permiten direcciones https, no $scheme: $url"
        }
        if (allowedDomains.isNotEmpty()) {
            val host = parsed.host?.lowercase().orEmpty()
            if (host.isEmpty()) return "La dirección no tiene dominio: $url"
            val allowed = allowedDomains.any { rule ->
                val clean = rule.trim().lowercase()
                clean == host || (clean.startsWith("*.") && host.endsWith(clean.substring(1)))
            }
            if (!allowed) {
                return "La extensión no tiene permiso para hablar con '$host'"
            }
        }
        return null
    }
}

/**
 * El cliente HTTP del sistema de extensiones.
 *
 * Es una interfaz para que las pruebas puedan sustituirlo por uno falso, y
 * para que la política de red se aplique en un solo sitio.
 */
interface HttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        policy: NetworkPolicy = NetworkPolicy(),
    ): HttpResponse

    suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        policy: NetworkPolicy = NetworkPolicy(),
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
        policy: NetworkPolicy = NetworkPolicy(),
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): DownloadOutcome
}

/**
 * El cliente HTTP real, sobre `HttpURLConnection`.
 *
 * Las redirecciones **no** se siguen por su cuenta: se siguen una a una,
 * comprobando la política en cada salto. Así una URL autorizada que redirige
 * a un dominio que no lo está se corta ahí, y una cabecera de autorización
 * no viaja detrás de una redirección a otro sitio.
 */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpClient {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        policy: NetworkPolicy,
    ): HttpResponse = request("GET", url, null, headers, policy)

    override suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String>,
        policy: NetworkPolicy,
    ): HttpResponse = request("POST", url, body, headers, policy)

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        policy: NetworkPolicy,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val hops = mutableListOf<String>()
        var current = url
        var origin = url
        var redirects = 0

        while (true) {
            policy.rejectionReason(current)?.let { reason ->
                throw IOException(reason)
            }
            hops += current
            val connection = open(method, current, headersFor(current, origin, headers, policy))
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && !location.isNullOrBlank()) {
                    redirects++
                    if (redirects > policy.maxRedirects) {
                        throw IOException(
                            "Demasiadas redirecciones (más de ${policy.maxRedirects}) desde $origin",
                        )
                    }
                    current = resolve(current, location)
                    continue
                }
                val stream: InputStream? =
                    if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                return@withContext HttpResponse(
                    statusCode = status,
                    body = text,
                    headers = connection.headerFields
                        .filterKeys { it != null }
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                    redirects = hops.dropLast(1),
                )
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("nunca llega aqui")
    }

    override suspend fun download(
        url: String,
        target: OutputStream,
        headers: Map<String, String>,
        policy: NetworkPolicy,
        onProgress: (written: Long, total: Long) -> Unit,
        shouldCancel: () -> Boolean,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        var current = url
        var origin = url
        var redirects = 0

        while (true) {
            policy.rejectionReason(current)?.let { reason ->
                throw IOException(reason)
            }
            val connection = open("GET", current, headersFor(current, origin, headers, policy))
            try {
                // Leer el estado ya puede fallar si el servidor corta la
                // conexion antes de mandar la respuesta completa.
                val status = try {
                    connection.responseCode
                } catch (error: IOException) {
                    throw IOException(
                        "La respuesta se ha cortado antes de poder leerse: ${error.message}",
                        error,
                    )
                }
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && !location.isNullOrBlank()) {
                    redirects++
                    if (redirects > policy.maxRedirects) {
                        throw IOException(
                            "Demasiadas redirecciones (más de ${policy.maxRedirects}) desde $origin",
                        )
                    }
                    current = resolve(current, location)
                    continue
                }
                if (status !in 200..299) {
                    throw IOException("La descarga falló con el código $status")
                }

                // Leer las cabeceras ya puede fallar: si el servidor corta la
                // conexion antes de mandar el Content-Length, el error sale
                // aqui y no al leer el cuerpo.
                val declared: Long?
                val contentType: String?
                try {
                    declared = connection.contentLengthLong.takeIf { it > 0 }
                    contentType = connection.contentType
                } catch (error: IOException) {
                    throw IOException("La respuesta se ha cortado antes de poder leerse", error)
                }

                val total = declared ?: 0L
                var written = 0L
                val buffer = ByteArray(64 * 1024)
                try {
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
                } catch (error: IOException) {
                    // Si el servidor dijo cuantos bytes venian y no han llegado
                    // todos, la descarga esta a medias: se dice explicitamente
                    // en vez de dejar un error de socket sin contexto.
                    if (declared != null && written < declared) {
                        throw IOException(
                            "La descarga se ha cortado: venían $declared bytes y han llegado $written",
                            error,
                        )
                    }
                    throw error
                }
                return@withContext DownloadOutcome(
                    bytesWritten = written,
                    totalBytes = total,
                    contentType = contentType,
                    statusCode = status,
                    expectedBytes = declared,
                    finalUrl = current,
                )
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("nunca llega aqui")
    }

    /**
     * Las cabeceras que viajan a este salto.
     *
     * Las sensibles (autorización, cookies) solo viajan mientras el destino
     * siga siendo el mismo dominio de origen: si la respuesta manda a otro
     * sitio, ese secreto se queda atrás.
     */
    private fun headersFor(
        url: String,
        origin: String,
        headers: Map<String, String>,
        policy: NetworkPolicy,
    ): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val sameHost = hostOf(url) == hostOf(origin)
        if (sameHost) return headers
        return headers.filterKeys { it.lowercase() !in policy.sensitiveHeaders }
    }

    /** Resuelve la ruta de una redirección contra la URL actual. */
    private fun resolve(current: String, location: String): String = try {
        URI(current).resolve(location).toString()
    } catch (_: Exception) {
        location
    }

    private fun open(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): HttpURLConnection {
        val parsed = try {
            URI(url).toURL()
        } catch (error: Exception) {
            throw IOException("URL inválida: $url", error)
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        // Las redirecciones se siguen a mano, salto a salto, para poder
        // comprobar la politica en cada uno.
        connection.instanceFollowRedirects = false
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
