package com.liora.android.extensions

import com.liora.android.extensions.install.ExtensionInstaller
import com.liora.android.extensions.install.PackageValidator
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.RegistryExtension
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La instalación de una extensión: descarga, verificación, validación y
 * activación.
 */
class ExtensionInstallerTest {

    private val manifestJson = """
        {
          "schemaVersion": 1,
          "id": "demo",
          "displayName": "Demo",
          "version": "1.0.0",
          "description": "Una demo",
          "author": "Liora",
          "type": ["metadata_provider", "track_search_provider"],
          "permissions": { "network": ["api.example.com"], "storage": true, "file": true },
          "entrypoint": "src/index.js"
        }
    """.trimIndent()

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "liora-install-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    private fun registryEntry(
        url: String,
        sha256: String,
        version: String = "1.0.0",
    ) = RegistryExtension(
        id = "demo",
        name = "demo",
        displayName = "Demo",
        version = version,
        description = "Una demo",
        downloadUrl = url,
        sha256 = sha256,
        category = "utility",
        tags = emptyList(),
        minLioraVersion = "1.0",
        iconUrl = "",
        updatedAt = "",
    )

    @Test
    fun `instala un paquete valido y lo deja listo para usar`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = buildPackage(manifestJson)
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val manifest = installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bytes))))

        assertEquals("demo", manifest.id)
        assertEquals("1.0.0", manifest.version)
        assertTrue(installer.installed().any { it.id == "demo" })
        assertTrue(File(installer.directoryOf("demo"), "src/index.js").exists())
    }

    @Test
    fun `rechaza un paquete cuyo hash no coincide`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = buildPackage(manifestJson)
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val error = runCatching {
            installer.install(registryEntry(url, "0".repeat(64)))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is ExtensionException.IntegrityFailure)
        assertTrue(installer.installed().isEmpty())
    }

    @Test
    fun `rechaza un paquete que no es un zip`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = ByteArray(200) { 7 }
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val error = runCatching {
            installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bytes))))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is ExtensionException.CorruptPackage)
    }

    @Test
    fun `rechaza una extension incompatible con esta version`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = buildPackage(
            manifestJson.replace(
                "\"version\": \"1.0.0\"",
                "\"version\": \"9.0.0\", \"minLioraVersion\": \"9.0.0\"",
            ),
        )
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val error = runCatching {
            installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bytes))))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is ExtensionException.IncompatibleExtension)
    }

    @Test
    fun `rechaza un manifiesto sin manifiesto valido`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = buildPackage("{\"nada\": true}")
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val error = runCatching {
            installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bytes))))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is ExtensionException.InvalidManifest)
    }

    @Test
    fun `una instalacion fallida no toca la que ya estaba`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val good = buildPackage(manifestJson)
        val http = FakeHttpClient().onBytes(url, good)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))
        installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(good))))

        // Ahora llega un paquete corrupto con el mismo identificador.
        val bad = buildPackage("no soy un manifiesto")
        http.onBytes(url, bad)
        runCatching {
            installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bad))))
        }

        // La instalacion anterior sigue viva y con su codigo.
        assertEquals("1.0.0", installer.installed().first().version)
        assertTrue(File(installer.directoryOf("demo"), "src/index.js").exists())
    }

    @Test
    fun `no se puede instalar por http`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val bytes = buildPackage(manifestJson)
        val http = FakeHttpClient().onBytes("http://example.com/demo.zip", bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))

        val error = runCatching {
            installer.install(registryEntry("http://example.com/demo.zip", PackageValidator.sha256(writeTemp(bytes))))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is ExtensionException.CorruptPackage)
    }

    @Test
    fun `desinstalar quita la extension`() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val url = "https://example.com/demo.zip"
        val bytes = buildPackage(manifestJson)
        val http = FakeHttpClient().onBytes(url, bytes)
        val installer = ExtensionInstaller(root, http, PackageValidator("1.0"))
        installer.install(registryEntry(url, PackageValidator.sha256(writeTemp(bytes))))

        assertTrue(installer.remove("demo"))
        assertTrue(installer.installed().isEmpty())
    }

    private fun writeTemp(bytes: ByteArray): File {
        val file = File.createTempFile("hash", ".pkg")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }
}

/** La protección frente a rutas manipuladas dentro del paquete. */
class PackagePathTest {

    @Test
    fun `rechaza las rutas peligrosas`() {
        assertTrue(!PackageValidator.isSafePath("../fuera.js"))
        assertTrue(!PackageValidator.isSafePath("/etc/passwd"))
        assertTrue(!PackageValidator.isSafePath("a/../../b.js"))
        assertTrue(!PackageValidator.isSafePath("a\\b.js"))
        assertTrue(PackageValidator.isSafePath("src/index.js"))
        assertTrue(PackageValidator.isSafePath("src/anidado/fichero.js"))
    }

    @Test
    fun `compara versiones`() {
        assertTrue(PackageValidator.compareVersions("1.2.0", "1.1.9") > 0)
        assertTrue(PackageValidator.compareVersions("1.0", "1.0.0") == 0)
        assertTrue(PackageValidator.compareVersions("0.9.9", "1.0.0") < 0)
    }
}
