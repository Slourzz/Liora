package com.liora.android.data.cache

import android.content.Context
import android.text.format.Formatter
import java.io.File

/**
 * Caché de reproducción de Liora.
 *
 * Vive en la carpeta de caché privada de la app (`cacheDir/playback`) y guarda
 * copias temporales (carátulas y datos ya decodificados) para no repetir
 * trabajo al volver sobre la misma música. Ajustes decide cuánto puede crecer:
 * al superar el límite se borran primero los archivos más antiguos, y con 0 MB
 * la caché queda vacía y desactivada.
 */
class PlaybackCache(context: Context) {

    private val appContext = context.applicationContext

    /** Carpeta real de la caché; se crea la primera vez que hace falta. */
    fun directory(): File = File(appContext.cacheDir, DirectoryName).apply {
        if (!exists()) mkdirs()
    }

    /** Archivo dentro de la caché. No lo crea. */
    fun file(name: String): File = File(directory(), name)

    /** Suma de todos los archivos guardados, en bytes. */
    fun usageBytes(): Long =
        directory().listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** Tamaño actual listo para mostrar ("12 MB", "0 B"...). */
    fun usageLabel(): String = Formatter.formatShortFileSize(appContext, usageBytes())

    /**
     * Recorta la caché hasta que quepa en [maxMb] (0 la vacía por completo),
     * empezando por los archivos usados hace más tiempo.
     */
    fun trimTo(maxMb: Int) {
        val files = directory().listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return

        var total = files.sumOf { it.length() }
        val limit = maxMb.coerceAtLeast(0).toLong() * 1024L * 1024L
        if (total <= limit) return

        for (file in files) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private companion object {
        const val DirectoryName = "playback"
    }
}
