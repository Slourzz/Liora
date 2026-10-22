package com.liora.android.extensions.runtime

import com.liora.android.extensions.model.AudioDownloadProvider
import com.liora.android.extensions.model.AudioSource
import com.liora.android.extensions.model.AudioSourceKind
import com.liora.android.extensions.model.AudioSourceProvider
import com.liora.android.extensions.model.Availability
import com.liora.android.extensions.model.DownloadResult
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.model.LyricsProvider
import com.liora.android.extensions.model.MetadataProvider
import com.liora.android.extensions.model.ProviderLyricLine
import com.liora.android.extensions.model.ProviderLyrics
import com.liora.android.extensions.model.ProviderTrack
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.model.TrackSearchProvider
import com.liora.android.extensions.net.HttpClient
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Function as JsFunction

/**
 * Una extensión cargada y viva.
 *
 * El JavaScript corre en un motor Rhino aislado:
 *
 * - **Sin acceso a Java**: el `ClassShutter` no deja ver ninguna clase de
 *   Java al script, así que no hay `Packages`, ni `java.lang`, ni acceso al
 *   contexto de Android.
 * - **Con tiempo limitado**: Rhino cuenta instrucciones, y al superarse el
 *   plazo la ejecución se aborta. Un bucle infinito no puede bloquear Liora.
 * - **En su propio hilo**: la extensión no comparte hilo con la aplicación y
 *   sus errores no la afectan.
 *
 * La única puerta hacia el exterior es [ExtensionHostApi], que aplica los
 * permisos declarados en el manifiesto.
 */
