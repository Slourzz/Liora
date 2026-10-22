package com.liora.android.extensions.install

import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Lo que se sabe de un paquete antes de instalarlo.
 *
 * @property manifest el manifiesto ya leído y validado.
 * @property entries los ficheros del paquete, con su tamaño.
 */
data class InspectedPackage(
    val manifest: ExtensionManifest,
    val entries: Map<String, Long>,
)

/**
 * Comprueba que un paquete sea instalable.
 *
 * Hace tres cosas, en este orden:
 *
 * 1. **Integridad**: el SHA-256 del paquete tiene que coincidir con el que
 *    publica el registro. Esto demuestra que el fichero es el que el
 *    registro dice; no dice que el contenido sea de fiar, de eso se ocupa el
 *    modelo de permisos y la decisión del usuario.
 * 2. **Forma**: tiene que ser un ZIP legible, con rutas seguras y sin
 *    pasarse de los límites de tamaño.
 * 3. **Contrato**: el manifiesto tiene que ser válido y la extensión tiene
 *    que ser compatible con esta versión de Liora.
 */
class PackageValidator(
    private val appVersion: String,
    private val maxPackageBytes: Long = MAX_PACKAGE_BYTES,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
) {

    /** Comprueba el hash del paquete. */
    fun verifyHash(packageFile: File, expectedSha256: String) {
        val expected = expectedSha256.trim().lowercase().removePrefix("sha256:")
        if (expected.isEmpty()) {
            throw ExtensionException.IntegrityFailure(
                "El registro no publica el hash de este paquete, así que no se puede comprobar su integridad",
            )
        }
        if (expected.length != 64 || !expected.all { it in "0123456789abcdef" }) {
            throw ExtensionException.IntegrityFailure(
                "El hash que publica el registro no tiene el formato de un SHA-256",
            )
        }
        val actual = sha256(packageFile)
        if (actual != expected) {
            throw ExtensionException.IntegrityFailure(
                "El paquete no coincide con el hash publicado (esperado $expected, obtenido $actual)",
            )
        }
    }

    /** Lee el manifiesto y comprueba la forma y el contrato del paquete. */
    fun inspect(packageFile: File): InspectedPackage {
        if (!packageFile.exists() || packageFile.length() == 0L) {
            throw ExtensionException.CorruptPackage("El paquete está vacío")
        }
        if (packageFile.length() > maxPackageBytes) {
            throw ExtensionException.CorruptPackage(
                "El paquete supera el límite de ${maxPackageBytes / (1024 * 1024)} MiB",
            )
        }
        // Un ZIP empieza por "PK": si no, el fichero no es un paquete.
        val magic = ByteArray(2)
        packageFile.inputStream().use { input ->
            if (input.read(magic) != 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                throw ExtensionException.CorruptPackage("El paquete no es un fichero ZIP")
            }
        }

        val entries = mutableMapOf<String, Long>()
        var total = 0L
        var manifestText: String? = null

        try {
            ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.getNextEntry()
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (!isSafePath(name)) {
                            throw ExtensionException.CorruptPackage(
                                "El paquete contiene una ruta no permitida: $name",
                            )
                        }
                        if (name == MANIFEST_NAME) {
                            if (manifestText != null) {
                                throw ExtensionException.CorruptPackage(
                                    "El paquete tiene más de un manifest.json",
                                )
                            }
                            manifestText = readText(zip, name)
                        } else {
                            entries[name] = entry.size.coerceAtLeast(0L)
                        }
                    }
                    total += entry.size.coerceAtLeast(0L)
                    if (total > maxExtractedBytes) {
                        throw ExtensionException.CorruptPackage(
                            "El paquete descomprimido supera el límite permitido",
                        )
                    }
                    zip.closeEntry()
                    entry = zip.getNextEntry()
                }
            }
        } catch (error: ExtensionException) {
            throw error
        } catch (error: Exception) {
            throw ExtensionException.CorruptPackage(
                "El paquete no se puede leer como un ZIP: ${error.message}",
                error,
            )
        }

        if (manifestText == null) {
            throw ExtensionException.InvalidManifest("El paquete no trae manifest.json")
        }

        val manifest = ExtensionManifest.parse(manifestText)
        if (!entries.containsKey(manifest.entrypoint)) {
            throw ExtensionException.InvalidManifest(
                "El paquete no incluye el punto de entrada '${manifest.entrypoint}'",
            )
        }
        checkCompatibility(manifest)
        return InspectedPackage(manifest, entries)
    }

    /** Comprueba que la extensión sirva para esta versión de Liora. */
    fun checkCompatibility(manifest: ExtensionManifest) {
        val minimum = manifest.minLioraVersion.trim()
        if (minimum.isNotEmpty() && compareVersions(appVersion, minimum) < 0) {
            throw ExtensionException.IncompatibleExtension(
                "La extensión necesita Liora $minimum o posterior (esta versión es $appVersion)",
            )
        }
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"

        /** El límite de un paquete comprimido: 32 MiB. */
        const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

        /** El límite de lo que se descomprime: 64 MiB. */
        const val MAX_EXTRACTED_BYTES = 64L * 1024 * 1024

        /**
         * ¿La ruta del paquete es segura?
         *
         * Ni absolutas, ni con `..`, ni con barras invertidas: es la defensa
         * contra el zip-slip (escribir fuera del directorio de la extensión).
         */
        fun isSafePath(name: String): Boolean {
            if (name.isEmpty()) return false
            if (name.startsWith("/") || name.startsWith("\\")) return false
            if (name.contains('\\') || name.contains('\u0000')) return false
            if (name.contains(':')) return false
            val parts = name.split('/')
            var depth = 0
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> {
                        depth--
                        if (depth < 0) return false
                    }
                    else -> depth++
                }
            }
            return true
        }

        /** El SHA-256 de un fichero, en minúsculas. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Compara dos versiones `x.y.z`. */
        fun compareVersions(first: String, second: String): Int {
            val left = first.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val right = second.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val a = left.getOrNull(index) ?: 0
                val b = right.getOrNull(index) ?: 0
                if (a != b) return a.compareTo(b)
            }
            return 0
        }

        private fun readText(input: InputStream, name: String): String {
            val text = input.bufferedReader(Charsets.UTF_8).readText()
            if (text.length > MAX_MANIFEST_CHARS) {
                throw ExtensionException.InvalidManifest("El manifest.json es demasiado grande")
            }
            return text
        }

        private const val MAX_MANIFEST_CHARS = 256 * 1024
    }
}
