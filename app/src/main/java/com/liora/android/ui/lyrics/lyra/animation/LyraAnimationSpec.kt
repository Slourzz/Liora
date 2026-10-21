package com.liora.android.ui.lyrics.lyrics.lyra.animation

/**
 * Parámetros visuales base de Lyra.
 *
 * Estos valores replican los defaults de am-lyrics.
 *
 * Las medidas llamadas *Px representan unidades visuales equivalentes
 * a CSS px. El renderer será responsable de convertirlas correctamente
 * a unidades Compose según densidad.
 */
internal object LyraAnimationSpec {

    // ─────────────────────────────────────────────
    // Tipografía y layout
    // ─────────────────────────────────────────────

    const val BASE_FONT_SIZE_PX = 34f
    const val LINE_HEIGHT = 1.2f
    const val LINE_SPACING_PX = 25f

    const val INLINE_PADDING_PX = 20f
    const val SCROLL_PADDING_TOP_FRACTION = 0.12f


    // ─────────────────────────────────────────────
    // Scroll / reproducción
    // ─────────────────────────────────────────────

    /*
     * Ventana usada por el engine para anticipar
     * el desplazamiento hacia la siguiente línea.
     */
    const val SCROLL_MIN_LOOKAHEAD_MS = 350.0
    const val SCROLL_MAX_LOOKAHEAD_MS = 500.0

    /*
     * Diferencia de tiempo a partir de la cual
     * Lyra considera que hubo un seek real.
     */
    const val SEEK_THRESHOLD_MS = 500.0


    // ─────────────────────────────────────────────
    // Línea principal
    // ─────────────────────────────────────────────

    const val INACTIVE_LINE_SCALE = 0.98f
    const val ACTIVE_LINE_SCALE = 1.0f

    const val TOUCH_SCALE = 0.96f


    // ─────────────────────────────────────────────
    // Opacidad de líneas
    // ─────────────────────────────────────────────

    const val BASE_LINE_OPACITY = 0.30f

    const val DISTANCE_2_OPACITY = 0.18f
    const val DISTANCE_3_OPACITY = 0.12f
    const val DISTANCE_4_OPACITY = 0.08f


    // ─────────────────────────────────────────────
    // Animaciones de línea
    // ─────────────────────────────────────────────

    const val LINE_FADE_DURATION_MS = 700

    const val TOUCH_SCALE_DURATION_MS = 120

    const val LINE_ACTIVE_SCALE_DURATION_MS = 500
    const val LINE_INACTIVE_SCALE_DURATION_MS = 700


    // ─────────────────────────────────────────────
    // Blur de líneas inactivas
    // ─────────────────────────────────────────────

    const val NEAR_INACTIVE_BLUR_EM = 0.055f

    const val NEXT_2_BLUR_EM = 0.030f
    const val NEXT_3_BLUR_EM = 0.045f

    const val INACTIVE_BLUR_EM = 0.055f


    // ─────────────────────────────────────────────
    // Highlight / karaoke
    // ─────────────────────────────────────────────

    const val HIGHLIGHT_RADIUS_PX = 16f
    const val PROGRESSION_FEATHER_PX = 30f
    const val GLOW_RADIUS_PX = 5f

    // Movimiento vertical máximo del carácter activo.
    const val CHARACTER_RISE_PEAK_PX = -1.25f


    // ─────────────────────────────────────────────
    // Preparación de la siguiente línea
    // ─────────────────────────────────────────────

    /*
     * La siguiente línea comienza desenfocada
     * y se limpia progresivamente antes de entrar.
     */
    const val NEXT_LINE_UNBLUR_DURATION_MS = 2000.0

    const val NEXT_LINE_BASE_BLUR_EM = 0.02f


    // ─────────────────────────────────────────────
    // Background vocals
    // ─────────────────────────────────────────────

    const val BACKGROUND_VOCAL_SCALE = 0.90f
    const val BACKGROUND_VOCAL_FONT_SIZE = 0.65f

    const val BACKGROUND_VOCAL_SPACING_PX = 15f
    const val BACKGROUND_VOCAL_STACK_SHIFT_PX = 7.5f
    const val BACKGROUND_VOCAL_MAX_HEIGHT_EM = 8f

    const val BACKGROUND_VOCAL_EXIT_DURATION_MS = 450


    // ─────────────────────────────────────────────
    // Instrumentales / pausas
    // ─────────────────────────────────────────────

    /*
     * Gap mínimo para mostrar el estado instrumental.
     */
    const val INSTRUMENTAL_THRESHOLD_MS = 7000.0

    const val INSTRUMENTAL_HEIGHT_PX = 40f
    const val INSTRUMENTAL_SPACING_PX = 16f

    const val INSTRUMENTAL_ENTER_DURATION_MS = 400
    const val INSTRUMENTAL_COLLAPSE_DURATION_MS = 500
    const val INSTRUMENTAL_EXIT_DURATION_MS = 350

    const val INSTRUMENTAL_EXIT_SCALE = 0f


    // ─────────────────────────────────────────────
    // Compact
    // viewport < 520
    // ─────────────────────────────────────────────

    const val COMPACT_BREAKPOINT_PX = 520f

    const val COMPACT_FONT_SIZE_PX = 28f
    const val COMPACT_BACKGROUND_VOCAL_FONT_SIZE = 0.857f
    const val COMPACT_LINE_SPACING_PX = 20f

    const val COMPACT_SELECTED_POSITION = 0.18f


    // ─────────────────────────────────────────────
    // Wide
    // viewport >= 900
    // ─────────────────────────────────────────────

    const val WIDE_BREAKPOINT_PX = 900f

    const val WIDE_FONT_SIZE_PX = 48f
    const val WIDE_BACKGROUND_VOCAL_FONT_SIZE = 0.667f
    const val WIDE_LINE_SPACING_PX = 32f

    const val WIDE_SELECTED_POSITION = 0.20f
}