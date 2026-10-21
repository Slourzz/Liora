package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.liora.android.ui.lyrics.DotGlowSpline
import com.liora.android.ui.lyrics.DotOpacitySpline
import com.liora.android.ui.lyrics.DotScaleSpline
import com.liora.android.ui.lyrics.DotYOffsetSpline
import com.liora.android.ui.lyrics.SpicyElementState
import com.liora.android.ui.lyrics.SpicySpring
import com.liora.android.ui.lyrics.getSpicyElementState
import com.liora.android.ui.lyrics.getSpicyProgressPercentage
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraInstrumentalGap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun LyraWaitingDots(
    gap: LyraInstrumentalGap,
    positionMs: Double,
    isPlaying: Boolean,
    /*
     * Nivel Simple: la coreografía completa de AMLL (respiración del grupo
     * y encendido secuencial en tres tramos), sin muelles ni halo.
     */
    simpleStyle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density =
        LocalDensity.current

    /*
     * Entrada y salida al estilo de AMLL.
     *
     * La entrada espera 500 ms desde el final de la línea anterior (en una
     * intro no espera), el grupo aparece en 180 ms y los tres puntos se
     * encienden escalonados: 80 ms entre uno y otro y 750 ms cada uno, con
     * una curva cuadrática.
     *
     * La salida empieza 1000 ms antes de su final, que a su vez queda 180 ms
     * antes de la siguiente línea: el grupo se hincha durante 750 ms hasta
     * 1.25x y se contrae a 0.4x en los últimos 250 ms, que es justo cuando se
     * desvanece. Después, el hueco que ocupan los puntos se pliega antes de
     * que la letra empiece.
     */
    val enterHoldMs =
        remember(gap.startMs) {
            if (gap.startMs <= 0.0) {
                0.0
            } else {
                LYRA_DOTS_ENTER_HOLD_MS
            }
        }

    val dotAnimationStartMs =
        gap.startMs + enterHoldMs

    val dotAnimationEndMs =
        max(
            dotAnimationStartMs + LYRA_DOTS_MIN_DURATION_MS,
            gap.endMs - LYRA_DOTS_EXIT_LEAD_OUT_MS - LYRA_DOTS_EXIT_TOTAL_MS,
        )

    var groupVisual by
        remember(
            gap.startMs,
            gap.endMs,
        ) {
            mutableStateOf(
                LyraDotsGroupVisual(
                    scale = 1f,
                    opacity = 0f,
                ),
            )
        }

    val latestPositionMs =
        rememberUpdatedState(
            positionMs,
        )

    val latestIsPlaying =
        rememberUpdatedState(
            isPlaying,
        )

    var anchorPositionMs by
    remember {
        mutableStateOf(
            positionMs,
        )
    }

    var anchorNanos by
    remember {
        mutableStateOf(
            0L,
        )
    }

    /*
     * El animator empieza a encender los puntos después de la pausa de
     * entrada y termina cuando arranca la salida. La multiplicación por
     * el fundido escalonado de cada punto se hace dentro del animator.
     */
    val animator =
        remember(
            gap.startMs,
            dotAnimationEndMs,
            enterHoldMs,
        ) {
            LyraDotAnimator(
                startMs =
                    gap.startMs,

                endMs =
                    dotAnimationEndMs,

                enterHoldMs =
                    enterHoldMs,
            )
        }

    var dots by
    remember(animator) {
        mutableStateOf(
            animator.snapshot(),
        )
    }

    /*
     * El estado del modo Simple: una función pura del reloj — la misma
     * coreografía de AMLL (breathing + tres tramos de encendido) sin muelles.
     */
    val amllState =
        remember(
            gap.startMs,
            gap.endMs,
            dotAnimationEndMs,
            enterHoldMs,
        ) {
            LyraAmllDotsState(
                gapStartMs = gap.startMs,
                enterHoldMs = enterHoldMs,
                bodyEndMs = dotAnimationEndMs,
            )
        }

    val latestSimpleStyle =
        rememberUpdatedState(
            simpleStyle,
        )

    val dotDiameterPx =
        with(density) {
            LYRA_DOT_DIAMETER
                .toPx()
        }

    val dotGapPx =
        with(density) {
            LYRA_DOT_GAP
                .toPx()
        }

    /*
     * AMLL escala alrededor del centro del grupo. El centro exacto es el
     * del punto intermedio: padding izquierdo + radio + un paso.
     */
    /*
     * El grupo crece hasta 1.25x al salir. El primer punto se sale por la
     * izquierda del canvas; este margen le da sitio para crecer sin que el
     * borde lo corte. El canvas se desplaza lo mismo a la izquierda, así que
     * los puntos siguen exactamente en la misma posición.
     */
    val canvasHeadroomPx =
        with(density) {
            LYRA_DOT_CANVAS_HEADROOM
                .toPx()
        }

    val canvasWidthPx =
        with(density) {
            (
                    LYRA_DOT_CANVAS_WIDTH +
                            LYRA_DOT_CANVAS_HEADROOM
                    )
                .toPx()
        }

    val groupPivotFractionX =
        (
                (
                        canvasHeadroomPx +
                                LYRA_DOT_LEFT_PADDING_PX +
                                dotDiameterPx /
                                2f +
                                dotDiameterPx +
                                dotGapPx
                        ) /
                        canvasWidthPx
                )
            .coerceIn(
                0f,
                1f,
            )

    /*
     * El item del LazyColumn recorta en su propio borde izquierdo: nada
     * dibujado dentro puede cruzar hacia la izquierda. Para que el punto 1
     * no amanezca cortado al crecer a 1.25x, la capa entera se corre a la
     * derecha lo mismo que el borde izquierdo del punto se movería, y así
     * ese borde se queda clavado en su posición de reposo (por dentro del
     * límite del item). El grupo respira expandido hacia la derecha.
     */
    val lyraDotPinDistancePx =
        groupPivotFractionX *
                canvasWidthPx -
                (
                        canvasHeadroomPx +
                                LYRA_DOT_LEFT_PADDING_PX
                        )

    val shadowPaint =
        remember {
            Paint(
                Paint.ANTI_ALIAS_FLAG,
            )
        }

    val dotPaint =
        remember {
            Paint(
                Paint.ANTI_ALIAS_FLAG,
            )
        }

    LaunchedEffect(
        positionMs,
        isPlaying,
    ) {
        anchorPositionMs =
            positionMs

        anchorNanos =
            0L
    }

    LaunchedEffect(animator, amllState) {
        var previousFrameNanos =
            withFrameNanos {
                it
            }

        while (true) {
            val frameNanos =
                withFrameNanos {
                    it
                }

            val deltaSeconds =
                (
                        frameNanos -
                                previousFrameNanos
                        ) /
                        1_000_000_000.0

            previousFrameNanos =
                frameNanos

            if (
                anchorNanos ==
                0L
            ) {
                anchorNanos =
                    frameNanos
            }

            val projectedPositionMs =
                if (
                    latestIsPlaying.value
                ) {
                    anchorPositionMs +
                            (
                                    frameNanos -
                                            anchorNanos
                                    ) /
                            1_000_000.0

                } else {
                    latestPositionMs.value
                }

            if (
                latestSimpleStyle.value
            ) {
                /*
                 * Nivel Simple: todo sale de resolver el reloj contra la
                 * coreografía de AMLL —sin muelles y sin halo—.
                 */
                val snapshot =
                    amllState.snapshot(
                        positionMs =
                            projectedPositionMs,
                    )

                dots =
                    List(
                        LYRA_DOT_COUNT,
                    ) { dotIndex ->
                        LyraDotVisual(
                            scale = 1f,
                            yOffset = 0f,
                            glow = 0f,
                            opacity =
                                snapshot.dotOpacities[
                                        dotIndex
                                ],
                        )
                    }

                groupVisual =
                    snapshot.groupVisual
            } else {
                dots =
                    animator.step(
                        positionMs =
                            projectedPositionMs,

                        deltaSeconds =
                            deltaSeconds.coerceIn(
                                0.0,
                                LYRA_MAX_FRAME_DELTA_SECONDS,
                            ),
                    )

                groupVisual =
                    resolveLyraDotsGroup(
                        positionMs =
                            projectedPositionMs,

                        gapStartMs =
                            gap.startMs,

                        gapEndMs =
                            gap.endMs,

                        enterHoldMs =
                            enterHoldMs,
                    )
            }
        }
    }

    Box(
        modifier =
            modifier
                .height(
                    LYRA_DOT_SLOT_HEIGHT,
                ),

        contentAlignment =
            Alignment.CenterStart,
    ) {
        Canvas(
            modifier =
                Modifier
                    .width(
                        LYRA_DOT_CANVAS_WIDTH +
                                LYRA_DOT_CANVAS_HEADROOM,
                    )
                    .height(
                        LYRA_DOT_SLOT_HEIGHT,
                    )
                    .offset(
                        x =
                            -LYRA_DOT_CANVAS_HEADROOM,
                    )
                    .graphicsLayer {
                        /*
                         * La entrada solo funde; la salida de AMLL hace
                         * respirar el grupo (1.0 -> 1.25 -> 0.4) y lo
                         * desvanece en el último tramo. El pivote es el
                         * centro de los tres puntos, no el borde.
                         */
                        scaleX =
                            groupVisual.scale

                        scaleY =
                            groupVisual.scale

                        alpha =
                            groupVisual.opacity

                        translationX =
                            (groupVisual.scale -
                                    1f) *
                                    lyraDotPinDistancePx

                        transformOrigin =
                            TransformOrigin(
                                pivotFractionX =
                                    groupPivotFractionX,

                                pivotFractionY =
                                    0.5f,
                            )
                    },
        ) {
            val baseRadius =
                dotDiameterPx /
                        2f

            val step =
                dotDiameterPx +
                        dotGapPx

            val centerY =
                size.height /
                        2f

            val firstCenterX =
                canvasHeadroomPx +
                        LYRA_DOT_LEFT_PADDING_PX +
                        baseRadius

            drawIntoCanvas { canvas ->
                val nativeCanvas =
                    canvas.nativeCanvas

                dots.forEachIndexed {
                        index,
                        dot ->

                    val opacity =
                        dot.opacity
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val glow =
                        dot.glow
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val radius =
                        baseRadius *
                                dot.scale

                    val center =
                        Offset(
                            x =
                                firstCenterX +
                                        index *
                                        step,

                            y =
                                centerY +
                                        dotDiameterPx *
                                        dot.yOffset,
                        )

                    val shadowAlpha =
                        (
                                glow *
                                        opacity *
                                        0.90f
                                )
                            .coerceIn(
                                0f,
                                1f,
                            )

                    val shadowBlur =
                        LYRA_DOT_SHADOW_BASE_PX +
                                LYRA_DOT_SHADOW_RANGE_PX *
                                glow

                    if (
                        shadowAlpha >
                        0f
                    ) {
                        shadowPaint.color =
                            Color.White
                                .copy(
                                    alpha =
                                        shadowAlpha,
                                )
                                .toAndroidArgb()

                        shadowPaint.maskFilter =
                            BlurMaskFilter(
                                shadowBlur,
                                BlurMaskFilter.Blur.NORMAL,
                            )

                        nativeCanvas.drawCircle(
                            center.x,
                            center.y,
                            radius,
                            shadowPaint,
                        )
                    }

                    dotPaint.color =
                        Color.White
                            .copy(
                                alpha =
                                    opacity,
                            )
                            .toAndroidArgb()

                    dotPaint.maskFilter =
                        null

                    nativeCanvas.drawCircle(
                        center.x,
                        center.y,
                        radius,
                        dotPaint,
                    )
                }
            }
        }
    }
}

