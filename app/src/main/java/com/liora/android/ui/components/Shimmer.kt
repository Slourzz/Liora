package com.liora.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * La banda de luz: el brillo que recorre el contenido mientras espera.
 *
 * El contenido se dibuja dos veces. La primera, apagado, para que se vea la
 * forma; la segunda con la banda por encima, recortada con la propia forma
 * (la banda solo se ve donde el contenido es opaco). Así el brillo respeta los
 * bordes del icono en vez de pintar un rectángulo encima.
 *
 * La banda va en diagonal, de la esquina superior izquierda a la inferior
 * derecha: para eso se gira el lienzo 45° y la banda viaja por él. El viaje
 * llega más lejos de lo que el icono ocupa, de modo que la banda sale del
 * todo antes de volver a empezar y el bucle no se nota.
 *
 * @param travel dónde está la banda: -1.3 la saca del todo por la esquina
 * superior izquierda y 1.3 por la inferior derecha. Se lee al dibujar, así
 * que el brillo no obliga a recomponer nada.
 * @param baseAlpha cuán apagado queda el contenido bajo el brillo.
 */
fun Modifier.shimmerBand(travel: () -> Float, baseAlpha: Float = 0.34f): Modifier =
    drawWithContent {
        val w = size.width
        val bandHalf = w * 0.35f
        val center = w * travel()

        // La base: el contenido se ve, pero sin brillo.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = baseAlpha })
            drawContent()
            canvas.restore()
        }

        // Y la banda por encima, recortada con la forma del contenido.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), Paint())
            drawContent()
            rotate(degrees = 45f, pivot = Offset(w / 2f, size.height / 2f)) {
                val band = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color.White.copy(alpha = 0.95f),
                        Color.Transparent,
                        Color.Transparent,
                    ),
                    startX = center - bandHalf,
                    endX = center + bandHalf,
                )
                drawRect(
                    brush = band,
                    topLeft = Offset(-w * 2f, -w * 2f),
                    size = Size(w * 4f, w * 4f),
                    blendMode = BlendMode.SrcIn,
                )
            }
            canvas.restore()
        }
    }

/**
 * El brillo de trabajo: la banda de luz que recorre el contenido mientras
 * espera. Es [shimmerBand] con la banda ya en marcha.
 *
 * El viaje va de -1.3 a 1.3 para que la banda entre y salga del todo antes de
 * volver a empezar: si el reinicio la pillara a medio camino, se notaría un
 * salto al final del bucle.
 */
@Composable
fun Modifier.shimmerWhileWorking(durationMs: Int = 1400): Modifier {
    val transition = rememberInfiniteTransition(label = "brillo de trabajo")
    val travel = transition.animateFloat(
        initialValue = -1.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "banda de luz",
    )
    return shimmerBand(travel = { travel.value })
}