class JsExtensionRuntime(
    val manifest: ExtensionManifest,
    private val script: String,
    private val host: ExtensionHostApi,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : MetadataProvider, TrackSearchProvider, AudioSourceProvider, AudioDownloadProvider,
    LyricsProvider {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liora-ext-${manifest.id}").apply { isDaemon = true }
    }

    private val deadline = AtomicLong(0L)
    private val factory = SandboxFactory(deadline)

    private var context: Context? = null
    private var scope: Scriptable? = null
    private var registered: Scriptable? = null

    @Volatile
    private var loaded = false

    /** ¿La extensión está cargada y lista? */
    val isLoaded: Boolean get() = loaded

    /** Compila el script, expone el puente y llama a `initialize`. */
    fun load() {
        try {
            executor.submit {
                val ctx = factory.enter()
                try {
                    context = ctx
                    ctx.setOptimizationLevel(-1)
                    ctx.setInstructionObserverThreshold(INSTRUCTION_THRESHOLD)
                    ctx.setClassShutter(ClassShutter { _ -> false })
                    val shared = ctx.initStandardObjects()
                    scope = shared
                    defineHostFunctions(ctx, shared)
                    ctx.evaluateString(shared, BOOTSTRAP, "liora-bootstrap.js", 1, null)
                    ctx.evaluateString(shared, script, "${manifest.id}.js", 1, null)

                    val functions = ScriptableObject.getProperty(shared, REGISTERED_GLOBAL)
                    if (functions == ScriptableObject.NOT_FOUND || functions == null) {
                        throw ExtensionException.RuntimeFailure(
                            "La extensión '${manifest.id}' no llamó a registerExtension()",
                        )
                    }
                    registered = functions as? Scriptable
                        ?: throw ExtensionException.RuntimeFailure(
                            "registerExtension() no recibió un objeto de funciones",
                        )

                    val result = callOnCurrentThread("initialize", listOf(configJson()))
                    val parsed = parseObject(result)
                    if (parsed != null && parsed.optBoolean("ok") == false &&
                        parsed.has("error")
                    ) {
                        throw ExtensionException.RuntimeFailure(
                            "La extensión '${manifest.id}' falló al inicializarse: ${parsed.optString("error")}",
                        )
                    }
                    loaded = true
                } catch (error: ExtensionException) {
                    throw error
                } catch (error: Throwable) {
                    throw ExtensionException.RuntimeFailure(
                        "No se pudo cargar la extensión '${manifest.id}': ${error.message}",
                        error,
                    )
                }
            }.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause
            if (cause is ExtensionException) throw cause
            throw ExtensionException.RuntimeFailure(
                "No se pudo cargar la extensión '${manifest.id}': ${cause?.message ?: error.message}",
                cause ?: error,
            )
        }
    }

    /** Llama a `cleanup` y apaga el motor. */
    fun unload() {
        runCatching {
            executor.submit {
                runCatching { invoke("cleanup", listOf()) }
                context = null
                scope = null
                registered = null
                loaded = false
                runCatching { factory.exit() }
            }.get()
        }
        executor.shutdown()
    }

    /** Cancela las descargas y operaciones en curso de esta extensión. */
    fun cancelDownloads() = host.cancel()

    // ------------------------------------------------------------- proveedores

    override fun getTrack(trackId: String): ProviderTrack? {
        if (!manifest.has(ProviderType.METADATA)) return null
        val result = callObject("getTrack", JSONObject().put("trackId", trackId)) ?: return null
        return result.toTrack(manifest.id)
    }

    override fun searchTracks(query: String, limit: Int): List<ProviderTrack> {
        if (!manifest.has(ProviderType.TRACK_SEARCH)) return emptyList()
        val result = callObject(
            "searchTracks",
            JSONObject().put("query", query).put("limit", limit),
        ) ?: return emptyList()
        val array = result.optJSONArray("tracks")
            ?: result.optJSONArray("results")
            ?: return emptyList()
        val tracks = mutableListOf<ProviderTrack>()
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.toTrack(manifest.id)?.let { tracks += it }
        }
        return tracks
    }

    override fun checkAvailability(trackId: String): Availability {
        if (!manifest.has(ProviderType.AUDIO_SOURCE) && !manifest.has(ProviderType.AUDIO_DOWNLOAD)) {
            return Availability(false, reason = "Esta extensión no ofrece fuentes de audio")
        }
        val result = callObject("checkAvailability", JSONObject().put("trackId", trackId))
            ?: return Availability(false, reason = "El proveedor no pudo responder")
        val kinds = mutableListOf<AudioSourceKind>()
        val array = result.optJSONArray("kinds")
        if (array != null) {
            for (index in 0 until array.length()) {
                AudioSourceKind.fromWire(array.optString(index))?.let { kinds += it }
            }
        }
        return Availability(
            available = result.optBoolean("available", false),
            kinds = kinds,
            reason = result.optString("reason").ifBlank { null },
            quality = result.optString("quality").ifBlank { null },
        )
    }

    override fun getAudioSource(trackId: String, quality: String?): AudioSource? {
        if (!manifest.has(ProviderType.AUDIO_SOURCE)) return null
        val payload = JSONObject().put("trackId", trackId)
        if (quality != null) payload.put("quality", quality)
        val result = callObject("getAudioSource", payload) ?: return null
        return result.toAudioSource()
    }

    override fun getDownloadUrl(trackId: String, quality: String?): AudioSource? {
        if (!manifest.has(ProviderType.AUDIO_DOWNLOAD)) return null
        val payload = JSONObject().put("trackId", trackId)
        if (quality != null) payload.put("quality", quality)
        val result = callObject("getDownloadUrl", payload) ?: return null
        return result.toAudioSource()
    }

    override fun download(
        trackId: String,
        quality: String?,
        targetPath: String,
        onProgress: (written: Long, total: Long) -> Unit,
    ): DownloadResult {
        if (!manifest.has(ProviderType.AUDIO_DOWNLOAD)) {
            throw ExtensionException.NotSupported(manifest.id, "download")
        }
        host.progress = onProgress
        try {
            val payload = JSONObject().put("trackId", trackId).put("outputPath", targetPath)
            if (quality != null) payload.put("quality", quality)
            val result = callObject("download", payload)
                ?: return DownloadResult(false, error = "El proveedor no devolvió resultado")
            if (result.optBoolean("success", false)) {
                return DownloadResult(
                    success = true,
                    filePath = result.optString("filePath").ifBlank { targetPath },
                    bytesWritten = result.optLong("bytes", 0L),
                    mimeType = result.optString("mimeType").ifBlank { null },
                )
            }
            return DownloadResult(
                success = false,
                error = result.optString("error", "descarga fallida"),
                errorType = result.optString("errorType").ifBlank { null },
            )
        } finally {
            host.progress = null
        }
    }

    override fun fetchLyrics(track: ProviderTrack, durationSec: Int): ProviderLyrics? {
        if (!manifest.has(ProviderType.LYRICS)) return null
        val payload = JSONObject()
            .put("trackId", track.id)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("durationSec", durationSec)
        val result = callObject("fetchLyrics", payload) ?: return null
        val lines = mutableListOf<ProviderLyricLine>()
        val array = result.optJSONArray("lines")
        if (array != null) {
            for (index in 0 until array.length()) {
                val line = array.optJSONObject(index) ?: continue
                lines += ProviderLyricLine(
                    startMs = line.optLong("startMs", line.optLong("startTimeMs", 0L)),
                    endMs = line.optLong("endMs", line.optLong("endTimeMs", 0L)),
                    text = line.optString("text"),
                )
            }
        }
        return ProviderLyrics(
            lines = lines,
            plainText = result.optString("plainText", result.optString("plainLyrics")),
            provider = result.optString("provider", manifest.displayName),
        )
    }

    // ---------------------------------------------------------------- interno

    private fun callObject(functionName: String, payload: JSONObject): JSONObject? =
        parseObject(invoke(functionName, listOf(payload.toString())))

    private fun parseObject(raw: Any?): JSONObject? {
        val text = raw as? String ?: return null
        if (text.isBlank() || text == "null") return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    /** Ejecuta una función de la_extension dentro del hilo del runtime. */
    private fun invoke(functionName: String, args: List<String>): Any? {
        val task = executor.submit<Any?> { callOnCurrentThread(functionName, args) }
        return awaitWithTimeout(task, functionName)
    }

    /**
     * La llamada en sí, en el hilo de la extensión.
     *
     * No se encola: el hilo es de uno en uno y llamarse a sí mismo desde una
     * tarea ya encolada se quedaría esperando forever.
     */
    private fun callOnCurrentThread(functionName: String, args: List<String>): Any? {
        val ctx = context ?: throw ExtensionException.RuntimeFailure("El runtime no está cargado")
        val functions = registered
            ?: throw ExtensionException.RuntimeFailure("El runtime no está cargado")
        val property = ScriptableObject.getProperty(functions, functionName)
        if (property == ScriptableObject.NOT_FOUND || property == null) return null
        val function = property as? JsFunction
            ?: throw ExtensionException.RuntimeFailure("'$functionName' no es una función")
        deadline.set(System.currentTimeMillis() + timeoutMs)
        val array = args.toTypedArray()
        return function.call(ctx, scope, scope, array) as? String
    }

    private fun awaitWithTimeout(task: Future<Any?>, functionName: String): Any? = try {
        task.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        task.cancel(true)
        host.cancel()
        throw ExtensionException.ProviderTimeout(manifest.id, timeoutMs)
    } catch (error: Exception) {
        // Si el sandbox cortó la ejecucion por tiempo, eso es lo que se cuenta.
        if (factory.expired.get()) {
            throw ExtensionException.ProviderTimeout(manifest.id, timeoutMs)
        }
        // Rhino envuelve lo que lanza el script en sus propias excepciones:
        // se busca la causa real en la cadena.
        val cause = rootCause(error)
        if (cause is ExtensionException) throw cause
        if (cause is Error && cause !is StackOverflowError && cause !is OutOfMemoryError) throw cause
        throw ExtensionException.ProviderFailure(
            manifest.id,
            "La llamada a '$functionName' falló: ${cause.message ?: cause::class.java.simpleName}",
            cause,
        )
    }

    /** La causa más profunda de una excepción. */
    private fun rootCause(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (depth < 8) {
            val next = current.cause ?: break
            if (next === current) break
            current = next
            depth++
        }
        return current
    }

    /** Define en el ámbito las funciones que el script puede usar. */
    private fun defineHostFunctions(ctx: Context, shared: Scriptable) {
        fun define(name: String, body: (Array<out Any?>) -> Any?) {
            val function = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any? = body(args)
            }
            ScriptableObject.putProperty(shared, name, function)
        }

        define("__host_http") { args ->
            host.http(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString(),
            )
        }
        define("__host_storage_get") { args ->
            host.storageGet(args.getOrNull(0)?.toString().orEmpty()) ?: "null"
        }
        define("__host_storage_set") { args ->
            host.storageSet(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        define("__host_log") { args ->
            val level = args.getOrNull(0)?.toString()?.toIntOrNull() ?: 1
            host.log(level, args.getOrNull(1)?.toString().orEmpty())
            "null"
        }
        define("__host_file_download") { args ->
            host.fileDownload(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        define("__host_file_read") { args ->
            host.fileRead(args.getOrNull(0)?.toString().orEmpty())
        }
        define("__host_file_write") { args ->
            host.fileWrite(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
    }

    /** La configuración que recibe la extensión en `initialize`. */
    private fun configJson(): String = JSONObject()
        .put("extensionId", manifest.id)
        .put("displayName", manifest.displayName)
        .put("version", manifest.version)
        .put("appVersion", APP_VERSION)
        .put(
            "permissions",
            JSONObject()
                .put("network", JSONArray(manifest.permissions.network))
                .put("storage", manifest.permissions.storage)
                .put("file", manifest.permissions.file),
        )
        .put("assetScheme", ASSET_SCHEME)
        .toString()

    private fun JSONObject.toTrack(providerId: String): ProviderTrack = ProviderTrack(
        id = optString("id"),
        title = optString("title", optString("name")),
        artist = optString("artist", optString("artists")),
        album = optString("album", optString("albumName")),
        durationMs = optLong("durationMs", 0L),
        isrc = optString("isrc").ifBlank { null },
        artworkUrl = optString("artworkUrl", optString("coverUrl")).ifBlank { null },
        providerId = providerId,
    )

    private fun JSONObject.toAudioSource(): AudioSource? {
        val url = optString("url").ifBlank { optString("downloadUrl") }
        if (url.isEmpty()) return null
        val headers = mutableMapOf<String, String>()
        optJSONObject("headers")?.let { obj ->
            for (key in obj.keys()) headers[key] = obj.optString(key)
        }
        return AudioSource(
            kind = AudioSourceKind.fromWire(optString("kind", "playable")) ?: AudioSourceKind.PLAYABLE,
            url = url,
            mimeType = optString("mimeType").ifBlank { null },
            bitrateKbps = optInt("bitrateKbps", 0).takeIf { it > 0 },
            sampleRateHz = optInt("sampleRateHz", 0).takeIf { it > 0 },
            channels = optInt("channels", 0).takeIf { it > 0 },
            durationMs = optLong("durationMs", 0L).takeIf { it > 0 },
            expiresAtMs = optLong("expiresAtMs", 0L).takeIf { it > 0 },
            headers = headers,
            requiresAuthentication = optBoolean("requiresAuthentication", false),
            notes = optString("notes").ifBlank { null },
        )
    }

    companion object {
        /** El plazo por defecto de una llamada a un proveedor. */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** La holgura antes de dar la llamada por perdida. */
        private const val GRACE_MS = 2_000L

        /** Cuántas instrucciones se cuentan entre aviso y aviso. */
        private const val INSTRUCTION_THRESHOLD = 20_000

        /** El nombre del global donde queda el objeto de funciones. */
        private const val REGISTERED_GLOBAL = "__registered"

        /** El esquema para los recursos que trae la propia aplicación. */
        const val ASSET_SCHEME = "liora-asset"

        /** La versión de Liora que ve la extensión. */
        const val APP_VERSION = "1.0"

        /**
         * La fábrica de contextos: aísla el script del mundo de Java.
         *
         * - El `ClassShutter` (puesto en el contexto) no deja ver ninguna
         *   clase de Java.
         * - El contador de instrucciones aborta la ejecución al pasar el plazo.
         */
        class SandboxFactory(private val deadline: AtomicLong) : ContextFactory() {

            /** Se enciende cuando el script ha agotado su tiempo. */
            val expired = AtomicBoolean(false)

            override fun observeInstructionCount(context: Context, instructionCount: Int) {
                val limit = deadline.get()
                if (limit > 0L && System.currentTimeMillis() > limit) {
                    expired.set(true)
                    // Rhino envuelve lo que se lanza aqui y pierde la causa,
                    // asi que el aviso real lo lleva esta marca.
                    throw TimeoutSignal()
                }
            }
        }

        /** La señal interna de que el script se ha pasado de tiempo. */
        class TimeoutSignal : RuntimeException("tiempo agotado")

        /**
         * El arranque que se evalúa antes que el script de la extensión.
         *
         * Define el objeto `host` (la única puerta al exterior) y la función
         * `registerExtension` con la que la extensión declara sus funciones.
         * Cada función registrada se envuelve para devolver JSON: entre Liora
         * y el script solo viajan cadenas.
         */
        private val BOOTSTRAP = """
            var __registered = null;
            var host = {
                http: function(method, url, body) {
                    var b = (body === null || body === undefined) ? null : String(body);
                    return JSON.parse(__host_http(String(method), String(url), b));
                },
                storage: {
                    get: function(key) {
                        var raw = __host_storage_get(String(key));
                        return (raw === null || raw === undefined || raw === 'null') ? null : JSON.parse(raw);
                    },
                    set: function(key, value) {
                        return JSON.parse(__host_storage_set(String(key), JSON.stringify(value)));
                    }
                },
                log: {
                    info: function(message) { __host_log(1, String(message)); },
                    warn: function(message) { __host_log(2, String(message)); },
                    error: function(message) { __host_log(3, String(message)); }
                },
                file: {
                    download: function(url, target) {
                        return JSON.parse(__host_file_download(String(url), String(target)));
                    },
                    readText: function(path) { return __host_file_read(String(path)); },
                    writeText: function(path, text) { __host_file_write(String(path), String(text)); }
                }
            };
            function registerExtension(functions) {
                var wrapped = {};
                for (var key in functions) {
                    if (typeof functions[key] === 'function') {
                        wrapped[key] = (function(name, fn) {
                            return function() {
                                var args = Array.prototype.slice.call(arguments);
                                // El puente manda los argumentos como JSON: aqui
                                // se vuelven a convertir en objetos.
                                if (args.length > 0 && typeof args[0] === 'string') {
                                    try { args[0] = JSON.parse(args[0]); } catch (e) { }
                                }
                                var result = fn.apply(null, args);
                                if (result === undefined || result === null) return null;
                                return JSON.stringify(result);
                            };
                        })(key, functions[key]);
                    }
                }
                __registered = wrapped;
                return true;
            }
        """.trimIndent()
    }
}
