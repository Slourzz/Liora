package com.liora.android.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.liora.android.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portadas de Liora, en tres pasos:
 *
 * 1. La que viene dentro del archivo, en sus etiquetas (ID3, FLAC o MP4).
 * 2. La que el teléfono tiene indexada para ese álbum (MediaStore).
 * 3. Si no hay ninguna, la que publica la tienda de iTunes, en tamaño grande
 *    (600x600). La dirección y la imagen se guardan 30 días en el teléfono
 *    para no volver a pedirlas.
 *
 * De la portada también se saca su color dominante, que es con el que el
 * estilo Dinámico tiñe la app.
 */
object ArtworkRepository {

    /** Tamaño con el que se analiza el color de una portada. */
    private const val SEED_PX = 96

    /** Tamaño con el que se entrega una portada para mostrarla. */
    private const val DISPLAY_PX = 512

    /** Lado de la nube atmosférica: la porción de portada de la que se parte. */
    private const val WASH_GRID = 26

    /** Luz media (0-255) a la que se nivela la nube, venga de donde venga. */
    private const val WASH_LUMA = 148.0

    /** Cuánto se intensifica la saturación de la portada en la nube. */
    private const val WASH_SATURATION = 1.30f

    /** Cuánto se intensifica el contraste de la portada en la nube. */
    private const val WASH_CONTRAST = 1.10f

    /** Cuánto se funde cada píxel con el tinte de la portada. */
    private const val WASH_TINT = 0.45f

    /** Diferencia mínima entre canales para que un píxel cuente como color. */
    private const val WASH_TINT_SPREAD = 0.12f

    /** Luz a partir de la cual las luces se frenan. */
    private const val WASH_KNEE = 0.62f

    /** Cuánto se frenan las luces por encima de [WASH_KNEE]. */
    private const val WASH_ROLL = 0.375f

    /** Calidad que se le pide a iTunes para la portada. */
    private const val HD_QUALITY = "600x600bb"

    private const val TIMEOUT_MS = 5000
    private const val CACHE_MS = 30L * 24 * 60 * 60 * 1000

    private const val PREFS = "liora_artwork"
    private const val KEY_URL_PREFIX = "url_"

    /** Marcador para "esta portada ya se buscó y no tiene color útil". */
    private val NoSeed = Color.Transparent

    /** Colores ya sacados, por artista y álbum. */
    private val seeds = LruCache<String, Color>(24)

    /** Lavados ambientales ya pintados, por artista y álbum. */
    private val washes = LruCache<String, Bitmap>(24)

    /** Portadas listas para pintar, por canción y tamaño. */
    private val displays = LruCache<String, Bitmap>(32)

    /**
     * Portada de [song] lista para mostrar, o null si no se encontró. Quien la
     * pida es responsable de reciclarla cuando termine.
     */
    suspend fun artwork(context: Context, song: Song, sizePx: Int = DISPLAY_PX): Bitmap? =
        withContext(Dispatchers.IO) {
            embeddedArtwork(context, song, sizePx)
                ?: indexedArtwork(context, song.albumArtUri, sizePx)
                ?: remoteArtwork(context, song, sizePx)
        }

    /**
     * Portada para la interfaz, guardada en memoria por canción y tamaño (las
     * filas piden copias pequeñas y el reproductor, una más grande).
     */
    suspend fun displayArtwork(context: Context, song: Song, sizePx: Int): Bitmap? {
        val key = "${song.id}@$sizePx"
        displays.get(key)?.let { return it }

        val bitmap = artwork(context, song, sizePx) ?: return null
        displays.put(key, bitmap)
        return bitmap
    }

