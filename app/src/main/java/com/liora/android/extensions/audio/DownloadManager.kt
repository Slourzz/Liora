package com.liora.android.extensions.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.provider.ProviderEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Un fichero ya descargado y publicado.
 *
 * @property uri dónde está, si se publicó en la carpeta del usuario.
 * @property location una frase para la interfaz: dónde quedó.
 */
data class DownloadedFile(
    val displayName: String,
    val bytes: Long,
    val uri: Uri?,
    val location: String,
    val format: String?,
)

/**
 * Publica un fichero ya descargado.
 *
 * Se separa del gestor para que la parte comprobable (la descarga y sus
 * comprobaciones) se pueda probar sin un dispositivo.
 */
interface DownloadPublisher {
    /**
     * Publica el temporal con el nombre indicado.
     *
     * @return la URI (si se publica en la carpeta del usuario) y una frase
     *   con dónde ha quedado.
     */
    fun publish(temp: File, displayName: String, mimeType: String): Pair<Uri?, String>
}

/**
 * Las descargas de audio que hacen los proveedores.
 *
 * El fichero se descarga a un temporal y **solo se publica cuando está
 * comprobado**: que el proveedor haya dicho `success: true` no vale. Se
 * comprueba que el fichero existe, que no está vacío, que el tamaño es el que
 * el proveedor dijo (si lo dijo) y que el contenido es de verdad un fichero de
 * audio. Un fallo deja intacta la descarga anterior.
 */
class DownloadManager(
    /** Donde se deja el temporal mientras se comprueba. */
    private val tempDir: File,
    /** Donde se guarda cuando el sistema no deja publicar en la carpeta del usuario. */
    private val fallbackDir: File,
    private val publisher: DownloadPublisher,
) {

    constructor(context: Context) : this(
        tempDir = File(context.applicationContext.cacheDir, "extensiones/descargas")
            .apply { mkdirs() },
        fallbackDir = File(context.applicationContext.filesDir, "extensiones/descargas")
            .apply { mkdirs() },
        publisher = MediaStoreDownloadPublisher(context),
    )

    /**
     * Descarga una pista desde un proveedor y la publica.
     *
     * @param displayName el nombre que tendrá el fichero (sin ruta).
     * @param onProgress se llama con (escritos, total) mientras baja.
     */
    suspend fun download(
        provider: ProviderEntry,
        trackId: String,
        quality: String?,
        displayName: String,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val safeName = sanitize(displayName)
        if (safeName.isEmpty()) {
            throw ExtensionException.CorruptPackage("El nombre del fichero no es válido")
        }

        val temp = File(tempDir, "$safeName.partial")
        temp.delete()

        val result = try {
            provider.runtime.download(trackId, quality, temp.absolutePath, onProgress)
        } catch (error: Exception) {
            temp.delete()
            throw when (error) {
                is ExtensionException -> error
                else -> ExtensionException.ProviderFailure(
                    provider.extensionId,
                    error.message ?: "la descarga falló",
                    error,
                )
            }
        }

        try {
            if (!result.success) {
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    result.error ?: "la descarga falló",
                )
            }
            if (!temp.exists() || temp.length() <= 0L) {
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "el proveedor dijo que terminó pero no dejó ningún fichero",
                )
            }
            if (result.bytesWritten > 0L && result.bytesWritten != temp.length()) {
                throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "el fichero descargado no coincide con lo que dijo el proveedor " +
                        "(${temp.length()} bytes de ${result.bytesWritten})",
                )
            }
            val format = AudioFormat.detect(temp)
                ?: throw ExtensionException.ProviderFailure(
                    provider.extensionId,
                    "el fichero descargado no parece audio (no se reconoce su formato)",
                )

            val published = publish(temp, safeName, result.mimeType ?: format.mimeType)
            DownloadedFile(
                displayName = safeName,
                bytes = temp.length(),
                uri = published.first,
                location = published.second,
                format = format.label,
            )
        } finally {
            // El temporal se borra siempre: si la publicacion va bien ya no
            // hace falta, y si va mal no debe quedar a medias.
            temp.delete()
        }
    }

    /**
     * Publica el fichero.
     *
     * En Android 10 o superior va a la carpeta de Descargas del usuario, a
     * traves de MediaStore, sin pedir permisos de almacenamiento. En las
     * versiones anteriores se queda en la carpeta de la app, y se dice con
     * claridad: no está en la carpeta del usuario.
     */
    private fun publish(temp: File, displayName: String, mimeType: String): Pair<Uri?, String> =
        publisher.publish(temp, displayName, mimeType)

    /** Cancela la descarga en curso de un proveedor. */
    fun cancel(provider: ProviderEntry) {
        provider.runtime.cancelDownloads()
    }

    /** Los ficheros ya descargados por el gestor. */
    fun downloaded(): List<File> = fallbackDir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") }
        ?.sortedBy { it.lastModified() }
        ?: emptyList()

    /** Quita un fichero descargado. */
    fun delete(file: File): Boolean = file.delete()

    /** Un nombre de fichero seguro: sin rutas, sin separadores ni cosas raras. */
    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        if (cleaned.isEmpty() || cleaned.startsWith(".") || cleaned == "..") return ""
        return cleaned.take(120)
    }
}

