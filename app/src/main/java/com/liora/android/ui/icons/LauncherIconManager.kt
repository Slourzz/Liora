package com.liora.android.ui.icons

import android.content.Context
import android.content.pm.PackageManager

/**
 * Cambia el icono del launcher habilitando/deshabilitando los
 * `<activity-alias>` declarados en el manifiesto.
 *
 * No hace falta guardar nada en preferencias: el estado del componente que
 * mantiene Android es la propia fuente de verdad.
 */
object LauncherIconManager {

    /** Estilo activo ahora mismo (por defecto [IconStyle.Glass]). */
    fun currentStyle(context: Context): IconStyle {
        val packageManager = context.packageManager
        return IconStyle.entries.firstOrNull { style ->
            isEnabled(packageManager, context, style)
        } ?: IconStyle.Glass
    }

    /**
     * Activa [style] y desactiva el resto.
     *
     * Se habilita primero el alias nuevo y solo después se deshabilitan los
     * demás para que la app nunca desaparezca del launcher a mitad del cambio.
     */
    fun setStyle(context: Context, style: IconStyle) {
        val packageManager = context.packageManager
        packageManager.setComponentEnabledSetting(
            style.componentName(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        IconStyle.entries
            .filter { it != style }
            .forEach { other ->
                packageManager.setComponentEnabledSetting(
                    other.componentName(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private fun isEnabled(
        packageManager: PackageManager,
        context: Context,
        style: IconStyle,
    ): Boolean = when (packageManager.getComponentEnabledSetting(style.componentName(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        // Sin estado explícito manda lo declarado en el manifiesto,
        // donde solo Glass viene habilitado.
        else -> style == IconStyle.Glass
    }
}
