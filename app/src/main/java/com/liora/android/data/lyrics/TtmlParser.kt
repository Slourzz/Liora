package com.liora.android.data.lyrics

import android.util.Xml
import com.liora.android.data.model.LyricBackgroundLine
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricWord
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object TtmlParser {
    /**
     * Qué es una línea auxiliar: la traducción de la letra o su romanización.
     *
     * AMLL (el TTML de Apple Music) las guarda de dos maneras: en la cabecera,
     * con `<translations>` y `<transliterations>` (cada `<text for="L1">` apunta
     * a la línea original por su clave), o como párrafos aparte con el papel
     * `x-translation` / `x-romanization`. Se leen las dos.
     */
    private enum class Auxiliary {
        Translation,
        Romanization,
    }

    private data class PartDirective(
        val declared: Boolean,
        val wantsPrevious: Boolean,
        val wantsNext: Boolean,
    )

    private data class DirectLeaf(
        val parentDepth: Int,
        val backgroundGroupId: Int?,
        val wordIndex: Int,
        val endsWithWhitespace: Boolean,
        val directive: PartDirective,
    )

    private data class TimedSpan(
        val depth: Int,
        val startMs: Long,
        val endMs: Long,
        val text: StringBuilder = StringBuilder(),
        val background: Boolean = false,
        val backgroundGroupId: Int? = null,
        val oppositeAligned: Boolean = false,
        var hasChildSpan: Boolean = false,
        val rawText: StringBuilder = StringBuilder(),
        val partDirective: PartDirective = PartDirective(false, false, false),
        val directPrevious: DirectLeaf? = null,
    )

    fun parse(input: InputStream): List<LyricLine> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        val parsed = mutableListOf<LyricLine>()
        val agentTypes = mutableMapOf<String, String>()

        // La traducción y la romanización, por la clave de la línea original.
        val translations = mutableMapOf<String, String>()
        val romanizations = mutableMapOf<String, String>()
        var auxiliary: Auxiliary? = null
        var collectingKey: String? = null
        val collected = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "agent" -> {
                        val id = attribute(parser, "id")
                        val type = listOf("type", "role").firstNotNullOfOrNull { attribute(parser, it) }
                        if (!id.isNullOrBlank() && !type.isNullOrBlank()) agentTypes[id] = type.lowercase()
                    }

                    // La cabecera de AMLL: qué texto auxiliar se está leyendo.
                    "translation" -> auxiliary = Auxiliary.Translation
                    "transliteration" -> auxiliary = Auxiliary.Romanization

                    // <text for="L1">: el texto auxiliar de la línea L1.
                    "text" -> {
                        val key = attribute(parser, "for")
                        if (auxiliary != null && !key.isNullOrBlank()) {
                            collectingKey = key
                            collected.setLength(0)
                        }
                    }

                    "p" -> {
                        val role = auxiliaryRole(parser)
                        val key = paragraphKey(parser)
                        val line = parseParagraph(
                            parser = parser,
                            agentTypes = agentTypes,
                            translations = translations,
                            romanizations = romanizations,
                            key = key,
                        )
                        if (role != null) {
                            // Una línea auxiliar no se canta: se guarda contra
                            // su clave para poder pegársela a la original.
                            if (line != null && !key.isNullOrBlank()) {
                                when (role) {
                                    Auxiliary.Translation -> translations[key] = line.text
                                    Auxiliary.Romanization -> romanizations[key] = line.text
                                }
                            }
                        } else {
                            line?.let(parsed::add)
                        }
                    }
                }
            }

            if (parser.eventType == XmlPullParser.TEXT || parser.eventType == XmlPullParser.CDSECT) {
                if (collectingKey != null) collected.append(parser.text.orEmpty())
            }

            if (parser.eventType == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "text" -> {
                        val key = collectingKey
                        if (key != null) {
                            val value = normalizeText(collected.toString())
                            if (value.isNotEmpty()) {
                                when (auxiliary) {
                                    Auxiliary.Translation -> translations[key] = value
                                    Auxiliary.Romanization -> romanizations[key] = value
                                    null -> Unit
                                }
                            }
                            collectingKey = null
                            collected.setLength(0)
                        }
                    }

                    "translation", "transliteration" -> auxiliary = null
                }
            }

            parser.next()
        }

        val sorted = parsed.sortedBy(LyricLine::startMs)
        return sorted.mapIndexed { index, line ->
            if (line.endMs > line.startMs) {
                line
            } else {
                line.copy(
                    endMs = sorted.getOrNull(index + 1)?.startMs
                        ?.takeIf { it > line.startMs }
                        ?: line.startMs + DEFAULT_LINE_DURATION_MS,
                )
            }
        }
    }

    /**
     * El papel de un párrafo: si es una traducción o una romanización, la
     * línea no se canta, se guarda aparte.
     */
    private fun auxiliaryRole(parser: XmlPullParser): Auxiliary? {
        val role = roleAttribute(parser)?.lowercase() ?: return null
        return when {
            role.contains("translation") -> Auxiliary.Translation
            role.contains("romanization") || role.contains("transliteration") -> Auxiliary.Romanization
            else -> null
        }
    }

    /** El atributo `role` con espacio de nombres o sin él. */
    private fun roleAttribute(parser: XmlPullParser): String? =
        (0 until parser.attributeCount)
            .firstOrNull { parser.getAttributeName(it) == "role" }
            ?.let(parser::getAttributeValue)

    /**
     * La clave de la línea (`itunes:key`): es lo que enlaza una línea con su
     * traducción y su romanización.
     */
    private fun paragraphKey(parser: XmlPullParser): String? =
        (0 until parser.attributeCount)
            .firstOrNull { index ->
                val name = parser.getAttributeName(index)
                if (name != "key") return@firstOrNull false
                val namespace = parser.getAttributeNamespace(index).orEmpty()
                namespace.isEmpty() ||
                    namespace.contains("apple") ||
                    namespace.contains("itunes") ||
                    namespace.contains("ttml")
            }
            ?.let(parser::getAttributeValue)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseParagraph(
        parser: XmlPullParser,
        agentTypes: Map<String, String>,
        translations: Map<String, String>,
        romanizations: Map<String, String>,
        key: String?,
    ): LyricLine? {
        val paragraphDepth = parser.depth
        val begin = parseTime(attribute(parser, "begin"))
        val declaredEnd = parseTime(attribute(parser, "end"))
        val lineText = StringBuilder()
        val words = mutableListOf<LyricWord>()
        val backgroundWordGroups = linkedMapOf<Int, MutableList<LyricWord>>()
        val backgroundOppositeGroups = mutableMapOf<Int, Boolean>()
        var nextBackgroundGroupId = 0
        val timedSpans = ArrayDeque<TimedSpan>()
        var directLeaf: DirectLeaf? = null
        val paragraphToken = elementToken(parser, agentTypes)
        val paragraphOpposite = isOpposite(paragraphToken)

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "span" -> {
                        timedSpans.lastOrNull()?.hasChildSpan = true
                        val spanBegin = parseTime(attribute(parser, "begin"))
                        val spanEnd = parseTime(attribute(parser, "end"))
                        val token = elementToken(parser, agentTypes)
                        val partDirective = readPartDirective(parser, token)
                        val parentSpan = timedSpans.lastOrNull()
                        val inheritedBackground = parentSpan?.background == true
                        val isBackground = inheritedBackground || isBackground(token)
                        val backgroundGroupId = if (isBackground) {
                            parentSpan?.backgroundGroupId ?: nextBackgroundGroupId++
                        } else {
                            null
                        }
                        val directPrevious = directLeaf?.takeIf {
                            it.parentDepth == parser.depth - 1 &&
                                it.backgroundGroupId == backgroundGroupId
                        }
                        directLeaf = null
                        if (spanBegin != null || spanEnd != null) {
                            timedSpans.addLast(
                                TimedSpan(
                                    depth = parser.depth,
                                    startMs = spanBegin ?: begin ?: 0L,
                                    endMs = spanEnd ?: declaredEnd ?: 0L,
                                    background = isBackground,
                                    backgroundGroupId = backgroundGroupId,
                                    oppositeAligned = when {
                                        isOpposite(token) -> true
                                        isStartSide(token) -> false
                                        else -> timedSpans.lastOrNull()?.oppositeAligned ?: paragraphOpposite
                                    },
                                    partDirective = partDirective,
                                    directPrevious = directPrevious,
                                ),
                            )
                        } else if (inheritedBackground || isBackground(token)) {
                            timedSpans.addLast(
                                TimedSpan(
                                    depth = parser.depth,
                                    startMs = begin ?: 0L,
                                    endMs = declaredEnd ?: 0L,
                                    background = true,
                                    backgroundGroupId = backgroundGroupId,
                                    oppositeAligned = isOpposite(token) || paragraphOpposite,
                                    partDirective = partDirective,
                                    directPrevious = directPrevious,
                                ),
                            )
                        }
                    }

                    "br" -> {
                        directLeaf = null
                        appendText(lineText, "\n")
                    }

                    else -> directLeaf = null
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val value = parser.text
                    directLeaf = null
                    timedSpans.lastOrNull()?.rawText?.append(value.orEmpty())
                    if (!value.isNullOrBlank()) {
                        appendText(lineText, value)
                        timedSpans.lastOrNull()?.text?.let { appendText(it, value) }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "span") {
                        val span = timedSpans.lastOrNull()
                        if (span != null && span.depth == parser.depth) {
                            timedSpans.removeLast()
                            val text = normalizeText(span.text.toString())
                            if (text.isNotEmpty() && !span.hasChildSpan) {
                                val target = if (span.background) {
                                    backgroundWordGroups.getOrPut(requireNotNull(span.backgroundGroupId)) {
                                        mutableListOf()
                                    }
                                } else {
                                    words
                                }
                                if (span.background) {
                                    backgroundOppositeGroups[requireNotNull(span.backgroundGroupId)] =
                                        span.oppositeAligned
                                }
                                val directPrevious = span.directPrevious
                                val canJoinPrevious = directPrevious != null &&
                                    !directPrevious.endsWithWhitespace &&
                                    !span.rawText.startsWithWhitespace()
                                val fallbackJoin = canJoinPrevious &&
                                    !span.partDirective.declared &&
                                    directPrevious.directive.declared == false
                                val joinWithPrevious = canJoinPrevious &&
                                    (span.partDirective.wantsPrevious || fallbackJoin)
                                val previousJoinsNext = canJoinPrevious &&
                                    (directPrevious.directive.wantsNext || fallbackJoin)

                                if (directPrevious != null && previousJoinsNext) {
                                    target.getOrNull(directPrevious.wordIndex)?.let { previous ->
                                        target[directPrevious.wordIndex] = previous.copy(
                                            partOfWord = true,
                                            joinWithNext = true,
                                        )
                                    }
                                }

                                target += LyricWord(
                                    text = text,
                                    startMs = span.startMs,
                                    endMs = span.endMs.takeIf { it > span.startMs }
                                        ?: span.startMs + DEFAULT_WORD_DURATION_MS,
                                    partOfWord = false,
                                    joinWithPrevious = joinWithPrevious,
                                    joinWithNext = false,
                                )
                                directLeaf = DirectLeaf(
                                    parentDepth = span.depth - 1,
                                    backgroundGroupId = span.backgroundGroupId,
                                    wordIndex = target.lastIndex,
                                    endsWithWhitespace = span.rawText.endsWithWhitespace(),
                                    directive = span.partDirective,
                                )
                            } else {
                                directLeaf = null
                            }
                        }
                    }
                    if (parser.name == "p" && parser.depth == paragraphDepth) break
                }
            }
        }

        val cleanText = normalizeText(lineText.toString())
        if (cleanText.isEmpty() && backgroundWordGroups.isEmpty()) return null
        val joinedWords = words
        val joinedBackgroundGroups = backgroundWordGroups.mapValues { (_, backgroundWords) ->
            backgroundWords.map { word ->
                word.copy(text = word.text.replace("(", "").replace(")", "").trim())
            }.filter { it.text.isNotEmpty() }
        }.filterValues(List<LyricWord>::isNotEmpty)
        val leadText = wordsToText(joinedWords).ifBlank { cleanText }
        val resolvedBegin = begin ?: words.minOfOrNull(LyricWord::startMs) ?: 0L
        val resolvedEnd = declaredEnd
            ?: words.maxOfOrNull(LyricWord::endMs)
            ?: resolvedBegin + DEFAULT_LINE_DURATION_MS

        return LyricLine(
            text = leadText,
            startMs = resolvedBegin,
            endMs = resolvedEnd,
            words = joinedWords
                .distinctBy { Triple(it.text, it.startMs, it.endMs) }
                .sortedBy(LyricWord::startMs),
            background = joinedBackgroundGroups.map { (groupId, backgroundWords) ->
                LyricBackgroundLine(
                    text = wordsToText(backgroundWords).removeSurrounding("(", ")"),
                    startMs = backgroundWords.minOf(LyricWord::startMs),
                    endMs = backgroundWords.maxOf(LyricWord::endMs),
                    words = backgroundWords,
                    oppositeAligned = backgroundOppositeGroups[groupId] ?: false,
                )
            },
            oppositeAligned = paragraphOpposite,
            // La traducción y la romanización llegan por la clave de la línea.
            translation = key?.let(translations::get)?.takeIf { it.isNotBlank() },
            transliteration = key?.let(romanizations::get)?.takeIf { it.isNotBlank() },
        )
    }

    private fun wordsToText(words: List<LyricWord>): String = buildString {
        words.forEachIndexed { index, word ->
            if (index > 0 && !word.joinWithPrevious && !words[index - 1].joinWithNext) append(' ')
            append(word.text)
        }
    }.trim()

    private fun elementToken(parser: XmlPullParser, agentTypes: Map<String, String>): String {
        val values = (0 until parser.attributeCount).flatMap { index ->
            val name = parser.getAttributeName(index).orEmpty().lowercase()
            val value = parser.getAttributeValue(index).orEmpty().lowercase()
            listOf(value, "$name=$value")
        }
        val agent = listOf("agent", "voice", "singer").firstNotNullOfOrNull { attribute(parser, it) }
        return (values + listOfNotNull(agent?.let(agentTypes::get))).joinToString(" ").lowercase()
    }

    private fun isBackground(token: String) = Regex("(^|\\W)(x-bg|bg|background|backing|harmony|ad[-_ ]?lib)(\\W|$)").containsMatchIn(token)
    private fun isOpposite(token: String) = Regex(
        "opposite|secondary|alternate|duet|voice[-_ ]?2|singer[-_ ]?2|agent[-_ ]?2|" +
            "(^|\\W)v2(\\W|$)|(^|\\W)right(\\W|$)|(side|placement|position|alignment)=(end|right)",
    ).containsMatchIn(token)
    private fun isStartSide(token: String) = Regex(
        "primary|main|lead|voice[-_ ]?1|singer[-_ ]?1|agent[-_ ]?1|" +
            "(^|\\W)v1(\\W|$)|(^|\\W)left(\\W|$)|(side|placement|position|alignment)=(start|left)",
    ).containsMatchIn(token)

    private fun readPartDirective(parser: XmlPullParser, token: String): PartDirective {
        val explicitPartOfWord = listOf(
            "partOfWord",
            "part-of-word",
            "data-part-of-word",
        )
            .firstNotNullOfOrNull { attribute(parser, it) }
            ?.trim()
            ?.lowercase()
        if (explicitPartOfWord in setOf("true", "1", "yes", "y")) {
            return PartDirective(true, wantsPrevious = true, wantsNext = true)
        }
        if (explicitPartOfWord in setOf("false", "0", "no", "n")) {
            return PartDirective(true, wantsPrevious = false, wantsNext = false)
        }

        val part = listOf("part", "wordPart", "word-part")
            .firstNotNullOfOrNull { attribute(parser, it) }
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when (part) {
            "begin", "start" -> PartDirective(true, wantsPrevious = false, wantsNext = true)
            "end", "tail", "continuation" -> PartDirective(true, wantsPrevious = true, wantsNext = false)
            "middle", "mid", "part", "syllable" -> PartDirective(true, wantsPrevious = true, wantsNext = true)
            else -> if (Regex("part[-_ ]?of[-_ ]?word|word[-_ ]?part|subword|syllable").containsMatchIn(token)) {
                PartDirective(true, wantsPrevious = true, wantsNext = true)
            } else {
                PartDirective(false, wantsPrevious = false, wantsNext = false)
            }
        }
    }

    private fun StringBuilder.startsWithWhitespace(): Boolean = firstOrNull()?.isWhitespace() == true
    private fun StringBuilder.endsWithWhitespace(): Boolean = lastOrNull()?.isWhitespace() == true

    private fun appendText(target: StringBuilder, raw: String) {
        if (raw == "\n") {
            if (target.isNotEmpty() && target.last() != '\n') target.append('\n')
            return
        }
        val value = raw.replace(Regex("[\\t\\r\\n ]+"), " ").trim()
        if (value.isEmpty()) return
        if (target.isNotEmpty() && target.last() != ' ' && target.last() != '\n' &&
            value.firstOrNull() !in NO_LEADING_SPACE
        ) {
            target.append(' ')
        }
        target.append(value)
    }

    private fun normalizeText(value: String): String = value
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()

    private fun attribute(parser: XmlPullParser, name: String): String? =
        (0 until parser.attributeCount)
            .firstOrNull { parser.getAttributeName(it) == name }
            ?.let(parser::getAttributeValue)

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        return when {
            value.endsWith("ms") -> value.removeSuffix("ms").toDoubleOrNull()?.toLong()
            value.endsWith("h") -> value.removeSuffix("h").toDoubleOrNull()?.times(3_600_000)?.toLong()
            value.endsWith("m") -> value.removeSuffix("m").toDoubleOrNull()?.times(60_000)?.toLong()
            value.endsWith("s") -> value.removeSuffix("s").toDoubleOrNull()?.times(1_000)?.toLong()
            ":" in value -> {
                val parts = value.split(":").mapNotNull(String::toDoubleOrNull)
                when (parts.size) {
                    3 -> ((parts[0] * 3_600 + parts[1] * 60 + parts[2]) * 1_000).toLong()
                    2 -> ((parts[0] * 60 + parts[1]) * 1_000).toLong()
                    else -> null
                }
            }

            else -> value.toDoubleOrNull()?.times(1_000)?.toLong()
        }
    }

    private val NO_LEADING_SPACE = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
    private const val DEFAULT_LINE_DURATION_MS = 4_000L
    private const val DEFAULT_WORD_DURATION_MS = 450L
}
