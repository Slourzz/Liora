package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.liora.android.data.model.LyricWord
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraElementState
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraWordFrame

/**
 * Texto de una línea con el mismo gesto que AMLL:
 *
 * - una copia tenue, siempre completa, que mantiene el layout original;
 * - una copia brillante encima, recortada por palabra según su tiempo.
 *
 * El recorte se hace con `DST_OUT` sobre la copia brillante. Las palabras que
 * todavía no suenan se borran y la palabra activa se descubre de izquierda a
 * derecha con un borde suave. El texto no se vuelve a medir en cada frame: el
 * layout se conserva y solo cambia la máscara de dibujo.
 */
@Composable
internal fun LyraWordMaskText(
    text: String,
    words: List<LyricWord>,
    frames: List<LyraWordFrame>,
    wordByWord: Boolean,
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
    /**
     * En el panel de letras el texto ocupa todo el ancho para que la alineación
     * y el recorte por palabra coincidan. En el preview compacto se deja
     * medir por su contenido, así el chevron queda pegado al final de la frase.
     */
    fillWidth: Boolean = true,
) {
    val spans = remember(text, words) {
        mapWordSpans(text, words)
    }

    val canMask =
        wordByWord &&
                spans != null &&
                words.isNotEmpty() &&
                frames.size == words.size

    /*
     * Si no hay tiempos utilizables, la copia tenue se pinta con el color de
     * siempre: la canción sigue viéndose bien aunque la fuente solo traiga
     * tiempos por línea.
     */
    val restingColor =
        if (canMask) dimColor else fallbackColor

    var textLayout by remember {
        mutableStateOf<TextLayoutResult?>(null)
    }

    val maskPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }

    val widthModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier

    Box(modifier = modifier.then(widthModifier)) {
        Text(
            text = text,
            color = restingColor,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout ->
                textLayout = layout
            },
            modifier = widthModifier,
        )

        if (canMask && spans != null) {
            Text(
                text = text,
                color = brightColor,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = fontWeight,
                fontSize = fontSize,
                lineHeight = lineHeight,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = overflow,
                modifier = widthModifier
                    .clearAndSetSemantics { }
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()

                        val layout = textLayout ?: return@drawWithContent
                        drawWordMask(
                            layout = layout,
                            spans = spans,
                            frames = frames,
                            paint = maskPaint,
                        )
                    },
            )
        }
    }
}

private data class WordSpan(
    val start: Int,
    val end: Int,
)

private fun mapWordSpans(
    text: String,
    words: List<LyricWord>,
): List<WordSpan>? {
    if (words.isEmpty()) return null

    val spans = ArrayList<WordSpan>(words.size)
    var cursor = 0

    for (word in words) {
        if (word.text.isEmpty()) return null

        val start = text.indexOf(word.text, cursor)
        if (start < 0) return null

        val end = start + word.text.length
        spans += WordSpan(start = start, end = end)
        cursor = end
    }

    return spans
}

private fun DrawScope.drawWordMask(
    layout: TextLayoutResult,
    spans: List<WordSpan>,
    frames: List<LyraWordFrame>,
    paint: Paint,
) {
    drawIntoCanvas { canvas ->
        spans.forEachIndexed { index, span ->
            val frame = frames.getOrNull(index) ?: return@forEachIndexed

            if (frame.state == LyraElementState.Finished) {
                return@forEachIndexed
            }

            val bounds = layout.wordBounds(span) ?: return@forEachIndexed

            if (
                frame.state == LyraElementState.Pending ||
                frame.progress <= 0f
            ) {
                paint.shader = null
                paint.color = android.graphics.Color.WHITE
                canvas.nativeCanvas.drawRect(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    paint,
                )
                return@forEachIndexed
            }

            val progress = frame.progress.coerceIn(0f, 1f)
            if (progress >= 0.999f) {
                return@forEachIndexed
            }

            val revealX = bounds.left + bounds.width * progress
            val feather = (bounds.height * 0.22f)
                .coerceIn(1f, bounds.width * 0.45f)
            val fadeEnd = (revealX + feather)
                .coerceAtMost(bounds.right)

            paint.shader = LinearGradient(
                revealX,
                bounds.top,
                fadeEnd,
                bounds.top,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.WHITE,
                ),
                null,
                Shader.TileMode.CLAMP,
            )
            paint.color = android.graphics.Color.WHITE

            canvas.nativeCanvas.drawRect(
                revealX,
                bounds.top,
                bounds.right,
                bounds.bottom,
                paint,
            )

            paint.shader = null
        }
    }
}

private fun TextLayoutResult.wordBounds(
    span: WordSpan,
): Rect? {
    val textLength =
        layoutInput.text.length

    if (
        span.start < 0 ||
        span.end > textLength ||
        span.start >= span.end
    ) {
        return null
    }

    val startLine = getLineForOffset(span.start)
    val endLine = getLineForOffset(span.end - 1)

    /*
     * Una palabra partida en dos renglones es rarísima. Si ocurre, no la
     * recortamos: se queda con el color de reposo en vez de pintar un bloque
     * que cruce el espacio entre líneas.
     */
    if (startLine != endLine) return null

    val startBox = getBoundingBox(span.start)
    val endBox = getBoundingBox(span.end - 1)

    return Rect(
        left = minOf(startBox.left, endBox.left),
        top = minOf(startBox.top, endBox.top),
        right = maxOf(startBox.right, endBox.right),
        bottom = maxOf(startBox.bottom, endBox.bottom),
    )
}