private class LyraDotAnimator(
    private val startMs: Double,
    endMs: Double,
    private val enterHoldMs: Double,
) {
    private val timings =
        buildLyraDotTimings(
            startMs =
                startMs + enterHoldMs,

            endMs =
                endMs,
        )

    private val springs =
        List(
            LYRA_DOT_COUNT,
        ) {
            LyraDotSprings()
        }

    fun snapshot(): List<LyraDotVisual> {
        return springs.map {
            it.snapshot()
        }
    }

    fun step(
        positionMs: Double,
        deltaSeconds: Double,
    ): List<LyraDotVisual> {
        val dt =
            if (
                deltaSeconds.isFinite() &&
                deltaSeconds >
                0.0
            ) {
                deltaSeconds
            } else {
                0.0
            }

        return springs.mapIndexed {
                index,
                spring ->

            val timing =
                timings[index]

            val progress =
                getSpicyProgressPercentage(
                    positionMs,
                    timing.startMs,
                    timing.endMs,
                )

            val target =
                when (
                    getSpicyElementState(
                        positionMs,
                        timing.startMs,
                        timing.endMs,
                    )
                ) {
                    SpicyElementState.Active ->

                        LyraDotVisual(
                            scale =
                                DotScaleSpline
                                    .at(
                                        progress,
                                    )
                                    .toFloat(),

                            yOffset =
                                DotYOffsetSpline
                                    .at(
                                        progress,
                                    )
                                    .toFloat(),

                            glow =
                                DotGlowSpline
                                    .at(
                                        progress,
                                    )
                                    .toFloat(),

                            opacity =
                                DotOpacitySpline
                                    .at(
                                        progress,
                                    )
                                    .toFloat(),
                        )

                    SpicyElementState.NotSung ->

                        LyraDotVisual(
                            scale =
                                DotScaleSpline
                                    .at(
                                        0.0,
                                    )
                                    .toFloat(),

                            yOffset =
                                DotYOffsetSpline
                                    .at(
                                        0.0,
                                    )
                                    .toFloat(),

                            glow =
                                DotGlowSpline
                                    .at(
                                        0.0,
                                    )
                                    .toFloat(),

                            opacity =
                                DotOpacitySpline
                                    .at(
                                        0.0,
                                    )
                                    .toFloat(),
                        )

                    SpicyElementState.Sung ->

                        LyraDotVisual(
                            scale =
                                DotScaleSpline
                                    .at(
                                        1.0,
                                    )
                                    .toFloat(),

                            yOffset =
                                DotYOffsetSpline
                                    .at(
                                        1.0,
                                    )
                                    .toFloat(),

                            glow =
                                DotGlowSpline
                                    .at(
                                        1.0,
                                    )
                                    .toFloat(),

                            opacity =
                                DotOpacitySpline
                                    .at(
                                        1.0,
                                    )
                                    .toFloat(),
                        )
                }

            spring.setGoal(
                target,
            )

            val visual =
                spring.step(
                    dt,
                )

            /*
             * AMLL enciende cada punto con su propio fundido: 80 ms de
             * separación y 750 ms de subida cuadrática. Se aplica al
             * resultado del spring para no alterar su estado interno.
             */
            visual.copy(
                opacity =
                    visual.opacity *
                            lyraDotEnterAlpha(
                                index =
                                    index,

                                internalMs =
                                    positionMs -
                                            startMs -
                                            enterHoldMs,
                            ),
            )
        }
    }
}