    /** Color dominante de la portada de [song], o null si no hay una útil. */
    suspend fun seedColor(context: Context, song: Song?): Color? = withContext(Dispatchers.IO) {
        if (song == null) return@withContext null

        val key = artKey(song)
        seeds.get(key)?.let { return@withContext it.takeIf { color -> color != NoSeed } }

        val bitmap = artwork(context, song, SEED_PX)
        if (bitmap == null) {
            seeds.put(key, NoSeed)
            return@withContext null
        }

        val color = try {
            dominantColor(bitmap)
        } finally {
            bitmap.recycle()
        }
        seeds.put(key, color ?: NoSeed)
        color
    }

    /**
     * Lavado del fondo del reproductor: la portada reducida a una rejilla
     * diminuta —que al estirarse queda suave, como una mancha de luz—, con la
     * saturación rebajada y la luz media nivelada, de modo que cualquier
     * portada, clara u oscura, deje un fondo sereno parecido al de Apple Music.
     * Se pinta una sola vez por portada y se guarda en memoria.
     */
    suspend fun atmosphereWash(context: Context, song: Song?): Bitmap? =
        withContext(Dispatchers.IO) {
            if (song == null) return@withContext null

            val key = artKey(song)
            washes.get(key)?.let { return@withContext it }

            val source = artwork(context, song, SEED_PX) ?: return@withContext null
            val wash = try {
                buildWash(source)
            } finally {
                source.recycle()
            }
            washes.put(key, wash)
            wash
        }

    /**
     * El mismo lavado, pero solo si ya está pintado (acceso sin suspender):
     * sirve para que el reproductor nazca con él desde su primer fotograma.
     */
    fun cachedAtmosphereWash(song: Song?): Bitmap? {
        if (song == null) return null
        return washes.get(artKey(song))
    }

    /**
     * Reduce la portada a [WASH_GRID], le enciende el color y la funde con su
     * propio tinte —la media de los píxeles con color, la paleta que saca
     * Apple— para que hasta los blancos hereden el tono de la portada. Las
     * luces se frenan y la luz media se nivela hacia [WASH_LUMA]: así el fondo
     * no depende de si la portada era luminosa, sombría o casi blanca.
     */
    private fun buildWash(source: Bitmap): Bitmap {
        val grid = WASH_GRID
        val scaled = Bitmap.createScaledBitmap(source, grid, grid, true)
        val pixels = IntArray(grid * grid)
        scaled.getPixels(pixels, 0, grid, 0, 0, grid, grid)
        if (scaled !== source) scaled.recycle()

        // 1. Color más vivo que el de la portada: saturación y contraste.
        val boosted = FloatArray(pixels.size * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            val gray = 0.299f * red + 0.587f * green + 0.114f * blue

            boosted[index * 3] = (gray + (red - gray) * WASH_SATURATION - 0.5f) * WASH_CONTRAST + 0.5f
            boosted[index * 3 + 1] = (gray + (green - gray) * WASH_SATURATION - 0.5f) * WASH_CONTRAST + 0.5f
            boosted[index * 3 + 2] = (gray + (blue - gray) * WASH_SATURATION - 0.5f) * WASH_CONTRAST + 0.5f
        }

        // 2. Tinte de la portada: la media de los píxeles que tienen color.
        var tintRed = 0.0
        var tintGreen = 0.0
        var tintBlue = 0.0
        var tintCount = 0
        for (index in pixels.indices) {
            val red = boosted[index * 3]
            val green = boosted[index * 3 + 1]
            val blue = boosted[index * 3 + 2]
            val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            val spread = maxOf(red, green, blue) - minOf(red, green, blue)
            if (spread > WASH_TINT_SPREAD && luma > 0.10f && luma < 0.92f) {
                tintRed += red
                tintGreen += green
                tintBlue += blue
                tintCount++
            }
        }
        val mixRed = if (tintCount > 0) (tintRed / tintCount).toFloat() else 0f
        val mixGreen = if (tintCount > 0) (tintGreen / tintCount).toFloat() else 0f
        val mixBlue = if (tintCount > 0) (tintBlue / tintCount).toFloat() else 0f

        // 3. Cada píxel se funde con el tinte, las luces se frenan —para que
        //    ningún blanco reviente— y la luz media se nivela.
        var sum = 0.0
        for (index in pixels.indices) {
            var red = boosted[index * 3]
            var green = boosted[index * 3 + 1]
            var blue = boosted[index * 3 + 2]
            if (tintCount > 0) {
                red += (mixRed - red) * WASH_TINT
                green += (mixGreen - green) * WASH_TINT
                blue += (mixBlue - blue) * WASH_TINT
            }

            var luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
            if (luma > WASH_KNEE) {
                val roll = (WASH_KNEE + (luma - WASH_KNEE) * WASH_ROLL) / luma
                red *= roll
                green *= roll
                blue *= roll
                luma *= roll
            }
            boosted[index * 3] = red
            boosted[index * 3 + 1] = green
            boosted[index * 3 + 2] = blue
            sum += luma
        }

        val mean = sum / pixels.size
        val gain = if (mean < 0.01) 1.0 else (WASH_LUMA / 255.0 / mean).coerceIn(0.6, 1.5)
        for (index in pixels.indices) {
            val r = (boosted[index * 3] * gain * 255f).toInt().coerceIn(0, 255)
            val g = (boosted[index * 3 + 1] * gain * 255f).toInt().coerceIn(0, 255)
            val b = (boosted[index * 3 + 2] * gain * 255f).toInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val wash = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888)
        wash.setPixels(pixels, 0, grid, 0, 0, grid, grid)
        return wash
    }

