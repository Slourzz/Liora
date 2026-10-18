package com.liora.android.data.prefs

import android.content.Context

/**
 * Preferencias locales de Liora (SharedPreferences, sin dependencias extra).
 */
class LioraPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("liora_settings", Context.MODE_PRIVATE)

    /**
     * Cuando está activo, la navegación usa el cristal líquido (píldora con
     * refracción y borde Fresnel, estilo Cloud). Si está apagado, se usa la
     * navegación simple con desenfoque suave.
     */
    var liquidGlass: Boolean
        get() = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        set(value) = prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()

    private companion object {
        const val KEY_LIQUID_GLASS = "liquid_glass"
    }
}
