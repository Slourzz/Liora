package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.liora.android.data.model.LyricWord
import com.liora.android.ui.lyrics.SpicyElementState
import com.liora.android.ui.lyrics.SpicySpring
import com.liora.android.ui.lyrics.SpicySpline
import com.liora.android.ui.lyrics.SpicySplinePoint
import com.liora.android.ui.lyrics.getSpicyElementState
import com.liora.android.ui.lyrics.getSpicyProgressPercentage
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Letras Exageradas: el motor de spicy-lyrics recreado con primitivas nativas
 * de Compose/Kotlin —sin portear ni una línea de DOM.
 *
 * Cada elemento animable es un sprite que se dibuja a mano:
 *
 * - Las palabras que aguantan un segundo o más se reparten en letras (el
 *   muestreo por letras de spicy: ventanas equitativas y 250 ms de recorte
 *   al final) y cada letra se hincha, flota y brilla con muelles propios,
 *   con un desvanecido en la distancia a la letra activa —la ola.
 * - Las palabras cortas son un sprite entero que crece, flota y brilla con
 *   los muelles de palabra.
 * - Los splits del TTML se quedan partidos, tal cual el modo por defecto de
 *   spicy (`joinSplitWords` en falso): "Ba" y "by" animan cada una con sus
 *   muelles y su relleno. La apariencia de que se abren —"lub" hacia la
 *   izquierda y "dub" hacia la derecha— no es un traslado: es el pivote de
 *   la escala. spicy ancla las partes con `transform-origin` (la primera
 *   parte desde su filo derecho, la parte que sigue a otra desde su filo
 *   izquierdo y las centrales desde el centro), y su muelle de palabra
 *   (0.95 → 1.0505) se abre hacia esos filos. Aquí se replica pivoteando
 *   cada parte por su anclaje.
 *
 * La geometría sale del layout del propio Texto (los huecos por grafema son
 * los de `getBoundingBox`), así la tipografía, la altura de línea y los
 * saltos de renglón coinciden con la Normal.
 */
@Composable
internal fun LyraWaveText(
    text: String,
    words: List<LyricWord>,
    /** Reloj continuo del engine (`frame.positionMs`). */
    positionMs: Double,
    isPlaying: Boolean,
    dimColor: Color,
    brightColor: Color,
    fallbackColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fillWidth: Boolean = true,
) {
    val model =
        remember(
            text,
            words,
        ) {
            buildLyraWaveModel(
                text = text,
                words = words,
            )
        }

    /*
     * Si la fuente no trae tiempos por palabra, el texto queda plano con el
     * color de siempre: la línea se ve igual que sin motor.
     */
    if (
        model ==
        null
    ) {
        Text(
            text = text,
            color = fallbackColor,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            modifier =
                modifier.then(
                    if (fillWidth) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                    },
                ),
        )

        return
    }

    val typographyStyle =
        MaterialTheme.typography
            .headlineLarge

    val waveTextStyle =
        remember(
            typographyStyle,
            fontWeight,
            fontSize,
            lineHeight,
        ) {
            typographyStyle.copy(
                fontWeight = fontWeight,
                fontSize = fontSize,
                lineHeight = lineHeight,
            )
        }

    val textMeasurer =
        rememberTextMeasurer()

    /*
     * El dibujo de cada letra usa layouts medidos UNA vez con el mismo
     * estilo del Texto; por fotograma no se mide nada.
     */
    val glyphLayouts =
        remember(
            model,
            waveTextStyle,
        ) {
            buildLyraWaveGlyphLayouts(
                model = model,
                measurer = textMeasurer,
                style = waveTextStyle,
            )
        }

    var textLayout by
    remember {
        mutableStateOf<TextLayoutResult?>(
            null,
        )
    }

    val spriteBoxes =
        remember(
            textLayout,
            model,
        ) {
            textLayout?.let { layout ->
                model.sprites.map { sprite ->
                    lyraWaveSpriteBox(
                        layout = layout,
                        sprite = sprite,
                    )
                }
            }
        }

    val animator =
        remember(
            model,
        ) {
            LyraWaveAnimator(
                model = model,
            )
        }

    var waveFrame by
    remember(
        animator,
    ) {
        mutableStateOf<FloatArray?>(
            null,
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

    LaunchedEffect(
        positionMs,
        isPlaying,
    ) {
        anchorPositionMs =
            positionMs

        anchorNanos =
            0L
    }

    /*
     * El loop de muelles vive aparte, igual que el de los puntos de espera:
     * sigue corriendo en pausa para que los muelles encuentren su reposo y
     * nada quede flotando a mitad del aire.
     */
    LaunchedEffect(
        animator,
    ) {
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

            waveFrame =
                animator.step(
                    positionMs =
                        projectedPositionMs,

                    deltaSeconds =
                        deltaSeconds.coerceIn(
                            0.0,
                            LYRA_WAVE_MAX_FRAME_DELTA_SECONDS,
                        ),
                )
        }
    }

    val widthModifier =
        if (fillWidth) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .then(widthModifier)
                .drawBehind {
                    val frame =
                        waveFrame

                    val boxes =
                        spriteBoxes

                    if (
                        frame !=
                        null &&
                        boxes !=
                        null
                    ) {
                        drawLyraWaveSprites(
                            frame = frame,
                            model = model,
                            boxes = boxes,
                            glyphLayouts = glyphLayouts,
                            dimColor = dimColor,
                            brightColor = brightColor,
                            fontSize = fontSize,
                        )
                    }
                },
    ) {
        /*
         * El Texto tenue nunca se ve: aporta el layout (los huecos por
         * grafema salen de él) y la semántica de la línea.
         */
        Text(
            text = text,
            color =
                Color.Transparent,
            style =
                MaterialTheme.typography
                    .headlineLarge,
            fontWeight =
                fontWeight,
            fontSize =
                fontSize,
            lineHeight =
                lineHeight,
            textAlign =
                textAlign,
            maxLines =
                maxLines,
            overflow =
                overflow,
            onTextLayout = { layout ->
                textLayout =
                    layout
            },
            modifier =
                widthModifier,
        )
    }
}

