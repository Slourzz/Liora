package com.liora.android.extensions.runtime

import android.content.Context
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.net.HttpClient
import com.liora.android.extensions.net.NetworkPolicy
import com.liora.android.extensions.net.hostOf
import com.liora.android.extensions.session.SessionStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Lo que una extensión puede hacer desde su JavaScript.
 *
 * El puente es la única puerta: una extensión no ve la aplicación, ni el
 * sistema de archivos, ni las credenciales. Pide lo que necesita y Liora lo
 * concede o lo deniega. La lista de dominios se comprueba **aquí**, en la
 * capa nativa, no en el script: un script modificado no puede saltarse la
 * comprobación.
 */
class ExtensionHostApi(
    private val dataDir: File,
    private val manifest: ExtensionManifest,
    private val http: HttpClient,
    private val logger: (String) -> Unit = {},
    /**
     * Resuelve un recurso que trae la propia aplicación.
     *
     * Es lo que permite el esquema `liora-asset://`: una extensión puede
     * usar una pista de ejemplo sin depender de ningún servicio. La app decide
     * qué recursos existen; la extensión solo pide el nombre.
     */
    private val assetResolver: (String) -> File? = { null },
    /**
     * Carpetas extra donde la extensión puede escribir.
     *
     * La app las concede de forma explicita (por ejemplo, la carpeta donde se
     * guardan las descargas). Fuera de su carpeta y de estas no puede
     * escribir.
     */
    private val extraRoots: List<File> = emptyList(),
    /**
     * Las sesiones de cuenta disponibles.
     *
     * La extensión no ve las cookies: la app las añade a la petición cuando
     * el manifiesto pide esa sesión y el dominio está permitido.
     */
    private val sessions: SessionStore? = null,
) {

    /** El directorio de datos de la extensión: su almacenamiento y sus ficheros. */
    val dataDirectory: File get() = dataDir

    /** Se pone a true para cancelar las operaciones en curso. */
    val cancelled = AtomicBoolean(false)

    /**
     * A quién se le avisa del avance de una descarga.
     *
     * Lo establece el gestor de descargas antes de llamar al proveedor y lo
     * retira al terminar: así el script no necesita saber de la app.
     */
    @Volatile
    var progress: ((written: Long, total: Long) -> Unit)? = null

    /** Cancela las operaciones de esta extensión. */
    fun cancel() = cancelled.set(true)

    // ------------------------------------------------------------------ http

    /**
     * Una peticion HTTP desde la extension, sujeta a los dominios permitidos.
     *
     * @param headers cabeceras extra. Las sensibles (autorizacion, cookies)
     *   no viajan a un dominio distinto del de origen: lo comprueba el
     *   cliente HTTP en cada salto de una redireccion.
     */
    fun http(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        useSession: Boolean = false,
    ): String {
        val host = hostOf(url)
        if (!manifest.permissions.allowsDomain(host)) {
            return error(
                "network_denied",
                "La extensión '${manifest.id}' no tiene permiso para hablar con '$host'",
            )
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            return error("network_denied", "Solo se permiten peticiones https")
        }
        return try {
            val started = System.currentTimeMillis()
            val sent = if (useSession) sessionHeaders(url, headers) else headers
            val response = runBlocking {
                when (method.uppercase()) {
                    "GET" -> http.get(url, sent, policy())
                    "POST" -> http.post(url, body, sent, policy())
                    else -> null
                }
            } ?: return error("unsupported", "Método no admitido: $method")
            com.liora.android.extensions.ExtLog.i(
                "HTTP ${method.uppercase()} ${hostOf(url)} -> ${response.statusCode} " +
                    "en ${System.currentTimeMillis() - started} ms " +
                    "(${response.body.length / 1024} KB)",
            )
            if (!response.isSuccessful) {
                com.liora.android.extensions.ExtLog.w(
                    "Respuesta ${response.statusCode}: " + response.body.take(300),
                )
            }
            JSONObject()
                .put("statusCode", response.statusCode)
                .put("body", response.body)
                .put("ok", response.isSuccessful)
                .toString()
        } catch (error: Exception) {
            error("network_error", error.message ?: "fallo de red")
        }
    }

    /**
     * Las cabeceras de la petición, con la sesión si la extensión la pide.
     *
     * La cookie de la sesión la pone la app, no la extensión: el script nunca
     * ve la sesión, solo la usa. Solo se añade si el manifiesto pide esa
     * sesión y el dominio de la petición está permitido.
     */
    private fun sessionHeaders(url: String, headers: Map<String, String>): Map<String, String> {
        val store = sessions ?: return headers
        val host = hostOf(url)
        val service = manifest.permissions.session.firstOrNull { service ->
            manifest.permissions.allowsSession(service) && serviceHosts(service, host)
        } ?: return headers
        val cookie = store.cookieHeader(service) ?: return headers
        val result = headers.toMutableMap()
        result["Cookie"] = cookie
        // La firma se hace con el origen de la pagina que pide (no con el del
        // servidor de la API): es lo que espera el servicio.
        store.authorizationHeader(service, sessionOrigin(service))?.let {
            result["Authorization"] = it
            result["Origin"] = sessionOrigin(service)
            result["X-Goog-AuthUser"] = "0"
        }
        return result
    }

    /** El origen con el que se firma la petición de cada servicio. */
    private fun sessionOrigin(service: String): String = when (service) {
        SessionStore.YOUTUBE -> "https://music.youtube.com"
        else -> "https://$service"
    }

    /**
     * ¿El dominio de la petición es uno de los de ese servicio?
     *
     * La cookie de una sesión solo se manda a los dominios del servicio que
     * la ha creado: mandarla a otro sitio sería regalarle la sesión.
     */
    private fun serviceHosts(service: String, host: String): Boolean = when (service) {
        SessionStore.YOUTUBE -> host == "music.youtube.com" ||
            host == "youtube.com" ||
            host == "www.youtube.com" ||
            host == "youtubei.googleapis.com" ||
            host.endsWith(".googlevideo.com")
        else -> false
    }

    /**
     * La política de red de esta extensión.
     *
     * Solo los dominios del manifiesto, https siempre, y las cabeceras
     * sensibles no viajan a otro dominio. La comprueba el cliente HTTP en
     * cada salto de una redirección, no el script.
     */
    private fun policy(): NetworkPolicy = NetworkPolicy(
        allowedDomains = manifest.permissions.network,
        requireHttps = true,
        maxRedirects = MAX_REDIRECTS,
    )

    // --------------------------------------------------------------- storage

    /** Lee un valor del almacenamiento de la extensión. */
    fun storageGet(key: String): String? {
        if (!manifest.permissions.storage) {
            return error("storage_denied", "La extensión '${manifest.id}' no tiene permiso de almacenamiento")
        }
        val file = storageFile(key)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** Guarda un valor en el almacenamiento de la extensión. */
    fun storageSet(key: String, value: String): String {
        if (!manifest.permissions.storage) {
            return error("storage_denied", "La extensión '${manifest.id}' no tiene permiso de almacenamiento")
        }
        return try {
            val file = storageFile(key)
            file.parentFile?.mkdirs()
            file.writeText(value)
            "{\"ok\":true}"
        } catch (error: Exception) {
            error("storage_error", error.message ?: "no se pudo guardar")
        }
    }

    // ------------------------------------------------------------------- log

    /** Anota un mensaje en el registro de la aplicación. */
    fun log(level: Int, message: String) {
        logger("[$level] ${manifest.id}: $message")
    }

    // ---------------------------------------------------------------- session

    /**
     * Si hay una sesión iniciada para este servicio.
     *
     * La extensión solo pregunta; la cookie no sale de la app.
     */
    fun hasSession(service: String): Boolean {
        if (!manifest.permissions.allowsSession(service)) return false
        return sessions?.has(service) == true
    }

    // ----------------------------------------------------------------- files

    /**
     * Descarga un fichero a la carpeta de la extensión.
     *
     * El destino está limitado a su directorio: una extensión no puede
     * escribir fuera de su espacio. Las cabeceras son las que el proveedor
     * necesita para que el servidor le entregue el fichero (algunos, como el
     * de video de YouTube, piden una peticion por rango).
     */
    fun fileDownload(
        url: String,
        targetPath: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        if (!manifest.permissions.file) {
            return error("file_denied", "La extensión '${manifest.id}' no tiene permiso de ficheros")
        }
        val target = resolveInsideData(targetPath)
            ?: return error("file_denied", "La ruta '$targetPath' está fuera del espacio de la extensión")

        // Un recurso de la propia aplicación: se copia tal cual.
        if (url.startsWith(ASSET_SCHEME, ignoreCase = true)) {
            val name = url.substringAfter("://").substringBefore('/').trim()
            val asset = assetResolver(name)
                ?: return error("asset_not_found", "La aplicación no tiene el recurso '$name'")
            return try {
                asset.copyTo(target, overwrite = true)
                JSONObject()
                    .put("success", true)
                    .put("path", target.absolutePath)
                    .put("bytes", target.length())
                    .toString()
            } catch (error: Exception) {
                error("file_error", error.message ?: "no se pudo copiar el recurso")
            }
        }

        if (!manifest.permissions.allowsDomain(hostOf(url))) {
            return error(
                "network_denied",
                "La extensión '${manifest.id}' no tiene permiso para hablar con '${hostOf(url)}'",
            )
        }
        return try {
            var written = 0L
            FileOutputStream(target).use { output ->
                val outcome = runBlocking {
                    http.download(
                        url = url,
                        target = output,
                        // Con la sesion: algunos servidores no sirven el
                        // archivo a quien no ha iniciado sesion.
                        headers = sessionHeaders(url, headers),
                        policy = policy(),
                        onProgress = { bytes, total ->
                            written = bytes
                            progress?.invoke(bytes, total)
                        },
                    )
                }
                if (outcome.bytesWritten <= 0L) {
                    return error("download_failed", "La descarga no devolvió datos")
                }
                if (outcome.looksTruncated) {
                    // El servidor dijo cuanto venir y no es lo que vino: el
                    // fichero esta a medias y no vale.
                    return error(
                        "download_incomplete",
                        "La descarga se ha cortado: venían ${outcome.expectedBytes} bytes y han llegado ${outcome.bytesWritten}",
                    )
                }
                if (outcome.statusCode !in 200..299) {
                    return error("download_failed", "La descarga falló con el código ${outcome.statusCode}")
                }
            }
            JSONObject()
                .put("success", true)
                .put("path", target.absolutePath)
                .put("bytes", written)
                .toString()
        } catch (error: Exception) {
            error("download_failed", error.message ?: "fallo de descarga")
        }
    }

    /** Lee un fichero de la extensión. */
    fun fileRead(path: String): String {
        if (!manifest.permissions.file) {
            return error("file_denied", "La extensión '${manifest.id}' no tiene permiso de ficheros")
        }
        val file = resolveInsideData(path)
            ?: return error("file_denied", "La ruta '$path' está fuera del espacio de la extensión")
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrElse { error("file_error", it.message.orEmpty()) }
    }

    /** Escribe un fichero de la extensión. */
    fun fileWrite(path: String, content: String): String {
        if (!manifest.permissions.file) {
            return error("file_denied", "La extensión '${manifest.id}' no tiene permiso de ficheros")
        }
        val file = resolveInsideData(path)
            ?: return error("file_denied", "La ruta '$path' está fuera del espacio de la extensión")
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            "{\"ok\":true}"
        } catch (error: Exception) {
            error("file_error", error.message ?: "no se pudo escribir")
        }
    }

    // ---------------------------------------------------------------- interno

    private fun storageFile(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "clave" }
        return File(storageDir(), safe)
    }

    private fun storageDir(): File = File(dataDir, "storage").apply { mkdirs() }

    /** Resuelve una ruta dentro del espacio de la extensión, o null si se sale. */
    private fun resolveInsideData(path: String): File? {
        if (path.isBlank()) return null
        val candidate = File(path)
        val target = if (candidate.isAbsolute) candidate else File(dataDir, path)
        val roots = buildList {
            add(dataDir)
            addAll(extraRoots)
        }
        for (root in roots) {
            val base = runCatching { root.canonicalFile.path + File.separator }.getOrNull() ?: continue
            val resolved = runCatching { target.canonicalFile.path }.getOrNull() ?: continue
            if (resolved.startsWith(base)) return target
        }
        return null
    }

    private fun error(type: String, message: String): String =
        JSONObject().put("ok", false).put("errorType", type).put("error", message).toString()

    companion object {
        /** El esquema con el que se piden los recursos de la propia aplicación. */
        const val ASSET_SCHEME = "liora-asset://"

        /** Cuántas redirecciones se siguen como máximo en una petición. */
        const val MAX_REDIRECTS = 5
    }
}
