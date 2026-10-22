package com.liora.android.extensions

import com.liora.android.extensions.audio.AudioFormat
import com.liora.android.extensions.audio.DownloadManager
import com.liora.android.extensions.audio.DownloadPublisher
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.provider.ProviderEntry
import com.liora.android.extensions.runtime.ExtensionHostApi
import com.liora.android.extensions.runtime.JsExtensionRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las descargas: nunca se da por buena una descarga que no esta comprobada.
 */
class DownloadManagerTest {

    private val manifest = ExtensionManifest.parse(
        """
        {
          "schemaVersion": 1, "id": "demo", "displayName": "demo", "version": "1.0.0",
          "description": "d", "author": "a",
          "type": ["audio_download_provider"],
          "permissions": { "network": [], "storage": true, "file": true },
          "entrypoint": "src/index.js"
        }
        """.trimIndent(),
    )

    /** Un publicador de mentira: anota lo que se publicaria. */
    private class FakePublisher : DownloadPublisher {
        val published = mutableListOf<Pair<String, Long>>()

        override fun publish(
            temp: File,
            displayName: String,
            mimeType: String,
        ): Pair<android.net.Uri?, String> {
            published += displayName to temp.length()
            return null to "Descargas/$displayName"
        }
    }

    /** El montaje de una prueba: el temporal, el gestor y el proveedor. */
    private class Fixture {
        /** El manifiesto de la extension de prueba. */
        val manifest = ExtensionManifest.parse(
            """
            {
              "schemaVersion": 1, "id": "demo", "displayName": "demo", "version": "1.0.0",
              "description": "d", "author": "a",
              "type": ["audio_download_provider"],
              "permissions": { "network": [], "storage": true, "file": true },
              "entrypoint": "src/index.js"
            }
            """.trimIndent(),
        )

        val root = File(
            System.getProperty("java.io.tmpdir"),
            "liora-dl-${System.nanoTime()}",
        ).apply { mkdirs() }
        val tempDir = File(root, "temp").apply { mkdirs() }
        val publisher = FakePublisher()
        val manager = DownloadManager(
            tempDir = tempDir,
            fallbackDir = File(root, "final").apply { mkdirs() },
            publisher = publisher,
        )

        /** Un script que "descarga" escribiendo texto en el temporal. */
        fun scriptWriting(content: String, bytes: Long): String = """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) {
                    host.file.writeText(request.outputPath, "$content");
                    return { success: true, filePath: request.outputPath, bytes: $bytes };
                }
            });
        """.trimIndent()

        fun provider(script: String): ProviderEntry {
            val dir = File(root, "datos").apply { mkdirs() }
            val host = ExtensionHostApi(
                dataDir = dir,
                manifest = manifest,
                http = FakeHttpClient(),
                // El temporal de la descarga es una carpeta que la app
                // concede a la extension.
                extraRoots = listOf(tempDir),
            )
            val runtime = JsExtensionRuntime(manifest, script, host)
            runtime.load()
            return ProviderEntry(
                extensionId = "demo",
                displayName = "demo",
                version = "1.0.0",
                types = manifest.types.toSet(),
                runtime = runtime,
                enabled = true,
                allowedDomains = manifest.permissions.network,
            )
        }
    }

    @Test
    fun `una descarga fallida no deja fichero`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(
            """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) { return { success: false, error: "no hay red" }; }
            });
            """.trimIndent(),
        )
        val error = runCatching {
            fixture.manager.download(entry, "x", null, "cancion.wav")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is ExtensionException.ProviderFailure)
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `una descarga que dice haber terminado pero no deja nada no se publica`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(
            """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) { return { success: true, filePath: request.outputPath }; }
            });
            """.trimIndent(),
        )
        val error = runCatching {
            fixture.manager.download(entry, "x", null, "cancion.wav")
        }.exceptionOrNull()
        assertNotNull("una descarga vacia no se puede publicar", error)
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `un fichero que no es audio no se publica`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(fixture.scriptWriting("<html>error del servidor</html>", 31))
        val error = runCatching {
            fixture.manager.download(entry, "x", null, "cancion.mp3")
        }.exceptionOrNull()
        assertNotNull("una pagina de error no es una descarga", error)
        assertTrue("el error debe explicar que no es audio: ${error?.message}", error!!.message!!.contains("audio"))
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `el tamano que dice el proveedor tiene que coincidir`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(fixture.scriptWriting("RIFF0000WAVEfmt ", 99999))
        val error = runCatching {
            fixture.manager.download(entry, "x", null, "cancion.wav")
        }.exceptionOrNull()
        assertNotNull("un tamaño que no cuadra no vale", error)
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `una descarga buena se publica y se sabe donde quedo`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(fixture.scriptWriting("RIFF0000WAVEfmt ", 16))
        val file = fixture.manager.download(entry, "x", null, "cancion.wav")
        assertEquals("cancion.wav", file.displayName)
        assertTrue(file.bytes > 0)
        assertTrue(file.location.contains("Descargas"))
        assertEquals(1, fixture.publisher.published.size)
    }

    @Test
    fun `un nombre manipulado no se cuela en la ruta`() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.provider(fixture.scriptWriting("RIFF0000WAVEfmt ", 16))
        val file = fixture.manager.download(entry, "x", null, "../../escapada/cancion.wav")
        assertTrue("el nombre no debe llevar rutas: ${file.displayName}", !file.displayName.contains("/"))
        assertTrue(!file.displayName.contains(".."))
    }

    @Test
    fun `se reconocen los formatos de audio`() {
        val root = File(System.getProperty("java.io.tmpdir"), "liora-fmt-${System.nanoTime()}")
        root.mkdirs()
        val wav = File(root, "a.wav").apply {
            writeBytes(ByteArray(44).also { "RIFF".toByteArray().copyInto(it, 0) })
        }
        assertEquals(AudioFormat.WAV, AudioFormat.detect(wav))

        val mp3 = File(root, "a.mp3").apply {
            writeBytes("ID3 ".toByteArray() + ByteArray(20))
        }
        assertEquals(AudioFormat.MP3, AudioFormat.detect(mp3))

        val html = File(root, "b.mp3").apply {
            writeBytes("<html>no soy audio</html>".toByteArray())
        }
        assertNull(AudioFormat.detect(html))
    }
}