private fun lyraDotEnterAlpha(
    index: Int,
    internalMs: Double,
): Float {
    val t =
        (
                (
                        internalMs -
                                index *
                                LYRA_DOT_ENTER_STAGGER_MS
                        ) /
                        LYRA_DOT_ENTER_FADE_MS
                )
            .coerceIn(
                0.0,
                1.0,
            )

    return (t * t).toFloat()
}

private fun buildLyraDotTimings(
    startMs: Double,
    endMs: Double,
): List<LyraDotTiming> {
    val duration =
        (
                endMs -
                        startMs
                )
            .coerceAtLeast(
                1.0,
            )

    val perDot =
        duration /
                LYRA_DOT_COUNT

    return List(
        LYRA_DOT_COUNT,
    ) { index ->
        LyraDotTiming(
            startMs =
                startMs +
                        perDot *
                        index,

            endMs =
                if (
                    index ==
                    LYRA_DOT_COUNT -
                    1
                ) {
                    endMs
                } else {
                    startMs +
                            perDot *
                            (
                                    index +
                                            1
                                    )
                },
        )
    }
}

private data class LyraDotTiming(
    val startMs: Double,
    val endMs: Double,
)

private data class LyraDotVisual(
    val scale: Float,
    val yOffset: Float,
    val glow: Float,
    val opacity: Float,
)

