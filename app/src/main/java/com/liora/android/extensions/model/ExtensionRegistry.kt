package com.liora.android.extensions.model

import org.json.JSONObject

/**
 * Una extensión tal y como aparece en el registro de un repositorio.
 *
 * El registro es lo que el usuario consulta: por eso lleva la versión, la
 * descripción, las etiquetas y, sobre todo, el SHA-256 del paquete. Ese hash
 * es la **integridad** respecto a lo publicado; no dice nada de la confianza
 * en quien publica (de eso se encarga el modelo de permisos y la decisión
 * del usuario al instalar).
 */
data class RegistryExtension(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String,
    val category: String,
    val tags: List<String>,
    val minLioraVersion: String,
    val iconUrl: String,
    val updatedAt: String,
) {
    /** El hash normalizado: en minúsculas, sin el prefijo `sha256:`. */
    val normalizedSha256: String get() = RegistryParser.normalizeSha256(sha256)
}

/** El registro completo de un repositorio de extensiones. */
data class ExtensionRegistry(
    val version: Int,
    val updatedAt: String,
    val extensions: List<RegistryExtension>,
) {
    fun find(id: String): RegistryExtension? = extensions.firstOrNull { it.id == id }
}

/**
 * Lee el registro JSON de un repositorio.
 *
 * Acepta los nombres en `snake_case` y en `camelCase` (como hace el registro
 * de Mornye), y da un error claro cuando lo que llega no es un registro.
 */
object RegistryParser {

    /** El error que ve el usuario si la URL no apunta a un registro. */
    fun parse(text: String): ExtensionRegistry {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw ExtensionException.MalformedRegistry("El repositorio devolvió un registro vacío")
        }
        if (trimmed.startsWith("<")) {
            throw ExtensionException.MalformedRegistry(
                "Esa dirección devuelve una página web, no un registro JSON. Debe apuntar a un archivo registry.json",
            )
        }

        val json = try {
            JSONObject(trimmed)
        } catch (error: Exception) {
            throw ExtensionException.MalformedRegistry(
                "El registro no es un JSON válido: ${error.message}",
            )
        }

        val array = json.optJSONArray("extensions")
            ?: throw ExtensionException.MalformedRegistry(
                "El registro no tiene la lista de extensiones",
            )

        val extensions = mutableListOf<RegistryExtension>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.pick("id", "name").trim()
            if (id.isEmpty()) continue
            extensions += RegistryExtension(
                id = id,
                name = item.pick("name", "id").trim(),
                displayName = item.pick("display_name", "displayName", "name").ifBlank { id },
                version = item.pick("version").trim(),
                description = item.pick("description").trim(),
                downloadUrl = item.pick("download_url", "downloadUrl").trim(),
                sha256 = item.pick("sha256", "checksum_sha256", "checksumSha256").trim(),
                category = item.pick("category").ifBlank { "utility" },
                tags = item.optJSONArray("tags").toStringList(),
                minLioraVersion = item.pick("min_app_version", "minAppVersion").trim(),
                iconUrl = item.pick("icon_url", "iconUrl").trim(),
                updatedAt = item.pick("updated_at", "updatedAt").trim(),
            )
        }

        return ExtensionRegistry(
            version = json.optInt("version", 1),
            updatedAt = json.pick("updated_at", "updatedAt").trim(),
            extensions = extensions,
        )
    }

    /** Normaliza un hash: minúsculas y sin el prefijo `sha256:`. */
    fun normalizeSha256(value: String): String {
        var normalized = value.trim().lowercase()
        if (normalized.startsWith("sha256:")) normalized = normalized.removePrefix("sha256:")
        return if (normalized.length == 64 && normalized.all { it in "0123456789abcdef" }) {
            normalized
        } else {
            ""
        }
    }

    /** El primer valor no vacío de las claves candidatas. */
    private fun JSONObject.pick(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }
}
