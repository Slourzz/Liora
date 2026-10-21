package com.liora.android.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * El campo de color de una portada: la textura que muestrea el fondo vivo.
 */
internal class AtmosphereField(
    /** El campo como textura, para el shader. */
    internal val shader: BitmapShader,
    /** El mismo campo como imagen, para el respaldo sin shader. */
    internal val image: ImageBitmap,
    /** Cuánto hay que bajarle la luz para que los controles se lean encima. */
    internal val brightness: Float,
)

/**
 * Los campos del fondo vivo, con la receta de kawarp
 * (github.com/better-lyrics/kawarp, MIT): la portada a 128x128, sus zonas
 * oscuras empujadas hacia el tono de la casa y ocho pasadas de Kawase, que la
 * convierten en manchas de color —la estructura del disco, no un degradado—.
 *
 * Se prepara una vez por disco, fuera del hilo principal, y se guarda ya en un
 * BitmapShader, que es lo que el shader necesita para muestrearlo.
 */
internal object AtmosphereFields {

    /** El lado al que trabaja kawarp (su BLUR_SIZE). */
    private const val SIDE = 128

    /** Pasadas de Kawase (kawarp: 8). */
    private const val PASSES = 8

    /** Hacia dónde tiran los negros de la portada (nuestro tono de casa). */
    private const val TINT_RED = 0.149f
    private const val TINT_GREEN = 0.145f
    private const val TINT_BLUE = 0.169f
    private const val TINT_INTENSITY = 0.15f

    /** La curva de oscurecido de kawarp: una portada clara se apaga sola. */
    private const val DARKEN = 0.60f
    private const val CEILING_CLEAR = 0.90f
    private const val CEILING_DARK = 0.15f
    private const val BRIGHTNESS_MIN = 0.15f

    private val fields = LruCache<String, AtmosphereField>(8)

    /** El campo ya preparado, si lo está. */
    fun cached(song: Song?): AtmosphereField? =
        song?.let { fields.get(ArtworkRepository.artKey(it)) }

    /** El campo de [song], preparándolo si aún no lo estaba. */
    suspend fun load(context: Context, song: Song?): AtmosphereField? {
        if (song == null) return null

        val key = ArtworkRepository.artKey(song)
        fields.get(key)?.let { return it }

        val source = ArtworkRepository.artwork(context, song, SIDE) ?: return null
        val field = withContext(Dispatchers.Default) { prepare(source) }
        fields.put(key, field)
        return field
    }

    // ---------------------------------------------------------------- receta

    /** La receta de kawarp: tinte de los negros y la cascada de Kawase. */
    private fun prepare(source: Bitmap): AtmosphereField {
        val small = Bitmap.createScaledBitmap(source, SIDE, SIDE, true)
        if (small !== source) source.recycle()

        val count = SIDE * SIDE
        val pixels = IntArray(count)
        small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)

        var read = FloatArray(count * 3)
        var write = FloatArray(count * 3)
        var luma = 0f
        for (i in 0 until count) {
            val pixel = pixels[i]
            val red = (pixel shr 16 and 0xFF) / 255f
            val green = (pixel shr 8 and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            luma += 0.2126f * red + 0.7152f * green + 0.0722f * blue

            // Los negros tiran hacia el tono de la casa, como en kawarp.
            val ink = 0.299f * red + 0.587f * green + 0.114f * blue
            val dark = (1f - soften(ink)) * TINT_INTENSITY
            val at = i * 3
            read[at] = red + (TINT_RED - red) * dark
            read[at + 1] = green + (TINT_GREEN - green) * dark
            read[at + 2] = blue + (TINT_BLUE - blue) * dark
        }
        luma /= count

        repeat(PASSES) { pass ->
            kawase(read, write, pass)
            val swap = read
            read = write
            write = swap
        }

        for (i in 0 until count) {
            val at = i * 3
            pixels[i] = (0xFF shl 24) or
                (channel(read[at]) shl 16) or
                (channel(read[at + 1]) shl 8) or
                channel(read[at + 2])
        }
        small.recycle()

        val blurred = Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        return AtmosphereField(shader, blurred.asImageBitmap(), brightnessFor(luma))
    }

    /**
     * Una pasada de Kawase: cada píxel se lleva la media de los dieciséis que
     * caen entre [pass] y [pass] + 1 píxeles a cada lado, que es justo lo que
     * la GPU consigue con sus cuatro muestras bilineales.
     */
    private fun kawase(read: FloatArray, write: FloatArray, pass: Int) {
        val offsets = intArrayOf(-(pass + 1), -pass, pass, pass + 1)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var red = 0f
                var green = 0f
                var blue = 0f
                for (dy in offsets) {
                    val row = clamp(y + dy) * SIDE
                    for (dx in offsets) {
                        val at = (row + clamp(x + dx)) * 3
                        red += read[at]
                        green += read[at + 1]
                        blue += read[at + 2]
                    }
                }
                val at = (y * SIDE + x) * 3
                write[at] = red / 16f
                write[at + 1] = green / 16f
                write[at + 2] = blue / 16f
            }
        }
    }

    /** Suavizado entre 0 y 0.5, como el smoothstep de kawarp. */
    private fun soften(value: Float): Float {
        val t = (value * 2f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Cuánta luz se le deja a una portada de luminancia media [luma]. */
    private fun brightnessFor(luma: Float): Float {
        if (luma <= 0f) return 1f
        val ceiling = CEILING_CLEAR - DARKEN * (CEILING_CLEAR - CEILING_DARK)
        if (luma <= ceiling) return 1f
        return max(ceiling / luma, BRIGHTNESS_MIN)
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, SIDE - 1)

    private fun channel(value: Float): Int =
        (value * 255f + 0.5f).toInt().coerceIn(0, 255)
}
