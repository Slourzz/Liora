package com.liora.android.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL

/**
 * Las portadas que traen los proveedores.
 *
 * Los proveedores (YouTube Music, por ejemplo) dan la dirección de la portada
 * en su respuesta. La app la baja aquí y la guarda en memoria, para que al
 * abrir la lista otra vez no vuelva a pedirla.
 *
 * La descarga es directa: son imágenes públicas de la web del proveedor, y
 * la app no guarda ninguna credencial para pedirlas.
 */
object ProviderArtworkLoader {

    /** Las portadas ya bajadas, por dirección. */
    private val cache = LruCache<String, Bitmap>(48)

    /** La portada de esa dirección, o null si no se pudo bajar. */
    fun load(context: Context, url: String, maxSizePx: Int = 256): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Liora/1.0 (Android)")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
                cache.put(url, bitmap)
                bitmap
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Reduce la imagen para no cargar más pixeles de los que se pintan. */
    private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxSize && height / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return sample
    }
}
