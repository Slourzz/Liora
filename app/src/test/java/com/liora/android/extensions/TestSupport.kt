package com.liora.android.extensions

import com.liora.android.extensions.net.DownloadOutcome
import com.liora.android.extensions.net.HttpClient
import com.liora.android.extensions.net.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Un cliente HTTP falso para las pruebas.
 *
 * Responde con lo que le pidan por URL y puede fallar a propósito, para
 * comprobar que la app degrada bien.
 */
class FakeHttpClient(
    private val responses: MutableMap<String, String> = mutableMapOf(),
) : HttpClient {

    /** Las URL pedidas, en orden. */
    val requested: MutableList<String> = mutableListOf()

    /** Cuando está a true, toda petición falla. */
    var offline: Boolean = false

    fun on(url: String, body: String): FakeHttpClient {
        responses[url] = body
        return this
    }

    fun onBytes(url: String, bytes: ByteArray): FakeHttpClient {
        binary[url] = bytes
        return this
    }

    private val binary: MutableMap<String, ByteArray> = mutableMapOf()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requested += url
        if (offline) throw java.io.IOException("sin red")
        binary[url]?.let { return HttpResponse(200, String(it)) }
        val body = responses[url] ?: return HttpResponse(404, "no encontrado")
        return HttpResponse(200, body)
    }

    override suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResponse {
        requested += url
        if (offline) throw java.io.IOException("sin red")
        responses[url]?.let { return HttpResponse(200, it) }
        return HttpResponse(404, "no encontrado")
    }

    override suspend fun download(
        url: String,
        target: OutputStream,
        headers: Map<String, String>,
        onProgress: (written: Long, total: Long) -> Unit,
        shouldCancel: () -> Boolean,
    ): DownloadOutcome {
        requested += url
        if (offline) throw java.io.IOException("sin red")
        val bytes = binary[url] ?: responses[url]?.toByteArray() ?: ByteArray(0)
        var written = 0L
        target.write(bytes)
        written += bytes.size
        onProgress(written, bytes.size.toLong())
        return DownloadOutcome(written, bytes.size.toLong(), "application/octet-stream")
    }
}

/**
 * Construye un paquete de extensión de prueba.
 *
 * @param manifest el contenido del manifest.json.
 * @param script el contenido del punto de entrada.
 * @param extraFiles ficheros adicionales (ruta → contenido).
 */
fun buildPackage(
    manifest: String,
    script: String = SAMPLE_SCRIPT,
    extraFiles: Map<String, String> = emptyMap(),
    entryPath: String = "src/index.js",
): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        zip.putEntry("manifest.json", manifest.toByteArray())
        zip.putEntry(entryPath, script.toByteArray())
        for ((name, content) in extraFiles) {
            zip.putEntry(name, content.toByteArray())
        }
    }
    return buffer.toByteArray()
}

private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

/** Un script de extensión de ejemplo, con las funciones básicas. */
val SAMPLE_SCRIPT = """
    registerExtension({
        initialize: function(config) {
            return { ok: true, extensionId: config.extensionId };
        },
        cleanup: function() { return { ok: true }; },
        searchTracks: function(request) {
            return {
                tracks: [
                    { id: "demo-1", title: request.query, artist: "Artista", durationMs: 1000 }
                ]
            };
        },
        getTrack: function(request) {
            return { id: request.trackId, title: "Pista", artist: "Artista", durationMs: 1000 };
        },
        checkAvailability: function(request) {
            return { available: true, kinds: ["playable", "downloadable"], quality: "demo" };
        },
        getAudioSource: function(request) {
            return {
                kind: "playable",
                url: "https://audio.example.com/track.m4a",
                mimeType: "audio/mp4",
                bitrateKbps: 320
            };
        },
        getDownloadUrl: function(request) {
            return { kind: "downloadable", url: "https://audio.example.com/full.m4a" };
        },
        download: function(request) {
            return { success: false, error: "sin red", errorType: "network_error" };
        },
        fetchLyrics: function(request) {
            return {
                lines: [{ startMs: 0, endMs: 1000, text: "linea" }],
                plainText: "linea",
                provider: "demo"
            };
        }
    });
""".trimIndent()

/** Escribe un fichero temporal con el contenido dado. */
fun tempFile(name: String, bytes: ByteArray): File {
    val file = File.createTempFile(name, ".pkg")
    file.writeBytes(bytes)
    file.deleteOnExit()
    return file
}
