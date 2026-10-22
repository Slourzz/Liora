package com.liora.android.extensions.provider

import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.runtime.JsExtensionRuntime

/**
 * Una extensión cargada, con lo que aporta y en qué estado está.
 *
 * `enabled` distingue una extensión desactivada (sigue instalada, pero no
 * aporta nada) de una que falló al arrancar (tiene un `error`).
 */
data class ProviderEntry(
    val extensionId: String,
    val displayName: String,
    val version: String,
    val types: Set<ProviderType>,
    val runtime: JsExtensionRuntime,
    val enabled: Boolean,
    val error: String? = null,
    /**
     * Los dominios a los que la extensión tiene permiso.
     *
     * Se comprueban antes de reproducir o descargar una fuente: una extensión
     * no puede entregar una URL de un sitio que no le está permitido.
     */
    val allowedDomains: List<String> = emptyList(),
) {
    /** ¿Esta extensión ofrece este tipo de proveedor? */
    fun provides(type: ProviderType): Boolean = enabled && types.contains(type)

    /** ¿Puede esta extensión hablar con ese dominio? */
    fun allowsDomain(domain: String): Boolean {
        if (allowedDomains.isEmpty()) return false
        val target = domain.trim().lowercase()
        if (target.isEmpty()) return false
        return allowedDomains.any { allowed ->
            val rule = allowed.trim().lowercase()
            rule == target || (rule.startsWith("*.") && target.endsWith(rule.substring(1)))
        }
    }
}

/**
 * Los proveedores que aportan las extensiones instaladas.
 *
 * Liora consulta aquí para saber qué puede hacer cada extensión. El núcleo
 * no conoce el interior de ninguna: solo el contrato.
 */
class ProviderRegistry {

    private val entries = mutableMapOf<String, ProviderEntry>()

    /** Registra (o sustituye) la entrada de una extensión. */
    fun register(entry: ProviderEntry) {
        entries[entry.extensionId] = entry
    }

    /** Quita una extensión del registro. */
    fun unregister(extensionId: String) {
        entries.remove(extensionId)
    }

    /** La entrada de una extensión, si está. */
    fun find(extensionId: String): ProviderEntry? = entries[extensionId]

    /** Todas las entradas, activas o no. */
    fun all(): List<ProviderEntry> = entries.values.sortedBy { it.displayName.lowercase() }

    /** Las entradas que aportan un tipo de proveedor. */
    fun providers(type: ProviderType): List<ProviderEntry> =
        all().filter { it.provides(type) }

    /** Marca una extensión como activada o desactivada. */
    fun setEnabled(extensionId: String, enabled: Boolean) {
        val entry = entries[extensionId] ?: return
        entries[extensionId] = entry.copy(enabled = enabled)
    }

    /** Anota un error de una extensión sin quitarla del registro. */
    fun setError(extensionId: String, error: String?) {
        val entry = entries[extensionId] ?: return
        entries[extensionId] = entry.copy(error = error, enabled = error == null && entry.enabled)
    }
}
