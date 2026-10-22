package com.liora.android.extensions

import android.content.Context
import android.util.Log
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.extensions.audio.AudioSourceAdapter
import com.liora.android.extensions.audio.DownloadManager
import com.liora.android.extensions.install.ExtensionInstaller
import com.liora.android.extensions.install.PackageValidator
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ExtensionManifest
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.model.RegistryExtension
import com.liora.android.extensions.net.HttpClient
import com.liora.android.extensions.net.UrlConnectionHttpClient
import com.liora.android.extensions.provider.ProviderEntry
import com.liora.android.extensions.provider.ProviderManager
import com.liora.android.extensions.provider.ProviderRegistry
import com.liora.android.extensions.repository.CatalogEntry
import com.liora.android.extensions.repository.RepositoryManager
import com.liora.android.extensions.repository.RepositoryStore
import com.liora.android.extensions.runtime.ExtensionHostApi
import com.liora.android.extensions.runtime.JsExtensionRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * El sistema de extensiones de Liora, en un solo sitio.
 *
 * Aquí viven las cuatro piezas del circuito:
 *
 * - **Repositorios**: las direcciones que el usuario añade y los registros que
 *   se descargan de ellas.
 * - **Catálogo**: la lista de extensiones disponibles, con su versión, sus
 *   capacidades y sus permisos.
 * - **Instalación**: descarga, verificación por SHA-256, validación del
 *   manifiesto y activación atómica.
 * - **Proveedores**: las extensiones cargadas y en marcha, y el uso que Liora
 *   hace de ellas.
 *
 * El núcleo de la app solo conoce este contrato: el interior de cada
 * extensión es asunto suyo.
 */
