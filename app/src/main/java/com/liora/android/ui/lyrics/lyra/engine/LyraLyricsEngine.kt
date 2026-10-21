package com.liora.android.ui.lyrics.lyrics.lyra.engine

import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricWord
import com.liora.android.ui.lyrics.lyrics.lyra.animation.LyraAnimationSpec
import kotlin.math.abs
import kotlin.math.max

internal enum class LyraElementState {
    Pending,
    Active,
    Finished,
}

internal data class LyraWordFrame(
    val index: Int,
    val state: LyraElementState,
    val progress: Float,
)

internal data class LyraBackgroundFrame(
    val index: Int,
    val active: Boolean,
    val words: List<LyraWordFrame>,
)

internal data class LyraLineFrame(
    val index: Int,
    val active: Boolean,
    val preActive: Boolean,
    val distanceFromFocus: Int,
    val words: List<LyraWordFrame>,
    val backgrounds: List<LyraBackgroundFrame>,
)

internal data class LyraInstrumentalGap(
    val insertBeforeIndex: Int,
    val startMs: Double,
    val endMs: Double,
) {
    val durationMs: Double
        get() = endMs - startMs
}

internal data class LyraEngineFrame(
    val positionMs: Double,

    val isSeek: Boolean,

    val activeLineIndices: List<Int>,

    /*
     * Línea que visualmente gobierna el stack:
     * blur, opacidad y posición relativa.
     */
    val focusLineIndex: Int?,

    /*
     * Línea hacia la que debe comenzar el scroll predictivo.
     */
    val scrollTargetIndex: Int?,

    val scrollLookAheadMs: Double,

    /*
     * Blur progresivo de la siguiente línea.
     * Equivale al filtro dinámico de am-lyrics.
     */
    val nextLineBlurEm: Float,

    val instrumentalGap: LyraInstrumentalGap?,

    val lines: List<LyraLineFrame>,
)

internal class LyraLyricsEngine {

    private var previousPositionMs = 0.0
    internal var lastPrimaryIndex: Int? = null

    fun reset(positionMs: Double = 0.0) {
        previousPositionMs = positionMs
        lastPrimaryIndex = null
    }

    fun update(
        lyrics: List<LyricLine>,
        positionMs: Double,
        /**
         * Solo hace falta calcular los tiempos de palabra para la línea que
         * está sonando (y la que entra). En modo por línea este trabajo se
         * evita por completo.
         */
        computeWordFrames: Boolean = true,
    ): LyraEngineFrame {

        if (lyrics.isEmpty()) {
            previousPositionMs = positionMs

            return LyraEngineFrame(
                positionMs = positionMs,
                isSeek = false,
                activeLineIndices = emptyList(),
                focusLineIndex = null,
                scrollTargetIndex = null,
                scrollLookAheadMs =
                    LyraAnimationSpec.SCROLL_MIN_LOOKAHEAD_MS,
                nextLineBlurEm = 0f,
                instrumentalGap = null,
                lines = emptyList(),
            )
        }

        val isSeek =
            abs(positionMs - previousPositionMs) >
                    LyraAnimationSpec.SEEK_THRESHOLD_MS

        val activeLineIndices =
            findActiveLineIndices(
                lyrics = lyrics,
                timeMs = positionMs,
            )

        val currentAudioIndex =
            findCurrentAudioIndex(
                lyrics = lyrics,
                timeMs = positionMs,
            )

        val scrollLookAheadMs =
            calculateScrollLookAhead(
                lyrics = lyrics,
                currentAudioIndex = currentAudioIndex,
            )

        val predictiveTime =
            positionMs + scrollLookAheadMs

        val predictiveActiveLines =
            findActiveLineIndices(
                lyrics = lyrics,
                timeMs = predictiveTime,
            )

        val rawPredictiveIndex =
            findRawLineIndexAtTime(
                lyrics = lyrics,
                timeMs = predictiveTime,
            )

        val scrollTargetIndex =
            resolvePrimaryScrollIndex(
                lyrics = lyrics,
                candidateIndex = rawPredictiveIndex,
                predictiveActiveLines = predictiveActiveLines,
            )

        if (scrollTargetIndex != null) {
            lastPrimaryIndex = scrollTargetIndex
        }

        val focusLineIndex =
            scrollTargetIndex
                ?: activeLineIndices.firstOrNull()
                ?: lastPrimaryIndex

        val gap =
            findInstrumentalGapAt(
                lyrics = lyrics,
                timeMs = positionMs,
            )

        val nextLineBlur =
            calculateNextLineBlur(
                lyrics = lyrics,
                currentAudioIndex = currentAudioIndex,
                activeLineIndices = activeLineIndices,
                timeMs = positionMs,
            )

        val frames =
            lyrics.mapIndexed { index, line ->

                val active =
                    index in activeLineIndices

                val preActive =
                    !active &&
                            scrollTargetIndex == index

                val distance =
                    focusLineIndex
                        ?.let { index - it }
                        ?: 0

                val needsWordFrames =
                    computeWordFrames &&
                            (active || preActive)

                val wordFrames =
                    if (needsWordFrames) {
                        buildWordFrames(
                            words = line.words,
                            timeMs = positionMs,
                        )
                    } else {
                        emptyList()
                    }

                val backgroundFrames =
                    line.background.mapIndexed { backgroundIndex, background ->

                        LyraBackgroundFrame(
                            index = backgroundIndex,
                            active =
                                positionMs >= background.startMs &&
                                        positionMs <= background.endMs,
                            words =
                                if (needsWordFrames) {
                                    buildWordFrames(
                                        words = background.words,
                                        timeMs = positionMs,
                                    )
                                } else {
                                    emptyList()
                                },
                        )
                    }

                LyraLineFrame(
                    index = index,
                    active = active,
                    preActive = preActive,
                    distanceFromFocus = distance,
                    words = wordFrames,
                    backgrounds = backgroundFrames,
                )
            }

        previousPositionMs = positionMs

        return LyraEngineFrame(
            positionMs = positionMs,
            isSeek = isSeek,
            activeLineIndices = activeLineIndices,
            focusLineIndex = focusLineIndex,
            scrollTargetIndex = scrollTargetIndex,
            scrollLookAheadMs = scrollLookAheadMs,
            nextLineBlurEm = nextLineBlur,
            instrumentalGap = gap,
            lines = frames,
        )
    }
}