/* ─────────────────────────────────────────────
 * CONSTANTES DEL MOTOR — spicy-lyrics Main.ts
 * ─────────────────────────────────────────────
 */

/** Techo del paso del muelle por fotograma, para que un tirón no rompa nada. */
private const val LYRA_WAVE_MAX_FRAME_DELTA_SECONDS =
    0.08

/** La palabra más corta que se reparte en letras (IsLetterCapable, MinDuration). */
private const val LYRA_WAVE_LETTER_MIN_DURATION_MS =
    1000.0

/** Recorte del final del muestreo por letras (Substractions.EndTime = 250). */
private const val LYRA_WAVE_LETTER_END_TRIM_MS =
    250L

/*
 * Reposo en escala 1 (decisión Lyra): las líneas Exageradas en espera se ven
 * igual que las de la Normal —sin salto al enfocar—. La amplitud del pop se
 * conserva relativa: los picos son los de spicy (0.95 → 1.0505 / 1.175),
 * re-anclados desde el reposo 1.
 */

/** Escala de palabra: reposo 1, pico en 70%, vuelta a 1 al terminar. */
private val WaveWordScaleSpline =
    SpicySpline(
        listOf(
            SpicySplinePoint(time = 0.0, value = 1.0),
            SpicySplinePoint(time = 0.7, value = 1.1005),
            SpicySplinePoint(time = 1.0, value = 1.0),
        ),
    )

/** Flotación de palabra: sube y regresa (amplitud 1/100 → −1/60 em). */
private val WaveWordYOffsetSpline =
    SpicySpline(
        listOf(
            SpicySplinePoint(time = 0.0, value = 0.0),
            SpicySplinePoint(time = 0.9, value = -0.0267),
            SpicySplinePoint(time = 1.0, value = 0.0),
        ),
    )

/** Escala por letra: el pop de la ola (equivalente 0.95 → 1.175). */
private val WaveLetterScaleSpline =
    SpicySpline(
        listOf(
            SpicySplinePoint(time = 0.0, value = 1.0),
            SpicySplinePoint(time = 0.7, value = 1.225),
            SpicySplinePoint(time = 1.0, value = 1.0),
        ),
    )

/** Flotación por letra (amplitud 1/100 → −1/56 em). */
private val WaveLetterYOffsetSpline =
    SpicySpline(
        listOf(
            SpicySplinePoint(time = 0.0, value = 0.0),
            SpicySplinePoint(time = 0.9, value = -0.0279),
            SpicySplinePoint(time = 1.0, value = 0.0),
        ),
    )

/** Brillo: sube en 15%, aguanta hasta 60% y se apaga al terminar (GlowRange). */
private val WaveGlowSpline =
    SpicySpline(
        listOf(
            SpicySplinePoint(time = 0.0, value = 0.0),
            SpicySplinePoint(time = 0.15, value = 1.0),
            SpicySplinePoint(time = 0.6, value = 1.0),
            SpicySplinePoint(time = 1.0, value = 0.0),
        ),
    )

/** Muelles de palabra (createWordSprings) y por letra (createLetterSprings). */
private const val LYRA_WAVE_SCALE_FREQUENCY =
    0.88

private const val LYRA_WAVE_SCALE_DAMPING =
    0.64

private const val LYRA_WAVE_Y_OFFSET_FREQUENCY =
    1.45

private const val LYRA_WAVE_Y_OFFSET_DAMPING =
    0.4

private const val LYRA_WAVE_GLOW_FREQUENCY =
    1.18

private const val LYRA_WAVE_GLOW_DAMPING =
    0.56

/** Resplandor de letras cantadas sin letra activa (SungLetterGlow). */
private const val LYRA_WAVE_SUNG_GLOW =
    0.2

