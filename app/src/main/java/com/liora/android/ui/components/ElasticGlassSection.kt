package com.liora.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.sqrt

/** Jalón máximo de la deformación visual. */
private val MaximumPull = 42.dp

/** Distancia horizontal real que debe superarse para armar un cambio de pista. */
private val TrackChangeThreshold = 36.dp

/** Cuánto debe dominar lo horizontal sobre lo vertical para contar como desliz. */
private const val HorizontalDominance = 1.35f

/**
 * Cristal elástico interactivo: al arrastrarlo se estira y, al soltar, puede
 * cambiar de canción.
 *
 * El truco del efecto "líquido" es que la deformación se aplica en
 * `layerBlock`: el cristal y su contenido (portada, títulos, iconos) se
 * estiran, mientras que la copia del fondo que se refracta se queda quieta.
 * Así los iconos pasan por debajo de la lente y se ven refractados.
 *
 * Port de la sección elástica del mini reproductor de Cloud:
 * límite circular de 42 dp, estiramiento del 7 % en el eje del gesto,
 * compresión cruzada del 1.4 %, escala de presión 1.03 y 1.042 al quedar
 * armado el cambio de pista.
 */
@Composable
fun ElasticGlassSection(
    liquidGlass: Boolean,
    backdrop: Backdrop,
    hazeState: HazeState,
    shape: Shape,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 4.dp,
    refractionHeight: Dp = 16.dp,
    refractionAmount: Dp = 32.dp,
    chromaticRefraction: Boolean = false,
    shadowRadius: Dp = 8.dp,
    hazeStyle: HazeBlurStyle = HazeMaterials.thin(),
    onClick: (() -> Unit)? = null,
    onStretchLeft: (() -> Unit)? = null,
    onStretchRight: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val clickInteractionSource = remember { MutableInteractionSource() }

    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    var accumulatedX by remember { mutableFloatStateOf(0f) }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    var pullXTarget by remember { mutableFloatStateOf(0f) }
    var pullYTarget by remember { mutableFloatStateOf(0f) }

    /** -1 = anterior, 0 = solo elasticidad, +1 = siguiente. Se confirma al soltar. */
    var trackChangeArmed by remember { mutableIntStateOf(0) }

    val pullX by animateFloatAsState(
        targetValue = pullXTarget,
        animationSpec = if (dragging) {
            spring(dampingRatio = 0.94f, stiffness = 920f, visibilityThreshold = 0.0005f)
        } else {
            spring(dampingRatio = 0.38f, stiffness = 235f, visibilityThreshold = 0.001f)
        },
        label = "jalon horizontal",
    )

    val pullY by animateFloatAsState(
        targetValue = pullYTarget,
        animationSpec = if (dragging) {
            spring(dampingRatio = 0.94f, stiffness = 920f, visibilityThreshold = 0.0005f)
        } else {
            spring(dampingRatio = 0.38f, stiffness = 235f, visibilityThreshold = 0.001f)
        },
        label = "jalon vertical",
    )

    val interactionScale by animateFloatAsState(
        targetValue = when {
            trackChangeArmed != 0 -> 1.042f
            pressed || dragging -> 1.030f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 300f, visibilityThreshold = 0.001f),
        label = "escala de presion",
    )

    // Se lee en fase de dibujo: no provoca recomposición por fotograma.
    val elasticLayerBlock: GraphicsLayerScope.() -> Unit = remember {
        {
            val resolvedX = pullX.coerceIn(-1f, 1f)
            val resolvedY = pullY.coerceIn(-1f, 1f)

            val horizontalAmount = abs(resolvedX)
            val verticalAmount = abs(resolvedY)

            val horizontalStretch = horizontalAmount * 0.070f
            val verticalStretch = verticalAmount * 0.070f

            val elasticScaleX = (
                1f + horizontalStretch - verticalAmount * 0.014f
                ).coerceAtLeast(0.94f)
            val elasticScaleY = (
                1f + verticalStretch - horizontalAmount * 0.014f
                ).coerceAtLeast(0.94f)

            // Mantiene fijo el lado contrario al jalón.
            translationX = when {
                resolvedX < 0f -> -size.width * horizontalStretch * 0.5f
                resolvedX > 0f -> size.width * horizontalStretch * 0.5f
                else -> 0f
            }
            translationY = when {
                resolvedY < 0f -> -size.height * verticalStretch * 0.5f
                resolvedY > 0f -> size.height * verticalStretch * 0.5f
                else -> 0f
            }

            scaleX = interactionScale * elasticScaleX
            scaleY = interactionScale * elasticScaleY
        }
    }

    // Se recuerda para no reconstruir la caché de efectos en cada fotograma.
    val glassModifier = remember(
        liquidGlass,
        backdrop,
        hazeState,
        shape,
        surfaceColor,
        blurRadius,
        refractionHeight,
        refractionAmount,
        chromaticRefraction,
        shadowRadius,
        hazeStyle,
        elasticLayerBlock,
    ) {
        if (liquidGlass) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    if (refractionHeight > 0.dp) {
                        lens(
                            refractionHeight = refractionHeight.toPx(),
                            refractionAmount = refractionAmount.toPx(),
                            chromaticAberration = chromaticRefraction,
                        )
                    }
                },
                highlight = { Highlight.Default },
                shadow = { Shadow(radius = shadowRadius) },
                layerBlock = elasticLayerBlock,
                onDrawSurface = { drawRect(surfaceColor) },
            )
        } else {
            Modifier
                .graphicsLayer(elasticLayerBlock)
                .clip(shape)
                .hazeBlur(input = HazeInput.Sources(hazeState), style = hazeStyle)
                .background(surfaceColor)
        }
    }

    Box(
        modifier = modifier.then(glassModifier),
    ) {
        var interactionModifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            }
            .pointerInput(onStretchLeft, onStretchRight) {
                val maximumPullPx = MaximumPull.toPx()
                val trackChangeThresholdPx = TrackChangeThreshold.toPx()

                var rawDragX = 0f
                var rawDragY = 0f

                fun resetGestureState() {
                    dragging = false
                    pressed = false
                    accumulatedX = 0f
                    accumulatedY = 0f
                    pullXTarget = 0f
                    pullYTarget = 0f
                    trackChangeArmed = 0
                    rawDragX = 0f
                    rawDragY = 0f
                }

                detectDragGestures(
                    onDragStart = {
                        resetGestureState()
                        dragging = true
                        pressed = true
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        rawDragX += dragAmount.x
                        rawDragY += dragAmount.y
                        accumulatedX += dragAmount.x
                        accumulatedY += dragAmount.y

                        val rawVisualDistance = sqrt(
                            accumulatedX * accumulatedX + accumulatedY * accumulatedY,
                        )

                        if (rawVisualDistance > maximumPullPx && rawVisualDistance > 0f) {
                            val limiter = maximumPullPx / rawVisualDistance
                            accumulatedX *= limiter
                            accumulatedY *= limiter
                        }

                        val normalizedX = (accumulatedX / maximumPullPx).coerceIn(-1f, 1f)
                        val normalizedY = (accumulatedY / maximumPullPx).coerceIn(-1f, 1f)

                        val normalizedDistance = sqrt(
                            normalizedX * normalizedX + normalizedY * normalizedY,
                        ).coerceIn(0f, 1f)

                        val softenedDistance = normalizedDistance * (2f - normalizedDistance)

                        if (normalizedDistance > 0f) {
                            pullXTarget = normalizedX / normalizedDistance * softenedDistance
                            pullYTarget = normalizedY / normalizedDistance * softenedDistance
                        } else {
                            pullXTarget = 0f
                            pullYTarget = 0f
                        }

                        val horizontalDominant = abs(rawDragX) >= abs(rawDragY) * HorizontalDominance

                        trackChangeArmed = when {
                            horizontalDominant &&
                                rawDragX >= trackChangeThresholdPx &&
                                onStretchRight != null -> 1

                            horizontalDominant &&
                                rawDragX <= -trackChangeThresholdPx &&
                                onStretchLeft != null -> -1

                            else -> 0
                        }
                    },
                    onDragEnd = {
                        val confirmedAction = trackChangeArmed
                        resetGestureState()
                        when (confirmedAction) {
                            1 -> onStretchRight?.invoke()
                            -1 -> onStretchLeft?.invoke()
                        }
                    },
                    onDragCancel = { resetGestureState() },
                )
            }
            .clip(shape)

        if (onClick != null) {
            interactionModifier = interactionModifier.clickable(
                interactionSource = clickInteractionSource,
                indication = null,
                onClick = onClick,
            )
        }

        Box(
            modifier = interactionModifier,
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
