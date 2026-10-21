package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liora.android.data.model.LyricLine
import com.liora.android.data.prefs.LyricAnimationLevel
import com.liora.android.ui.lyrics.lyrics.lyra.animation.LyraAnimationSpec
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraEngineFrame
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraInstrumentalGap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun LyraLyricsList(
    lyrics: List<LyricLine>,
    frame: LyraEngineFrame,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    wordByWordEnabled: Boolean = true,
    animationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    showRomanization: Boolean = false,
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val listState = rememberLazyListState()

    var scrollEvent by remember {
        mutableStateOf(LyraScrollEvent())
    }

    var lastTargetIndex by remember {
        mutableStateOf<Int?>(null)
    }

    var userScrolling by remember {
        mutableStateOf(false)
    }

    var programmaticScrolling by remember {
        mutableStateOf(false)
    }

    var userScrollGeneration by remember {
        mutableLongStateOf(0L)
    }

    val scrollRequests =
        remember {
            Channel<LyraScrollRequest>(
                capacity = Channel.CONFLATED,
            )
        }

    LaunchedEffect(
        listState.isScrollInProgress,
        programmaticScrolling,
    ) {
        if (
            listState.isScrollInProgress &&
            !programmaticScrolling
        ) {
            userScrolling = true
            userScrollGeneration += 1L

            /*
             * Obliga a recuperar seguimiento
             * aunque el lyric objetivo no haya cambiado.
             */
            lastTargetIndex = null
        } else if (
            !listState.isScrollInProgress &&
            userScrolling &&
            !programmaticScrolling
        ) {
            val generation =
                ++userScrollGeneration

            delay(
                USER_SCROLL_RESUME_DELAY_MS,
            )

            if (
                generation ==
                userScrollGeneration
            ) {
                userScrolling = false
            }
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    compositingStrategy =
                        CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val mask =
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.00f to Color.Transparent,
                                    0.09f to Color.Black,
                                    0.84f to Color.Black,
                                    1.00f to Color.Transparent,
                                ),
                            startY = 0f,
                            endY = size.height,
                        )

                    onDrawWithContent {
                        drawContent()

                        drawRect(
                            brush = mask,
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
    ) {
        val profile =
            when {
                maxWidth.value <
                        LyraAnimationSpec.COMPACT_BREAKPOINT_PX ->

                    LyraResponsiveProfile(
                        fontSizePx =
                            LyraAnimationSpec
                                .COMPACT_FONT_SIZE_PX,

                        lineHeight =
                            1.20f,

                        lineSpacingPx =
                            LyraAnimationSpec
                                .COMPACT_LINE_SPACING_PX,

                        selectedPosition =
                            LyraAnimationSpec
                                .COMPACT_SELECTED_POSITION,

                        /*
                         * PlayerUi ya da margen horizontal.
                         */
                        inlinePaddingPx =
                            0f,
                    )

                maxWidth.value >=
                        LyraAnimationSpec.WIDE_BREAKPOINT_PX ->

                    LyraResponsiveProfile(
                        fontSizePx =
                            LyraAnimationSpec
                                .WIDE_FONT_SIZE_PX,

                        lineHeight =
                            1.17f,

                        lineSpacingPx =
                            LyraAnimationSpec
                                .WIDE_LINE_SPACING_PX,

                        selectedPosition =
                            LyraAnimationSpec
                                .WIDE_SELECTED_POSITION,

                        inlinePaddingPx =
                            32f,
                    )

                else ->

                    LyraResponsiveProfile(
                        fontSizePx =
                            LyraAnimationSpec
                                .BASE_FONT_SIZE_PX,

                        lineHeight =
                            LyraAnimationSpec
                                .LINE_HEIGHT,

                        lineSpacingPx =
                            LyraAnimationSpec
                                .LINE_SPACING_PX,

                        selectedPosition =
                            LyraAnimationSpec
                                .SCROLL_PADDING_TOP_FRACTION,

                        inlinePaddingPx =
                            LyraAnimationSpec
                                .INLINE_PADDING_PX,
                    )
            }

        val inlinePadding =
            profile.inlinePaddingPx.dp

        val lineSpacing =
            profile.lineSpacingPx.dp

        val fontSizeSp =
            profile.fontSizePx

        val availableWidth =
            (
                    maxWidth -
                            inlinePadding * 2
                    ).coerceAtLeast(
                    0.dp,
                )

        /*
         * Los puntos ocupan un hueco real dentro del flujo de la letra, no
         * un overlay. Así las líneas de abajo se apartan lo mismo que un
         * verso y recuperan su sitio cuando la animación termina.
         */
        val waitingGap =
            frame.instrumentalGap

        val dotsSlotHeight =
            LYRA_DOT_SLOT_HEIGHT +
                    (fontSizeSp * 0.4f * 2f).dp

        val duetGutter =
            maxOf(
                10.dp,
                availableWidth *
                        DUET_GUTTER_FRACTION,
            )

        val hasDuetLines =
            remember(lyrics) {
                lyrics.any {
                    it.oppositeAligned
                }
            }

        val selectedPositionPx =
            with(density) {
                maxHeight.toPx() *
                        profile.selectedPosition
            }

        val selectedPositionDp =
            with(density) {
                selectedPositionPx.toDp()
            }

        /*
         * ─────────────────────────────────────────
         * ENGINE -> SCROLL REQUEST
         * ─────────────────────────────────────────
         */
        LaunchedEffect(
            frame.scrollTargetIndex,
            frame.isSeek,
            frame.scrollLookAheadMs,
            userScrolling,
        ) {
            if (userScrolling) {
                return@LaunchedEffect
            }

            val targetIndex =
                frame.scrollTargetIndex
                    ?: return@LaunchedEffect

            if (
                targetIndex !in
                lyrics.indices
            ) {
                return@LaunchedEffect
            }

            if (
                targetIndex ==
                lastTargetIndex &&
                !frame.isSeek
            ) {
                return@LaunchedEffect
            }

            scrollRequests.trySend(
                LyraScrollRequest(
                    targetIndex =
                        targetIndex,

                    forceScroll =
                        frame.isSeek,

                    requestedDurationMs =
                        frame
                            .scrollLookAheadMs
                            .roundToInt(),
                ),
            )
        }

        /*
         * ─────────────────────────────────────────
         * FLIP PROCESSOR
         * ─────────────────────────────────────────
         */
        LaunchedEffect(
            profile.selectedPosition,
            maxHeight,
            animationsEnabled,
        ) {
            for (request in scrollRequests) {
                val targetIndex =
                    request.targetIndex

                if (
                    targetIndex !in
                    lyrics.indices
                ) {
                    continue
                }

                /*
                 * item 0 = top spacer.
                 * lyric 0 = item 1.
                 */
                val lazyIndex =
                    targetIndex + 1

                val desiredOffset =
                    selectedPositionPx
                        .roundToInt()

                if (request.forceScroll) {
                    programmaticScrolling =
                        true

                    try {
                        if (animationsEnabled) {
                            listState
                                .animateScrollToItem(
                                    index =
                                        lazyIndex,

                                    scrollOffset =
                                        -desiredOffset,
                                )
                        } else {
                            listState
                                .scrollToItem(
                                    index =
                                        lazyIndex,

                                    scrollOffset =
                                        -desiredOffset,
                                )
                        }

                        scrollEvent =
                            LyraScrollEvent()

                        lastTargetIndex =
                            targetIndex
                    } finally {
                        programmaticScrolling =
                            false
                    }

                    continue
                }

                val previousTarget =
                    lastTargetIndex

                val baseDurationMs =
                    request
                        .requestedDurationMs
                        .coerceIn(
                            100,
                            450,
                        )

                val delayIncrementMs =
                    baseDurationMs *
                            0.10f

                val visibleItem =
                    listState
                        .layoutInfo
                        .visibleItemsInfo
                        .firstOrNull {
                            it.index ==
                                    lazyIndex
                        }

                val viewportHeight =
                    listState
                        .layoutInfo
                        .viewportSize
                        .height

                val maxVisualDelta =
                    max(
                        with(density) {
                            MAX_VISUAL_DELTA_DP
                                .dp
                                .toPx()
                        },

                        viewportHeight *
                                0.75f,
                    )

                val scrollingDown =
                    when {
                        visibleItem !=
                                null ->

                            visibleItem
                                .offset
                                .toFloat() >=
                                    selectedPositionPx

                        previousTarget !=
                                null ->

                            targetIndex >
                                    previousTarget

                        else ->
                            true
                    }

                val visualDelta =
                    if (
                        visibleItem !=
                        null
                    ) {
                        (
                                visibleItem
                                    .offset
                                    .toFloat() -
                                        selectedPositionPx
                                )
                            .coerceIn(
                                -maxVisualDelta,
                                maxVisualDelta,
                            )
                    } else {
                        if (scrollingDown) {
                            maxVisualDelta
                        } else {
                            -maxVisualDelta
                        }
                    }

                /*
                 * Commit físico del LazyColumn.
                 */
                programmaticScrolling =
                    true

                listState.scrollToItem(
                    index =
                        lazyIndex,

                    scrollOffset =
                        -desiredOffset,
                )

                if (
                    !animationsEnabled ||
                    abs(visualDelta) <
                    1f
                ) {
                    scrollEvent =
                        LyraScrollEvent()

                    lastTargetIndex =
                        targetIndex

                    programmaticScrolling =
                        false

                    continue
                }

                val availableStaggerSteps =
                    if (scrollingDown) {
                        min(
                            MAX_STAGGER_STEPS,
                            lyrics.lastIndex -
                                    targetIndex,
                        )
                    } else {
                        min(
                            MAX_STAGGER_STEPS,
                            targetIndex,
                        )
                    }
                        .coerceAtLeast(0)

                val maximumDelayMs =
                    delayIncrementMs *
                            availableStaggerSteps

                val maxAnimationDurationMs =
                    (
                            baseDurationMs +
                                    EXTRA_SCROLL_SETTLE_MS +
                                    maximumDelayMs
                            )
                        .roundToInt()

                scrollEvent =
                    LyraScrollEvent(
                        id =
                            scrollEvent.id +
                                    1L,

                        deltaPx =
                            visualDelta,

                        referenceIndex =
                            targetIndex,

                        exitingIndex =
                            previousTarget
                                ?: -1,

                        scrollingDown =
                            scrollingDown,

                        baseDurationMs =
                            baseDurationMs,

                        maxAnimationDurationMs =
                            maxAnimationDurationMs,
                    )

                lastTargetIndex =
                    targetIndex

                delay(
                    maxAnimationDurationMs
                        .toLong(),
                )

                scrollEvent =
                    scrollEvent.copy(
                        exitingIndex =
                            -1,
                    )

                programmaticScrolling =
                    false
            }
        }

        /*
         * ─────────────────────────────────────────
         * LYRICS
         * ─────────────────────────────────────────
         *
         * SOLO letras.
         *
         * Los puntos van dentro, en un hueco real antes de la línea
         * siguiente.
         */
        LazyColumn(
            state =
                listState,

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal =
                            inlinePadding,
                    ),
        ) {
            item(
                key =
                    "lyra-top-padding",
            ) {
                Spacer(
                    modifier =
                        Modifier.height(
                            CONTAINER_TOP_PADDING_DP.dp,
                        ),
                )
            }

            itemsIndexed(
                items =
                    lyrics,

                key = { index,
                        line ->

                    "lyra-" +
                            "$index-" +
                            "${line.startMs}-" +
                            "${line.endMs}"
                },
            ) { index,
                line ->

                val lineFrame =
                    frame
                        .lines
                        .getOrNull(
                            index,
                        )
                        ?: return@itemsIndexed

                val visualActive =
                    lineFrame.active ||
                            (
                                    frame
                                        .activeLineIndices
                                        .isEmpty() &&
                                            frame.instrumentalGap ==
                                            null &&
                                            (
                                                    frame.focusLineIndex ==
                                                            index ||
                                                            (
                                                                    frame.focusLineIndex ==
                                                                            null &&
                                                                            frame.scrollTargetIndex ==
                                                                            index
                                                                    )
                                                    )
                                    )

                val visualPreActive =
                    !visualActive &&
                            (
                                    lineFrame.preActive ||
                                            (
                                                    frame.instrumentalGap ==
                                                            null &&
                                                            frame.scrollTargetIndex ==
                                                            index
                                                    )
                                    )

                val actuallyScrollExiting =
                    scrollEvent.exitingIndex ==
                            index &&
                            !visualActive &&
                            !visualPreActive

                /*
                 * IMPORTANTE:
                 *
                 * El FLIP sigue siendo POR LÍNEA.
                 * No lo cambiamos.
                 */
                LyraScrollAnimatedLine(
                    index =
                        index,

                    event =
                        scrollEvent,

                    animationsEnabled =
                        animationsEnabled &&
                                !userScrolling,
                ) {
                    Column {
                        waitingGap
                            ?.takeIf {
                                it.insertBeforeIndex == index
                            }
                            ?.let { gapForThisLine ->
                                LyraWaitingDotsSlot(
                                    gap = gapForThisLine,
                                    positionMs = frame.positionMs,
                                    isPlaying = isPlaying,
                                    simpleStyle =
                                    animationLevel == LyricAnimationLevel.Simple,
                                    slotHeight = dotsSlotHeight,
                                )
                            }

                        LyraLyricLine(
                            line =
                                line,

                            frame =
                                lineFrame,

                            visualActive =
                                visualActive,

                            visualPreActive =
                                visualPreActive,

                            scrollExiting =
                                actuallyScrollExiting,

                            fontSizeSp =
                                fontSizeSp,

                            lineHeightMultiplier =
                                profile.lineHeight,

                            lineSpacing =
                                lineSpacing,

                            duetGutter =
                                duetGutter,

                            hasDuetLines =
                                hasDuetLines,

                            nextLineBlurEm =
                                frame.nextLineBlurEm,

                            animationsEnabled =
                                animationsEnabled,

                            wordByWordEnabled =
                                wordByWordEnabled,

                            animationLevel =
                                animationLevel,

                            positionMs =
                                frame.positionMs,

                            isPlaying =
                                isPlaying,

                            showRomanization =
                                showRomanization,

                            showTranslation =
                                showTranslation,

                            onSeek =
                                onSeek,
                        )
                    }
                }
            }

            item(
                key =
                    "lyra-bottom-padding",
            ) {
                Spacer(
                    modifier =
                        Modifier.height(
                            maxHeight *
                                    (
                                            1f -
                                                    profile.selectedPosition
                                            ) +
                                    END_SCROLL_SAFETY_PADDING_DP.dp,
                        ),
                )
            }
        }
    }
}
/*
 * ─────────────────────────────────────────────
 * HUECO DE LOS PUNTOS DE ESPERA
 * ─────────────────────────────────────────────
 */