/**
 * Publica el fichero en la carpeta de Descargas del usuario.
 *
 * En Android 10 o superior usa MediaStore, que no necesita permisos de
 * almacenamiento: la escritura se hace a traves del ContentResolver y el
 * fichero aparece en la carpeta del usuario. En las versiones anteriores no
 * hay MediaStore Downloads, asi que se guarda en la carpeta de la app y se
 * dice con claridad que no esta en la carpeta del usuario.
 */
class MediaStoreDownloadPublisher(context: Context) : DownloadPublisher {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun publish(
        temp: File,
        displayName: String,
        mimeType: String,
    ): Pair<Uri?, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(appContext.filesDir, "extensiones/descargas").apply { mkdirs() }
            val target = File(dir, displayName)
            temp.copyTo(target, overwrite = true)
            return null to "la carpeta de la app (${target.absolutePath})"
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw ExtensionException.ProviderFailure(
                "liora",
                "el sistema no ha permitido guardar el fichero",
            )
        try {
            resolver.openOutputStream(uri)?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            } ?: throw ExtensionException.ProviderFailure(
                "liora",
                "no se ha podido escribir el fichero en la carpeta del usuario",
            )
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            // Si la publicacion falla, se borra la fila: no queda un fichero a
            // medias en la carpeta del usuario.
            runCatching { resolver.delete(uri, null, null) }
            throw ExtensionException.ProviderFailure(
                "liora",
                error.message ?: "no se ha podido publicar la descarga",
                error,
            )
        }
        return uri to "Descargas/$displayName"
    }
}

/**
 * Los formatos de audio que se reconocen de verdad.
 *
 * Se mira el principio del fichero: es la unica forma de saber si lo
 * descargado es audio o una pagina de error con extension de audio.
 */
enum class AudioFormat(val label: String, val mimeType: String) {
    MP3("mp3", "audio/mpeg"),
    FLAC("flac", "audio/flac"),
    WAV("wav", "audio/wav"),
    OGG("ogg", "audio/ogg"),
    M4A("m4a", "audio/mp4"),
    AAC("aac", "audio/aac"),
    WEBM("weba", "audio/webm"),
    ;

    companion object {
        /** El formato del fichero, o null si no se reconoce ninguno. */
        fun detect(file: File): AudioFormat? {
            if (!file.exists() || file.length() < 12) return null
            val head = ByteArray(12)
            file.inputStream().use { input -> input.read(head) }
            val text = String(head, Charsets.ISO_8859_1)
            return when {
                text.startsWith("fLaC") -> FLAC
                text.startsWith("RIFF") -> WAV
                text.startsWith("OggS") -> OGG
                text.startsWith("ID3") -> MP3
                text.startsWith("ftyp") -> M4A
                text.startsWith("\u00FF\u00FB") || text.startsWith("\u00FF\u00F3") ||
                    text.startsWith("\u00FF\u00F2") -> MP3
                text.contains("webm") -> WEBM
                else -> null
            }
        }
    }
}
