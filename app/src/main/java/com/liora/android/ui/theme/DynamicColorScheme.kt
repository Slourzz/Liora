package com.liora.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song

/** Duración del degradado al cambiar de una canción a otra. */
private const val TransitionMs = 900

/**
 * Paleta del estilo Dinámico.
 *
 * Toma el color dominante de la portada de [song] (la etiqueta del archivo, la
 * del teléfono o, si no hay, la de iTunes) y arma con él toda la paleta: los
 * mismos colores que usa Material You, pero salidos de la música que suena. Al
 * cambiar de canción los colores se deslizan con un degradado suave; si no hay
 * portada, se queda la paleta de siempre.
 */
@Composable
fun rememberDynamicColorScheme(song: Song?, darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    var seed by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(song?.id) {
        seed = ArtworkRepository.seedColor(context, song)
    }

    val base = remember(context, darkTheme) { lioraBaseScheme(context, darkTheme) }
    val target = remember(seed, darkTheme, base) {
        seed?.let { dynamicScheme(it, darkTheme) } ?: base
    }
    return animateScheme(target)
}

/**
 * Arma una paleta Material completa a partir de un solo color de portada: los
 * tonos secundarios giran alrededor del principal y cada ranura recibe la
 * claridad que le toca según el modo claro u oscuro.
 */
private fun dynamicScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val primaryHue = hsl[0]
    val saturation = hsl[1]
    val secondaryHue = (primaryHue + 42f) % 360f
    val tertiaryHue = (primaryHue + 84f) % 360f

    fun tone(hue: Float, saturation: Float, lightness: Float): Color = Color(
        ColorUtils.HSLToColor(
            floatArrayOf(hue % 360f, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f)),
        ),
    )

    val vivid = (saturation * 0.9f).coerceIn(0.35f, 0.72f)
    val soft = (saturation * 0.55f).coerceIn(0.22f, 0.48f)

    return if (dark) {
        darkColorScheme(
            primary = tone(primaryHue, (saturation * 0.75f).coerceIn(0.35f, 0.65f), 0.80f),
            onPrimary = tone(primaryHue, 0.7f, 0.18f),
            primaryContainer = tone(primaryHue, soft, 0.32f),
            onPrimaryContainer = tone(primaryHue, soft, 0.92f),
            secondary = tone(secondaryHue, soft, 0.80f),
            onSecondary = tone(secondaryHue, 0.5f, 0.18f),
            secondaryContainer = tone(secondaryHue, soft, 0.30f),
            onSecondaryContainer = tone(secondaryHue, soft, 0.92f),
            tertiary = tone(tertiaryHue, soft, 0.80f),
            onTertiary = tone(tertiaryHue, 0.5f, 0.18f),
            tertiaryContainer = tone(tertiaryHue, soft, 0.32f),
            onTertiaryContainer = tone(tertiaryHue, soft, 0.92f),
            background = tone(primaryHue, 0.18f, 0.10f),
            onBackground = tone(primaryHue, 0.12f, 0.92f),
            surface = tone(primaryHue, 0.18f, 0.10f),
            onSurface = tone(primaryHue, 0.12f, 0.92f),
            surfaceVariant = tone(primaryHue, 0.14f, 0.24f),
            onSurfaceVariant = tone(primaryHue, 0.12f, 0.78f),
            surfaceContainerLowest = tone(primaryHue, 0.16f, 0.07f),
            surfaceContainerLow = tone(primaryHue, 0.17f, 0.13f),
            surfaceContainer = tone(primaryHue, 0.17f, 0.16f),
            surfaceContainerHigh = tone(primaryHue, 0.16f, 0.19f),
            surfaceContainerHighest = tone(primaryHue, 0.15f, 0.22f),
            surfaceDim = tone(primaryHue, 0.16f, 0.08f),
            surfaceBright = tone(primaryHue, 0.15f, 0.26f),
            outline = tone(primaryHue, 0.12f, 0.60f),
            outlineVariant = tone(primaryHue, 0.12f, 0.30f),
            inverseSurface = tone(primaryHue, 0.12f, 0.90f),
            inverseOnSurface = tone(primaryHue, 0.18f, 0.16f),
            inversePrimary = tone(primaryHue, vivid, 0.40f),
        )
    } else {
        lightColorScheme(
            primary = tone(primaryHue, vivid, 0.40f),
            onPrimary = tone(primaryHue, 0.3f, 1f),
            primaryContainer = tone(primaryHue, soft, 0.90f),
            onPrimaryContainer = tone(primaryHue, vivid, 0.16f),
            secondary = tone(secondaryHue, soft, 0.38f),
            onSecondary = tone(secondaryHue, 0.3f, 1f),
            secondaryContainer = tone(secondaryHue, soft, 0.90f),
            onSecondaryContainer = tone(secondaryHue, vivid, 0.15f),
            tertiary = tone(tertiaryHue, soft, 0.40f),
            onTertiary = tone(tertiaryHue, 0.3f, 1f),
            tertiaryContainer = tone(tertiaryHue, soft, 0.90f),
            onTertiaryContainer = tone(tertiaryHue, vivid, 0.16f),
            background = tone(primaryHue, 0.30f, 0.985f),
            onBackground = tone(primaryHue, 0.25f, 0.10f),
            surface = tone(primaryHue, 0.28f, 0.985f),
            onSurface = tone(primaryHue, 0.22f, 0.11f),
            surfaceVariant = tone(primaryHue, 0.26f, 0.91f),
            onSurfaceVariant = tone(primaryHue, 0.18f, 0.30f),
            surfaceContainerLowest = tone(primaryHue, 0.30f, 1f),
            surfaceContainerLow = tone(primaryHue, 0.30f, 0.96f),
            surfaceContainer = tone(primaryHue, 0.28f, 0.93f),
            surfaceContainerHigh = tone(primaryHue, 0.26f, 0.90f),
            surfaceContainerHighest = tone(primaryHue, 0.24f, 0.87f),
            surfaceDim = tone(primaryHue, 0.22f, 0.85f),
            surfaceBright = tone(primaryHue, 0.30f, 0.985f),
            outline = tone(primaryHue, 0.16f, 0.50f),
            outlineVariant = tone(primaryHue, 0.14f, 0.78f),
            inverseSurface = tone(primaryHue, 0.18f, 0.19f),
            inverseOnSurface = tone(primaryHue, 0.12f, 0.95f),
            inversePrimary = tone(primaryHue, (saturation * 0.7f).coerceIn(0.30f, 0.60f), 0.78f),
        )
    }
}

/**
 * Cambia cada color poco a poco: al pasar de una canción a otra la app entera se
 * tiñe con un degradado en vez de saltar de golpe.
 */
@Composable
private fun animateScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = TransitionMs)

    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
    val onTertiary by animateColorAsState(target.onTertiary, spec, label = "onTertiary")
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer")
    val background by animateColorAsState(target.background, spec, label = "background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "outlineVariant")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        outline = outline,
        outlineVariant = outlineVariant,
    )
}
