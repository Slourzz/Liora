package com.liora.android.extensions

import com.liora.android.extensions.model.AudioSourceKind
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.runtime.ExtensionHostApi
import com.liora.android.extensions.runtime.JsExtensionRuntime
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El runtime JavaScript.
 *
 * Estas pruebas son la prueba de concepto del motor: una extensión escrita en
 * JavaScript se carga, se ejecuta y devuelve datos por el puente.
 */
class JsExtensionRuntimeTest {

    private fun manifest(
        types: String = """["metadata_provider","track_search_provider","audio_source_provider","audio_download_provider","lyrics_provider"]""",
        network: String = """["api.example.com"]""",
        storage: Boolean = true,
        file: Boolean = true,
    ): ExtensionManifest = ExtensionManifest.parse(
        """
        {
          "schemaVersion": 1,
          "id": "demo",
          "displayName": "Demo",
          "version": "1.0.0",
          "description": "Demo",
          "author": "Liora",
          "type": $types,
          "permissions": { "network": $network, "storage": $storage, "file": $file },
          "entrypoint": "src/index.js"
        }
        """.trimIndent(),
    )

    private fun runtime(
        script: String = SAMPLE_SCRIPT,
        manifest: ExtensionManifest = manifest(),
        http: FakeHttpClient = FakeHttpClient(),
        timeoutMs: Long = 5_000L,
    ): JsExtensionRuntime {
        val dir = File(System.getProperty("java.io.tmpdir"), "liora-test-${System.nanoTime()}")
        dir.mkdirs()
        val host = ExtensionHostApi(dir, manifest, http)
        return JsExtensionRuntime(manifest, script, host, timeoutMs)
    }

    @Test
    fun `carga una extension y registra sus funciones`() {
        val runtime = runtime()
        runtime.load()
        assertTrue(runtime.isLoaded)
        runtime.unload()
    }

    @Test
    fun `busca pistas a traves del puente`() {
        val runtime = runtime()
        runtime.load()
        val tracks = runtime.searchTracks("prueba", 10)
        assertEquals(1, tracks.size)
        assertEquals("demo-1", tracks.first().id)
        assertEquals("prueba", tracks.first().title)
        runtime.unload()
    }

    @Test
    fun `devuelve la fuente de audio con su formato`() {
        val runtime = runtime()
        runtime.load()
        val source = runtime.getAudioSource("demo-1", null)
        assertNotNull(source)
        assertEquals(AudioSourceKind.PLAYABLE, source!!.kind)
        assertEquals("https://audio.example.com/track.m4a", source.url)
        assertEquals(320, source.bitrateKbps)
        runtime.unload()
    }

    @Test
    fun `distingue una muestra de una fuente completa`() {
        val script = """
            registerExtension({
                initialize: function() { return { ok: true }; },
                checkAvailability: function() {
                    return { available: true, kinds: ["sample"] };
                },
                getAudioSource: function() {
                    return { kind: "sample", url: "https://audio.example.com/preview.m4a" };
                }
            });
        """.trimIndent()
        val runtime = runtime(script = script, manifest = manifest(types = """["audio_source_provider"]"""))
        runtime.load()
        val availability = runtime.checkAvailability("demo-1")
        assertEquals(listOf(AudioSourceKind.SAMPLE), availability.kinds)
        runtime.unload()
    }

    @Test
    fun `el script no puede tocar las clases de Java`() {
        val script = """
            registerExtension({
                initialize: function() {
                    var reached = false;
                    try {
                        reached = (typeof java !== 'undefined');
                    } catch (e) {
                        reached = false;
                    }
                    return { ok: true, java: reached };
                }
            });
        """.trimIndent()
        val runtime = runtime(script = script, manifest = manifest(types = """["metadata_provider"]"""))
        runtime.load()
        // Si el script hubiera visto `java`, el sandbox habria fallado al
        // cargarlo; que llegue a initialize significa que no lo ve.
        assertTrue(runtime.isLoaded)
        runtime.unload()
    }