    // ---------------------------------------------------------------- portadas

    /** Portada incrustada en el propio archivo de audio. */
    private fun embeddedArtwork(context: Context, song: Song, sizePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, song.contentUri)
            retriever.embeddedPicture?.let { decodeBytes(it, sizePx) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Portada que el teléfono tiene indexada para el álbum de la canción. */
    private fun indexedArtwork(context: Context, uri: Uri?, sizePx: Int): Bitmap? {
        if (uri == null) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    /** Portada de la tienda de iTunes, con copia local de 30 días. */
    private fun remoteArtwork(context: Context, song: Song, sizePx: Int): Bitmap? {
        val key = artKey(song)
        val file = File(artworkFolder(context), fileName(key))

        if (file.exists() && isFresh(file)) {
            return decodeFile(file, sizePx)
        }

        val url = remoteUrl(context, key, song)
        if (!url.isNullOrEmpty()) {
            downloadBytes(url)?.let { bytes ->
                runCatching {
                    artworkFolder(context).mkdirs()
                    file.writeBytes(bytes)
                }
            }
        }
        return if (file.exists()) decodeFile(file, sizePx) else null
    }

    /** Dirección de la portada en iTunes, guardada para no repetir la búsqueda. */
    private fun remoteUrl(context: Context, key: String, song: Song): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_URL_PREFIX + key, null)?.let { saved ->
            val separator = saved.indexOf('|')
            val stamp = saved.substring(0, separator).toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - stamp < CACHE_MS) {
                return saved.substring(separator + 1).takeIf { it.isNotEmpty() }
            }
        }

