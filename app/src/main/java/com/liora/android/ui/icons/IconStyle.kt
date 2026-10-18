package com.liora.android.ui.icons

import android.content.ComponentName
import android.content.Context
import androidx.annotation.DrawableRes
import com.liora.android.R

/**
 * Estilos de icono disponibles para el launcher.
 *
 * Cada entrada se corresponde con un `<activity-alias>` declarado en el
 * AndroidManifest: el alias habilitado es el que el launcher muestra como
 * icono de la aplicación, así que cambiar de estilo es simplemente habilitar
 * un alias y deshabilitar el resto (ver [LauncherIconManager]).
 */
enum class IconStyle(
    val alias: String,
    val label: String,
    val description: String,
    @param:DrawableRes val preview: Int,
) {
    Glass(
        alias = "Glass",
        label = "Cristal",
        description = "El degradado original con la nota de cristal",
        preview = R.drawable.ic_style_glass,
    ),
    White(
        alias = "White",
        label = "Blanco glaciar",
        description = "Nota azul noche sobre hielo",
        preview = R.drawable.ic_style_white,
    ),
    Liora(
        alias = "Liora",
        label = "Liora",
        description = "Nota multicolor con degradado pastel",
        preview = R.drawable.ic_style_liora,
    ),
    Outline(
        alias = "Outline",
        label = "Contorno",
        description = "Trazo fino minimalista sobre crema",
        preview = R.drawable.ic_style_outline,
    ),
    Softblue(
        alias = "Softblue",
        label = "Azul suave",
        description = "Nota plana sobre rosa y azul suave",
        preview = R.drawable.ic_style_softblue,
    );

    /** Componente del alias tal y como aparece en el manifiesto. */
    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.icon.$alias")
}
