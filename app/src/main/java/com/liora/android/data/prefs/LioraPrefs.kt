package com.liora.android.data.prefs

import android.content.Context
import com.liora.android.data.lyrics.TranslationLanguages

/**
 * Preferencias locales de Liora (SharedPreferences, sin dependencias extra).
 */
class LioraPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("liora_settings", Context.MODE_PRIVATE)

    /**
     * Estilo visual de Liora: Material You, Dinámico (colores de la portada) o
     * Glass (cristal líquido). Solo uno puede estar activo a la vez.
     *
     * El antiguo interruptor de Liquid Glass se migra aquí: quien lo tuviera
     * activado pasa a [AppStyle.Glass] y quien no, a [AppStyle.Material].
     */
    var appStyle: AppStyle
        get() {
            val saved = prefs.getString(KEY_APP_STYLE, null)
            AppStyle.entries.firstOrNull { it.name == saved }?.let { return it }
            return if (prefs.getBoolean(KEY_LIQUID_GLASS, true)) {
                AppStyle.Glass
            } else {
                AppStyle.Material
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_STYLE, value.name).apply()

    /** Tema de la app: claro, oscuro o el que tenga el teléfono. */
    var appThemeMode: AppThemeMode
        get() {
            val saved = prefs.getString(KEY_APP_THEME_MODE, null)
            return AppThemeMode.entries.firstOrNull { it.name == saved } ?: AppThemeMode.System
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME_MODE, value.name).apply()

    /** Fondo del reproductor completo: el campo vivo de kawarp o un color. */
    var playerBackground: PlayerBackground
        get() {
            val saved = prefs.getString(KEY_PLAYER_BACKGROUND, null)
            return PlayerBackground.entries.firstOrNull { it.name == saved }
                ?: PlayerBackground.Kawarp
        }
        set(value) = prefs.edit().putString(KEY_PLAYER_BACKGROUND, value.name).apply()

    /**
     * Modo de lectura de las letras.
     *
     * En [wordByWord] cada palabra se enciende cuando empieza su tiempo; si la
     * fuente solo trae tiempos por línea, Lyra cae sola al modo por línea.
     */
    var lyricsWordByWord: Boolean
        get() = prefs.getBoolean(KEY_LYRICS_WORD_BY_WORD, true)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS_WORD_BY_WORD, value).apply()

    /**
     * Nivel de animación de las letras de Lyra.
     *
     * - [LyricAnimationLevel.Simple]: letras planas ligadas a la línea y puntos
     *   de espera de AMLL (respiración y encendido secuencial).
     * - [LyricAnimationLevel.Normal]: palabra a palabra con muelle, tal como
     *   está desde que existe Lyra.
     * - [LyricAnimationLevel.Exaggerated]: motora de spicy-lyrics recreada con
     *   primitivas nativas: muestreo por letras, splits que se abren con física
     *   y brillo sobre la palabra que suena.
     */
    var lyricsAnimationLevel: LyricAnimationLevel
        get() {
            val saved = prefs.getString(KEY_LYRICS_ANIMATION_LEVEL, null)
            return LyricAnimationLevel.entries.firstOrNull { it.name == saved }
                ?: LyricAnimationLevel.Normal
        }
        set(value) = prefs.edit().putString(KEY_LYRICS_ANIMATION_LEVEL, value.name).apply()

    /** Muestra la romanización bajo la letra original cuando la fuente la trae. */
    var lyricsShowRomanization: Boolean
        get() = prefs.getBoolean(KEY_LYRICS_SHOW_ROMANIZATION, false)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS_SHOW_ROMANIZATION, value).apply()

    /** Muestra la traducción bajo la letra original cuando la fuente la trae. */
    var lyricsShowTranslation: Boolean
        get() = prefs.getBoolean(KEY_LYRICS_SHOW_TRANSLATION, false)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS_SHOW_TRANSLATION, value).apply()

    /**
     * Idioma al que se traduce la letra en el teléfono cuando la fuente no trae
     * traducción.
     *
     * Ahora es el de la app, el español. Cuando llegue el selector global de
     * idioma de Liora, será ese selector quien lo sincronice con aquí.
     */
    var lyricsTranslationLanguage: String
        get() = prefs.getString(
            KEY_LYRICS_TRANSLATION_LANGUAGE,
            TranslationLanguages.DEFAULT,
        ) ?: TranslationLanguages.DEFAULT
        set(value) = prefs.edit()
            .putString(KEY_LYRICS_TRANSLATION_LANGUAGE, value)
            .apply()

    /**
     * Carpetas de audio que Liora no debe mostrar ni reproducir. Se guardan por
     * ruta completa para que dos carpetas con el mismo nombre no se confundan.
     */
    var excludedFolders: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_FOLDERS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED_FOLDERS, value).apply()

    /**
     * Cuando está activo, Liora oculta los sonidos del sistema (alarmas,
     * timbres y avisos) que se cuelan en la biblioteca del teléfono.
     */
    var filterSystemSounds: Boolean
        get() = prefs.getBoolean(KEY_FILTER_SYSTEM_SOUNDS, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_SYSTEM_SOUNDS, value).apply()

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

    /**
     * Los repositorios de extensiones que el usuario ha añadido.
     *
     * Cada entrada es la dirección https del `registry.json` de un repositorio
     * externo. Es lo único que Liora guarda de ellos: el registro y los
     * paquetes se descargan cuando hacen falta.
     */
    var extensionRepositories: List<String>
        get() = prefs.getStringSet(KEY_EXTENSION_REPOSITORIES, emptySet()).orEmpty().toList()
        set(value) = prefs.edit()
            .putStringSet(KEY_EXTENSION_REPOSITORIES, value.toSet())
            .apply()

    /**
     * Las extensiones que están desactivadas.
     *
     * Una extensión desactivada sigue instalada (su código y sus ajustes se
     * conservan) pero no aporta ningún proveedor a Liora.
     */
    var disabledExtensions: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_EXTENSIONS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit()
            .putStringSet(KEY_DISABLED_EXTENSIONS, value.toSet())
            .apply()

    /**
     * Los ajustes que el usuario ha puesto a cada extension.
     *
     * Son claves sueltas (por ejemplo, las credenciales de una API), nunca
     * secretos de la app. Se guardan en las preferencias de la app, no en la
     * extension: la extension solo los recibe al inicializarse.
     */
    var extensionSettings: Map<String, Map<String, String>>
        get() {
            val raw = prefs.getString(KEY_EXTENSION_SETTINGS, null) ?: return emptyMap()
            return runCatching {
                val json = org.json.JSONObject(raw)
                val result = mutableMapOf<String, Map<String, String>>()
                for (id in json.keys()) {
                    val values = json.optJSONObject(id) ?: continue
                    result[id] = values.keys().asSequence().associateWith { key ->
                        values.optString(key)
                    }
                }
                result
            }.getOrDefault(emptyMap())
        }
        set(value) {
            val json = org.json.JSONObject()
            for ((id, values) in value) {
                val object_ = org.json.JSONObject()
                for ((key, item) in values) object_.put(key, item)
                json.put(id, object_)
            }
            prefs.edit().putString(KEY_EXTENSION_SETTINGS, json.toString()).apply()
        }

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

        private const val KEY_APP_STYLE = "app_style"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"
        private const val KEY_PLAYER_BACKGROUND = "player_background"
        private const val KEY_LYRICS_WORD_BY_WORD = "lyrics_word_by_word"
        private const val KEY_LYRICS_ANIMATION_LEVEL = "lyrics_animation_level"
        private const val KEY_LYRICS_SHOW_ROMANIZATION = "lyrics_show_romanization"
        private const val KEY_LYRICS_SHOW_TRANSLATION = "lyrics_show_translation"
        private const val KEY_LYRICS_TRANSLATION_LANGUAGE = "lyrics_translation_language"

        /** Clave del antiguo interruptor; se lee solo para migrar el estilo. */
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_EXCLUDED_FOLDERS = "excluded_folders"
        private const val KEY_CACHE_MAX_MB = "cache_max_mb"
        private const val DEFAULT_CACHE_MB = 500
        private const val KEY_FILTER_SYSTEM_SOUNDS = "filter_system_sounds"
        private const val KEY_LAST_SONG_ID = "last_song_id"
        private const val KEY_LAST_POSITION_MS = "last_position_ms"
        private const val KEY_LAST_PLAYING = "last_playing"
        private const val KEY_EXTENSION_REPOSITORIES = "extension_repositories"
        private const val KEY_DISABLED_EXTENSIONS = "disabled_extensions"
        private const val KEY_EXTENSION_SETTINGS = "extension_settings"
    }
}

/**
 * Estilo visual de Liora. Los tres son excluyentes: activar uno apaga el otro.
 *
 * - [Material]: el aspecto de siempre, con los colores del sistema (Material You).
 * - [Dynamic]: los colores salen de la portada de la canción actual, y cambian
 *   con un degradado suave al pasar de una canción a otra.
 * - [Glass]: cristal líquido con refracción real (Backdrop): navegación, barra
 *   de música y controles de cristal.
 */
enum class AppStyle { Material, Dynamic, Glass }

/** Modo de tema elegido por quien usa la app. */
enum class AppThemeMode { System, Light, Dark }

/**
 * Fondo del reproductor completo.
 *
 * - [Kawarp]: la portada hecha manchas de color, meciéndose despacio con el
 *   warp de kawarp mientras suena la música.
 * - [Solid]: un único color sacado de la portada, sin degradado y sin gastar
 *   ni un fotograma.
 */
enum class PlayerBackground { Kawarp, Solid }

/**
 * Nivel de animación de las letras, en orden de intensidad. El orden también es
 * el de los pasos del slider de Ajustes (0 = Simple, 2 = Exagerada).
 */
enum class LyricAnimationLevel {
    Simple,
    Normal,
    Exaggerated,
}