class ExtensionsService(
    context: Context,
    private val prefs: LioraPrefs,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {
    private val appContext = context.applicationContext

    /** La raíz donde vive todo lo del sistema de extensiones. */
    private val rootDir: File get() = File(appContext.filesDir, "extensiones").apply { mkdirs() }

    /** El registro de proveedores aportados por las extensiones. */
    val registry = ProviderRegistry()

    /** El uso de esos proveedores. */
    val providers = ProviderManager(registry)

    /** Los repositorios que el usuario ha añadido. */
    val repositories = RepositoryManager(
        rootDir = appContext.filesDir,
        http = http,
        store = object : RepositoryStore {
            override fun repositories(): List<String> = prefs.extensionRepositories
            override fun setRepositories(value: List<String>) {
                prefs.extensionRepositories = value
            }
        },
    )

    /** El instalador de extensiones. */
    val installer = ExtensionInstaller(rootDir, http, PackageValidator(appVersion()))

    /** El gestor de descargas de audio. */
    val downloads = DownloadManager(appContext.filesDir)

    /** El adaptador que traduce las fuentes de los proveedores a Media3. */
    val audio = AudioSourceAdapter(appContext)

    // ------------------------------------------------------------- repositorios

    /** Añade un repositorio y descarga su registro. */
    suspend fun addRepository(url: String) = repositories.addRepository(url)

    /** Quita un repositorio de la lista. */
    suspend fun removeRepository(url: String) = repositories.removeRepository(url)

    /** Los repositorios añadidos. */
    fun repositories(): List<String> = repositories.repositories()

    /** El catálogo de extensiones disponibles. */
    suspend fun catalog(): List<CatalogEntry> =
        repositories.catalog(installedVersions())

    // --------------------------------------------------------------- catálogo

    /** Las extensiones instaladas y su versión. */
    fun installedVersions(): Map<String, String> =
        installer.installed().associate { it.id to it.version }

    /** Las extensiones instaladas, con su estado. */
    fun installedEntries(): List<ProviderEntry> = registry.all()

    // ------------------------------------------------------------ instalación

    /**
     * Instala (o actualiza) una extensión del catálogo.
     *
     * La extensión queda cargada y registrada si todo va bien; si su script
     * falla al arrancar, se instala igual pero se marca con su error, para que
     * el usuario vea qué pasa sin que la app se rompa.
     */
    suspend fun install(entry: RegistryExtension): ProviderEntry {
        val manifest = installer.install(entry)
        return load(manifest)
    }

    /** Quita una extensión y la descarga del registro. */
    suspend fun uninstall(extensionId: String) {
        registry.find(extensionId)?.runtime?.unload()
        registry.unregister(extensionId)
        installer.remove(extensionId)
    }

    /** Activa o desactiva una extensión sin desinstalarla. */
    fun setEnabled(extensionId: String, enabled: Boolean) {
        registry.setEnabled(extensionId, enabled)
        prefs.disabledExtensions = if (enabled) {
            prefs.disabledExtensions - extensionId
        } else {
            prefs.disabledExtensions + extensionId
        }
    }

    /** ¿Está activada una extensión? */
    fun isEnabled(extensionId: String): Boolean =
        extensionId !in prefs.disabledExtensions

    /**
     * Carga las extensiones instaladas.
     *
     * Se llama al abrir la app: cada extensión se carga en su propio runtime
     * y, si falla, se anota su error sin impedir que las demás funcionen.
     */
    suspend fun loadInstalled(): List<ProviderEntry> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<ProviderEntry>()
        for (manifest in installer.installed()) {
            val entry = load(manifest)
            loaded += entry
        }
        loaded
    }

    /** Carga una extensión concreta y la registra. */
    private fun load(manifest: ExtensionManifest): ProviderEntry {
        val directory = installer.directoryOf(manifest.id)
        val scriptFile = File(directory, manifest.entrypoint)
        if (!scriptFile.exists()) {
            val entry = ProviderEntry(
                extensionId = manifest.id,
                displayName = manifest.displayName,
                version = manifest.version,
                types = manifest.types.toSet(),
                runtime = JsExtensionRuntime(
                    manifest,
                    "",
                    ExtensionHostApi(
                        dataDir = File(appContext.filesDir, "extensiones/datos/${manifest.id}"),
                        manifest = manifest,
                        http = http,
                    ),
                ),
                enabled = false,
                error = "Falta el fichero '${manifest.entrypoint}'",
            )
            registry.register(entry)
            return entry
        }

        val script = scriptFile.readText()
        val host = ExtensionHostApi(
            dataDir = File(appContext.filesDir, "extensiones/datos/${manifest.id}"),
            manifest = manifest,
            http = http,
            assetResolver = { name -> resolveAsset(name) },
            logger = { message -> Log.i("LioraExt", message) },
            // La carpeta de descargas es de la app: la extension puede
            // escribir ahi porque es donde se publican sus ficheros.
            extraRoots = listOf(File(appContext.filesDir, "extensiones/descargas")),
        )
        val runtime = JsExtensionRuntime(manifest, script, host)
        val enabled = isEnabled(manifest.id)
        val entry = ProviderEntry(
            extensionId = manifest.id,
            displayName = manifest.displayName,
            version = manifest.version,
            types = manifest.types.toSet(),
            runtime = runtime,
            enabled = enabled,
        )
        registry.register(entry)

        return try {
            runtime.load()
            registry.setError(manifest.id, null)
            entry.copy(enabled = enabled)
        } catch (error: ExtensionException) {
            Log.w("LioraExt", "No se pudo cargar ${manifest.id}: ${error.message}")
            registry.setError(manifest.id, error.message)
            entry.copy(enabled = false, error = error.message)
        } catch (error: Throwable) {
            Log.w("LioraExt", "No se pudo cargar ${manifest.id}", error)
            registry.setError(manifest.id, error.message ?: "error desconocido")
            entry.copy(enabled = false, error = error.message ?: "error desconocido")
        }
    }

    /** La versión de Liora que ven las extensiones. */
    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    /**
     * Un recurso que trae la propia aplicación, si existe.
     *
     * Solo se expone lo que esté en `res/raw` y con un nombre sobrio: es la
     * vía por la que una extensión de demostración puede servir una pista de
     * prueba sin depender de ningún servicio.
     */
    private fun resolveAsset(name: String): File? {
        if (!name.matches(Regex("[a-z0-9_]{1,40}"))) return null
        val id = appContext.resources.getIdentifier(name, "raw", appContext.packageName)
        if (id == 0) return null
        val file = File(appContext.filesDir, "extensiones/assets/$name")
        file.parentFile?.mkdirs()
        return try {
            appContext.resources.openRawResource(id).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    /** Los proveedores de un tipo, para la interfaz. */
    fun providers(type: ProviderType): List<ProviderEntry> = registry.providers(type)
}