        val outcome = searchITunes(song)
        // Si la consulta no llegó a responder (sin internet, por ejemplo) no se
        // guarda nada: así se vuelve a intentar más adelante.
        if (outcome.answered) {
            val saved = "${System.currentTimeMillis()}|${outcome.url.orEmpty()}"
            prefs.edit().putString(KEY_URL_PREFIX + key, saved).apply()
        }
        return outcome.url
    }

    /** Resultado de buscar en iTunes: si respondió y, si la hay, su portada. */
    private class SearchOutcome(val answered: Boolean, val url: String?)

    /**
     * Busca el álbum en iTunes y devuelve su portada en calidad grande. Se
     * queda con el resultado cuyo álbum coincide con el de la canción. Sin
     * artista no se busca: el nombre de la carpeta no identifica un disco.
     */
    private fun searchITunes(song: Song): SearchOutcome {
        if (song.artist.isBlank()) return SearchOutcome(answered = false, url = null)

        val terms = buildList {
            add(song.artist)
            val release = if (song.album.isNotBlank()) song.album else song.title
            release.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" ")

        val address = "https://itunes.apple.com/search?term=" +
            URLEncoder.encode(terms, "UTF-8") + "&entity=album&limit=5"
        val body = readText(address) ?: return SearchOutcome(answered = false, url = null)
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return SearchOutcome(answered = false, url = null)

        var firstFound: String? = null
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            val cover = result.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
            val hd = cover.replace("100x100bb", HD_QUALITY)
            if (firstFound == null) firstFound = hd

            val album = result.optString("collectionName")
            if (song.album.isNotBlank() && album.contains(song.album, ignoreCase = true)) {
                return SearchOutcome(answered = true, url = hd)
            }
        }
        return SearchOutcome(answered = true, url = firstFound)
    }

    // ------------------------------------------------------------------ color

    /**
     * Tono con más presencia de la portada: cada píxel vota en su franja de
     * tono con un peso que premia la saturación y castiga los brillos extremos.
     */
    private fun dominantColor(bitmap: Bitmap): Color? {
        val stepX = max(1, bitmap.width / 64)
        val stepY = max(1, bitmap.height / 64)

        val weight = FloatArray(12)
        val sumRed = FloatArray(12)
        val sumGreen = FloatArray(12)
        val sumBlue = FloatArray(12)
        val hsl = FloatArray(3)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                x += stepX

                if (android.graphics.Color.alpha(pixel) < 128) continue

                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                ColorUtils.RGBToHSL(red, green, blue, hsl)

                val lightness = hsl[2]
                if (lightness < 0.12f || lightness > 0.88f) continue

                val score = (0.1f + hsl[1] * 0.9f) *
                    (1f - abs(lightness - 0.5f) * 1.5f).coerceAtLeast(0f)
                if (score <= 0f) continue

                val bucket = (hsl[0] / 30f).toInt() % 12
                weight[bucket] += score
                sumRed[bucket] += red * score
                sumGreen[bucket] += green * score
                sumBlue[bucket] += blue * score
            }
            y += stepY
        }

        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        val total = weight[best]
        if (total <= 0f) return null

        val red = (sumRed[best] / total).roundToInt()
        val green = (sumGreen[best] / total).roundToInt()
        val blue = (sumBlue[best] / total).roundToInt()
        ColorUtils.RGBToHSL(red, green, blue, hsl)

        // Una portada gris no tiene color que dar: mejor la paleta de Liora.
        if (hsl[1] < 0.08f) return null

        // Un empujón para que el tema se note sin volverse fluorescente.
        hsl[1] = (hsl[1] * 1.2f).coerceIn(0.22f, 0.85f)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.72f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    // ----------------------------------------------------------------- varios

    private fun artKey(song: Song): String {
        val artist = song.artist.ifBlank { "?" }.trim().lowercase()
        val release = if (song.album.isNotBlank()) song.album else song.title
        return "$artist|${release.trim().lowercase()}"
    }

    private fun fileName(key: String): String = "${key.hashCode().toUInt().toString(16)}.img"

    private fun artworkFolder(context: Context): File = File(context.cacheDir, "artwork")

    private fun isFresh(file: File): Boolean =
        System.currentTimeMillis() - file.lastModified() < CACHE_MS

    private fun decodeBytes(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun decodeFile(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSize(width: Int, height: Int, sizePx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= sizePx && height / (sample * 2) >= sizePx) {
            sample *= 2
        }
        return sample
    }

    private fun openConnection(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Liora/1.0 (Android)")
        }

    private fun readText(address: String): String? {
        return try {
            val connection = openConnection(address)
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadBytes(address: String): ByteArray? {
        return try {
            val connection = openConnection(address)
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