/*
 * Resplandor nativo: spicy lo pinta con text-shadow (letras: blur 4+12·glow px
 * a glow·185%; palabras: 4±2 px a glow·35%). Aquí es un anillo de copias
 * desplazadas del propio dibujo del glifo.
 */
private const val LYRA_WAVE_LETTER_GLOW_ALPHA =
    1.85

private const val LYRA_WAVE_LETTER_GLOW_STRENGTH =
    0.5f

private const val LYRA_WAVE_WORD_GLOW_ALPHA =
    0.35

private const val LYRA_WAVE_WORD_GLOW_STRENGTH =
    0.9f

/** Radio del halo por letra: 0.08 em de base + 0.20 em por glow. */
private const val LYRA_WAVE_LETTER_GLOW_BASE_EM =
    0.08

private const val LYRA_WAVE_LETTER_GLOW_SPAN_EM =
    0.20

/** Radio del halo por palabra, más corto. */
private const val LYRA_WAVE_WORD_GLOW_BASE_EM =
    0.06

private const val LYRA_WAVE_WORD_GLOW_SPAN_EM =
    0.1

/** Copias del anillo exterior (8 rumbos) e interior (4 rumbos al 55%). */
private const val LYRA_WAVE_GLOW_OUTER_COPIES =
    8

private const val LYRA_WAVE_GLOW_INNER_COPIES =
    4

private const val LYRA_WAVE_GLOW_INNER_RATIO =
    0.55

/** Umbral bajo el cual el resplandor no se dibuja. */
private const val LYRA_WAVE_GLOW_DRAW_THRESHOLD =
    0.03

/**
 * Relleno por letra: la aproximación nativa del gradiente deslizante de
 * spicy (`-20% + 120% · easeSinOut(p)`), que sobre un glifo de una letra se
 * lee igual que un baño de luz creciente.
 */
private fun waveEaseSinOut(
    progress: Double,
): Double {
    val fixed =
        progress.coerceIn(
            0.0,
            1.0,
        )

    return sin(
        fixed *
                PI /
                2.0,
    )
}

/* ─────────────────────────────────────────────
 * ANCLAJE DE PARTES — transform-origin de spicy
 * ─────────────────────────────────────────────
 */

/** Anclaje del pivote de escala de un sprite (el `transform-origin` de spicy). */
private enum class WaveAnchor {
    Left,
    Center,
    Right,
}

/**
 * Ancla de cada palabra del renglón, con las reglas de cascada del CSS en
 * el renderer de spicy v6.4.50:
 *
 * - todo sprite arranca centrado;
 * - una parte marcada `IsPartOfWord` —y que no cierre el renglón: a la
 *   palabra final del renglón spicy le pone `LastWordInLine` y no la
 *   categoría— pivota desde su filo derecho;
 * - una parte precedida de otra parte es "en medio de la palabra", y pivota
 *   centrada (la cascada pisa el filo derecho);
 * - una palabra NORMAL que sigue a una parte pivota desde su filo
 *   izquierdo: es "la cola del split" ('by' tras 'Ba', 'dub' tras 'Lub-').
 *
 * La pwd cruda del TTML es `joinWithNext` (el atributo original de la
 * sílaba, "se pega a la siguiente").
 */
private fun waveSpriteAnchor(
    words: List<LyricWord>,
    index: Int,
): WaveAnchor {
    val selfIsPart =
        words[index].joinWithNext &&
                index !=
                words.lastIndex

    val prevIsPart =
        index >
                0 &&
                words[index - 1].joinWithNext &&
                index - 1 !=
                words.lastIndex

    return when {
        selfIsPart && prevIsPart -> WaveAnchor.Center
        selfIsPart -> WaveAnchor.Right
        else -> if (prevIsPart) {
            WaveAnchor.Left
        } else {
            WaveAnchor.Center
        }
    }
}

/** Salto de línea raro: versiones RTL de spicy no reparten letras (LT). */
private fun isRtlText(
    text: String,
): Boolean {
    val rtlScriptRegexChars: List<CharRange> =
        listOf(
            '\u0590'..'\u05FF',
            '\uFB1D'..'\uFB4F',
            '\u0600'..'\u06FF',
            '\u0750'..'\u077F',
            '\u08A0'..'\u08FF',
            '\uFB50'..'\uFDFF',
            '\uFE70'..'\uFEFF',
        )

    /*
     * La prueba de spicy mira el primer carácter que no es dígito, espacio
     * ni signo de puntuación; el resto del texto no importa.
     */
    val neutralChars =
        "1234567890 \t\n,.;:?!()[]{}\"'\\/<>@#$%^&*_=+-"

    for (character in text) {
        if (character in neutralChars) {
            continue
        }

        return rtlScriptRegexChars.any { range ->
            character in range
        }
    }

    return false
}

/* ─────────────────────────────────────────────
 * MODELO
 * ─────────────────────────────────────────────
 */