@Composable
private fun LyraWaitingDotsSlot(
    gap: LyraInstrumentalGap,
    positionMs: Double,
    isPlaying: Boolean,
    /** true cuando el nivel de animación es Simple: coreografía de AMLL. */
    simpleStyle: Boolean,
    slotHeight: Dp,
) {
    /*
     * La animación de los puntos termina LYRA_DOTS_EXIT_LEAD_OUT_MS antes de
     * la siguiente línea. En ese margen el hueco se pliega, de modo que la
     * letra recupera su posición justo antes de empezar a sonar.
     */
    val visible =
        positionMs <
                gap.endMs -
                LYRA_DOTS_EXIT_LEAD_OUT_MS

    val density =
        LocalDensity.current

    val slotHeightPx =
        with(density) {
            slotHeight.toPx()
        }

    /*
     * No usamos AnimatedVisibility: su recorte cortaba el primer punto
     * cuando el grupo crecía a 1.25x. Animamos solo la altura y dejamos que
     * el canvas respire fuera de la caja (wrapContentHeight sin límite).
     */
    val animatedHeightPx =
        remember {
            Animatable(0f)
        }

    LaunchedEffect(
        visible,
        slotHeightPx,
    ) {
        animatedHeightPx.animateTo(
            targetValue =
                if (visible) {
                    slotHeightPx
                } else {
                    0f
                },

            animationSpec =
                tween(
                    durationMillis =
                        if (visible) {
                            260
                        } else {
                            LYRA_DOTS_EXIT_LEAD_OUT_MS
                                .toInt()
                        },
                ),
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    with(density) {
                        animatedHeightPx.value.toDp()
                    },
                )
                .wrapContentHeight(
                    align =
                        Alignment.CenterVertically,

                    unbounded =
                        true,
                ),

        contentAlignment =
            Alignment.CenterStart,
    ) {
        LyraWaitingDots(
            gap =
                gap,

            positionMs =
                positionMs,

            isPlaying =
                isPlaying,

            simpleStyle =
                simpleStyle,
        )
    }
}

