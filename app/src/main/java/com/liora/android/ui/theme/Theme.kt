package com.liora.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
 */
@Composable
fun LioraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
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