/** Un sprite dibujable: una letra del muestreo o una palabra entera. */
private class LyraWaveSprite(
    val text: String,
    /** Hueco del sprite dentro de `line.text`. */
    val charStart: Int,
    val charEnd: Int,
    /** Ventana de tiempo: de la letra en el muestreo, de la palabra si no. */
    val startMs: Long,
    val endMs: Long,
    /** true cuando el sprite pertenece a un muestreo por letras. */
    val isLetterSprite: Boolean,
    /** Pivote de la escala (las letras no lo usan: van centradas). */
    val anchor: WaveAnchor = WaveAnchor.Center,
)

/** Una palabra (unida o partida en partes) con su ventana y sus sprites. */
private class LyraWaveElement(
    val startMs: Long,
    val endMs: Long,
    /** Índice del primer sprite de la palabra. */
    val spriteStart: Int,
    /** 1 si la palabra es un sprite entero; letras si se muestreó. */
    val spriteCount: Int,
    /** true si la palabra se partió en letras. */
    val isLetterGroup: Boolean,
)

private class LyraWaveModel(
    val sprites: List<LyraWaveSprite>,
    val elements: List<LyraWaveElement>,
)

/** Une las palabras con el texto y decide letras, sprites y anclajes. */
private fun buildLyraWaveModel(
    text: String,
    words: List<LyricWord>,
): LyraWaveModel? {
    if (
        words.isEmpty() ||
        text.isEmpty()
    ) {
        return null
    }

    /*
     * Igual que el recorte por palabra de la Normal: cada palabra busca su
     * hueco exacto en el texto, en orden.
     */
    val spans =
        ArrayList<Pair<Int, Int>>(
            words.size,
        )

    var cursor =
        0

    for (word in words) {
        if (
            word.text.isEmpty()
        ) {
            return null
        }

        val start =
            text.indexOf(
                string = word.text,
                startIndex = cursor,
            )

        if (
            start <
            0
        ) {
            return null
        }

        val end =
            start +
                    word.text.length

        spans +=
            Pair(
                first = start,
                second = end,
            )

        cursor =
            end
    }

    /*
     * El modo por defecto de spicy no une splits (`joinSplitWords` en
     * falso): cada parte del TTML es su propia entrada, sea palabra suelta
     * o pedazo —'Ba', 'by', 'Lub-', 'dub'—, y anima por su cuenta.
     */
    val sprites =
        ArrayList<LyraWaveSprite>(
            text.length,
        )

    val elements =
        ArrayList<LyraWaveElement>(
            words.size,
        )

    for (
        (wordIndex, word) in
        words.withIndex()
    ) {
        val spanStart =
            spans[wordIndex]
                .first

        val spanEnd =
            spans[wordIndex]
                .second

        val spriteStart =
            sprites.size

        val anchor =
            waveSpriteAnchor(
                words = words,
                index = wordIndex,
            )

        val clusters =
            graphemeClusters(
                word.text,
            )

        val canSampleLetters =
            clusters.isNotEmpty() &&
                    (
                            word.endMs -
                                    word.startMs
                            ) >=
                    LYRA_WAVE_LETTER_MIN_DURATION_MS &&
                    !isRtlText(
                        word.text,
                    )

        if (
            !canSampleLetters
        ) {
            sprites +=
                LyraWaveSprite(
                    text = word.text,
                    charStart = spanStart,
                    charEnd = spanEnd,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    isLetterSprite = false,
                    anchor = anchor,
                )
        } else {
            /*
             * Ventanas equitativas con el recorte final de spicy: la
             * letra i canta [start + i·paso, start + (i+1)·paso] sobre
             * la ventana ya recortada 250 ms.
             */
            val sampledWindowMs =
                (
                        word.endMs -
                                word.startMs
                        ) -
                        LYRA_WAVE_LETTER_END_TRIM_MS

            val stepMs =
                maxOf(
                    1L,
                    sampledWindowMs /
                            clusters.size,
                )

            for (
                letterIndex in
                clusters.indices
            ) {
                val letterSpan =
                    clusters[letterIndex]

                sprites +=
                    LyraWaveSprite(
                        text =
                            word.text.substring(
                                startIndex = letterSpan.first,
                                endIndex = letterSpan.last + 1,
                            ),

                        charStart =
                            spanStart +
                                    letterSpan.first,

                        charEnd =
                            spanStart +
                                    letterSpan.last +
                                    1,

                        startMs =
                            word.startMs +
                                    stepMs *
                                    letterIndex,

                        endMs =
                            word.startMs +
                                    stepMs *
                                    (
                                            letterIndex +
                                                    1
                                            ),

                        isLetterSprite = true,
                    )
            }
        }

        elements +=
            LyraWaveElement(
                startMs = word.startMs,
                endMs = word.endMs,
                spriteStart = spriteStart,
                spriteCount =
                    sprites.size -
                            spriteStart,

                isLetterGroup = canSampleLetters,
            )
    }

    return LyraWaveModel(
        sprites = sprites,
        elements = elements,
    )
}

