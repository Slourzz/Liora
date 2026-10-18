package com.liora.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Paleta de respaldo (rosa cálido de Liora) para dispositivos sin Material You
 * dinámico, es decir Android 11 o anterior.
 */
private val LioraDarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF56132C),
    primaryContainer = Color(0xFF731F3D),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE3BDC6),
    onSecondary = Color(0xFF422931),
    secondaryContainer = Color(0xFF5A3F47),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFFEFBD94),
    onTertiary = Color(0xFF462A0E),
    background = Color(0xFF191113),
    onBackground = Color(0xFFF0DEE1),
    surface = Color(0xFF191113),
    onSurface = Color(0xFFF0DEE1),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD5C2C6),
    outline = Color(0xFF9E8C90),
)

private val LioraLightColors = lightColorScheme(
    primary = Color(0xFF8E4A5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3A0719),
    secondary = Color(0xFF75565E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF2B151B),
    tertiary = Color(0xFF7D5736),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF3DDE1),
    onSurfaceVariant = Color(0xFF514347),
    outline = Color(0xFF857377),
)

/**
 * Tema de Liora.
 *
 * En Android 12 o superior toma los colores del fondo de pantalla del usuario
 * (Material You); en versiones anteriores usa la paleta rosa propia de la app.
 *
 * [colorSchemeOverride] permite sustituir toda la paleta, que es lo que usa el
 * estilo Dinámico para pintar la app con los colores de la portada actual.
 */
@Composable
fun LioraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = colorSchemeOverride ?: when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LioraDarkColors
        else -> LioraLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * Indica si el tema activo es oscuro mirando el brillo de la superficie.
 *
 * Sirve para reaccionar al modo elegido en Ajustes (claro, oscuro o sistema) y
 * no solo al modo del teléfono, que es lo que responde `isSystemInDarkTheme`.
 */
val ColorScheme.isDark: Boolean
    get() = surface.luminance() < 0.5f

/**
 * Pinta los iconos de las barras del sistema (estado y navegación) en claro u
 * oscuro, para que sigan al tema elegido dentro de la app.
 */
@Composable
fun LioraSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
