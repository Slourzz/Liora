package com.liora.android.extensions.repository

import android.content.Context
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionRegistry
import com.liora.android.extensions.model.RegistryExtension
import com.liora.android.extensions.model.RegistryParser
import com.liora.android.extensions.net.HttpClient
import com.liora.android.extensions.net.isHttpsUrl
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dónde se guardan las direcciones de los repositorios.
 *
 * La app usa las preferencias; las pruebas usan una implementación en
 * memoria. Así el gestor de repositorios no depende de Android.
 */
interface RepositoryStore {
    fun repositories(): List<String>
    fun setRepositories(value: List<String>)
}

/**
 * Una entrada del catálogo: la extensión tal como la publica un repositorio,
 * junto con el repositorio del que viene y si ya está instalada.
 */
data class CatalogEntry(
    val extension: RegistryExtension,
    val repositoryUrl: String,
    val installedVersion: String?,
) {
    val id: String get() = extension.id
    val isInstalled: Boolean get() = installedVersion != null
    val hasUpdate: Boolean
        get() = installedVersion != null && installedVersion != extension.version
}

/**
 * Los repositorios de extensiones que el usuario ha añadido.
 *
 * Un repositorio es una dirección https a un `registry.json`. Se acepta
 * también la forma corta de GitHub (`github.com/usuario/repo`), que se
 * traduce a la URL en bruto del repositorio.
 *
 * El registro se guarda en disco para que el catálogo siga disponible sin
 * conexión; al volver la conexión se refresca.
 */
class RepositoryManager(
    private val rootDir: File,
    private val http: HttpClient,
    private val store: RepositoryStore,
) {
    private val cacheDir: File
        get() = File(rootDir, "extensiones/repos").apply { mkdirs() }

    private val lock = Mutex()

    /** Las direcciones de los repositorios añadidos. */
    fun repositories(): List<String> = store.repositories()

    /**
     * Añade un repositorio y descarga su registro.
     *
     * @throws ExtensionException.InvalidRepositoryUrl si la dirección no vale.
     * @throws ExtensionException.MalformedRegistry si lo que hay no es un registro.
     */
    suspend fun addRepository(url: String): ExtensionRegistry {
        val normalized = normalizeRepositoryUrl(url)
        val registry = fetchRegistry(normalized)
        lock.withLock {
            val current = store.repositories().toMutableList()
            if (!current.contains(normalized)) current += normalized
            store.setRepositories(current)
        }
        return registry
    }

    /** Vuelve a descargar el registro de un repositorio ya añadido. */
    suspend fun refreshRepository(url: String): ExtensionRegistry {
        val normalized = normalizeRepositoryUrl(url)
        val registry = fetchRegistry(normalized)
        writeCache(normalized, registry)
        return registry
    }

    /** Quita un repositorio de la lista (no desinstala sus extensiones). */
    suspend fun removeRepository(url: String) {
        val normalized = normalizeRepositoryUrl(url)
        lock.withLock {
            store.setRepositories(store.repositories().filterNot { it == normalized })
        }
        withContext(Dispatchers.IO) { cacheFile(normalized).delete() }
    }

    /**
     * El catálogo unificado de todos los repositorios.
     *
     * Si un repositorio no se puede refreshed, se usa su copia guardada; si no
     * hay copia, se salta. Un repositorio caído nunca deja la app sin
     * catálogo.
     */
    suspend fun catalog(installedVersions: Map<String, String>): List<CatalogEntry> {
        val entries = mutableListOf<CatalogEntry>()
        for (url in repositories()) {
            val registry = runCatching { refreshRepository(url) }.getOrNull()
                ?: readCache(url)
                ?: continue
            for (extension in registry.extensions) {
                if (entries.any { it.id == extension.id }) continue
                entries += CatalogEntry(
                    extension = extension,
                    repositoryUrl = url,
                    installedVersion = installedVersions[extension.id],
                )
            }
        }
        return entries.sortedBy { it.extension.displayName.lowercase() }
    }

    /** El registro guardado de un repositorio, si lo hay. */
    fun readCache(url: String): ExtensionRegistry? {
        val file = cacheFile(url)
        if (!file.exists()) return null
        return runCatching { RegistryParser.parse(file.readText()) }.getOrNull()
    }

    private suspend fun fetchRegistry(url: String): ExtensionRegistry {
        val response = try {
            http.get(url)
        } catch (error: Exception) {
            throw ExtensionException.RepositoryUnreachable(
                "No se pudo contactar con el repositorio: ${error.message}",
                error,
            )
        }
        if (!response.isSuccessful) {
            throw ExtensionException.RepositoryUnreachable(
                "El repositorio respondió con el código ${response.statusCode}",
            )
        }
        val registry = RegistryParser.parse(response.body)
        writeCache(url, registry)
        return registry
    }

    private suspend fun writeCache(url: String, registry: ExtensionRegistry) =
        withContext(Dispatchers.IO) {
            val file = cacheFile(url)
            runCatching {
                file.writeText(
                    """{"version":${registry.version},"updated_at":"${registry.updatedAt}","extensions":[${registry.extensions.joinToString(",") { entry ->
                        """{"id":"${entry.id}","name":"${entry.name}","display_name":"${entry.displayName}","version":"${entry.version}","description":"${entry.description}","download_url":"${entry.downloadUrl}","sha256":"${entry.sha256}","category":"${entry.category}","tags":[${entry.tags.joinToString(",") { "\"$it\"" }}],"min_app_version":"${entry.minLioraVersion}","icon_url":"${entry.iconUrl}","updated_at":"${entry.updatedAt}"}"""
                    }}]}""",
                )
            }
        }

    private fun cacheFile(url: String): File = File(cacheDir, "${url.hashCode().toUInt().toString(16)}.json")

    companion object {
        /**
         * Valida y normaliza la dirección de un repositorio.
         *
         * Acepta la forma corta de GitHub y la traduce a la URL en bruto del
         * `registry.json`. Solo se admite https: un repositorio remoto que
         * se descarga sin cifrar es un vector de sustitución de extensiones.
         */
        fun normalizeRepositoryUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                throw ExtensionException.InvalidRepositoryUrl("La dirección del repositorio está vacía")
            }
            if (trimmed.startsWith("github.com/", ignoreCase = true) ||
                trimmed.startsWith("www.github.com/", ignoreCase = true)
            ) {
                val path = trimmed.substringAfter("github.com/").trim('/')
                val parts = path.split('/')
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw ExtensionException.InvalidRepositoryUrl(
                        "La dirección de GitHub debe ser github.com/usuario/repositorio",
                    )
                }
                val repository = parts[1].removeSuffix(".git")
                val file = parts.getOrNull(2) ?: "registry.json"
                return "https://raw.githubusercontent.com/${parts[0]}/$repository/HEAD/$file"
            }
            if (!isHttpsUrl(trimmed)) {
                throw ExtensionException.InvalidRepositoryUrl(
                    "La dirección del repositorio debe ser una URL https válida",
                )
            }
            return trimmed
        }
    }
}