/** Corta un texto en grafemas (clusters), como el SplitLetters de spicy. */
private fun graphemeClusters(
    text: String,
): List<IntRange> {
    if (
        text.isEmpty()
    ) {
        return emptyList()
    }

    val iterator =
        BreakIterator
            .getCharacterInstance()

    iterator.setText(
        text,
    )

    val clusters =
        ArrayList<IntRange>()

    var start =
        iterator.first()

    var end =
        iterator.next()

    while (
        end !=
        BreakIterator.DONE
    ) {
        clusters +=
            start until end

        start =
            end

        end =
            iterator.next()
    }

    return clusters
}

/* ─────────────────────────────────────────────
 * ANIMATOR
 * ─────────────────────────────────────────────
 */

/** Muelles de un sprite y su salida del fotograma actual. */
private class LyraWaveAnimatedSprite(
    val sprite: LyraWaveSprite,
) {
    val scaleSpring =
        SpicySpring(
            startPosition = 1.0,
            frequency = LYRA_WAVE_SCALE_FREQUENCY,
            dampingRatio = LYRA_WAVE_SCALE_DAMPING,
            goal = 1.0,
        )

    val ySpring =
        SpicySpring(
            startPosition = 0.0,
            frequency = LYRA_WAVE_Y_OFFSET_FREQUENCY,
            dampingRatio = LYRA_WAVE_Y_OFFSET_DAMPING,
            goal = 0.0,
        )

    val glowSpring =
        SpicySpring(
            startPosition = 0.0,
            frequency = LYRA_WAVE_GLOW_FREQUENCY,
            dampingRatio = LYRA_WAVE_GLOW_DAMPING,
            goal = 0.0,
        )

    var scale =
        1.0

    /** Flotación, en em del tamaño de letra. */
    var yEm =
        0.0

    var glow =
        0.0

    /** Cuánta luz ya pasó por el sprite: 0..1. */
    var fill =
        0.0
}

