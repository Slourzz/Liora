package com.liora.android.extensions

import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.model.RegistryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La lectura del registro de un repositorio. */
class RegistryParserTest {

    @Test
    fun `lee un registro valido`() {
        val registry = RegistryParser.parse(
            """
            {
              "version": 1,
              "updated_at": "2026-01-01T00:00:00Z",
              "extensions": [
                {
                  "id": "demo",
                  "name": "demo",
                  "display_name": "Demo",
                  "version": "1.2.3",
                  "description": "Una demo",
                  "download_url": "https://example.com/demo.zip",
                  "sha256": "368c0cab332c03fc77abc45bc",
                  "category": "download",
                  "tags": ["demo", "audio"],
                  "min_app_version": "1.0"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, registry.extensions.size)
        val extension = registry.extensions.first()
        assertEquals("demo", extension.id)
        assertEquals("Demo", extension.displayName)
        assertEquals("1.2.3", extension.version)
        assertEquals("https://example.com/demo.zip", extension.downloadUrl)
        assertEquals(listOf("demo", "audio"), extension.tags)
    }

    @Test
    fun `acepta los nombres en camelCase`() {
        val registry = RegistryParser.parse(
            """
            {
              "version": 1,
              "extensions": [
                {
                  "id": "demo",
                  "displayName": "Demo",
                  "version": "1.0.0",
                  "downloadUrl": "https://example.com/demo.zip",
                  "minAppVersion": "1.0",
                  "checksumSha256": "368c0cab332c03fc77abc45bc"
                }
              ]
            }
            """.trimIndent(),
        )

        val extension = registry.extensions.first()
        assertEquals("Demo", extension.displayName)
        assertEquals("https://example.com/demo.zip", extension.downloadUrl)
        assertEquals("1.0", extension.minLioraVersion)
    }

    @Test(expected = ExtensionException.MalformedRegistry::class)
    fun `rechaza un json malformado`() {
        RegistryParser.parse("{ esto no es json ")
    }

    @Test(expected = ExtensionException.MalformedRegistry::class)
    fun `rechaza una pagina web en vez de un registro`() {
        RegistryParser.parse("<!DOCTYPE html><html><body>hola</body></html>")
    }

    @Test(expected = ExtensionException.MalformedRegistry::class)
    fun `rechaza un registro sin lista de extensiones`() {
        RegistryParser.parse("""{"version": 1}""")
    }

    @Test
    fun `normaliza el hash y rechaza los que no son sha256`() {
        val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(hash, RegistryParser.normalizeSha256("SHA256:${hash.uppercase()}"))
        assertEquals("", RegistryParser.normalizeSha256("no es un hash"))
        assertEquals("", RegistryParser.normalizeSha256("abc123"))
    }
}

/** El manifiesto de una extensión. */
class ExtensionManifestTest {

    private fun manifest(types: String = """["metadata_provider"]""") = """
        {
          "schemaVersion": 1,
          "id": "demo",
          "displayName": "Demo",
          "version": "1.0.0",
          "description": "Una demo",
          "author": "Liora",
          "minLioraVersion": "1.0",
          "requiredFeatures": ["jsRuntime@1"],
          "type": $types,
          "permissions": { "network": ["api.example.com", "*.cdn.example.com"], "storage": true, "file": true },
          "entrypoint": "src/index.js"
        }
    """.trimIndent()

    @Test
    fun `lee un manifiesto valido`() {
        val parsed = com.liora.android.extensions.model.ExtensionManifest.parse(manifest())
        assertEquals("demo", parsed.id)
        assertEquals("1.0.0", parsed.version)
        assertEquals("src/index.js", parsed.entrypoint)
        assertTrue(parsed.has(ProviderType.METADATA))
        assertEquals(listOf("jsRuntime@1"), parsed.requiredFeatures)
    }

    @Test
    fun `los permisos de red respetan los comodines`() {
        val parsed = com.liora.android.extensions.model.ExtensionManifest.parse(manifest())
        assertTrue(parsed.permissions.allowsDomain("api.example.com"))
        assertTrue(parsed.permissions.allowsDomain("a.cdn.example.com"))
        assertTrue(!parsed.permissions.allowsDomain("otro.example.com"))
        assertTrue(!parsed.permissions.allowsDomain(""))
    }

    @Test(expected = ExtensionException.InvalidManifest::class)
    fun `rechaza un manifiesto sin identificador`() {
        com.liora.android.extensions.model.ExtensionManifest
            .parse(manifest().replace("\"id\": \"demo\"", "\"id\": \"\""))
    }

    @Test(expected = ExtensionException.InvalidManifest::class)
    fun `rechaza un manifiesto sin tipo de proveedor`() {
        com.liora.android.extensions.model.ExtensionManifest
            .parse(manifest(types = "[]"))
    }

    @Test(expected = ExtensionException.InvalidManifest::class)
    fun `rechaza un punto de entrada que se sale del paquete`() {
        com.liora.android.extensions.model.ExtensionManifest
            .parse(manifest().replace("\"src/index.js\"", "\"../fuera.js\""))
    }

    @Test(expected = ExtensionException.InvalidManifest::class)
    fun `rechaza un esquema futuro`() {
        com.liora.android.extensions.model.ExtensionManifest
            .parse(manifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
    }
}
