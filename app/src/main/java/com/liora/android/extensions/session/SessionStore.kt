package com.liora.android.extensions.session

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La sesión de una cuenta con la que la app ha iniciado sesión.
 *
 * YouTube (y otros servicios) exigen una sesión con cookies para servir el
 * audio: sin ella responden "inicia sesión para confirmar que no eres un
 * bot" y no dan ninguna fuente. Esta clase guarda esas cookies para que la
 * app pueda entregárselas al proveedor que las necesita.
 *
 * Las cookies **no** se guardan en claro: se cifran con una clave que vive
 * en el almacén de claves del propio Android (`AndroidKeyStore`), de forma que
 * un fichero copiado del almacenamiento de la app no sirve de nada.
 *
 * Aquí no hay ningún secreto escrito a mano: la sesión la consigue el
 * usuario iniciando sesión en la página real del servicio.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences("liora_sesiones", Context.MODE_PRIVATE)

    /**
     * Guarda la sesión de un servicio.
     *
     * @param service el identificador del servicio ("youtube", por ejemplo).
     * @param cookies las cookies de la sesión, en el formato de una cabecera
     *   `Cookie`. Solo las del propio servicio: las de otro dominio se
     *   quedan fuera.
     * @param sapisid la cookie `SAPISID`, que solo se usa para firmar las
     *   peticiones y nunca se envía como cookie.
     * @param avatarUrl la dirección de la foto de la cuenta, para enseñarla.
     */
    fun save(service: String, cookies: String, sapisid: String? = null, avatarUrl: String? = null) {
        val clean = cookies.trim()
        if (clean.isEmpty()) {
            prefs.edit()
                .remove(key(service))
                .remove(keySapisid(service))
                .remove(keyAvatar(service))
                .apply()
            return
        }
        prefs.edit()
            .putString(key(service), encrypt(clean))
            .putString(keySapisid(service), sapisid?.trim().orEmpty())
            .apply()
        avatarUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString(keyAvatar(service), it).apply()
        }
    }

    /** La dirección de la foto de la cuenta, si la hay. */
    fun avatarUrl(service: String): String? =
        prefs.getString(keyAvatar(service), null)?.takeIf { it.isNotBlank() }

    /** La sesión de un servicio, o null si no hay ninguna. */
    fun load(service: String): String? {
        val raw = prefs.getString(key(service), null) ?: return null
        if (isPlain(raw)) return raw.removePrefix(PLAIN_PREFIX)
        return runCatching { decrypt(raw) }.getOrNull()
    }

    /** ¿Hay sesión guardada para este servicio? */
    fun has(service: String): Boolean = !load(service).isNullOrBlank()

    /** Olvida la sesión de un servicio. */
    fun clear(service: String) {
        prefs.edit().remove(key(service)).remove(keySapisid(service)).apply()
    }

    /**
     * La cabecera `Cookie` de la sesión, si la hay.
     *
     * Se devuelve tal cual: es lo que el servidor espera.
     */
    fun cookieHeader(service: String): String? = load(service)?.takeIf { it.isNotBlank() }

    /**
     * La cabecera de autorización que YouTube espera con la sesión.
     *
     * Es el `SAPISIDHASH`: una firma de la marca de tiempo, la cookie
     * `SAPISID` y el origen. Se calcula aquí, en la app, y no en la
     * extensión: así el secreto (`SAPISID`) no sale nunca del teléfono.
     *
     * @param service el servicio.
     * @param origin el origen al que se pide (por ejemplo
     *   `https://music.youtube.com`).
     */
    fun authorizationHeader(service: String, origin: String): String? {
        val sapisid = prefs.getString(keySapisid(service), null)?.takeIf { it.isNotBlank() }
            ?: return null
        val timestamp = System.currentTimeMillis() / 1000L
        val payload = "$timestamp $sapisid $origin"
        return "SAPISIDHASH ${timestamp}_${sha1(payload)}"
    }

    private fun key(service: String) = "sesion_$service"

    private fun keySapisid(service: String) = "sapisid_$service"

    private fun keyAvatar(service: String) = "avatar_$service"

    // ------------------------------------------------------------- cifrado

    private fun encrypt(text: String): String {
        val key = secretKey() ?: return plainStore(text)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(raw: String): String {
        val key = secretKey()
        if (key == null) return raw
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val data = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * La clave con la que se cifra la sesión.
     *
     * Vive en el almacén de claves de Android: no sale del dispositivo y no se
     * puede copiar con los datos de la app. La clave del almacén **no** se
     * puede exportar, así que no se copia: se usa directamente para cifrar y
     * descifrar con el propio almacén.
     *
     * Si el dispositivo no lo soporta, la sesión se guarda sin cifrar: es
     * peor, pero funciona.
     */
    private fun secretKey(): SecretKey? {
        val store = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrNull() ?: return null
        val entry = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        return runCatching {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                            android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE,
                        )
                        .build(),
                )
            }.generateKey()
        }.getOrNull()
    }

    /** Marca una sesión que se guardó sin cifrar. */
    private fun plainStore(text: String): String = PLAIN_PREFIX + text

    private fun isPlain(value: String): Boolean = value.startsWith(PLAIN_PREFIX)

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** El nombre del servicio de YouTube. */
        const val YOUTUBE = "youtube"

        private const val KEY_ALIAS = "liora_sesion"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128

        /** Marca las sesiones que no se pudieron cifrar. */
        private const val PLAIN_PREFIX = "plain:"
    }
}