/** El animador de una línea Exagerada: interpreta tiempos y mueve muelles. */
private class LyraWaveAnimator(    private val model: LyraWaveModel,
) {
    private val animatedSprites =
        model.sprites.map { sprite ->
            LyraWaveAnimatedSprite(
                sprite,
            )
        }

    /**
     * Un fotograma: escribe el estado por sprite en un arreglo de 4 valores
     * —[escala, y (em), glow, fill]— y no toca Compose: solo números.
     */
    fun step(
        positionMs: Double,
        deltaSeconds: Double,
    ): FloatArray {
        val frame =
            FloatArray(
                animatedSprites.size *
                        4,
            )

        for (
            elementIndex in
            model.elements.indices
        ) {
            val element =
                model.elements[elementIndex]

            val wordState =
                getSpicyElementState(
                    currentTimeMs = positionMs,
                    startTimeMs = element.startMs.toDouble(),
                    endTimeMs = element.endMs.toDouble(),
                )

            if (
                element.isLetterGroup
            ) {
                stepLetterGroup(
                    element = element,
                    wordState = wordState,
                    positionMs = positionMs,
                    deltaSeconds = deltaSeconds,
                    frame = frame,
                )
            } else {
                stepWordSprite(
                    element = element,
                    wordState = wordState,
                    positionMs = positionMs,
                    deltaSeconds = deltaSeconds,
                    frame = frame,
                )
            }
        }

        return frame
    }

    private fun stepLetterGroup(
        element: LyraWaveElement,
        wordState: SpicyElementState,
        positionMs: Double,
        deltaSeconds: Double,
        frame: FloatArray,
    ) {
        /*
         * La letra activa de la palabra: la ola pivota alrededor de ella.
         */
        var activeLetterIndex =
            -1

        var activeLetterPercentage =
            0.0

        if (
            wordState ==
            SpicyElementState.Active
        ) {
            for (
                k in
                0 until element.spriteCount
            ) {
                val letterSprite =
                    model.sprites[element.spriteStart +
                            k]

                val letterState =
                    getSpicyElementState(
                        currentTimeMs = positionMs,
                        startTimeMs = letterSprite.startMs.toDouble(),
                        endTimeMs = letterSprite.endMs.toDouble(),
                    )

                if (
                    letterState ==
                    SpicyElementState.Active
                ) {
                    activeLetterIndex =
                        k

                    activeLetterPercentage =
                        getSpicyProgressPercentage(
                            currentTimeMs = positionMs,
                            startTimeMs = letterSprite.startMs.toDouble(),
                            endTimeMs = letterSprite.endMs.toDouble(),
                        )

                    break
                }
            }
        }

        for (
            k in
            0 until element.spriteCount
        ) {
            val animated =
                animatedSprites[element.spriteStart +
                        k]

            val letterSprite =
                animated.sprite

            val letterState =
                getSpicyElementState(
                    currentTimeMs = positionMs,
                    startTimeMs = letterSprite.startMs.toDouble(),
                    endTimeMs = letterSprite.endMs.toDouble(),
                )

            var targetScale =
                1.0

            var targetY =
                0.0

            var targetGlow =
                0.0

            when (
                wordState
            ) {
                SpicyElementState.Active -> {
                    if (
                        activeLetterIndex !=
                        -1
                    ) {
                        val baseScale =
                            WaveLetterScaleSpline.at(
                                activeLetterPercentage,
                            )

                        val baseY =
                            WaveLetterYOffsetSpline.at(
                                activeLetterPercentage,
                            )

                        val baseGlow =
                            WaveGlowSpline.at(
                                activeLetterPercentage,
                            )

                        val distance =
                            abs(
                                k -
                                        activeLetterIndex,
                            ).toDouble()

                        /*
                         * Desvanecido de la ola de spicy: la influencia
                         * cae con la distancia a la letra activa.
                         */
                        val falloff =
                            1.0 /
                                    (
                                            1.0 +
                                                    distance.pow(
                                                        x = 2.8,
                                                    )
                                            )

                        val glowFalloff =
                            1.0 /
                                    (
                                            1.0 +
                                                    distance *
                                                    0.9
                                            )

                        targetScale =
                            1.0 +
                                    (
                                            baseScale -
                                                    1.0
                                            ) *
                                    falloff

                        targetY =
                            baseY *
                                    falloff

                        targetGlow =
                            baseGlow *
                                    glowFalloff
                    }

                    /*
                     * Las letras que aún no cantan están clavadas en
                     * reposo, aunque su palabra ya esté cantando.
                     */
                    if (
                        letterState ==
                        SpicyElementState.NotSung
                    ) {
                        targetScale =
                            1.0

                        targetY =
                            0.0

                        targetGlow =
                            0.0
                    } else if (
                        letterState ==
                        SpicyElementState.Sung &&
                        activeLetterIndex ==
                        -1
                    ) {
                        /*
                         * Solo brillo tenue conservado para las cantadas
                         * cuando ninguna letra está activa.
                         */
                        targetGlow =
                            WaveGlowSpline.at(
                                LYRA_WAVE_SUNG_GLOW,
                            )
                    }
                }

                SpicyElementState.NotSung -> {
                    targetScale =
                        1.0

                    targetY =
                        0.0

                    targetGlow =
                        0.0
                }

                SpicyElementState.Sung -> {
                    /*
                     * Las letras se asientan al terminar la palabra.
                     */
                    targetScale =
                        WaveLetterScaleSpline.at(
                            1.0,
                        )

                    targetY =
                        WaveLetterYOffsetSpline.at(
                            1.0,
                        )

                    targetGlow =
                        WaveGlowSpline.at(
                            1.0,
                        )
                }
            }

            stepAnimatedSprite(
                animated = animated,
                targetScale = targetScale,
                targetY = targetY,
                targetGlow = targetGlow,
                deltaSeconds = deltaSeconds,
            )

            animated.fill =
                when (
                    letterState
                ) {
                    SpicyElementState.NotSung ->
                        0.0

                    SpicyElementState.Active ->
                        waveEaseSinOut(
                            getSpicyProgressPercentage(
                                currentTimeMs = positionMs,
                                startTimeMs = letterSprite.startMs.toDouble(),
                                endTimeMs = letterSprite.endMs.toDouble(),
                            ),
                        )

                    SpicyElementState.Sung ->
                        1.0
                }

            writeSpriteFrame(
                frame = frame,
                spriteIndex =
                    element.spriteStart +
                            k,
                animated = animated,
            )
        }
    }

    private fun stepWordSprite(
        element: LyraWaveElement,
        wordState: SpicyElementState,
        positionMs: Double,
        deltaSeconds: Double,
        frame: FloatArray,
    ) {
        val animated =
            animatedSprites[element.spriteStart]

        var targetScale =
            1.0

        var targetY =
            0.0

        var targetGlow =
            0.0

        when (
            wordState
        ) {
            SpicyElementState.Active -> {
                val percentage =
                    getSpicyProgressPercentage(
                        currentTimeMs = positionMs,
                        startTimeMs = element.startMs.toDouble(),
                        endTimeMs = element.endMs.toDouble(),
                    )

                targetScale =
                    WaveWordScaleSpline.at(
                        percentage,
                    )

                targetY =
                    WaveWordYOffsetSpline.at(
                        percentage,
                    )

                targetGlow =
                    WaveGlowSpline.at(
                        percentage,
                    )
            }

            SpicyElementState.NotSung -> {
                targetScale =
                    1.0

                targetY =
                    0.0

                targetGlow =
                    0.0
            }

            SpicyElementState.Sung -> {
                targetScale =
                    WaveWordScaleSpline.at(
                        1.0,
                    )

                targetY =
                    WaveWordYOffsetSpline.at(
                        1.0,
                    )

                targetGlow =
                    WaveGlowSpline.at(
                        1.0,
                    )
            }
        }

        stepAnimatedSprite(
            animated = animated,
            targetScale = targetScale,
            targetY = targetY,
            targetGlow = targetGlow,
            deltaSeconds = deltaSeconds,
        )

        val fill =
            when (
                wordState
            ) {
                SpicyElementState.NotSung ->
                    0.0

                SpicyElementState.Active ->
                    waveEaseSinOut(
                        getSpicyProgressPercentage(
                            currentTimeMs = positionMs,
                            startTimeMs = element.startMs.toDouble(),
                            endTimeMs = element.endMs.toDouble(),
                        ),
                    )

                SpicyElementState.Sung ->
                    1.0
            }

        animated.fill =
            fill

        writeSpriteFrame(
            frame = frame,
            spriteIndex = element.spriteStart,
            animated = animated,
        )
    }

    private fun stepAnimatedSprite(
        animated: LyraWaveAnimatedSprite,
        targetScale: Double,
        targetY: Double,
        targetGlow: Double,
        deltaSeconds: Double,
    ) {
        animated.scaleSpring.setGoal(
            targetScale,
        )

        animated.ySpring.setGoal(
            targetY,
        )

        animated.glowSpring.setGoal(
            targetGlow,
        )

        animated.scale =
            animated.scaleSpring.step(
                deltaSeconds,
            )

        animated.yEm =
            animated.ySpring.step(
                deltaSeconds,
            )

        animated.glow =
            animated.glowSpring.step(
                deltaSeconds,
            )
    }

    private fun writeSpriteFrame(
        frame: FloatArray,
        spriteIndex: Int,
        animated: LyraWaveAnimatedSprite,
    ) {
        val offset =
            spriteIndex *
                    4

        frame[offset] =
            animated.scale.toFloat()

        frame[offset + 1] =
            animated.yEm.toFloat()

        frame[offset + 2] =
            animated.glow.toFloat()

        frame[offset + 3] =
            animated.fill.toFloat()
    }
}

