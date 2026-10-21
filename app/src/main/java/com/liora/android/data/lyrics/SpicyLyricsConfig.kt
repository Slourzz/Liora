package com.liora.android.data.lyrics

/**
 * Configuración de la API de Spicy Lyrics.
 *
 * `PUBLISHABLE_KEY` es la clave de cliente (`sl_pk_…`) de la aplicación
 * registrada en el panel de Spicy Lyrics con el cliente nativo permitido. Las
 * claves publicables están pensadas para viajar dentro de la app; aquí no debe
 * aparecer nunca una clave secreta (`sl_sk_…`).
 */
internal object SpicyLyricsConfig {

    /** Servidor documentado por Spicy Lyrics. */
    const val BASE_URL = "https://api.spicylyrics.org"

    /**
     * Clave publicable de la aplicación. Si está vacía, la app se salta Spicy
     * Lyrics y usa directamente el siguiente proveedor.
     */
    const val PUBLISHABLE_KEY = "sl_pk_VH-AVInQHCuynx_V9u5HZybnGP5Job23sNVvJT8fLuw"
}
