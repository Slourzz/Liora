package com.liora.android.extensions

import com.liora.android.extensions.audio.DownloadManager
import com.liora.android.extensions.model.AudioSourceKind
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.model.ProviderTrack
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.provider.ProviderEntry
import com.liora.android.extensions.provider.ProviderManager
import com.liora.android.extensions.provider.ProviderRegistry
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
 * El uso de los proveedores: cuando uno falla, el siguiente entra; y las
 * descargas, que no pueden dejar un fichero a medias por delante.
 */
class ProviderManagerTest {

    private fun manifest(id: String, types: String): ExtensionManifest = ExtensionManifest.parse(
        """
        {
          "schemaVersion": 1, "id": "$id", "displayName": "$id", "version": "1.0.0",
          "description": "d", "author": "a",
          "type": $types,
          "permissions": { "network": [], "storage": false, "file": false },
          "entrypoint": "src/index.js"
        }
        """.trimIndent(),
    )

    private fun entry(id: String, script: String, types: String = """["audio_source_provider"]"""): ProviderEntry {
        val manifest = manifest(id, types)
        val dir = File(System.getProperty("java.io.tmpdir"), "liora-prov-${System.nanoTime()}")
        dir.mkdirs()
        val runtime = JsExtensionRuntime(manifest, script, ExtensionHostApi(dir, manifest, FakeHttpClient()))
        runtime.load()
        return ProviderEntry(
            extensionId = id,
            displayName = id,
            version = "1.0.0",
            types = manifest.types.toSet(),
            runtime = runtime,
            enabled = true,
        )
    }

    @Test
    fun `un proveedor que falla deja que entre el siguiente`() = runBlocking {
        val registry = ProviderRegistry()
        registry.register(
            entry(
                "roto",
                """
                registerExtension({
                    initialize: function() { return { ok: true }; },
                    getAudioSource: function() { throw new Error("revento"); }
                });
                """.trimIndent(),
            ),
        )
        registry.register(
            entry(
                "bueno",
                """
                registerExtension({
                    initialize: function() { return { ok: true }; },
                    getAudioSource: function() {
                        return { kind: "playable", url: "https://audio.example.com/x.m4a" };
                    }
                });
                """.trimIndent(),
            ),
        )
        val manager = ProviderManager(registry)
        val source = manager.audioSource("cualquier")
        assertNotNull("el segundo proveedor deberia responder", source)
        assertEquals("https://audio.example.com/x.m4a", source!!.url)
    }

    @Test
    fun `un proveedor sin ese tipo no se consulta`() = runBlocking {
        val registry = ProviderRegistry()
        registry.register(
            entry(
                "solo-metadatos",
                """
                registerExtension({
                    initialize: function() { return { ok: true }; },
                    getTrack: function(request) { return { id: request.trackId, title: "x" }; }
                });
                """.trimIndent(),
                types = """["metadata_provider"]""",
            ),
        )
        val manager = ProviderManager(registry)
        assertNull(manager.audioSource("nada"))
        assertEquals(0, manager.providers(ProviderType.AUDIO_SOURCE).size)
        assertEquals(1, manager.providers(ProviderType.METADATA).size)
    }

    @Test
    fun `la mejor coincidencia gana`() {
        val manager = ProviderManager(ProviderRegistry())
        val candidates = listOf(
            ProviderTrack("a", "Otra cosa", "Liora", providerId = "demo"),
            ProviderTrack("b", "Tono de prueba", "Liora", "Demo", 2000L, providerId = "demo"),
        )
        assertEquals(
            "b",
            manager.bestMatch("Tono de prueba", "Liora", 2000L, candidates)?.id,
        )
    }
}

/** Las descargas: nunca un fichero a medias por delante. */
class DownloadManagerTest {

    private fun provider(script: String, downloadsRoot: File? = null): ProviderEntry {
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
        val dir = File(System.getProperty("java.io.tmpdir"), "liora-dl-${System.nanoTime()}")
        dir.mkdirs()
        val host = ExtensionHostApi(
            dataDir = dir,
            manifest = manifest,
            http = FakeHttpClient(),
            extraRoots = listOfNotNull(downloadsRoot),
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
        )
    }

    @Test
    fun `una descarga fallida no deja fichero`() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "liora-dlroot-${System.nanoTime()}")
        val manager = DownloadManager(root)
        val entry = provider(
            """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) { return { success: false, error: "no hay red" }; }
            });
            """.trimIndent(),
        )
        val error = runCatching {
            manager.download(entry, "x", null, "cancion.wav")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is ExtensionException.ProviderFailure)
        assertTrue(manager.downloaded().isEmpty())
    }

    @Test
    fun `una descarga que dice haber terminado pero no deja nada no se publica`() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "liora-dlroot-${System.nanoTime()}")
        val manager = DownloadManager(root)
        val entry = provider(
            """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) { return { success: true, filePath: request.outputPath }; }
            });
            """.trimIndent(),
        )
        val error = runCatching {
            manager.download(entry, "x", null, "cancion.wav")
        }.exceptionOrNull()
        assertNotNull("una descarga vacia no se puede publicar", error)
        assertTrue(manager.downloaded().isEmpty())
    }

    @Test
    fun `una descarga buena se publica`() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "liora-dlroot-${System.nanoTime()}")
        val manager = DownloadManager(root)
        val entry = provider(
            """
            registerExtension({
                initialize: function() { return { ok: true }; },
                download: function(request) {
                    host.file.writeText(request.outputPath, "contenido");
                    return { success: true, filePath: request.outputPath, bytes: 8 };
                }
            });
            """.trimIndent(),
            downloadsRoot = File(root, "extensiones/descargas"),
        )
        val file = manager.download(entry, "x", null, "cancion.wav")
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        assertEquals(1, manager.downloaded().size)
    }
}

/** La fuente de audio y lo que significa cada tipo. */
class AudioSourceContractTest {

    @Test
    fun `una muestra no es una fuente completa`() {
        val sample = com.liora.android.extensions.model.AudioSource(
            kind = AudioSourceKind.SAMPLE,
            url = "https://x/preview.m4a",
        )
        val playable = com.liora.android.extensions.model.AudioSource(
            kind = AudioSourceKind.PLAYABLE,
            url = "https://x/full.m4a",
        )
        assertTrue(sample.kind != AudioSourceKind.PLAYABLE)
        assertTrue(playable.kind != AudioSourceKind.SAMPLE)
    }

    @Test
    fun `un enlace caducado se nota`() {
        val expired = com.liora.android.extensions.model.AudioSource(
            kind = AudioSourceKind.PLAYABLE,
            url = "https://x/full.m4a",
            expiresAtMs = 1000L,
        )
        val valid = com.liora.android.extensions.model.AudioSource(
            kind = AudioSourceKind.PLAYABLE,
            url = "https://x/full.m4a",
            expiresAtMs = System.currentTimeMillis() + 60_000L,
        )
        assertTrue(expired.isExpired())
        assertTrue(!valid.isExpired())
    }
}
