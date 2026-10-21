package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liora.android.data.model.LyricLine
import com.liora.android.data.prefs.LyricAnimationLevel
import com.liora.android.ui.lyrics.lyrics.lyra.animation.LyraAnimationSpec
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraLineFrame
import kotlin.math.abs

@Composable
internal fun LyraLyricLine(
    line: LyricLine,

    lineHeightMultiplier: Float =
        LyraAnimationSpec.LINE_HEIGHT,

    scrollExiting: Boolean =
        false,

    frame: LyraLineFrame,

    /*
     * Estado VISUAL.
     *
     * Normalmente coincide con frame.active,
     * pero permite mantener el foco durante
     * pequeños huecos del engine.
     */
    visualActive: Boolean =
        frame.active,

    visualPreActive: Boolean =
        frame.preActive,

    fontSizeSp: Float,
    lineSpacing: Dp,
    duetGutter: Dp,
    hasDuetLines: Boolean,
    nextLineBlurEm: Float,
    animationsEnabled: Boolean,
    wordByWordEnabled: Boolean = true,
    animationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    /** Reloj continuo del engine: el fuelle del motor Exagerado. */
    positionMs: Double = 0.0,
    isPlaying: Boolean = false,
    showRomanization: Boolean = false,
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density =
        LocalDensity.current

    val interactionSource =
        remember {
            MutableInteractionSource()
        }

    val pressed by
    interactionSource
        .collectIsPressedAsState()

    val distance =
        abs(
            frame.distanceFromFocus,
        )

    // ─────────────────────────────────────────────
    // Opacidad
    // ─────────────────────────────────────────────

    val targetOpacity =
        when {
            /*
             * La saliente empieza a perder protagonismo.
             */
            scrollExiting ->
                0.80f

            /*
             * La activa visual SIEMPRE es sólida.
             */
            visualActive ->
                1f

            /*
             * Pre-active mantiene opacity completa,
             * aunque todavía conserve color secundario.
             */
            visualPreActive ->
                1f

            distance == 2 ->
                LyraAnimationSpec
                    .DISTANCE_2_OPACITY

            distance == 3 ->
                LyraAnimationSpec
                    .DISTANCE_3_OPACITY

            distance == 4 ->
                LyraAnimationSpec
                    .DISTANCE_4_OPACITY

            else ->
                LyraAnimationSpec
                    .BASE_LINE_OPACITY
        }

    val opacity by
    animateFloatAsState(
        targetValue =
            targetOpacity,

        animationSpec =
            if (animationsEnabled) {
                tween(
                    durationMillis =
                        LyraAnimationSpec
                            .LINE_FADE_DURATION_MS,

                    easing =
                        CssEase,
                )
            } else {
                snap()
            },

        label =
            "LyraLineOpacity",
    )

    // ─────────────────────────────────────────────
    // Color
    // ─────────────────────────────────────────────

    /*
     * Solo ACTIVE llega a blanco puro.
     *
     * Pre-active conserva el color secundario
     * mientras empieza a crecer/desenfocarse.
     */
    val targetColorAlpha =
        if (
            visualActive &&
            !scrollExiting
        ) {
            1f
        } else {
            0.72f
        }

    val colorAlpha by
    animateFloatAsState(
        targetValue =
            targetColorAlpha,

        animationSpec =
            if (animationsEnabled) {
                tween(
                    durationMillis =
                        LyraAnimationSpec
                            .LINE_FADE_DURATION_MS,

                    easing =
                        CssEase,
                )
            } else {
                snap()
            },

        label =
            "LyraLineColor",
    )

    val textColor =
        Color.White.copy(
            alpha =
                colorAlpha,
        )

    // ─────────────────────────────────────────────
    // Escala
    // ─────────────────────────────────────────────

    val scaleFocused =
        !scrollExiting &&
                (
                        visualActive ||
                                visualPreActive
                        )

    val restingScale =
        if (scaleFocused) {
            LyraAnimationSpec
                .ACTIVE_LINE_SCALE
        } else {
            LyraAnimationSpec
                .INACTIVE_LINE_SCALE
        }

    val targetScale =
        if (pressed) {
            LyraAnimationSpec
                .TOUCH_SCALE
        } else {
            restingScale
        }

    val scaleDuration =
        when {
            pressed ->
                LyraAnimationSpec
                    .TOUCH_SCALE_DURATION_MS

            scaleFocused ->
                LyraAnimationSpec
                    .LINE_ACTIVE_SCALE_DURATION_MS

            else ->
                LyraAnimationSpec
                    .LINE_INACTIVE_SCALE_DURATION_MS
        }

    val scale by
    animateFloatAsState(
        targetValue =
            targetScale,

        animationSpec =
            if (animationsEnabled) {
                tween(
                    durationMillis =
                        scaleDuration,

                    easing =
                        CssEase,
                )
            } else {
                snap()
            },

        label =
            "LyraLineScale",
    )

    // ─────────────────────────────────────────────
    // Blur
    // ─────────────────────────────────────────────

    val targetBlurEm =
        when {
            scrollExiting ->
                LyraAnimationSpec
                    .NEAR_INACTIVE_BLUR_EM

            /*
             * Active y pre-active quedan nítidas.
             */
            visualActive ||
                    visualPreActive ->
                0f

            frame.distanceFromFocus ==
                    -1 ->
                LyraAnimationSpec
                    .NEAR_INACTIVE_BLUR_EM

            frame.distanceFromFocus ==
                    1 ->
                nextLineBlurEm

            frame.distanceFromFocus ==
                    2 ->
                LyraAnimationSpec
                    .NEXT_2_BLUR_EM

            frame.distanceFromFocus ==
                    3 ->
                LyraAnimationSpec
                    .NEXT_3_BLUR_EM

            else ->
                LyraAnimationSpec
                    .INACTIVE_BLUR_EM
        }

    val blurEm by
    animateFloatAsState(
        targetValue =
            targetBlurEm,

        animationSpec =
            if (animationsEnabled) {
                tween(
                    durationMillis =
                        LyraAnimationSpec
                            .LINE_ACTIVE_SCALE_DURATION_MS,

                    easing =
                        CssEase,
                )
            } else {
                snap()
            },

        label =
            "LyraLineBlur",
    )

    val blurRadius =
        with(density) {
            (
                    fontSizeSp.sp.toPx() *
                            blurEm
                    ).toDp()
        }

    // ─────────────────────────────────────────────
    // Seguridad para scale 1.04
    // ─────────────────────────────────────────────

    val normalScaleSafetyPadding =
        14.dp

    val oppositeScaleSafetyPadding =
        22.dp

    val startPadding =
        if (
            line.oppositeAligned
        ) {
            oppositeScaleSafetyPadding +
                    if (
                        hasDuetLines
                    ) {
                        duetGutter
                    } else {
                        0.dp
                    }
        } else {
            /*
             * Alineado con la portada.
             */
            0.dp
        }

    val endPadding =
        if (
            !line.oppositeAligned
        ) {
            normalScaleSafetyPadding +
                    if (
                        hasDuetLines
                    ) {
                        duetGutter
                    } else {
                        0.dp
                    }
        } else {
            0.dp
        }

    // ─────────────────────────────────────────────
    // Origen de escala
    // ─────────────────────────────────────────────

    val transformOrigin =
        if (
            line.oppositeAligned
        ) {
            TransformOrigin(
                pivotFractionX =
                    1f,

                pivotFractionY =
                    0.5f,
            )
        } else {
            TransformOrigin(
                pivotFractionX =
                    0f,

                pivotFractionY =
                    0.5f,
            )
        }

    // ─────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────

    val textAlign =
        if (line.oppositeAligned) {
            TextAlign.End
        } else {
            TextAlign.Start
        }

    val romanization =
        remember(line) {
            line.romanizationText()
        }

    val translation =
        remember(line) {
            line.translationText()
        }

    /*
     * El word-by-word de AMLL solo tiene sentido mientras la línea está
     * enfocada. Las líneas lejanas conservan su color de reposo para no
     * multiplicar capas ni trabajo de dibujo.
     */
    val wordHighlight =
        wordByWordEnabled &&
                (
                        visualActive ||
                                visualPreActive ||
                                scrollExiting
                        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()

                .padding(
                    start =
                        startPadding,

                    end =
                        endPadding,

                    bottom =
                        lineSpacing,
                )

                /*
                 * Unbounded evita que se muerdan
                 * los bordes de los caracteres.
                 */
                .blur(
                    radius =
                        blurRadius,

                    edgeTreatment =
                        BlurredEdgeTreatment.Unbounded,
                )

                .graphicsLayer {
                    alpha =
                        opacity

                    scaleX =
                        scale

                    scaleY =
                        scale

                    this.transformOrigin =
                        transformOrigin
                }

                .clickable(
                    interactionSource =
                        interactionSource,

                    indication =
                        null,
                ) {
                    onSeek(
                        line.startMs
                            .coerceAtLeast(
                                0L,
                            ),
                    )
                },

        horizontalAlignment =
            if (line.oppositeAligned) {
                Alignment.End
            } else {
                Alignment.Start
            },
    ) {
        if (animationLevel == LyricAnimationLevel.Exaggerated) {
            /*
             * Exagerada: los sprites nativos de spicy —letras con física,
             * splits que se abren y resplandor— pintan la línea completa.
             */
            LyraWaveText(
                text =
                    line.text,

                words =
                    line.words,

                positionMs =
                    positionMs,

                isPlaying =
                    isPlaying,

                dimColor =
                    Color.White.copy(
                        alpha = 0.46f,
                    ),

                brightColor =
                    Color.White,

                fallbackColor =
                    textColor,

                fontSize =
                    fontSizeSp.sp,

                lineHeight =
                    (
                            fontSizeSp *
                                    lineHeightMultiplier
                            ).sp,

                fontWeight =
                    FontWeight.Bold,

                textAlign =
                    textAlign,
            )
        } else {
            LyraWordMaskText(
                text =
                    line.text,

                words =
                    line.words,

                frames =
                    frame.words,

                wordByWord =
                    wordHighlight,

                dimColor =
                    Color.White.copy(
                        alpha = 0.46f,
                    ),

                brightColor =
                    Color.White,

                fallbackColor =
                    textColor,

                fontSize =
                    fontSizeSp.sp,

                lineHeight =
                    (
                            fontSizeSp *
                                    lineHeightMultiplier
                            ).sp,

                fontWeight =
                    FontWeight.Bold,

                textAlign =
                    textAlign,
            )
        }

        /*
         * Coros y voces de fondo. AMLL los pinta más pequeños y tenues,
         * también con su propio avance por palabra. En Exagerada llevan la
         * misma ola de la línea principal —spicy anima sus bg-word con los
         * mismos muelles de palabra, no con un relleno plano—.
         */
        line.background.forEachIndexed { index, background ->
            val backgroundFrame =
                frame.backgrounds.getOrNull(index)

            val backgroundActive =
                backgroundFrame?.active == true

            if (animationLevel == LyricAnimationLevel.Exaggerated) {
                LyraWaveText(
                    text =
                        background.text,

                    words =
                        background.words,

                    positionMs =
                        positionMs,

                    isPlaying =
                        isPlaying,

                    dimColor =
                        Color.White.copy(
                            alpha = 0.34f,
                        ),

                    brightColor =
                        Color.White.copy(
                            alpha = 0.92f,
                        ),

                    fallbackColor =
                        Color.White.copy(
                            alpha =
                                    if (backgroundActive) {
                                        0.70f
                                    } else {
                                        0.40f
                                    },
                        ),

                    fontSize =
                        (
                                fontSizeSp *
                                        0.56f
                                ).sp,

                    lineHeight =
                        (
                                fontSizeSp *
                                        0.72f
                                ).sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    textAlign =
                        if (background.oppositeAligned) {
                            TextAlign.End
                        } else {
                            textAlign
                        },

                    modifier =
                        Modifier.padding(
                            top = 1.dp,
                        ),
                )
            } else {
                LyraWordMaskText(
                text =
                    background.text,

                words =
                    background.words,

                frames =
                    backgroundFrame?.words.orEmpty(),

                wordByWord =
                    wordHighlight,

                dimColor =
                    Color.White.copy(
                        alpha = 0.30f,
                    ),

                brightColor =
                    Color.White.copy(
                        alpha = 0.92f,
                    ),

                fallbackColor =
                    Color.White.copy(
                        alpha =
                            if (backgroundActive) {
                                0.76f
                            } else {
                                0.40f
                            },
                    ),

                fontSize =
                    (
                            fontSizeSp *
                                    0.56f
                            ).sp,

                lineHeight =
                    (
                            fontSizeSp *
                                    0.72f
                            ).sp,

                fontWeight =
                    FontWeight.SemiBold,

                textAlign =
                    if (background.oppositeAligned) {
                        TextAlign.End
                    } else {
                        textAlign
                    },

                modifier =
                    Modifier.padding(
                        top = 1.dp,
                    ),
                )
            }
        }

        if (showRomanization && romanization != null) {
            LyraSecondaryLyricText(
                text =
                    romanization,

                color =
                    Color.White.copy(
                        alpha = 0.58f,
                    ),

                fontSizeSp =
                    fontSizeSp,

                textAlign =
                    textAlign,
            )
        }

        if (showTranslation && translation != null) {
            LyraSecondaryLyricText(
                text =
                    translation,

                color =
                    Color.White.copy(
                        alpha = 0.64f,
                    ),

                fontSizeSp =
                    fontSizeSp,

                textAlign =
                    textAlign,
            )
        }
    }
}

@Composable
private fun LyraSecondaryLyricText(
    text: String,
    color: Color,
    fontSizeSp: Float,
    textAlign: TextAlign,
) {
    Text(
        text =
            text,

        color =
            color,

        fontSize =
            (
                    fontSizeSp *
                            0.54f
                    ).sp,

        lineHeight =
            (
                    fontSizeSp *
                            0.70f
                    ).sp,

        fontWeight =
            FontWeight.Medium,

        textAlign =
            textAlign,

        maxLines =
            3,

        overflow =
            TextOverflow.Ellipsis,

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    top = 1.dp,
                ),
    )
}

private val CssEase =
    CubicBezierEasing(
        0.25f,
        0.10f,
        0.25f,
        1.00f,
    )

internal val LyraScrollEasing =
    CubicBezierEasing(
        0.41f,
        0.00f,
        0.12f,
        0.99f,
    )