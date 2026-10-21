package com.liora.android.data.model

/**
 * Los modelos de letra que usa Lyra —el motor de letras de Cloud, portado tal
 * cual—. Una canción se compone de líneas; una línea puede traer sus palabras
 * con tiempo (para el karaoke), coros de fondo y una alineación aparte para
 * los duetos.
 */

/** Palabra con su ventana de tiempo; `partOfWord` marca sílabas de una misma palabra. */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val partOfWord: Boolean = false,
    val joinWithPrevious: Boolean = false,
    val joinWithNext: Boolean = false,
    /** Romanización que trae la fuente para esta sílaba, si la hay. */
    val transliteration: String? = null,
)

/** Coro o voz de fondo: se pinta más pequeño, debajo de su línea. */
data class LyricBackgroundLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord> = emptyList(),
    val oppositeAligned: Boolean = false,
)

/** Una línea de la letra, con sus palabras y sus coros de fondo. */
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord> = emptyList(),
    val background: List<LyricBackgroundLine> = emptyList(),
    val oppositeAligned: Boolean = false,
    /** Traducción que trae la fuente, si la hay; Lyra aún no la pinta. */
    val translation: String? = null,
    /** Romanización de la línea, si la fuente la trae. */
    val transliteration: String? = null,
)

/** Quién subió o creó una letra de la comunidad de Spicy Lyrics. */
data class LyricsContributor(
    val username: String,
    val url: String? = null,
)

/** De dónde salieron las letras y a quién hay que acreditar debajo de ellas. */
data class LyricsAttribution(
    val providerName: String,
    val originName: String? = null,
    val uploader: LyricsContributor? = null,
    val maker: LyricsContributor? = null,
    /** Quién escribió la canción, cuando la fuente lo dice. */
    val songwriters: List<String> = emptyList(),
)

/** Resultado de buscar letras: las líneas y su atribución. */
data class LyricsResult(
    val lines: List<LyricLine> = emptyList(),
    val attribution: LyricsAttribution? = null,
)

/** De dónde salió la letra: la comunidad de Cloud, Apple Music o lyrics.ovh. */
data class LyricsCredits(
    val source: String,
    val submissionId: String? = null,
    val synchronizer: String? = null,
    val synchronizerId: String? = null,
    val synchronizerAvatarUrl: String? = null,
)

/**
 * La línea con su traducción puesta.
 *
 * Cuando la letra se traduce, el original desaparece: en su lugar queda la
 * traducción, pero siguiendo el mismo ritmo. Para eso la traducción se reparte
 * entre las palabras originales: cada palabra traducida hereda el tramo de
 * tiempo que le tocaba a la original, calculado sobre el tamaño de los textos.
 * Así la letra traducida se canta con el mismo compás, sin tocar los tiempos
 * ni la estructura de la línea.
 */
fun LyricLine.withTranslation(translated: String): LyricLine {
    val target = translated.trim()
    if (target.isEmpty()) return this

    val tokens = target.split(' ', '\n').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this

    val aligned = alignWords(tokens, words)
    return copy(
        text = aligned.joinToString(" ") { it.text },
        words = aligned,
        // La traducción ya está en el texto: no se pinta otra vez debajo.
        translation = null,
    )
}

/**
 * Reparte las palabras traducidas entre las originales, con los tiempos de
 * estas últimos: cada palabra traducida ocupa el tramo que le corresponde por
 * su tamaño dentro del texto original.
 */
private fun alignWords(tokens: List<String>, original: List<LyricWord>): List<LyricWord> {
    if (original.isEmpty()) {
        // Sin tiempos no hay karaoke: se pintan como una línea sola.
        return tokens.mapIndexed { index, token ->
            LyricWord(text = token, startMs = 0L, endMs = 0L)
        }
    }

    // Los puntos del texto original: cuántos caracteres lleva cada palabra y
    // a qué hora termina.
    val starts = ArrayList<Int>(original.size)
    val ends = ArrayList<Int>(original.size)
    var chars = 0
    original.forEach { word ->
        starts += chars
        chars += word.text.length
        ends += chars
    }
    val originalChars = chars.coerceAtLeast(1)
    val tokenChars = tokens.sumOf { it.length }.coerceAtLeast(1)

    fun timeAt(position: Int): Long {
        val p = position.coerceIn(0, originalChars)
        for (index in original.indices) {
            if (p <= ends[index]) {
                val word = original[index]
                val span = (ends[index] - starts[index]).coerceAtLeast(1)
                val inside = ((p - starts[index]).toFloat() / span).coerceIn(0f, 1f)
                val start = word.startMs
                val end = maxOf(word.endMs, start + 1L)
                return start + ((end - start) * inside).toLong()
            }
        }
        return original.last().endMs
    }

    val aligned = ArrayList<LyricWord>(tokens.size)
    var consumed = 0
    tokens.forEachIndexed { index, token ->
        val from = consumed
        consumed += token.length
        val to = consumed
        val originalFrom = (from.toDouble() / tokenChars * originalChars).toInt()
        val originalTo = (to.toDouble() / tokenChars * originalChars).toInt()
        val start = timeAt(originalFrom)
        val end = maxOf(timeAt(originalTo), start + 1L)
        aligned += LyricWord(
            text = token,
            startMs = start,
            endMs = end,
            partOfWord = false,
            joinWithPrevious = false,
            joinWithNext = false,
        )
    }
    return aligned
}
