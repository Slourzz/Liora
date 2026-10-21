package com.liora.android.ui.lyrics.lyrics.lyra.renderer

import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricWord

/**
 * Texto auxiliar que se pinta debajo de una línea.
 *
 * Spicy Lyrics entrega la romanización por línea y también por sílaba. Se
 * prefiere la de línea; si no existe, se reconstruye con las sílabas para no
 * perder el espaciado de palabras como «don't» o «kanji».
 */
internal fun LyricLine.romanizationText(): String? {
    val lineText =
        transliteration
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    if (
        lineText != null &&
        !lineText.equals(text, ignoreCase = true)
    ) {
        return lineText
    }

    if (words.isEmpty()) return null

    val parts = ArrayList<String>(words.size)

    for (word in words) {
        val transliterated =
            word.transliteration
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        if (transliterated != null) {
            parts += transliterated
            continue
        }

        /*
         * La puntuación no necesita romanización. Si la palabra tiene letras
         * y la fuente no trajo su transliteración, es mejor no inventar una
         * mezcla de alfabetos.
         */
        if (word.text.any { it.isLetter() }) {
            return null
        }

        parts += word.text
    }

    val rebuilt = joinRomanization(words, parts)
        .trim()
        .takeIf { it.isNotEmpty() }

    return rebuilt?.takeIf {
        !it.equals(text, ignoreCase = true)
    }
}

/** Traducción lista para pintar, si la fuente la trae y no repite la letra. */
internal fun LyricLine.translationText(): String? =
    translation
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.takeIf { !it.equals(text, ignoreCase = true) }

private fun joinRomanization(
    words: List<LyricWord>,
    parts: List<String>,
): String = buildString {
    words.forEachIndexed { index, word ->
        val joinsPrevious =
            word.joinWithPrevious ||
                    words.getOrNull(index - 1)?.joinWithNext == true

        if (
            index > 0 &&
            !joinsPrevious &&
            word.text.firstOrNull() !in NoLeadingSpace
        ) {
            append(' ')
        }

        append(parts[index])
    }
}

/** Signos que no llevan espacio delante al recomponer la línea. */
private val NoLeadingSpace =
    setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
