package com.liora.android.extensions.audio

import android.content.Context
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.provider.ProviderEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Las descargas de audio que hacen los proveedores.
 *
 * El fichero se descarga a un temporal y solo se publica cuando está
 * terminado: un archivo a medias nunca aparece como descargado. Si el
 * proveedor dice que ha ido bien pero el fichero no está o está vacío, la
 * descarga se considera fallida.
 */
class DownloadManager(rootDir: File) {

    private val root = rootDir

    private val downloadsDir: File
        get() = File(root, "extensiones/descargas").apply { mkdirs() }

    /**
     * Descarga una pista desde un proveedor.
     *
     * @param targetName el nombre final del fichero (sin ruta).
     * @param onProgress se llama con (escritos, total) mientras baja.
     */
    suspend fun download(
        provider: ProviderEntry,
        trackId: String,
        quality: String?,
        targetName: String,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val safeName = sanitize(targetName)
        if (safeName.isEmpty()) {
            throw ExtensionException.CorruptPackage("El nombre del fichero no es válido")
        }
        val target = File(downloadsDir, safeName)
        val partial = File(downloadsDir, ".$safeName.partial")

        partial.delete()
        try {
            val result = provider.runtime.download(trackId, quality, partial.absolutePath, onProgress)
            if (!result.success) {
                partial.delete()
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    result.error ?: "la descarga falló",
                )
            }

            val written = partial.length()
            if (written <= 0L) {
                partial.delete()
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "el proveedor dijo que terminó pero no dejó ningún fichero",
                )
            }
            if (target.exists() && !target.delete()) {
                partial.delete()
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "no se pudo sustituir el fichero anterior",
                )
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "no se pudo publicar el fichero descargado",
                )
            }
            target
        } catch (error: ExtensionException) {
            partial.delete()
            throw error
        } catch (error: Exception) {
            partial.delete()
            throw ExtensionException.ProviderFailure(
                provider.extensionId,
                error.message ?: "la descarga falló",
                error,
            )
        }
    }

    /** Cancela la descarga en curso de un proveedor. */
    fun cancel(provider: ProviderEntry) {
        provider.runtime.cancelDownloads()
    }

    /** Los ficheros ya descargados. */
    fun downloaded(): List<File> = downloadsDir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") }
        ?.sortedBy { it.lastModified() }
        ?: emptyList()

    /** Quita un fichero descargado. */
    fun delete(file: File): Boolean = file.delete()

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (cleaned.isEmpty() || cleaned.startsWith(".")) return ""
        return cleaned.take(120)
    }
}