private class LyraDotSprings {

    private val scale =
        SpicySpring(
            DotScaleSpline.at(
                0.0,
            ),
            frequency =
                0.7,
            dampingRatio =
                0.6,
        )

    private val yOffset =
        SpicySpring(
            DotYOffsetSpline.at(
                0.0,
            ),
            frequency =
                1.25,
            dampingRatio =
                0.4,
        )

    private val glow =
        SpicySpring(
            DotGlowSpline.at(
                0.0,
            ),
            frequency =
                1.0,
            dampingRatio =
                0.5,
        )

    private val opacity =
        SpicySpring(
            DotOpacitySpline.at(
                0.0,
            ),
            frequency =
                1.0,
            dampingRatio =
                0.5,
        )

    fun snapshot(): LyraDotVisual {
        return LyraDotVisual(
            scale =
                scale.value.toFloat(),

            yOffset =
                yOffset.value.toFloat(),

            glow =
                glow.value.toFloat(),

            opacity =
                opacity.value.toFloat(),
        )
    }

    fun setGoal(
        target: LyraDotVisual,
    ) {
        scale.setGoal(
            target.scale.toDouble(),
        )

        yOffset.setGoal(
            target.yOffset.toDouble(),
        )

        glow.setGoal(
            target.glow.toDouble(),
        )

        opacity.setGoal(
            target.opacity.toDouble(),
        )
    }

    fun step(
        deltaSeconds: Double,
    ): LyraDotVisual {
        return LyraDotVisual(
            scale =
                scale.step(
                    deltaSeconds,
                )
                    .toFloat(),

            yOffset =
                yOffset.step(
                    deltaSeconds,
                )
                    .toFloat(),

            glow =
                glow.step(
                    deltaSeconds,
                )
                    .toFloat(),

            opacity =
                opacity.step(
                    deltaSeconds,
                )
                    .toFloat(),
        )
    }
}

private fun Color.toAndroidArgb(): Int {
    return android.graphics.Color.argb(
        (
                alpha *
                        255
                )
            .toInt()
            .coerceIn(
                0,
                255,
            ),

        (
                red *
                        255
                )
            .toInt()
            .coerceIn(
                0,
                255,
            ),

        (
                green *
                        255
                )
            .toInt()
            .coerceIn(
                0,
                255,
            ),

        (
                blue *
                        255
                )
            .toInt()
            .coerceIn(
                0,
                255,
            ),
    )
}

private data class LyraDotsGroupVisual(
    val scale: Float,
    val opacity: Float,
)

/**
 * Estado del grupo en un instante del reloj de reproducción.
 *
 * Reproduce la coreografía de AMLL: 180 ms de fundido de entrada; la salida
 * ocupa el último segundo, con 750 ms de expansión hasta 1.25x, 250 ms de
 * contracción hasta 0.4x y el fundido concentrado en ese último tramo.
 */
