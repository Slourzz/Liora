package com.liora.android.ui.lyrics.lyrics.lyra

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.liora.android.data.model.LyricsCredits
import com.liora.android.data.model.LyricLine
import com.liora.android.data.prefs.LyricAnimationLevel
import com.liora.android.ui.lyrics.lyrics.lyra.engine.LyraLyricsEngine
import com.liora.android.ui.lyrics.lyrics.lyra.engine.rememberLyraPlaybackClock
import com.liora.android.ui.lyrics.lyrics.lyra.renderer.LyraLyricsList

/**
 * Entrada principal del motor Lyra.
 *
 * PlayerUi solo habla con este composable.
 *
 * Aquí conectamos:
 *
 * ExoPlayer position
 *      ↓
 * LyraPlaybackClock
 *      ↓
 * LyraLyricsEngine
 *      ↓
 * LyraLyricsList
 *      ↓
 * LyraLyricLine
 */
@Composable
fun LyraLyricsDisplay(
    lyrics: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    credits: LyricsCredits? = null,
    showCredits: Boolean = true,
    wordByWordEnabled: Boolean = true,
    animationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    showRomanization: Boolean = false,
    showTranslation: Boolean = false,
) {
    /*
     * Clock continuo.
     *
     * Convierte las actualizaciones discretas del reproductor
     * en un tiempo visual actualizado cada frame.
     */
    val playbackFrame =
        rememberLyraPlaybackClock(
            playerPositionMs = positionMs,
            isPlaying = isPlaying,
        )

    /*
     * Una instancia del engine mientras este renderer exista.
     */
    val engine =
        remember {
            LyraLyricsEngine()
        }

    /*
     * Si cambia completamente el set de letras,
     * limpiamos el estado interno de navegación.
     */
    LaunchedEffect(lyrics) {
        engine.reset(
            positionMs =
                positionMs
                    .coerceAtLeast(0L)
                    .toDouble(),
        )
    }

    /*
     * Estado temporal actual de todas las líneas.
     *
     * playbackFrame.positionMs cambia cada frame cuando
     * la reproducción está activa.
     */
    /*
     * Nivel de animación: Simple aplana el karaoke (la línea se rellena
     * entera) y Exagerada conserva el flujo palabra a palabra del engine.
     */
    val levelWordByWordEnabled =
        wordByWordEnabled &&
                animationLevel != LyricAnimationLevel.Simple

    val engineFrame =
        engine.update(
            lyrics = lyrics,
            positionMs =
                playbackFrame.positionMs,
            computeWordFrames =
                levelWordByWordEnabled,
        )

    Box(
        modifier = modifier,
    ) {
        LyraLyricsList(
            lyrics = lyrics,
            frame = engineFrame,
            isPlaying = isPlaying,
            animationsEnabled = animationsEnabled,
            wordByWordEnabled = levelWordByWordEnabled,
            animationLevel = animationLevel,
            showRomanization = showRomanization,
            showTranslation = showTranslation,
            onSeek = onSeek,
            modifier = Modifier.fillMaxSize(),
        )
    }

    /*
     * credits y showCredits permanecen en la API porque
     * PlayerUi ya los entrega y después añadiremos el
     * renderer de créditos de Lyra.
     *
     * No los eliminamos para mantener el contrato del motor.
     */
    @Suppress("UNUSED_VARIABLE")
    val keepCreditsContract =
        credits to showCredits
}