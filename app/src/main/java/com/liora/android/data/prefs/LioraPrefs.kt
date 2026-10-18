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

    /**
     * Carpetas de audio que Liora no debe mostrar ni reproducir. Se guardan por
     * ruta completa para que dos carpetas con el mismo nombre no se confundan.
     */
    var excludedFolders: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, value).apply()

    /**
     * Tamaño máximo de la caché de reproducción en MB. 0 la desactiva; al bajar
     * el límite se borran primero los archivos más viejos.
     */
    var cacheMaxMb: Int
        get() = prefs.getInt(KEY_CACHE_MAX_MB, DEFAULT_CACHE_MB)
        set(value) = prefs.edit().putInt(KEY_CACHE_MAX_MB, value).apply()

    /** Identificador de la última canción reproducida (0 = ninguna). */
    val lastSongId: Long
        get() = prefs.getLong(KEY_LAST_SONG_ID, 0L)

    /** Posición en la que se quedó esa canción, en milisegundos. */
    val lastPositionMs: Long
        get() = prefs.getLong(KEY_LAST_POSITION_MS, 0L)

    /** Si la última sesión estaba sonando, para reanudarla tal cual. */
    val lastPlaying: Boolean
        get() = prefs.getBoolean(KEY_LAST_PLAYING, false)

    /** Guarda de una sola vez canción, posición y estado de la reproducción. */
    fun savePlayback(songId: Long, positionMs: Long, playing: Boolean) {
        prefs.edit()
            .putLong(KEY_LAST_SONG_ID, songId)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .putBoolean(KEY_LAST_PLAYING, playing)
            .apply()
    }

    companion object {
        /** Tamaños de caché que ofrece Ajustes, en MB. */
        val CACHE_SIZES_MB = listOf(0, 200, 500, 1024, 2048, 5120)

        /** Etiqueta legible para un tamaño de caché. */
        fun cacheSizeLabel(mb: Int): String = when {
            mb <= 0 -> "Desactivada"
            mb % 1024 == 0 -> "${mb / 1024} GB"
            else -> "$mb MB"
        }

        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
        private const val KEY_CACHE_MAX_MB = "cache_max_mb"
        private const val DEFAULT_CACHE_MB = 500
        private const val KEY_LAST_SONG_ID = "last_song_id"
        private const val KEY_LAST_POSITION_MS = "last_position_ms"
        private const val KEY_LAST_PLAYING = "last_playing"
    }
}
