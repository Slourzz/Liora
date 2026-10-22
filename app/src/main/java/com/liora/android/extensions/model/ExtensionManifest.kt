package com.liora.android.extensions.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lo que una extensión puede aportar a Liora.
 *
 * Una extensión declara en su manifiesto los tipos que implementa. Solo se
 * registran como proveedores de esos tipos: una extensión de metadatos nunca
 * aparece como proveedor de descarga.
 */
enum class ProviderType(val wire: String) {
    METADATA("metadata_provider"),
    TRACK_SEARCH("track_search_provider"),
    AUDIO_SOURCE("audio_source_provider"),
    AUDIO_DOWNLOAD("audio_download_provider"),
    LYRICS("lyrics_provider"),
    ;

    companion object {
        fun fromWire(value: String): ProviderType? =
            entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * Los permisos que una extensión pide.
 *
 * `network` es una lista de dominios: solo se pueden pedir peticiones a
 * esos dominios, y la comprobación la hace la capa nativa de Liora, no el
 * código de la extensión.
 */
data class ExtensionPermissions(
    val network: List<String> = emptyList(),
    val storage: Boolean = false,
    val file: Boolean = false,
    /**
     * Las sesiones de cuenta que la extensión puede usar.
     *
     * Son servicios ("youtube", por ejemplo) con los que el usuario ha
     * iniciado sesión en Liora. La extensión **no** ve la sesión: la app se
     * la añade a las peticiones cuando la extensión lo pide aquí. Sin este
     * permiso, la extensión no recibe ninguna cookie.
     */
    val session: List<String> = emptyList(),
) {
    /** ¿Permite este permiso que se hable con ese dominio? */
    fun allowsDomain(domain: String): Boolean {
        val target = domain.trim().lowercase()
        if (target.isEmpty()) return false
        return network.any { allowed ->
            val rule = allowed.trim().lowercase()
            rule == target || (rule.startsWith("*.") && target.endsWith(rule.substring(1)))
        }
    }

    /** ¿Puede esta extensión usar la sesión de ese servicio? */
    fun allowsSession(service: String): Boolean {
        val target = service.trim().lowercase()
        if (target.isEmpty()) return false
        return session.any { it.trim().lowercase() == target }
    }

    companion object {
        val NONE = ExtensionPermissions()

        fun from(json: JSONObject?): ExtensionPermissions {
            if (json == null) return NONE
            val domains = json.optJSONArray("network")?.toStringList().orEmpty()
            return ExtensionPermissions(
                network = domains,
                storage = json.optBoolean("storage", false),
                file = json.optBoolean("file", false),
                session = json.optJSONArray("session")?.toStringList().orEmpty(),
            )
        }
    }
}

/**
 * Un ajuste que la extension pide al usuario.
 *
 * `secret` marca los que son claves: la app los guarda como cualquier otro
 * ajuste, pero no los enseña en ningun registro.
 */
data class ExtensionSetting(
    val key: String,
    val label: String,
    val type: String = "string",
    val secret: Boolean = false,
    val default: String = "",
)

/**
 * El manifiesto de una extensión: el contrato que declara qué es, qué
 * necesita y qué puede hacer.
 */
data class ExtensionManifest(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val author: String,
    val homepage: String,
    val minLioraVersion: String,
    val requiredFeatures: List<String>,
    val types: List<ProviderType>,
    val permissions: ExtensionPermissions,
    val entrypoint: String,
    /** Los ajustes que la extension pide al usuario. */
    val settings: List<ExtensionSetting> = emptyList(),
) {
    /** ¿La extensión declara este tipo de proveedor? */
    fun has(type: ProviderType): Boolean = types.contains(type)

    companion object {
        const val CURRENT_SCHEMA = 1

        /**
         * Lee el manifiesto de un paquete.
         *
         * @throws ExtensionException.InvalidManifest si el manifiesto no cumple
         *   el contrato: sin identificador, sin versión, sin punto de entrada o
         *   con un tipo de proveedor desconocido.
         */
        fun parse(text: String): ExtensionManifest {
            val json = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw ExtensionException.InvalidManifest(
                    "El manifiesto no es un JSON válido: ${error.message}",
                )
            }

            val schema = json.optInt("schemaVersion", 1)
            if (schema > CURRENT_SCHEMA) {
                throw ExtensionException.InvalidManifest(
                    "El manifiesto usa la versión de esquema $schema y esta versión de Liora entiende hasta la $CURRENT_SCHEMA",
                )
            }

            val id = json.optString("id").trim()
            if (id.isEmpty() || !id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                throw ExtensionException.InvalidManifest(
                    "El identificador de la extensión no es válido: '$id'",
                )
            }

            val version = json.optString("version").trim()
            if (!version.matches(Regex("\\d+\\.\\d+(\\.\\d+)?(-[0-9A-Za-z.-]+)?"))) {
                throw ExtensionException.InvalidManifest(
                    "La versión de la extensión no es válida: '$version'",
                )
            }

            val entrypoint = json.optString("entrypoint").trim()
            if (entrypoint.isEmpty()) {
                throw ExtensionException.InvalidManifest("El manifiesto no dice dónde está el punto de entrada")
            }
            if (entrypoint.startsWith("/") || entrypoint.contains("..") || entrypoint.contains('\\')) {
                throw ExtensionException.InvalidManifest(
                    "El punto de entrada '$entrypoint' debe ser una ruta relativa dentro del paquete",
                )
            }

            val types = json.optJSONArray("type").toStringList()
                .mapNotNull { ProviderType.fromWire(it) }
            if (types.isEmpty()) {
                throw ExtensionException.InvalidManifest(
                    "La extensión no declara ningún tipo de proveedor válido",
                )
            }

            val settings = mutableListOf<ExtensionSetting>()
            val settingArray = json.optJSONArray("settings")
            if (settingArray != null) {
                for (index in 0 until settingArray.length()) {
                    val item = settingArray.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    if (key.isEmpty()) continue
                    settings += ExtensionSetting(
                        key = key,
                        label = item.optString("label").ifBlank { key },
                        type = item.optString("type", "string"),
                        secret = item.optBoolean("secret", false),
                        default = item.optString("default"),
                    )
                }
            }

            return ExtensionManifest(
                schemaVersion = schema,
                id = id,
                displayName = json.optString("displayName").ifBlank { id },
                version = version,
                description = json.optString("description"),
                author = json.optString("author"),
                homepage = json.optString("homepage"),
                minLioraVersion = json.optString("minLioraVersion"),
                requiredFeatures = json.optJSONArray("requiredFeatures").toStringList(),
                types = types,
                permissions = ExtensionPermissions.from(json.optJSONObject("permissions")),
                entrypoint = entrypoint,
                settings = settings,
            )
        }
    }
}

/** Convierte un JSONArray en una lista de cadenas, ignorando los huecos. */
internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index).trim()
        if (value.isNotEmpty()) values += value
    }
    return values
}
