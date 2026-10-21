package com.liora.android.data.lyrics

/**
 * Los idiomas a los que Liora puede traducir la letra.
 *
 * La lista es la que admite el servicio de Unison, con el nombre de cada uno
 * en español para que se pueda elegir sin código. Ahora mismo el destino es
 * el de la app (el español); cuando llegue el selector global de idioma, este
 * será el que se sincronice con él.
 */
object TranslationLanguages {

    /** El idioma de destino por defecto: el de la app, el español. */
    const val DEFAULT = "es"

    /** Todos los idiomas con su nombre, en el orden de la lista. */
    fun supported(): List<Pair<String, String>> = names.map { it.key to it.value }

    /** El nombre de un idioma; si no se conoce, su código. */
    fun label(code: String): String = names[code] ?: code

    /** Si el servicio admite ese idioma. */
    fun isSupported(code: String): Boolean = code.isNotBlank() && code in names

    private val names = mapOf(
        "af" to "Afrikáans",
        "sq" to "Albanés",
        "ar" to "Árabe",
        "be" to "Bielorruso",
        "bg" to "Búlgaro",
        "bn" to "Bengalí",
        "ca" to "Catalán",
        "zh" to "Chino",
        "hr" to "Croata",
        "cs" to "Checo",
        "da" to "Danés",
        "nl" to "Neerlandés",
        "en" to "Inglés",
        "eo" to "Esperanto",
        "et" to "Estonio",
        "fi" to "Finés",
        "fr" to "Francés",
        "gl" to "Gallego",
        "ka" to "Georgiano",
        "de" to "Alemán",
        "el" to "Griego",
        "gu" to "Gujarati",
        "ht" to "Haitiano",
        "he" to "Hebreo",
        "hi" to "Hindi",
        "hu" to "Húngaro",
        "is" to "Islandés",
        "id" to "Indonesio",
        "ga" to "Irlandés",
        "it" to "Italiano",
        "ja" to "Japonés",
        "kn" to "Kannada",
        "ko" to "Coreano",
        "lt" to "Lituano",
        "lv" to "Letón",
        "mk" to "Macedonio",
        "mr" to "Maratí",
        "ms" to "Malayo",
        "mt" to "Maltés",
        "no" to "Noruego",
        "fa" to "Persa",
        "pl" to "Polaco",
        "pt" to "Portugués",
        "ro" to "Rumano",
        "ru" to "Ruso",
        "sk" to "Eslovaco",
        "sl" to "Esloveno",
        "es" to "Español",
        "sw" to "Suajili",
        "sv" to "Sueco",
        "tl" to "Tagalo",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Tailandés",
        "tr" to "Turco",
        "uk" to "Ucraniano",
        "ur" to "Urdu",
        "vi" to "Vietnamita",
        "cy" to "Galés",
    )
}
