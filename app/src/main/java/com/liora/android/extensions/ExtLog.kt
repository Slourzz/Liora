package com.liora.android.extensions

/**
 * El registro de mensajes del sistema de extensiones.
 *
 * Es una indirección sobre `android.util.Log` para que las pruebas de la
 * JVM (donde `Log` no existe) no fallen al escribir un mensaje. En Android
 * escribe en el log del sistema con la etiqueta `LioraExt`.
 */
object ExtLog {

    /** La etiqueta con la que salen estos mensajes. */
    const val TAG = "LioraExt"

    /** Un mensaje informativo. */
    fun i(message: String) {
        log(android.util.Log.INFO, message, null)
    }

    /** Un mensaje de aviso, con su causa. */
    fun w(message: String, error: Throwable? = null) {
        log(android.util.Log.WARN, message, error)
    }

    /** Un mensaje de error, con su causa. */
    fun e(message: String, error: Throwable? = null) {
        log(android.util.Log.ERROR, message, error)
    }

    private fun log(level: Int, message: String, error: Throwable?) {
        try {
            when {
                error != null -> android.util.Log.println(level, TAG, message + "\n" + error.stackTraceToString())
                else -> android.util.Log.println(level, TAG, message)
            }
        } catch (_: RuntimeException) {
            // En la JVM de las pruebas `android.util.Log` no existe: el mensaje
            // se pierde, pero la prueba sigue siendo valida.
        }
    }
}