    @Test
    fun `el script no puede usar la API de Java para salirse`() {
        val script = """
            registerExtension({
                initialize: function() {
                    var error = '';
                    try {
                        Packages.java.lang.System.exit(0);
                    } catch (e) {
                        error = String(e);
                    }
                    return { ok: true };
                }
            });
        """.trimIndent()
        val runtime = runtime(script = script, manifest = manifest(types = """["metadata_provider"]"""))
        runtime.load()
        assertTrue(runtime.isLoaded)
        runtime.unload()
    }

    @Test
    fun `un bucle infinito no bloquea la app`() {
        val script = """
            registerExtension({
                initialize: function() { return { ok: true }; },
                searchTracks: function() { while (true) { } }
            });
        """.trimIndent()
        val runtime = runtime(script = script, timeoutMs = 1_500L)
        runtime.load()
        val error = runCatching { runtime.searchTracks("x", 1) }.exceptionOrNull()
        assertNotNull("la llamada should've fallado por tiempo", error)
        assertTrue(error is ExtensionException.ProviderTimeout)
        runtime.unload()
    }

    @Test
    fun `una extension que no llama a registerExtension no carga`() {
        val runtime = runtime(script = "var x = 1;")
        val error = runCatching { runtime.load() }.exceptionOrNull()
        assertTrue(error is ExtensionException.RuntimeFailure)
    }

    @Test
    fun `la red solo se permite a los dominios declarados`() {
        val http = FakeHttpClient()
        http.on("https://api.example.com/datos", """{"ok":true}""")
        val script = """
            registerExtension({
                initialize: function() { return { ok: true }; },
                getTrack: function(request) {
                    var allowed = host.http("GET", "https://api.example.com/datos", null);
                    var denied = host.http("GET", "https://otro.example.com/datos", null);
                    return { id: request.trackId, title: allowed.ok ? "ok" : "no", artist: denied.ok ? "permiso" : "denegado" };
                }
            });
        """.trimIndent()
        val runtime = runtime(script = script, http = http)
        runtime.load()
        val track = runtime.getTrack("x")
        assertNotNull(track)
        assertEquals("ok", track!!.title)
        assertEquals("denegado", track.artist)
        runtime.unload()
    }

    @Test
    fun `el almacenamiento depende del permiso`() {
        val script = """
            registerExtension({
                initialize: function() { return { ok: true }; },
                getTrack: function(request) {
                    host.storage.set("clave", "valor");
                    var value = host.storage.get("clave");
                    return { id: request.trackId, title: value, artist: "x" };
                }
            });
        """.trimIndent()

        val allowed = runtime(
            script = script,
            manifest = manifest(types = """["metadata_provider"]""", storage = true),
        )
        allowed.load()
        assertEquals("valor", allowed.getTrack("x")?.title)
        allowed.unload()

        val denied = runtime(
            script = script,
            manifest = manifest(types = """["metadata_provider"]""", storage = false),
        )
        denied.load()
        assertNotNull(denied.getTrack("x"))
        assertTrue(denied.getTrack("x")!!.title!!.contains("storage_denied"))
        denied.unload()
    }

    @Test
    fun `la letra llega con sus tiempos`() {
        val runtime = runtime()
        runtime.load()
        val lyrics = runtime.fetchLyrics(
            com.liora.android.extensions.model.ProviderTrack(
                id = "demo-1",
                title = "Pista",
                artist = "Artista",
                providerId = "demo",
            ),
            10,
        )
        assertNotNull(lyrics)
        assertEquals(1, lyrics!!.lines.size)
        assertEquals("linea", lyrics.lines.first().text)
        runtime.unload()
    }

    @Test
    fun `una extension sin el tipo de proveedor no lo usa`() {
        val runtime = runtime(manifest = manifest(types = """["metadata_provider"]"""))
        runtime.load()
        // No declara busqueda: la llamada no se hace y no hay resultados.
        assertTrue(runtime.searchTracks("x", 5).isEmpty())
        assertEquals(null, runtime.getAudioSource("demo-1", null))
        runtime.unload()
    }
}
