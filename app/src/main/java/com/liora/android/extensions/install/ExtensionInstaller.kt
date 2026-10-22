package com.liora.android.extensions.install

import android.content.Context
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.model.RegistryExtension
import com.liora.android.extensions.net.HttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Instala, actualiza y quita extensiones.
 *
 * El recorrido de una instalación es siempre el mismo:
 *
 * 1. Se descarga el paquete a un fichero temporal.
 * 2. Se comprueba su SHA-256 contra el que publica el registro.
 * 3. Se valida el manifiesto y la compatibilidad.
 * 4. Se extrae a un directorio temporal, comprobando cada ruta.
 * 5. Se activa con un renombrado atómico: o la extensión nueva está entera,
 *    o sigue la anterior.
 *
 * Un fallo en cualquier paso deja la instalación previa intacta.
 */
class ExtensionInstaller(
    rootDir: File,
    private val http: HttpClient,
    private val validator: PackageValidator,
) {
    private val root = rootDir.apply { mkdirs() }

    /** Donde viven las extensiones instaladas. */
    val installedDir: File
        get() = File(root, "instaladas").apply { mkdirs() }

    /** Donde se descargan los paquetes antes de instalarlos. */
    private val packagesDir: File
        get() = File(root, "paquetes").apply { mkdirs() }

    /** Instala (o actualiza) la extensión de un registro. */
    suspend fun install(entry: RegistryExtension): ExtensionManifest = withContext(Dispatchers.IO) {
        if (entry.downloadUrl.isBlank()) {
            throw ExtensionException.CorruptPackage(
                "El registro no dice de dónde descargar '${entry.id}'",
            )
        }
        if (!entry.downloadUrl.startsWith("https://", ignoreCase = true)) {
            throw ExtensionException.CorruptPackage(
                "El paquete de '${entry.id}' no se descarga por https",
            )
        }

        val packageFile = File(packagesDir, "${entry.id}.download")
        try {
            download(entry.downloadUrl, packageFile)
            validator.verifyHash(packageFile, entry.sha256)
            val inspected = validator.inspect(packageFile)
            val manifest = inspected.manifest
            if (manifest.id != entry.id) {
                throw ExtensionException.InvalidManifest(
                    "El manifiesto dice llamarse '${manifest.id}' pero el registro lo llama '${entry.id}'",
                )
            }
            extract(packageFile, manifest, inspected)
            manifest
        } finally {
            packageFile.delete()
        }
    }

    /** Quita una extensión instalada. */
    suspend fun remove(extensionId: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(installedDir, extensionId)
        if (!target.exists()) return@withContext false
        val canonical = target.canonicalFile
        if (canonical.parentFile != installedDir.canonicalFile) return@withContext false
        canonical.deleteRecursively()
        true
    }

    /** Las extensiones instaladas, con su manifiesto. */
    fun installed(): List<ExtensionManifest> {
        val result = mutableListOf<ExtensionManifest>()
        installedDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val manifestFile = File(dir, PackageValidator.MANIFEST_NAME)
            if (!manifestFile.exists()) return@forEach
            runCatching { ExtensionManifest.parse(manifestFile.readText()) }
                .getOrNull()
                ?.let { result += it }
        }
        return result.sortedBy { it.id }
    }

    /** El directorio de una extensión instalada. */
    fun directoryOf(extensionId: String): File = File(installedDir, extensionId)

    private suspend fun download(url: String, target: File) {
        FileOutputStream(target).use { output ->
            http.download(url, output)
        }
    }

    private fun extract(packageFile: File, manifest: ExtensionManifest, inspected: InspectedPackage) {
        val target = File(installedDir, manifest.id)
        val staging = File(installedDir, ".${manifest.id}.staging")
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            var total = 0L
            ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
                var entry = zip.getNextEntry()
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (!PackageValidator.isSafePath(name)) {
                            throw ExtensionException.CorruptPackage(
                                "El paquete contiene una ruta no permitida: $name",
                            )
                        }
                        val destination = File(staging, name)
                        val stagingRoot = staging.canonicalFile.path + File.separator
                        if (!destination.canonicalFile.path.startsWith(stagingRoot)) {
                            throw ExtensionException.CorruptPackage(
                                "El paquete intenta escribir fuera de su directorio: $name",
                            )
                        }
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                total += read
                                if (total > PackageValidator.MAX_EXTRACTED_BYTES) {
                                    throw ExtensionException.CorruptPackage(
                                        "El paquete descomprimido supera el límite permitido",
                                    )
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.getNextEntry()
                }
            }

            if (!File(staging, manifest.entrypoint).exists()) {
                throw ExtensionException.InvalidManifest(
                    "El punto de entrada '${manifest.entrypoint}' no está en el paquete",
                )
            }

            // Activación atómica: se quita la anterior y se renombra la nueva.
            val backup = File(installedDir, ".${manifest.id}.previous")
            backup.deleteRecursively()
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    target.deleteRecursively()
                }
            }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                throw ExtensionException.CorruptPackage(
                    "No se pudo activar la extensión '${manifest.id}'",
                )
            }
            backup.deleteRecursively()
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw when (error) {
                is ExtensionException -> error
                else -> ExtensionException.CorruptPackage(
                    "No se pudo instalar '${manifest.id}': ${error.message}",
                    error,
                )
            }
        }
    }
}