/*
 * am-lyrics considera activa una línea hasta highlightEndTime,
 * no simplemente hasta line.endMs.
 */
private fun findActiveLineIndices(
    lyrics: List<LyricLine>,
    timeMs: Double,
): List<Int> {

    val result = mutableListOf<Int>()

    lyrics.forEachIndexed { index, line ->

        if (line.startMs > timeMs) {
            return@forEachIndexed
        }

        val end =
            getLineHighlightEndTime(
                lyrics = lyrics,
                index = index,
            )

        if (
            timeMs >= line.startMs &&
            timeMs < end
        ) {
            result += index
        }
    }

    return result
}

private fun getLineHighlightEndTime(
    lyrics: List<LyricLine>,
    index: Int,
): Double {

    val line =
        lyrics.getOrNull(index)
            ?: return 0.0

    val backgroundEnd =
        line.background.maxOfOrNull { background ->
            max(
                background.endMs,
                background.words.maxOfOrNull(LyricWord::endMs)
                    ?: background.endMs,
            )
        } ?: line.startMs

    val wordEnd =
        line.words.maxOfOrNull(LyricWord::endMs)
            ?: line.endMs

    val rawEnd =
        maxOf(
            line.endMs,
            wordEnd,
            backgroundEnd,
            line.startMs,
        ).toDouble()

    val nextLine =
        lyrics.getOrNull(index + 1)

    /*
     * Última línea.
     * am-lyrics mantiene el highlight otros 200 ms.
     */
    if (
        nextLine == null ||
        nextLine.startMs <= line.startMs
    ) {
        return if (rawEnd > line.startMs) {
            rawEnd + 200.0
        } else {
            rawEnd
        }
    }

    if (rawEnd > line.startMs) {

        /*
         * Overlap real:
         * no cortamos la línea anterior.
         */
        if (nextLine.startMs < rawEnd) {
            return rawEnd
        }

        val gapToNext =
            nextLine.startMs - rawEnd

        /*
         * Si es instrumental largo,
         * dejamos terminar la línea normalmente.
         */
        if (
            gapToNext >=
            LyraAnimationSpec.INSTRUMENTAL_THRESHOLD_MS
        ) {
            return rawEnd
        }
    }

    /*
     * En gaps normales la línea conserva highlight
     * hasta que comienza la siguiente.
     */
    return nextLine.startMs.toDouble()
}

private fun findCurrentAudioIndex(
    lyrics: List<LyricLine>,
    timeMs: Double,
): Int {

    var result = -1

    for (index in lyrics.indices) {

        if (lyrics[index].startMs > timeMs) {
            break
        }

        result = index
    }

    return result
}

private fun calculateScrollLookAhead(
    lyrics: List<LyricLine>,
    currentAudioIndex: Int,
): Double {

    var lookAhead =
        LyraAnimationSpec.SCROLL_MIN_LOOKAHEAD_MS

    if (
        currentAudioIndex >= 0 &&
        currentAudioIndex + 1 < lyrics.size
    ) {
        val current =
            lyrics[currentAudioIndex]

        val next =
            lyrics[currentAudioIndex + 1]

        val gap =
            next.startMs.toDouble() -
                    current.endMs.toDouble()

        lookAhead =
            gap.coerceIn(
                LyraAnimationSpec.SCROLL_MIN_LOOKAHEAD_MS,
                LyraAnimationSpec.SCROLL_MAX_LOOKAHEAD_MS,
            )
    }

    return lookAhead
}

