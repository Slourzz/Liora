package com.styropyr0.prismal.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.styropyr0.prismal.shapes.PrismalCapsule
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalWrappedSource
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.effects.prismalBlur
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.specular.PrismalSpecular
import com.styropyr0.prismal.interactive.PrismalSpringMotion
import com.styropyr0.prismal.depth.PrismalDepthInset
import com.styropyr0.prismal.depth.PrismalDepthShadow
import kotlin.math.abs

/**
 * Glass-styled slider with a refracting thumb and damped drag physics.
 *
 * @param value Current value provider (read on each frame during drag).
 * @param onValueChange Called when the value changes.
 * @param valueRange Allowed value range.
 * @param visibilityThreshold Minimum change delta before [onValueChange] fires.
 * @param backdrop Source sampled through the track and thumb glass.
 * @param stops Optional detent values: while dragging, the thumb sticks to the
 * nearest stop inside its magnet radius (with a haptic tick on entry); on
 * release and tap it always settles on the closest stop.
 */
@Composable
fun PrismalGlassSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
    adaptiveLuminance: Boolean = false,
    luminance: () -> Float = { 0.5f },
    stops: List<Float>? = null,
) {
    val density = LocalDensity.current
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberPrismalGlassLayer()

    trackBackdrop.readSamplingState()

    /*
     * Box con ancho capturado por onSizeChanged en lugar de
     * BoxWithConstraints: este último (SubcomposeLayout) no soporta
     * medidas intrínsecas y rompe dentro de ListItem, que consulta
     * la altura intrínseca de sus contenidos.
     */
    val trackWidthState = remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { trackWidthState.intValue = it.width },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = trackWidthState.intValue
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val detentClick = rememberDetentClick()
        var didDrag by remember { mutableStateOf(false) }
        var isDragging by remember { mutableStateOf(false) }
        var lastReportedValue by remember { mutableFloatStateOf(value()) }

        /*
         * Los callbacks del motion viven en un remember (se crean UNA vez):
         * si capturaran `onValueChange` directamente, quedarían congelados
         * con la lambda de la primera composición —y el guard interno del
         * dueño del valor, que compara contra su nivel capturado obsoleto,
         * descartaría escritos válidos—. Ese era el desync clásico: el
         * pulgar se movía con animateToValue pero el nivel nunca cambiaba.
         * rememberUpdatedState entrega SIEMPRE la lambda más reciente.
         */
        val onValueChangeLatest by rememberUpdatedState(onValueChange)

        /*
         * Paradas magnéticas: el valor crudo del arrastre se acumula aparte
         * (rawDragValue) para que el imán no atrape al pulgar —el objetivo se
         * pega a la parada mientras el crudo sigue libre y puede salirse del
         * radio—. Al entrar en el radio de una parada vibra un tick.
         */
        val magnetRadius =
            stops
                ?.takeIf { it.size >= 2 }
                ?.let { list ->
                    (valueRange.endInclusive - valueRange.start) /
                            ((list.size - 1) * 2) *
                            0.35f
                }

        var rawDragValue by remember { mutableFloatStateOf(0f) }
        var magnetStop by remember { mutableFloatStateOf(Float.NaN) }

        val dampedDragAnimation = remember(
            animationScope,
            valueRange.start,
            valueRange.endInclusive,
        ) {
            PrismalSpringMotion(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    isDragging = true
                    didDrag = false
                    rawDragValue = targetValue
                    magnetStop = Float.NaN
                },
                onDragStopped = {
                    isDragging = false
                    if (didDrag) {
                        /*
                         * Al soltar siempre cae en la parada más cercana: el
                         * pulgar salta hacia el imán con su muelle y avisa al
                         * dueño del valor ya asentado.
                         */
                        val settled =
                            stops
                                ?.minByOrNull { stop -> abs(stop - targetValue) }
                                ?.fastCoerceIn(valueRange.start, valueRange.endInclusive)
                                ?: targetValue

                        lastReportedValue = settled
                        onValueChangeLatest(settled)

                        if (settled != targetValue) {
                            detentClick()
                            animateToValue(settled)
                        }
                    }
                },
                onDrag = { _, dragAmount ->
                    val width = trackWidthState.intValue
                    if (width == 0) return@PrismalSpringMotion
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta =
                        (valueRange.endInclusive - valueRange.start) * (dragAmount.x / width.toFloat())
                    val raw = (
                            if (isLtr) rawDragValue + delta
                            else rawDragValue - delta
                            ).fastCoerceIn(valueRange.start, valueRange.endInclusive)
                    rawDragValue = raw

                    val nextValue =
                        if (magnetRadius != null) {
                            val near =
                                stops
                                    ?.minByOrNull { stop -> abs(stop - raw) }

                            if (near != null && abs(raw - near) <= magnetRadius) {
                                if (magnetStop != near) {
                                    magnetStop = near
                                    detentClick()
                                }
                                near
                            } else {
                                magnetStop = Float.NaN
                                raw
                            }
                        } else {
                            raw
                        }

                    updateValue(nextValue)
                    if (abs(nextValue - lastReportedValue) >= visibilityThreshold) {
                        lastReportedValue = nextValue
                        onValueChangeLatest(nextValue)
                    }
                }
            )
        }
        var motionFrame by remember { mutableIntStateOf(0) }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { dampedDragAnimation.animationSnapshot() }
                .collect { motionFrame++ }
        }

        /*
         * Resincronización con el valor comprometido. Se releen los datos por
         * composición (no con un snapshotFlow sobre la lambda `value`, que
         * captura el valor plano de la primera composición y deja de emitir):
         * si el valor cambia por fuera del gesto —restaurar preferencias, un
         * write externo— el pulgar vuelve al valor con su muelle en vez de
         * quedar varado donde sea.
         */
        val committedValue = value()

        LaunchedEffect(dampedDragAnimation, committedValue) {
            if (!isDragging && dampedDragAnimation.targetValue != committedValue) {
                /*
                 * Snap, no muelle: updateValue lanza la animación diferida y
                 * dos comandos seguidos pueden perder el orden. El snap deja
                 * al pulgar clavado en la parada comprometida al instante.
                 */
                dampedDragAnimation.snapToValue(committedValue)
            }
        }

        Box(Modifier.prismalGlassLayer(trackBackdrop)) {
            Box(
                Modifier
                    .clip(PrismalCapsule())
                    .background(trackColor)
                    .pointerInput(animationScope, trackWidth, valueRange, isLtr, stops) {
                        detectTapGestures { position ->
                            val delta =
                                (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                            val rawValue =
                                (if (isLtr) valueRange.start + delta
                                else valueRange.endInclusive - delta)
                                    .coerceIn(valueRange)
                            /*
                             * Un toque siempre cae en la parada más cercana.
                             */
                            val targetValue =
                                stops
                                    ?.minByOrNull { stop -> abs(stop - rawValue) }
                                    ?: rawValue

                            dampedDragAnimation.animateToValue(targetValue)
                            onValueChangeLatest(targetValue)
                            detentClick()
                        }
                    }
                    .height(6.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(PrismalCapsule())
                    .background(accentColor)
                    .height(6.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width =
                            (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        if (motionFrame >= 0) {
            Box(
                Modifier
                    .graphicsLayer {
                        translationX =
                            (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                                .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                                    if (isLtr) 1f else -1f
                    }
                    .then(dampedDragAnimation.modifier)
                    .drawPrismalGlass(
                        /*
                         * Solo la pista envuelta —NO la capa del tema—: esa capa
                         * graba todo el contenido de la pantalla (glassSource) y
                         * el pulgar vive dentro de ella; muestrearla desde acá
                         * forma un ciclo de RenderNodes y tumba libhwui con un
                         * desbordamiento de pila (SIGSEGV en RenderThread).
                         */
                        backdrop = rememberPrismalWrappedSource(trackBackdrop) { drawPrismalGlass ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawPrismalGlass()
                            }
                        },
                        shape = { PrismalCapsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            if (adaptiveLuminance) {
                                applyPrismalGlassEffects(
                                    density = density,
                                    adaptiveLuminance = true,
                                    luminance = luminance(),
                                    blurRadiusPx = with(density) { 8.dp.toPx() } * (1f - progress),
                                    refractionHeightPx = with(density) { 10.dp.toPx() } * progress,
                                    refractionAmountPx = with(density) { 14.dp.toPx() } * progress,
                                    chromaticAberration = 1f
                                )
                            } else {
                                prismalBlur(with(density) { 8.dp.toPx() } * (1f - progress))
                                prismalLens(
                                    refractionHeight = with(density) { 10.dp.toPx() } * progress,
                                    refractionAmount = with(density) { 14.dp.toPx() } * progress,
                                    chromaticAberration = 1f
                                )
                            }
                        },
                        specular = {
                            val progress = dampedDragAnimation.pressProgress
                            PrismalSpecular.Ambient.copy(
                                width = PrismalSpecular.Ambient.width / 1.5f,
                                blurRadius = PrismalSpecular.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        depthShadow = {
                            PrismalDepthShadow(
                                radius = 4.dp,
                                color = Color.Black.copy(alpha = 0.05f)
                            )
                        },
                        depthInset = {
                            val progress = dampedDragAnimation.pressProgress
                            PrismalDepthInset(
                                radius = 4.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(Color.White.copy(alpha = 1f - progress))
                        }
                    )
                    .size(40.dp, 24.dp)
            )
        }
    }
}

/**
 * Click de parada para los detentes del slider.
 *
 * Va directo al vibrador con el efecto pre-horneado EFFECT_CLICK en lugar
 * de View.performHapticFeedback(CLOCK_TICK): el tick del sistema es casi
 * imperceptible y se silencia con el ajuste de "retroalimentación táctil",
 * mientras que el click del vibrador siempre se siente.
 */
@Composable
private fun rememberDetentClick(): () -> Unit {
    val context = LocalContext.current

    return remember(context) {
        {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (
                            context.getSystemService(
                                Context.VIBRATOR_MANAGER_SERVICE,
                            ) as? VibratorManager
                            )
                        ?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

            if (vibrator?.hasVibrator() == true) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(
                            VibrationEffect.createPredefined(
                                VibrationEffect.EFFECT_CLICK,
                            ),
                        )
                    } else {
                        vibrator.vibrate(20)
                    }
                } catch (_: Exception) {
                    // Sin el permiso VIBRATE o con el motor apagado, el slider
                    // sigue funcionando sin el click.
                }
            }
        }
    }
}
