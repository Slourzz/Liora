package com.liora.android.ui.lyrics.lyrics.lyra.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.liora.android.ui.lyrics.lyrics.lyra.animation.LyraAnimationSpec
import kotlin.math.abs
import kotlin.math.exp

internal data class LyraPlaybackFrame(
    val positionMs: Double,
    val deltaSeconds: Double,
    val frameNanos: Long,
)

/**
 * Reloj visual continuo de Lyra.
 *
 * Media3 puede entregar posiciones discretas.
 * Lyra necesita una posición continua por frame, similar a cómo
 * am-lyrics normalmente recibe currentTime desde requestAnimationFrame.
 *
 * Reglas:
 *
 * - Playback normal:
 *      avanza cada frame.
 *
 * - Pequeñas correcciones de Media3:
 *      se absorben gradualmente.
 *
 * - Seek real:
 *      salta inmediatamente.
 *
 * - Pausa:
 *      queda exactamente en la posición del reproductor.
 *
 * Nunca hacemos spring ni overshoot con el tiempo.
 */
@Composable
internal fun rememberLyraPlaybackClock(
    playerPositionMs: Long,
    isPlaying: Boolean,
): LyraPlaybackFrame {

    val initialPosition =
        playerPositionMs
            .coerceAtLeast(0L)
            .toDouble()

    var visualPositionMs by remember {
        mutableDoubleStateOf(initialPosition)
    }

    var deltaSeconds by remember {
        mutableDoubleStateOf(0.0)
    }

    var frameNanos by remember {
        mutableLongStateOf(0L)
    }

    /*
     * Diferencia que todavía debemos absorber entre
     * el reloj visual y Media3.
     */
    var pendingCorrectionMs by remember {
        mutableDoubleStateOf(0.0)
    }

    /*
     * ---------------------------------------------------------
     * ACTUALIZACIONES EXTERNAS DE MEDIA3
     * ---------------------------------------------------------
     *
     * IMPORTANTÍSIMO:
     *
     * NO reiniciamos visualPositionMs en cada actualización.
     *
     * Solo:
     *
     * 1. hacemos snap ante un seek grande;
     * 2. corregimos lentamente drift pequeño;
     * 3. sincronizamos exactamente estando pausados.
     */
    LaunchedEffect(
        playerPositionMs,
        isPlaying,
    ) {
        val externalPosition =
            playerPositionMs
                .coerceAtLeast(0L)
                .toDouble()

        if (!isPlaying) {
            visualPositionMs =
                externalPosition

            pendingCorrectionMs =
                0.0

            deltaSeconds =
                0.0

            frameNanos =
                0L

            return@LaunchedEffect
        }

        val driftMs =
            externalPosition -
                    visualPositionMs

        /*
         * am-lyrics usa 500 ms como SEEK_THRESHOLD_MS.
         *
         * Un cambio suficientemente grande no se suaviza:
         * es un salto deliberado del reproductor.
         */
        if (
            abs(driftMs) >=
            LyraAnimationSpec.SEEK_THRESHOLD_MS
        ) {
            visualPositionMs =
                externalPosition

            pendingCorrectionMs =
                0.0

            frameNanos =
                0L
        } else {
            /*
             * Poll normal del reproductor.
             *
             * NO hacemos snap.
             */
            pendingCorrectionMs =
                driftMs
        }
    }

    /*
     * ---------------------------------------------------------
     * FRAME CLOCK
     * ---------------------------------------------------------
     */
    LaunchedEffect(isPlaying) {

        if (!isPlaying) {
            return@LaunchedEffect
        }

        var previousFrameNanos =
            0L

        while (true) {

            withFrameNanos { now ->

                /*
                 * Primer frame.
                 */
                if (previousFrameNanos == 0L) {
                    previousFrameNanos =
                        now

                    frameNanos =
                        now

                    deltaSeconds =
                        0.0

                    return@withFrameNanos
                }

                /*
                 * Tiempo real transcurrido.
                 *
                 * Limitamos frames gigantes después de:
                 *
                 * - suspensión
                 * - cambio de Activity
                 * - frame perdido
                 */
                val dtSeconds =
                    (
                            (
                                    now -
                                            previousFrameNanos
                                    )
                                .coerceAtLeast(0L)
                                .toDouble() /
                                    1_000_000_000.0
                            )
                        .coerceIn(
                            0.0,
                            MAX_FRAME_DELTA_SECONDS,
                        )

                previousFrameNanos =
                    now

                val normalAdvanceMs =
                    dtSeconds *
                            1000.0

                /*
                 * -------------------------------------------------
                 * DRIFT CORRECTION
                 * -------------------------------------------------
                 *
                 * Corrección exponencial sin rebote.
                 *
                 * Esto es importante:
                 *
                 * NO animamos la posición con tween.
                 * NO usamos spring.
                 *
                 * Estamos alterando ligeramente la VELOCIDAD del
                 * reloj hasta alinearnos otra vez con Media3.
                 */
                var correctionThisFrame =
                    0.0

                if (
                    abs(pendingCorrectionMs) >
                    CORRECTION_EPSILON_MS
                ) {
                    val alpha =
                        1.0 -
                                exp(
                                    -dtSeconds /
                                            CORRECTION_TIME_CONSTANT_SECONDS,
                                )

                    val desiredCorrection =
                        pendingCorrectionMs *
                                alpha

                    /*
                     * Una corrección negativa jamás puede hacer
                     * retroceder visualmente las letras durante
                     * playback normal.
                     *
                     * Simplemente hacemos que el reloj avance
                     * temporalmente un poco más lento.
                     */
                    val maximumSlowDown =
                        normalAdvanceMs *
                                MAX_SLOWDOWN_RATIO

                    /*
                     * Una corrección positiva puede acelerar el
                     * reloj ligeramente hasta alcanzar Media3.
                     */
                    val maximumSpeedUp =
                        normalAdvanceMs *
                                MAX_SPEEDUP_RATIO

                    correctionThisFrame =
                        desiredCorrection
                            .coerceIn(
                                -maximumSlowDown,
                                maximumSpeedUp,
                            )

                    /*
                     * No pasarnos del error pendiente.
                     */
                    if (
                        abs(correctionThisFrame) >
                        abs(pendingCorrectionMs)
                    ) {
                        correctionThisFrame =
                            pendingCorrectionMs
                    }

                    pendingCorrectionMs -=
                        correctionThisFrame
                } else {
                    pendingCorrectionMs =
                        0.0
                }

                /*
                 * El tiempo visual solamente avanza hacia delante
                 * salvo cuando ocurrió un seek explícito arriba.
                 */
                val totalAdvanceMs =
                    (
                            normalAdvanceMs +
                                    correctionThisFrame
                            )
                        .coerceAtLeast(
                            0.0,
                        )

                visualPositionMs +=
                    totalAdvanceMs

                deltaSeconds =
                    dtSeconds

                frameNanos =
                    now
            }
        }
    }

    return LyraPlaybackFrame(
        positionMs =
            visualPositionMs,
        deltaSeconds =
            deltaSeconds,
        frameNanos =
            frameNanos,
    )
}

private const val MAX_FRAME_DELTA_SECONDS =
    0.05

/*
 * ~180 ms para absorber una pequeña desviación.
 *
 * Lo bastante rápido para mantenerse sincronizado,
 * pero sin que se vea como un snap.
 */
private const val CORRECTION_TIME_CONSTANT_SECONDS =
    0.18

/*
 * Permitimos frenar hasta 85% del avance normal.
 *
 * Sigue avanzando siempre.
 */
private const val MAX_SLOWDOWN_RATIO =
    0.85

/*
 * Permitimos añadir hasta 150% del avance normal.
 *
 * Velocidad máxima temporal ≈ 2.5x durante la corrección,
 * durante unos pocos frames.
 */
private const val MAX_SPEEDUP_RATIO =
    1.50

private const val CORRECTION_EPSILON_MS =
    0.25