/*
 * ─────────────────────────────────────────────
 * FLIP POR LÍNEA
 * ─────────────────────────────────────────────
 */
@Composable
private fun LyraScrollAnimatedLine(
    index: Int,
    event: LyraScrollEvent,
    animationsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val initialTranslation =
        if (
            animationsEnabled &&
            event.id != 0L &&
            event.referenceIndex >=
            0
        ) {
            event.deltaPx
        } else {
            0f
        }

    val translationYAnim =
        remember(
            index,
            event.id,
        ) {
            Animatable(
                initialValue =
                    initialTranslation,
            )
        }

    LaunchedEffect(
        event.id,
        animationsEnabled,
    ) {
        if (
            !animationsEnabled ||
            event.id == 0L ||
            event.referenceIndex <
            0
        ) {
            return@LaunchedEffect
        }

        val distance =
            abs(
                index -
                        event.referenceIndex,
            )

        if (
            distance >
            SCROLL_LOOKAHEAD_LINES
        ) {
            translationYAnim.snapTo(
                0f,
            )

            return@LaunchedEffect
        }

        val staggerStep =
            when {
                event.scrollingDown &&
                        index >=
                        event.referenceIndex ->

                    min(
                        index -
                                event.referenceIndex,

                        MAX_STAGGER_STEPS,
                    )

                !event.scrollingDown &&
                        index <=
                        event.referenceIndex ->

                    min(
                        event.referenceIndex -
                                index,

                        MAX_STAGGER_STEPS,
                    )

                else ->
                    0
            }

        val delayMillis =
            (
                    event.baseDurationMs *
                            0.10f *
                            staggerStep
                    )
                .roundToInt()

        val animationDurationMs =
            max(
                MIN_SCROLL_LINE_DURATION_MS,

                event
                    .maxAnimationDurationMs -
                        delayMillis,
            )

        if (
            delayMillis >
            0
        ) {
            delay(
                delayMillis.toLong(),
            )
        }

        translationYAnim.animateTo(
            targetValue =
                0f,

            animationSpec =
                tween(
                    durationMillis =
                        animationDurationMs,

                    easing =
                        LyraScrollEasing,
                ),
        )
    }

    Box(
        modifier =
            Modifier.graphicsLayer {
                translationY =
                    translationYAnim.value
            },
    ) {
        content()
    }
}

