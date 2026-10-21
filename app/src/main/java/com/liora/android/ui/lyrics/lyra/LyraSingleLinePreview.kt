package com.liora.android.ui.lyrics.lyrics.lyra

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liora.android.data.model.LyricLine
import com.liora.android.data.prefs.LyricAnimationLevel
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraLyricsEngine
import com.liora.android.ui.lyrics.lyrics.lyra.engine.rememberLyraPlaybackClock
import com.liora.android.ui.lyrics.lyrics.lyra.renderer.LyraWaitingDots
import com.liora.android.ui.lyrics.lyrics.lyra.renderer.LyraWaveText
import com.liora.android.ui.lyrics.lyrics.lyra.renderer.LyraWordMaskText

/**
 * Preview compacto del reproductor, gobernado por el mismo reloj, motor y
 * renderer de Lyra que el panel completo.
 *
 * Muestra hasta dos líneas —sin puntos suspensivos: lo que no cabe se recorta—
 * y conserva el avance palabra a palabra cuando la fuente trae tiempos. En los
 * silencios largos aparecen los puntos de espera del panel de letras. El
 * chevron vive al final de la frase y abre el panel completo.
 */
@Composable
fun LyraSingleLinePreview(
    lyrics: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    animationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyraPlaybackClock(positionMs, isPlaying)
    val engine = remember { LyraLyricsEngine() }
    LaunchedEffect(lyrics) { engine.reset(positionMs.coerceAtLeast(0L).toDouble()) }
    val frame = engine.update(
        lyrics = lyrics,
        positionMs = clock.positionMs,
        computeWordFrames = animationLevel != LyricAnimationLevel.Simple,
    )
    val activeIndex = frame.activeLineIndices.firstOrNull()
        ?: frame.focusLineIndex
        ?: return
    if (activeIndex !in lyrics.indices) return
    val interaction = remember { MutableInteractionSource() }
    val gap = frame.instrumentalGap

    AnimatedContent(
        targetState = activeIndex,
        contentKey = { index -> lyrics.getOrNull(index)?.startMs ?: index },
        transitionSpec = {
            if (animationsEnabled) {
                (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 3 })
                    .togetherWith(
                        fadeOut(tween(160)) +
                            slideOutVertically(tween(210, easing = FastOutSlowInEasing)) { -it / 3 },
                    )
                    .using(SizeTransform(clip = false))
            } else {
                fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
            }
        },
        label = "lyra-player-line-preview",
        contentAlignment = Alignment.CenterStart,
        modifier = modifier,
    ) { shownIndex ->
        val line = lyrics.getOrNull(shownIndex) ?: return@AnimatedContent
        val lineFrame = frame.lines.getOrNull(shownIndex)
        val lineIsActive = shownIndex in frame.activeLineIndices
        val showWaitingDots = gap != null && shownIndex == activeIndex

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showWaitingDots) {
                LyraWaitingDots(
                    gap = gap,
                    positionMs = frame.positionMs,
                    isPlaying = isPlaying,
                    simpleStyle = animationLevel == LyricAnimationLevel.Simple,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }

            if (animationLevel == LyricAnimationLevel.Exaggerated) {
                /*
                 * Exagerada: la fila del título también lleva la ola con
                 * físicas, los splits abiertos y el resplandor de spicy.
                 */
                LyraWaveText(
                    text = line.text,
                    words = line.words,
                    positionMs = frame.positionMs,
                    isPlaying = isPlaying,
                    dimColor = Color.White.copy(alpha = 0.44f),
                    brightColor = Color.White.copy(alpha = 0.96f),
                    fallbackColor = Color.White.copy(
                        alpha = if (lineIsActive) 0.88f else 0.50f,
                    ),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                LyraWordMaskText(
                    text = line.text,
                    words = line.words,
                    frames = lineFrame?.words.orEmpty(),
                    wordByWord = animationLevel != LyricAnimationLevel.Simple,
                    dimColor = Color.White.copy(alpha = 0.44f),
                    brightColor = Color.White.copy(alpha = 0.96f),
                    fallbackColor = Color.White.copy(
                        alpha = if (lineIsActive) 0.88f else 0.50f,
                    ),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    fillWidth = false,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "Abrir letras",
                tint = Color.White.copy(alpha = 0.42f),
                modifier = Modifier
                    .padding(start = 5.dp)
                    .size(15.dp),
            )
        }
    }
}
