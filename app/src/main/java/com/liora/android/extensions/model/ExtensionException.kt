package com.liora.android.extensions.model

/**
 * Los fallos del sistema de extensiones.
 *
 * Cada uno lleva un motivo legible: son errores que ve el usuario en la
 * pantalla de Extensiones, no trazas internas.
 */
sealed class ExtensionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** La URL del repositorio no es válida (vacía, sin https, no es una URL). */
    class InvalidRepositoryUrl(message: String) : ExtensionException(message)

    /** El repositorio no respondió o no se pudo leer su registro. */
    class RepositoryUnreachable(message: String, cause: Throwable? = null) :
        ExtensionException(message, cause)

    /** El registro existe pero no es un JSON de registro válido. */
    class MalformedRegistry(message: String) : ExtensionException(message)

    /** El paquete no se pudo descargar o no es un ZIP legible. */
    class CorruptPackage(message: String, cause: Throwable? = null) :
        ExtensionException(message, cause)

    /** El SHA-256 del paquete no coincide con el que publica el registro. */
    class IntegrityFailure(message: String) : ExtensionException(message)

    /** El manifiesto no cumple el contrato o pide permisos incoherentes. */
    class InvalidManifest(message: String) : ExtensionException(message)

    /** La extensión no es compatible con esta versión de Liora. */
    class IncompatibleExtension(message: String) : ExtensionException(message)

    /** El motor JavaScript no pudo cargar o inicializar la extensión. */
    class RuntimeFailure(message: String, cause: Throwable? = null) :
        ExtensionException(message, cause)

    /** Una llamada al proveedor tardó más de lo permitido. */
    class ProviderTimeout(val providerId: String, val timeoutMs: Long) :
        ExtensionException("El proveedor '$providerId' tardó más de ${timeoutMs} ms")

    /** El proveedor no admite la operación pedida. */
    class NotSupported(val providerId: String, val operation: String) :
        ExtensionException("El proveedor '$providerId' no admite la operación '$operation'")

    /** El proveedor falló al ejecutar la operación. */
    class ProviderFailure(val providerId: String, message: String, cause: Throwable? = null) :
        ExtensionException("El proveedor '$providerId' falló: $message", cause)

    /** La operación se canceló. */
    class Cancelled(val what: String) : ExtensionException("Operación cancelada: $what")
}