private data class LyraResponsiveProfile(
    val fontSizePx: Float,
    val lineHeight: Float,
    val lineSpacingPx: Float,
    val selectedPosition: Float,
    val inlinePaddingPx: Float,
)

private data class LyraScrollEvent(
    val id: Long = 0L,
    val deltaPx: Float = 0f,
    val referenceIndex: Int = -1,
    val exitingIndex: Int = -1,
    val scrollingDown: Boolean = true,
    val baseDurationMs: Int = 350,
    val maxAnimationDurationMs: Int = 350,
)

private data class LyraScrollRequest(
    val targetIndex: Int,
    val forceScroll: Boolean,
    val requestedDurationMs: Int,
)

private const val DUET_GUTTER_FRACTION =
    0.15f

private const val CONTAINER_TOP_PADDING_DP =
    60f

private const val MAX_VISUAL_DELTA_DP =
    120f

private const val SCROLL_LOOKAHEAD_LINES =
    20

private const val MAX_STAGGER_STEPS =
    4

private const val EXTRA_SCROLL_SETTLE_MS =
    100f

private const val MIN_SCROLL_LINE_DURATION_MS =
    100

private const val USER_SCROLL_RESUME_DELAY_MS =
    5_000L

private const val END_SCROLL_SAFETY_PADDING_DP =
    16f