/* ─────────────────────────────────────────────
 * DIBUJO
 * ─────────────────────────────────────────────
 */

/** Mide cada glifo único una vez, con el mismo estilo del Texto real. */
private fun buildLyraWaveGlyphLayouts(
    model: LyraWaveModel,
    measurer: TextMeasurer,
    style: TextStyle,
): Map<String, TextLayoutResult> {
    val layouts =
        HashMap<String, TextLayoutResult>()

    for (sprite in model.sprites) {
        val glyph =
            sprite.text

        if (
            glyph.isBlank() ||
            layouts.containsKey(
                glyph,
            )
        ) {
            continue
        }

        layouts[glyph] =
            measurer.measure(
                text = AnnotatedString(glyph),
                style = style,
            )
    }

    return layouts
}

/**
 * Hueco de un sprite en el layout. Un sprite partido entre dos renglones es
 * rarísimo y se dibuja completo en el renglón del primer carácter.
 */
private fun lyraWaveSpriteBox(
    layout: TextLayoutResult,
    sprite: LyraWaveSprite,
): Rect? {
    val textLength =
        layout.layoutInput.text
            .length

    if (
        sprite.charStart <
        0 ||
        sprite.charEnd >
        textLength ||
        sprite.charStart >=
        sprite.charEnd
    ) {
        return null
    }

    val startLine =
        layout.getLineForOffset(
            sprite.charStart,
        )

    val endLine =
        layout.getLineForOffset(
            sprite.charEnd -
                    1,
        )

    if (
        startLine ==
        endLine
    ) {
        val startBox =
            layout.getBoundingBox(
                sprite.charStart,
            )

        val endBox =
            layout.getBoundingBox(
                sprite.charEnd -
                        1,
            )

        return Rect(
            left =
                minOf(
                    startBox.left,
                    endBox.left,
                ),
            top =
                minOf(
                    startBox.top,
                    endBox.top,
                ),
            right =
                maxOf(
                    startBox.right,
                    endBox.right,
                ),
            bottom =
                maxOf(
                    startBox.bottom,
                    endBox.bottom,
                ),
        )
    }

    return layout.getBoundingBox(
        sprite.charStart,
    )
}