/*
 * Stable forward scan.
 *
 * am-lyrics usa la primera línea cuyo intervalo
 * contiene el tiempo para evitar jitter con overlaps.
 */
private fun findRawLineIndexAtTime(
    lyrics: List<LyricLine>,
    timeMs: Double,
): Int? {

    for (index in lyrics.indices) {

        val line =
            lyrics[index]

        if (line.startMs > timeMs) {
            break
        }

        if (
            timeMs >= line.startMs &&
            timeMs < line.endMs
        ) {
            return index
        }
    }

    return null
}

/*
 * Replica el guard de am-lyrics para overlaps:
 *
 * si una línea nueva comparte exactamente el mismo end time
 * que la primaria actual, no avanzamos inmediatamente cuando
 * el cluster tiene <= 3 líneas.
 */
private fun LyraLyricsEngine.resolvePrimaryScrollIndex(
    lyrics: List<LyricLine>,
    candidateIndex: Int?,
    predictiveActiveLines: List<Int>,
): Int? {

    candidateIndex ?: return null

    val previous =
        lastPrimaryIndex
            ?: return candidateIndex

    if (
        candidateIndex > previous &&
        previous in lyrics.indices &&
        candidateIndex in lyrics.indices &&
        lyrics[previous].endMs ==
        lyrics[candidateIndex].endMs &&
        predictiveActiveLines.size <= 3
    ) {
        return previous
    }

    return candidateIndex
}

private fun findInstrumentalGapAt(
    lyrics: List<LyricLine>,
    timeMs: Double,
): LyraInstrumentalGap? {

    if (lyrics.isEmpty()) {
        return null
    }

    val first =
        lyrics.first()

    /*
     * Instrumental antes de la primera línea.
     */
    if (
        timeMs >= 0 &&
        timeMs < first.startMs
    ) {
        val start = 0.0
        val end = first.startMs.toDouble()

        if (
            end - start >=
            LyraAnimationSpec.INSTRUMENTAL_THRESHOLD_MS
        ) {
            return LyraInstrumentalGap(
                insertBeforeIndex = 0,
                startMs = start,
                endMs = end,
            )
        }
    }

    for (index in 0 until lyrics.lastIndex) {

        val current =
            lyrics[index]

        val next =
            lyrics[index + 1]

        val start =
            current.endMs.toDouble()

        val end =
            next.startMs.toDouble()

        if (
            timeMs > start &&
            timeMs < end &&
            end - start >=
            LyraAnimationSpec.INSTRUMENTAL_THRESHOLD_MS
        ) {
            return LyraInstrumentalGap(
                insertBeforeIndex = index + 1,
                startMs = start,
                endMs = end,
            )
        }
    }

    return null
}

private fun calculateNextLineBlur(
    lyrics: List<LyricLine>,
    currentAudioIndex: Int,
    activeLineIndices: List<Int>,
    timeMs: Double,
): Float {

    val nextIndex =
        currentAudioIndex + 1

    if (nextIndex !in lyrics.indices) {
        return 0f
    }

    if (nextIndex in activeLineIndices) {
        return 0f
    }

    val remaining =
        max(
            0.0,
            lyrics[nextIndex].startMs.toDouble() - timeMs,
        )

    val fraction =
        (
                remaining /
                        LyraAnimationSpec.NEXT_LINE_UNBLUR_DURATION_MS
                )
            .coerceIn(0.0, 1.0)

    return (
            LyraAnimationSpec.NEXT_LINE_BASE_BLUR_EM *
                    fraction.toFloat()
            )
}

private fun buildWordFrames(
    words: List<LyricWord>,
    timeMs: Double,
): List<LyraWordFrame> {

    return words.mapIndexed { index, word ->

        val start =
            word.startMs.toDouble()

        val end =
            word.endMs
                .coerceAtLeast(word.startMs + 1L)
                .toDouble()

        val state =
            when {
                timeMs < start ->
                    LyraElementState.Pending

                timeMs > end ->
                    LyraElementState.Finished

                else ->
                    LyraElementState.Active
            }

        val progress =
            when (state) {
                LyraElementState.Pending ->
                    0f

                LyraElementState.Finished ->
                    1f

                LyraElementState.Active ->
                    (
                            (timeMs - start) /
                                    (end - start)
                            )
                        .coerceIn(0.0, 1.0)
                        .toFloat()
            }

        LyraWordFrame(
            index = index,
            state = state,
            progress = progress,
        )
    }
}