private fun resolveLyraDotsGroup(
    positionMs: Double,
    gapStartMs: Double,
    gapEndMs: Double,
    enterHoldMs: Double,
): LyraDotsGroupVisual {
    val internalMs =
        positionMs -
                gapStartMs -
                enterHoldMs

    if (internalMs <= 0.0) {
        return LyraDotsGroupVisual(
            scale = 1f,
            opacity = 0f,
        )
    }

    val entryOpacity =
        LyraDotsEnterEasing.transform(
            (internalMs / LYRA_DOTS_ENTER_FADE_MS)
                .coerceIn(
                    0.0,
                    1.0,
                )
                .toFloat(),
        )

    val exitStartMs =
        gapEndMs -
                LYRA_DOTS_EXIT_LEAD_OUT_MS -
                LYRA_DOTS_EXIT_TOTAL_MS

    if (positionMs < exitStartMs) {
        return LyraDotsGroupVisual(
            scale = 1f,
            opacity = entryOpacity,
        )
    }

    val exitElapsedMs =
        (positionMs - exitStartMs)
            .coerceAtLeast(
                0.0,
            )

    val fadeT =
        (
                (
                        exitElapsedMs -
                                (
                                        LYRA_DOTS_EXIT_TOTAL_MS -
                                                LYRA_DOTS_EXIT_FADE_MS
                                        )
                        ) /
                        LYRA_DOTS_EXIT_FADE_MS
                )
            .coerceIn(
                0.0,
                1.0,
            )
            .toFloat()

    val opacity =
        entryOpacity *
                (
                        1f -
                                LyraDotsExitFadeEasing
                                    .transform(
                                        fadeT,
                                    )
                        )

    val scale =
        if (exitElapsedMs < LYRA_DOTS_EXIT_PHASE1_MS) {
            val t =
                (exitElapsedMs / LYRA_DOTS_EXIT_PHASE1_MS)
                    .coerceIn(
                        0.0,
                        1.0,
                    )
                    .toFloat()

            1f +
                    LyraDotsExitPhase1Easing
                        .transform(
                            t,
                        ) *
                    (LYRA_DOTS_MAX_SCALE - 1f)
        } else {
            val t =
                (
                        (exitElapsedMs - LYRA_DOTS_EXIT_PHASE1_MS) /
                                LYRA_DOTS_EXIT_PHASE2_MS
                        )
                    .coerceIn(
                        0.0,
                        1.0,
                    )
                    .toFloat()

            LYRA_DOTS_MAX_SCALE -
                    LyraDotsExitPhase2Easing
                        .transform(
                            t,
                        ) *
                    (LYRA_DOTS_MAX_SCALE - LYRA_DOTS_MIN_SCALE)
        }

    return LyraDotsGroupVisual(
        scale = scale,
        opacity = opacity,
    )
}

private val LyraDotsEnterEasing =
    CubicBezierEasing(
        0.59f,
        0.02f,
        0.07f,
        1f,
    )

private val LyraDotsExitPhase1Easing =
    CubicBezierEasing(
        0.14f,
        0.06f,
        0.25f,
        1f,
    )

private val LyraDotsExitPhase2Easing =
    CubicBezierEasing(
        0.29f,
        0.03f,
        1f,
        0.38f,
    )

private val LyraDotsExitFadeEasing =
    CubicBezierEasing(
        0.43f,
        0.08f,
        0.83f,
        0.31f,
    )

private val LYRA_DOT_DIAMETER =
    12.25.dp

private val LYRA_DOT_GAP =
    2.dp

/*
 * Ya no necesitamos 62dp.
 * Los puntos reales son pequeños y esto es overlay.
 */
internal val LYRA_DOT_SLOT_HEIGHT =
    34.dp

private val LYRA_DOT_CANVAS_WIDTH =
    56.dp

private val LYRA_DOT_CANVAS_HEADROOM =
    5.dp

private const val LYRA_DOT_LEFT_PADDING_PX =
    4f

private const val LYRA_DOT_SHADOW_BASE_PX =
    4f

private const val LYRA_DOT_SHADOW_RANGE_PX =
    6f

private const val LYRA_MAX_FRAME_DELTA_SECONDS =
    0.05

private const val LYRA_DOT_COUNT =
    3

/*
 * Márgenes temporales de AMLL para la entrada y la salida.
 */
private const val LYRA_DOTS_ENTER_HOLD_MS =
    500.0

private const val LYRA_DOTS_ENTER_FADE_MS =
    180.0

private const val LYRA_DOT_ENTER_FADE_MS =
    750.0

private const val LYRA_DOT_ENTER_STAGGER_MS =
    80.0

private const val LYRA_DOTS_EXIT_TOTAL_MS =
    1000.0

/*
 * La salida termina este margen antes de que empiece la siguiente línea.
 * Ese mismo margen se usa para plegar el hueco que ocupan los puntos, de
 * modo que la letra recupera su sitio justo cuando va a sonar.
 */
internal const val LYRA_DOTS_EXIT_LEAD_OUT_MS =
    180.0

private const val LYRA_DOTS_EXIT_PHASE1_MS =
    750.0

