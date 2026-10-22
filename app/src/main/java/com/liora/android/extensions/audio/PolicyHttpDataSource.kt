package com.liora.android.extensions.audio

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.liora.android.extensions.net.NetworkPolicy
import com.liora.android.extensions.net.hostOf
import com.liora.android.extensions.session.SessionStore
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Las cabeceras que una fuente de audio necesita.
 *
 * Viven aquí, y no en el `MediaItem`, porque son un dato del origen de datos:
 * un mismo `MediaItem` puede reproducirse con cabeceras o sin ellas, y dos
 * fuentes distintas no pueden mezclarse. La tabla se vacía cuando cambia la
 * pista, así que una cabecera de una fuente nunca llega a otra.
 */
object ProviderSourceRegistry {

    private val headers = mutableMapOf<String, Map<String, String>>()

    /** Anota las cabeceras de una URL concreta. */
    @Synchronized
    fun register(url: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        headers[url] = values
    }

    /** Las cabeceras de una URL, si las tiene. */
    @Synchronized
    fun headersFor(url: String): Map<String, String>? = headers[url]

    /** Olvida todas las cabeceras. */
    @Synchronized
    fun clear() = headers.clear()

    /** Olvida las cabeceras de una URL. */
    @Synchronized
    fun forget(url: String) {
        headers.remove(url)
    }
}

/**
 * Un origen de datos HTTP que sigue la política de red de Liora.
 *
 * Media3, por su cuenta, sigue las redirecciones a su manera y se lleva las
 * cabeceras a donde apunte la redireccion. Aquí no: cada salto se comprueba
 * contra la política, una cabecera sensible no viaja a otro dominio, y no se
 * puede bajar de https a http.
 */
class PolicyHttpDataSource(
    private val policy: NetworkPolicy,
    /**
     * Las sesiones de cuenta disponibles.
     *
     * Some servers (the video one of YouTube, for example) do not give the
     * audio to a request that is not signed in: the session has to travel
     * with the request, and the app is the one that holds it.
     */
    private val sessions: SessionStore? = null,
) : DataSource {

    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var currentUrl: String = ""
    private var redirects = 0

    private val responseHeaders: MutableMap<String, List<String>> = mutableMapOf()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        currentUrl = dataSpec.uri.toString()
        redirects = 0
        while (true) {
            policy.rejectionReason(currentUrl)?.let { throw IOException(it) }
            val isSameHost = hostOf(currentUrl) == hostOf(dataSpec.uri.toString())
            val headers = ProviderSourceRegistry.headersFor(dataSpec.uri.toString()).orEmpty()
            val sent = if (isSameHost) {
                headers
            } else {
                // A otro dominio no viajan las cabeceras sensibles.
                headers.filterKeys { it.lowercase() !in policy.sensitiveHeaders }
            }
            // La sesion del usuario viaja con la peticion: sin ella, algunos
            // servidores no sirven el archivo.
            val withSession = sessionHeaders(currentUrl, sent)

            val open = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            open.connectTimeout = CONNECT_TIMEOUT_MS
            open.readTimeout = READ_TIMEOUT_MS
            open.instanceFollowRedirects = false
            open.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in withSession) open.setRequestProperty(key, value)

            val status = open.responseCode
            val location = open.getHeaderField("Location")
            if (status in 300..399 && !location.isNullOrBlank()) {
                redirects++
                if (redirects > policy.maxRedirects) {
                    open.disconnect()
                    throw IOException("Demasiadas redirecciones desde ${dataSpec.uri}")
                }
                val next = URI(currentUrl).resolve(location).toString()
                open.disconnect()
                currentUrl = next
                continue
            }

            if (status !in 200..299) {
                com.liora.android.extensions.ExtLog.w(
                    "La fuente de audio respondió $status (${hostOf(currentUrl)}), " +
                        "con sesión: ${withSession.containsKey("Cookie")}, " +
                        "cabeceras: ${withSession.keys.joinToString()}",
                )
                open.disconnect()
                throw IOException("La fuente de audio respondió con el código $status")
            }

            connection = open
            stream = open.inputStream
            open.headerFields.forEach { (key, values) ->
                if (key != null) responseHeaders[key] = values
            }
            return open.contentLengthLong
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val input = stream ?: throw IOException("El origen de datos no está abierto")
        val read = input.read(buffer, offset, length)
        return if (read < 0) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): android.net.Uri? = android.net.Uri.parse(currentUrl)

    /**
     * Las cabeceras con la sesión de cuenta, si la hay para ese dominio.
     *
     * La cookie solo se manda a los dominios del servicio con el que se
     * inició sesión: mandarla a otro sitio sería regalarla.
     */
    private fun sessionHeaders(url: String, headers: Map<String, String>): Map<String, String> {
        val store = sessions ?: return headers
        val host = hostOf(url)
        val service = when {
            host == "music.youtube.com" || host == "youtube.com" ||
                host == "www.youtube.com" || host == "youtubei.googleapis.com" ||
                host.endsWith(".googlevideo.com") -> SessionStore.YOUTUBE
            else -> null
        } ?: return headers
        val cookie = store.cookieHeader(service) ?: return headers
        val result = headers.toMutableMap()
        result["Cookie"] = cookie
        store.authorizationHeader(service, "https://music.youtube.com")?.let {
            result["Authorization"] = it
        }
        return result
    }

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        runCatching { stream?.close() }
        runCatching { connection?.disconnect() }
        stream = null
        connection = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

/**
 * La fábrica de orígenes de datos del reproductor.
 *
 * Para lo local (ficheros, `content://`) sigue usando el comportamiento
 * normal de Media3; para lo HTTP usa la fuente con política, con las
 * cabeceras que el proveedor autorizó para esa URL.
 */
class ProviderDataSourceFactory(
    context: android.content.Context,
    private val policy: NetworkPolicy = NetworkPolicy(),
    private val sessions: SessionStore? = null,
) : DataSource.Factory {

    private val defaultFactory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = ProviderDataSource(policy, defaultFactory, sessions)
}

/** Elige entre la fuente normal y la que lleva la política. */
class ProviderDataSource(
    private val policy: NetworkPolicy,
    private val defaultFactory: DataSource.Factory,
    private val sessions: SessionStore? = null,
) : DataSource {

    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val chosen: DataSource = if (scheme == "http" || scheme == "https") {
            PolicyHttpDataSource(policy, sessions)
        } else {
            defaultFactory.createDataSource()
        }
        delegate = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): android.net.Uri? =
        delegate?.getUri() ?: throw IOException("El origen de datos no está abierto")

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
