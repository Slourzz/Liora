package com.liora.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** Nodo de un trazo (M, L o Q) con sus coordenadas en un lienzo de 24 unidades. */
private class MorphPathNode(val type: Char, val values: FloatArray)

/**
 * Icono de reproducir/pausar que se transforma entre ambas formas.
 *
 * Port del morph redondeado de ozgurg
 * (https://gist.github.com/ozgurg/0ff8e1e315b1cb5c385bda1c25da80e6): el
 * AnimatedVectorDrawable original interpola estos mismos nodos durante 300 ms
 * con fast-out-slow-in; aquí se dibuja directamente con Canvas para no
 * depender de un recurso animado.
 */
@Composable
fun PlayPauseMorphIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val playNodes = remember {
        listOf(
            MorphPathNode('M', floatArrayOf(8.016f, 6.797f)),
            MorphPathNode('Q', floatArrayOf(8.016f, 6.797f, 8.016f, 6.797f)),
            MorphPathNode('Q', floatArrayOf(8.016f, 6.234f, 8.532f, 5.953f)),
            MorphPathNode('Q', floatArrayOf(9.048f, 5.672f, 9.563f, 6.000f)),
            MorphPathNode('Q', floatArrayOf(13.617f, 8.578f, 17.672f, 11.156f)),
            MorphPathNode('Q', floatArrayOf(18.141f, 11.437f, 18.141f, 12.000f)),
            MorphPathNode('Q', floatArrayOf(18.141f, 12.563f, 17.672f, 12.844f)),
            MorphPathNode('Q', floatArrayOf(13.617f, 15.422f, 9.563f, 18.000f)),
            MorphPathNode('Q', floatArrayOf(9.047f, 18.328f, 8.532f, 18.047f)),
            MorphPathNode('Q', floatArrayOf(8.017f, 17.766f, 8.016f, 17.203f)),
            MorphPathNode('Q', floatArrayOf(8.016f, 15.469f, 8.016f, 13.734f)),
            MorphPathNode('Q', floatArrayOf(8.016f, 12.000f, 8.016f, 10.266f)),
            MorphPathNode('L', floatArrayOf(8.016f, 6.797f)),
            MorphPathNode('M', floatArrayOf(16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('L', floatArrayOf(16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(16.009f, 11.999f, 16.009f, 11.999f)),
            MorphPathNode('L', floatArrayOf(16.009f, 11.999f)),
            MorphPathNode('L', floatArrayOf(16.009f, 11.999f)),
        )
    }

    val pauseNodes = remember {
        listOf(
            MorphPathNode('M', floatArrayOf(6.000f, 6.984f)),
            MorphPathNode('Q', floatArrayOf(6.000f, 6.187f, 6.586f, 5.601f)),
            MorphPathNode('Q', floatArrayOf(7.172f, 5.015f, 8.016f, 5.015f)),
            MorphPathNode('Q', floatArrayOf(8.813f, 5.015f, 9.399f, 5.601f)),
            MorphPathNode('Q', floatArrayOf(9.985f, 6.187f, 9.985f, 6.984f)),
            MorphPathNode('Q', floatArrayOf(9.985f, 9.492f, 9.985f, 11.999f)),
            MorphPathNode('Q', floatArrayOf(9.985f, 14.507f, 9.985f, 17.015f)),
            MorphPathNode('Q', floatArrayOf(9.985f, 17.812f, 9.399f, 18.398f)),
            MorphPathNode('Q', floatArrayOf(8.813f, 18.984f, 8.016f, 18.984f)),
            MorphPathNode('Q', floatArrayOf(8.016f, 18.984f, 8.016f, 18.984f)),
            MorphPathNode('Q', floatArrayOf(7.172f, 18.984f, 6.586f, 18.398f)),
            MorphPathNode('Q', floatArrayOf(6.000f, 17.812f, 6.000f, 17.015f)),
            MorphPathNode('L', floatArrayOf(6.000f, 6.984f)),
            MorphPathNode('M', floatArrayOf(14.016f, 6.984f)),
            MorphPathNode('Q', floatArrayOf(14.016f, 6.187f, 14.602f, 5.601f)),
            MorphPathNode('Q', floatArrayOf(15.188f, 5.015f, 15.985f, 5.015f)),
            MorphPathNode('Q', floatArrayOf(16.829f, 5.015f, 17.415f, 5.601f)),
            MorphPathNode('Q', floatArrayOf(18.001f, 6.187f, 18.001f, 6.984f)),
            MorphPathNode('L', floatArrayOf(18.001f, 17.015f)),
            MorphPathNode('Q', floatArrayOf(18.001f, 17.812f, 17.415f, 18.398f)),
            MorphPathNode('Q', floatArrayOf(16.829f, 18.984f, 15.985f, 18.984f)),
            MorphPathNode('Q', floatArrayOf(15.188f, 18.984f, 14.602f, 18.398f)),
            MorphPathNode('Q', floatArrayOf(14.016f, 17.812f, 14.016f, 17.015f)),
            MorphPathNode('L', floatArrayOf(14.016f, 6.984f)),
            MorphPathNode('L', floatArrayOf(14.016f, 6.984f)),
        )
    }

    val morphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        ),
        label = "morph play pausa",
    )

    Canvas(modifier = modifier) {
        val viewport = 24f
        val scale = minOf(size.width, size.height) / viewport
        val offsetX = (size.width - viewport * scale) / 2f
        val offsetY = (size.height - viewport * scale) / 2f

        fun lerpValue(from: Float, to: Float): Float =
            from + (to - from) * morphProgress

        fun x(value: Float): Float = offsetX + value * scale
        fun y(value: Float): Float = offsetY + value * scale

        val path = Path()

        playNodes.indices.forEach { index ->
            val from = playNodes[index]
            val to = pauseNodes[index]

            when (from.type) {
                'M' -> path.moveTo(
                    x(lerpValue(from.values[0], to.values[0])),
                    y(lerpValue(from.values[1], to.values[1])),
                )

                'L' -> path.lineTo(
                    x(lerpValue(from.values[0], to.values[0])),
                    y(lerpValue(from.values[1], to.values[1])),
                )

                'Q' -> path.quadraticBezierTo(
                    x(lerpValue(from.values[0], to.values[0])),
                    y(lerpValue(from.values[1], to.values[1])),
                    x(lerpValue(from.values[2], to.values[2])),
                    y(lerpValue(from.values[3], to.values[3])),
                )
            }
        }

        drawPath(path = path, color = color)
    }
}