private const val LYRA_DOTS_EXIT_PHASE2_MS =
    250.0

private const val LYRA_DOTS_EXIT_FADE_MS =
    250.0

private const val LYRA_DOTS_MAX_SCALE =
    1.25f

private const val LYRA_DOTS_MIN_SCALE =
    0.4f

private const val LYRA_DOTS_MIN_DURATION_MS =
    300.0

/* ─────────────────────────────────────────────
 * MODO SIMPLE — interlude-dots.ts de AMLL, portado
 * ─────────────────────────────────────────────
 */

/** Cola de brillo que el tercer punto recibe durante la salida (DOT3_TRAILING_MS). */
private const val LYRA_AMLL_DOT3_TRAILING_MS =
    750.0

/** El cuerpo entero dura un ciclo u otro coherente (BREATHE_BASE_PERIOD_MS). */
private const val LYRA_AMLL_BREATHE_BASE_PERIOD_MS =
    4000.0

/** Debajo de este cuerpo no hay respiración, solo espera encendida (FALLBACK_HOLD_THRESHOLD_MS). */
private const val LYRA_AMLL_FALLBACK_THRESHOLD_MS =
    3000.0

/** Menos que esto (910 ms de entrada escalonada) no se muestra nada (DOT_ENTER_TOTAL_MS). */
private const val LYRA_AMLL_DOT_ENTER_TOTAL_MS =
    910.0

/** Opacidad entre la que interpola el encendido (DOT_INACTIVE / DOT_ACTIVE). */
private const val LYRA_AMLL_DOT_INACTIVE_OPACITY =
    0.2f

private const val LYRA_AMLL_DOT_ACTIVE_OPACITY =
    0.9f

/** Curva con la que se ilumina cada punto (lightingEasing). */
private val LyraAmllLightingEasing =
    CubicBezierEasing(
        0.56f,
        0.01f,
        0.45f,
        1f,
    )

/** Instantánea de la coreografía AMLL en un instante del reloj. */
private data class LyraAmllDotsSnapshot(
    val groupVisual: LyraDotsGroupVisual,
    /** Opacidad de cada punto, ya con su fundido escalonado de entrada. */
    val dotOpacities: FloatArray,
)

/** La curva de la respiración del grupo de AMLL (breathingProgress). */
private fun lyraAmllBreathingProgress(
    cycleT: Double,
): Double {
    if (
        cycleT <=
        0.0
    ) {
        return 0.0
    }

    if (
        cycleT >=
        1.0
    ) {
        return 1.0
    }

    val angle =
        4.0 *
                PI *
                cycleT

    val s =
        sin(angle)

    val c =
        cos(angle)
    return cycleT -
            0.084 *
                    s +
            0.008 *
                    (
                            1.0 -
                                    c
                            ) +
            0.0046 *
                    s *
                    (
                            c -
                                    s
                            )
}

/** Fracción de encendido de un punto en un instante (computeDotFraction). */
private fun lyraAmllDotFraction(
    startDelayMs: Double,
    durationMs: Double,
    internalMs: Double,
    target: Double,
): Double {
    if (
        internalMs <=
        startDelayMs
    ) {
        return 0.0
    }

    if (
        durationMs <=
        0.0
    ) {
        return target
    }

    val localT =
        (
                internalMs -
                        startDelayMs
                ) /
                durationMs

    return LyraAmllLightingEasing
        .transform(
            localT
                .coerceIn(
                    0.0,
                    1.0,
                )
                .toFloat(),
        )
        .toDouble() *
            target
}

/** Fundido de entrada de todo el grupo (enterOpacity de AMLL). */
private fun lyraAmllEnterOpacity(
    internalMs: Double,
): Float {
    return LyraDotsEnterEasing
        .transform(
            (
                    internalMs /
                            LYRA_DOTS_ENTER_FADE_MS
                    )
                .coerceIn(
                    0.0,
                    1.0,
                )
                .toFloat(),
        )
}

/** El muelle del encendido: opacidad entre 0.2 y 0.9 según la fracción. */
private fun lyraAmllDotOpacity(
    fraction: Double,
): Float {
    return (
            LYRA_AMLL_DOT_INACTIVE_OPACITY +
                    (
                            LYRA_AMLL_DOT_ACTIVE_OPACITY -
                                    LYRA_AMLL_DOT_INACTIVE_OPACITY
                            ) *
                            fraction
                            .coerceIn(
                                0.0,
                                1.0,
                            )
            )
        .toFloat()
}

/**
 * La coreografía completa de AMLL para el nivel Simple, como una función
 * pura del reloj: respiración del grupo, encendido secuencial en tramos
 * (dos puntos completos y el tercero a media luz), salida en dos fases con
 * la cola del tercer punto, y espera tenue cuando el hueco es corto.
 * Cero muelles: la misma matemática resuelta cada fotograma.
 */