/** Pinta la línea completa: por sprite, brillo tenue → halo → relleno. */
private fun DrawScope.drawLyraWaveSprites(
    frame: FloatArray,
    model: LyraWaveModel,
    boxes: List<Rect?>,
    glyphLayouts: Map<String, TextLayoutResult>,
    dimColor: Color,
    brightColor: Color,
    fontSize: TextUnit,
) {
    val fontSizePx =
        fontSize
            .toPx()

    for (
        spriteIndex in
        model.sprites.indices
    ) {
        val sprite =
            model.sprites[spriteIndex]

        if (
            sprite.text.isBlank()
        ) {
            continue
        }

        val box =
            boxes.getOrNull(
                spriteIndex,
            ) ?:
            continue

        val glyphLayout =
            glyphLayouts[sprite.text] ?:
            continue

        val offset =
            spriteIndex *
                    4

        val scale =
            frame[offset]

        val yEm =
            frame[offset + 1]

        val glow =
            frame[offset + 2].toDouble()

        val fill =
            frame[offset + 3]

        val translateYPx =
            yEm *
                    fontSizePx

        /*
         * El pivote sale del anclaje de spicy: las partes de un split
         * crecen hacia su filo externo —'Lub-' a la izquierda ('Ba' con
         * él), 'dub' a la derecha ('by' con él)— y todo lo demás crece
         * centrado.
         */
        val pivot =
            when (sprite.anchor) {
                WaveAnchor.Left -> Offset(
                    x = box.left,
                    y =
                        (
                                box.top +
                                        box.bottom
                                ) /
                                2f,
                )

                WaveAnchor.Center -> Offset(
                    x =
                        (
                                box.left +
                                        box.right
                                ) /
                                2f,
                    y =
                        (
                                box.top +
                                        box.bottom
                                ) /
                                2f,
                )

                WaveAnchor.Right -> Offset(
                    x = box.right,
                    y =
                        (
                                box.top +
                                        box.bottom
                                ) /
                                2f,
                )
            }

        translate(
            left = 0f,
            top = translateYPx,
        ) {
            scale(
                scaleX = scale,
                scaleY = scale,
                pivot = pivot,
            ) {
                if (
                    glow >
                    LYRA_WAVE_GLOW_DRAW_THRESHOLD
                ) {
                    drawLyraWaveGlowRing(
                        glyphLayout = glyphLayout,
                        topLeft = box.topLeft,
                        fontSizePx = fontSizePx,
                        sprite = sprite,
                        glow = glow,
                        color = brightColor,
                    )
                }

                drawText(
                    textLayoutResult = glyphLayout,
                    color = dimColor,
                    topLeft = box.topLeft,
                )

                if (
                    fill >
                    0.005f
                ) {
                    drawText(
                        textLayoutResult = glyphLayout,
                        color = brightColor,
                        topLeft = box.topLeft,
                        alpha = fill,
                    )
                }
            }
        }
    }
}

/** Anillo de copias desplazadas: el halo del glifo que está cantando. */
private fun DrawScope.drawLyraWaveGlowRing(
    glyphLayout: TextLayoutResult,
    topLeft: Offset,
    fontSizePx: Float,
    sprite: LyraWaveSprite,
    glow: Double,
    color: Color,
) {
    val isLetter =
        sprite.isLetterSprite

    val baseEm =
        if (isLetter) {
            LYRA_WAVE_LETTER_GLOW_BASE_EM
        } else {
            LYRA_WAVE_WORD_GLOW_BASE_EM
        }

    val spanEm =
        if (isLetter) {
            LYRA_WAVE_LETTER_GLOW_SPAN_EM
        } else {
            LYRA_WAVE_WORD_GLOW_SPAN_EM
        }

    val alphaMultiplier =
        if (isLetter) {
            LYRA_WAVE_LETTER_GLOW_ALPHA
        } else {
            LYRA_WAVE_WORD_GLOW_ALPHA
        }

    val strength =
        if (isLetter) {
            LYRA_WAVE_LETTER_GLOW_STRENGTH
        } else {
            LYRA_WAVE_WORD_GLOW_STRENGTH
        }

    val radiusPx =
        fontSizePx *
                (
                        baseEm +
                                spanEm *
                                        glow
                        )
            .toFloat()

    val haloAlpha =
        (
                glow *
                        alphaMultiplier
                )
            .coerceIn(
                0.0,
                1.0,
            )
            .toFloat() *
                strength

    if (
        haloAlpha <=
        0.005f
    ) {
        return
    }

    val outerAlpha =
        haloAlpha *

                0.6f /
                LYRA_WAVE_GLOW_OUTER_COPIES

    for (
        k in
        0 until LYRA_WAVE_GLOW_OUTER_COPIES
    ) {
        val angle =
            k *
                    PI /
                    (LYRA_WAVE_GLOW_OUTER_COPIES /
                            2.0)

        val dx =
            cos(angle) *
                    radiusPx

        val dy =
            sin(angle) *
                    radiusPx

        drawText(
            textLayoutResult = glyphLayout,
            color = color,
            topLeft =
                topLeft +
                        Offset(
                            x = dx.toFloat(),
                            y = dy.toFloat(),
                        ),
            alpha = outerAlpha,
        )
    }

    val innerRadiusPx =
        radiusPx *
                LYRA_WAVE_GLOW_INNER_RATIO
            .toFloat()

    val innerAlpha =
        haloAlpha *

                0.4f /
                LYRA_WAVE_GLOW_INNER_COPIES

    for (
        k in
        0 until LYRA_WAVE_GLOW_INNER_COPIES
    ) {
        val angle =
            k *
                    PI /
                    2.0 +
                    PI /
                    4.0

        val dx =
            cos(angle) *
                    innerRadiusPx

        val dy =
            sin(angle) *
                    innerRadiusPx

        drawText(
            textLayoutResult = glyphLayout,
            color = color,
            topLeft =
                topLeft +
                        Offset(
                            x = dx.toFloat(),
                            y = dy.toFloat(),
                        ),
            alpha = innerAlpha,
        )
    }
}