private class LyraAmllDotsState(
    private val gapStartMs: Double,
    /** Fin del cuerpo del espectáculo (la salida completa vive después). */
    private val bodyEndMs: Double,
    private val enterHoldMs: Double,
) {
    /** Duración del cuerpo entre el fin de la espera de entrada y la salida. */
    private val bodyMs =
        (bodyEndMs -
                (gapStartMs +
                        enterHoldMs)
                )
            .coerceAtLeast(
                0.0,
            )

    /** Sin cuerpo suficiente: espera encendida, sin respiración. */
    private val fallbackHold =
        bodyMs <
                LYRA_AMLL_FALLBACK_THRESHOLD_MS

    /** Periodo de la respiración: un ciclo base o los múltiplos que quepan. */
    private val breathePeriodMs =
        if (fallbackHold) {
            LYRA_AMLL_BREATHE_BASE_PERIOD_MS
        } else {
            val breatheCycles =
                maxOf(
                    1.0,
                    (
                            bodyMs /
                                    LYRA_AMLL_BREATHE_BASE_PERIOD_MS
                            ).floorToLong()
                            .toDouble(),
                )

            bodyMs /
                    breatheCycles
        }

    /** Tramos de encendido: (cuerpo + cola del tercero) / 3, redondeado. */
    private val segmentMs =
        if (fallbackHold) {
            0.0
        } else {
            (
                    bodyMs +
                            LYRA_AMLL_DOT3_TRAILING_MS
                    ) /
                    LYRA_DOT_COUNT
        }
            .let(::roundToIntDouble)

    /** Cuánto dura el tercer punto, que puede quedar a media luz. */
    private val dot3DurationMs =
        if (fallbackHold) {
            0.0
        } else {
            bodyMs -
                    segmentMs *
                            2.0
        }

    /** La fracción a la que llega el tercer punto antes de la salida. */
    private val dot3Target =
        if (fallbackHold) {
            1.0
        } else {
            (
                    dot3DurationMs /
                            segmentMs
                    )
                .coerceIn(
                    0.0,
                    1.0,
                )
        }

    fun snapshot(
        positionMs: Double,
    ): LyraAmllDotsSnapshot {
        val elapsedMs =
            positionMs -
                    gapStartMs

        if (
            elapsedMs >=
            totalEndMs() ||
            bodyMs <
            LYRA_AMLL_DOT_ENTER_TOTAL_MS
        ) {
            return LyraAmllDotsSnapshot(
                groupVisual =
                    LyraDotsGroupVisual(
                        scale = 1f,
                        opacity = 0f,
                    ),
                dotOpacities =
                    FloatArray(
                        LYRA_DOT_COUNT,
                    ),
            )
        }

        if (
            elapsedMs <
            enterHoldMs
        ) {
            /*
             * La espera de entrada: el hueco ya está reservado y vacío.
             */
            return LyraAmllDotsSnapshot(
                groupVisual =
                    LyraDotsGroupVisual(
                        scale = 1f,
                        opacity = 0f,
                    ),
                dotOpacities =
                    FloatArray(
                        LYRA_DOT_COUNT,
                    ),
            )
        }

        val internalMs =
            elapsedMs -
                    enterHoldMs

        if (
            elapsedMs >=
            bodyEndMs
        ) {
            /*
             * Salida: dos fases del grupo y la cola del tercer punto.
             */
            val exitElapsedMs =
                (elapsedMs -
                        bodyEndMs)

            val fadeT =
                (
                        exitElapsedMs -
                                (
                                        LYRA_DOTS_EXIT_TOTAL_MS -
                                                LYRA_DOTS_EXIT_FADE_MS
                                        )
                        ) /
                        LYRA_DOTS_EXIT_FADE_MS

            val opacity =
                lyraAmllEnterOpacity(
                    internalMs,
                ) *
                        (
                                1f -
                                        LyraDotsExitFadeEasing
                                            .transform(
                                                fadeT
                                                    .coerceIn(
                                                        0.0,
                                                        1.0,
                                                    )
                                                    .toFloat(),
                                            )
                                )

            val scale =
                if (
                    exitElapsedMs <
                    LYRA_DOTS_EXIT_PHASE1_MS
                ) {
                    val t =
                        (exitElapsedMs /
                                LYRA_DOTS_EXIT_PHASE1_MS)
                            .coerceIn(
                                0.0,
                                1.0,
                            )
                            .toFloat()

                    1f +
                            LyraDotsExitPhase1Easing
                                .transform(t) *
                            (
                                    LYRA_DOTS_MAX_SCALE -
                                            1f
                                    )
                } else {
                    val t =
                        (
                                exitElapsedMs -
                                        LYRA_DOTS_EXIT_PHASE1_MS
                                ) /
                                LYRA_DOTS_EXIT_PHASE2_MS

                    LYRA_DOTS_MAX_SCALE -
                            LyraDotsExitPhase2Easing
                                .transform(
                                    t
                                        .coerceIn(
                                            0.0,
                                            1.0,
                                        )
                                        .toFloat(),
                                ) *
                            (
                                    LYRA_DOTS_MAX_SCALE -
                                            LYRA_DOTS_MIN_SCALE
                                    )
                }

            val trailing =
                (exitElapsedMs /
                        LYRA_AMLL_DOT3_TRAILING_MS)
                    .coerceIn(
                        0.0,
                        1.0,
                    )

            return LyraAmllDotsSnapshot(
                groupVisual =
                    LyraDotsGroupVisual(
                        scale = scale,
                        opacity = opacity,
                    ),
                dotOpacities =
                    floatArrayOf(
                        lyraAmllDotPacked(
                            dotIndex = 0,
                            fraction = 1.0,
                            internalMs = internalMs,
                        ),
                        lyraAmllDotPacked(
                            dotIndex = 1,
                            fraction = 1.0,
                            internalMs = internalMs,
                        ),
                        lyraAmllDotPacked(
                            dotIndex = 2,
                            fraction =
                                    dot3Target +
                                            (
                                                    1.0 -
                                                            dot3Target
                                                    ) *
                                            trailing,
                            internalMs = internalMs,
                        ),
                    ),
            )
        }

        /*
         * Cuerpo de la coreografía: fundido de entrada del grupo con la
         * respiración en paralelo y el encendido por tramos.
         */
        val opacity =
            lyraAmllEnterOpacity(
                internalMs,
            )

        if (fallbackHold) {
            return LyraAmllDotsSnapshot(
                groupVisual =
                    LyraDotsGroupVisual(
                        scale = 1f,
                        opacity = opacity,
                    ),
                dotOpacities =
                    floatArrayOf(
                        lyraAmllDotPacked(
                            dotIndex = 0,
                            fraction = 1.0,
                            internalMs = internalMs,
                        ),
                        lyraAmllDotPacked(
                            dotIndex = 1,
                            fraction = 1.0,
                            internalMs = internalMs,
                        ),
                        lyraAmllDotPacked(
                            dotIndex = 2,
                            fraction = 1.0,
                            internalMs = internalMs,
                        ),
                    ),
            )
        }

        val cycleT =
            (internalMs %
                    breathePeriodMs) /
                    breathePeriodMs

        val breathing =
            lyraAmllBreathingProgress(
                cycleT,
            )

        val scale =
            if (
                breathing <=
                0.5
            ) {
                1f +
                        (
                                breathing /
                                        0.5
                                )
                            .toFloat() *
                        (
                                LYRA_DOTS_MAX_SCALE -
                                        1f
                                )
            } else {
                LYRA_DOTS_MAX_SCALE -
                        (
                                (
                                        breathing -
                                                0.5
                                        ) /
                                        0.5
                                )
                            .toFloat() *
                        (
                                LYRA_DOTS_MAX_SCALE -
                                        LYRA_DOTS_MIN_SCALE
                                )
            }

        return LyraAmllDotsSnapshot(
            groupVisual =
                LyraDotsGroupVisual(
                    scale = scale,
                    opacity = opacity,
                ),
            dotOpacities =
                floatArrayOf(
                    lyraAmllDotPacked(
                        dotIndex = 0,
                        fraction =
                            lyraAmllDotFraction(
                                startDelayMs = 0.0,
                                durationMs = segmentMs,
                                internalMs = internalMs,
                                target = 1.0,
                            ),
                        internalMs = internalMs,
                    ),
                    lyraAmllDotPacked(
                        dotIndex = 1,
                        fraction =
                            lyraAmllDotFraction(
                                startDelayMs = segmentMs,
                                durationMs = segmentMs,
                                internalMs = internalMs,
                                target = 1.0,
                            ),
                        internalMs = internalMs,
                    ),
                    lyraAmllDotPacked(
                        dotIndex = 2,
                        fraction =
                            lyraAmllDotFraction(
                                startDelayMs = segmentMs *
                                        2.0,
                                durationMs = dot3DurationMs,
                                internalMs = internalMs,
                                target = dot3Target,
                            ),
                        internalMs = internalMs,
                    ),
                ),
        )
    }

    private fun totalEndMs():
            Double {
        return bodyEndMs +
                LYRA_DOTS_EXIT_TOTAL_MS
    }

    /** Opacidad final de un punto: brillo por fracción × fundido escalonado. */
    private fun lyraAmllDotPacked(
        dotIndex: Int,
        fraction: Double,
        internalMs: Double,
    ): Float {
        return lyraAmllDotOpacity(
            fraction,
        ) *
                lyraDotEnterAlpha(
                    index = dotIndex,
                    internalMs = internalMs,
                )
    }
}

/** Cortes de redondeo para los cálculos de AMLL, a un sonido Kotlin sano. */
private fun Double.floorToLong():
        Long {
    return kotlin.math.floor(
        this,
    ).toLong()
}

private fun roundToIntDouble(
    value: Double,
): Double {
    return kotlin.math.round(
        value,
    )
}
