package com.liora.android.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/**
 * Iniciar sesión en un servicio en su propia página.
 *
 * Se abre la página real del servicio en un WebView: el usuario escribe su
 * usuario y su contraseña **en la página del servicio**, no en Liora. Liora
 * solo lee las cookies que esa página deja, y no pide ni almacena la
 * contraseña.
 *
 * Lo que se guarda son las cookies de la sesión, cifradas, y nada más.
 */
@Composable
fun WebLoginScreen(
    url: String,
    title: String,
    /** Se llama con las cookies de la sesión, el SAPISID y la foto. */
    onCaptured: (cookies: String, sapisid: String?, avatarUrl: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    /** Cuando es true, se ve la pagina de inicio de sesion dentro de Liora. */
    var inApp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (loading && inApp) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (!inApp) {
            // El inicio de sesion va en una pestaña de Chrome dentro de
            // Liora: es la pagina real de Google, sin recortes y sin la
            // version mini-web que no cabe en una pantalla pequeña.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(28.dp))
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Inicia sesión en Google",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Se abre en una pestaña dentro de Liora. Tus datos no se " +
                        "guardan: solo las cookies de la sesión, cifradas en el " +
                        "teléfono.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { openCustomTab(context, url, toolbarColor) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Iniciar sesión en una pestaña")
                }
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = { inApp = true }) {
                    Text("Iniciar sesión dentro de Liora")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message
                        ?: "La pestaña de Chrome guarda la sesión en el navegador. " +
                        "Si prefieres que Liora la use, inicia sesión dentro de " +
                        "Liora: es donde Liora puede leerla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        // La pagina de inicio de sesion dentro de Liora: aqui es donde la
        // app puede leer las cookies que deja la pagina.
        Text(
            text = message
                ?: "Inicia sesión en la página de Google. Tus datos no se guardan: " +
                "solo las cookies de la sesión, cifradas en el teléfono.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            LoginWebView(
                url = url,
                onLoadingChange = { loading = it },
                onSessionDetected = { cookies ->
                    // Cuando la pagina ya tiene sesion, Liora la guarda y
                    // vuelve a su pantalla: no hace falta que el usuario
                    // confirme nada.
                    if (hasSession(cookies)) {
                        onCaptured(cookies, readSapisid(), readAvatar())
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { inApp = false }) { Text("Volver") }
            TextButton(
                onClick = {
                    val cookies = readCookies(url)
                    if (hasSession(cookies)) {
                        onCaptured(cookies, readSapisid(), readAvatar())
                    } else {
                        message = "Todavía no hay sesión. Inicia sesión en la página."
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Ya he iniciado sesión")
            }
        }
    }
}

/**
 * El WebView de la pantalla de inicio de sesión.
 *
 * Se queda con las cookies de la propia página (no las de terceros) y avisa
 * en cuanto hay una sesión, para que el botón aparezca con sentido.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    url: String,
    onLoadingChange: (Boolean) -> Unit,
    onSessionDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            // El WebView necesita el contexto de la actividad: con el de la
            // aplicacion no encuentra los recursos y la app se cae.
            val activity = context.findActivity() ?: context
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Google no deja iniciar sesion en un WebView que se
                // declara como tal ("wv"): avisa de que el navegador no es
                // seguro. Con el agente de un Chrome de movil, que es lo que
                // es de verdad esta pagina, la pagina funciona.
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; SM-A125F) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                // Las cookies de terceros hacen falta para que el aviso de
                // cookies de Google no corte el inicio de sesion.
                runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        pageUrl: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        pageUrl: String?,
                    ) {
                        onLoadingChange(false)
                        // La sesion se detecta sola: cuando la pagina ya la
                        // tiene, Liora la guarda y vuelve a su pantalla.
                        onSessionDetected(readCookies(pageUrl ?: url))
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString() ?: return false
                        // El inicio de sesion se queda aqui; cualquier otro
                        // enlace se abre en el navegador, que es lo que quiere
                        // el usuario al tocar un link.
                        if (target.startsWith("intent://") || !isLoginUrl(target)) {
                            openInBrowser(context, target)
                            return true
                        }
                        return false
                    }

                }
                loadUrl(url)
            }
        },
    )
}

/** Abre una dirección en el navegador del teléfono. */
private fun openInBrowser(context: android.content.Context, url: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url),
    ).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * Abre la dirección en una pestaña de Chrome dentro de Liora.
 *
 * Es una "Chrome Custom Tab": una ventana ligera del navegador dentro de la
 * app, sin barra de direcciones al desplazarse y con los enlaces externos
 * llevados al navegador. Se lanza con la librería oficial
 * (`androidx.browser`), que es la que recomienda Chrome.
 */
private fun openCustomTab(context: android.content.Context, url: String, toolbarColor: Int) {
    val intent = androidx.browser.customtabs.CustomTabsIntent.Builder()
        .setShowTitle(false)
        .setUrlBarHidingEnabled(true)
        .setToolbarColor(toolbarColor)
        .setNavigationBarColor(toolbarColor)
        .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_ON)
        .build()
    runCatching {
        intent.launchUrl(context, android.net.Uri.parse(url))
    }.onFailure { openInBrowser(context, url) }
}

/**
 * Si una dirección es parte del inicio de sesión.
 *
 * Solo estas se quedan dentro de Liora: el resto de enlaces se abren en el
 * navegador.
 */
private fun isLoginUrl(url: String): Boolean {
    val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
    return host.endsWith("accounts.google.com") ||
        host.endsWith("music.youtube.com") ||
        host.endsWith("youtube.com")
}

/** La actividad que hay detrás de un contexto, si la hay. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

/** Las cookies que el navegador tiene para esa dirección. */
private fun readCookies(url: String): String =
    runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")

/**
 * Si un conjunto de cookies es una sesión iniciada.
 *
 * La sesión de Google en YouTube se ve por sus cookies de sesión: el `SID`
 * y sus derivados. Sin ellas, la pagina es la de un visitante cualquiera.
 */
private fun hasSession(cookies: String): Boolean {
    if (cookies.isBlank()) return false
    return cookies.contains("SID=", ignoreCase = true) &&
        (cookies.contains("__Secure-3PSID=") ||
            cookies.contains("__Secure-1PSID=") ||
            cookies.contains("__Secure-3PAPISID=") ||
            cookies.contains("SAPISID="))
}

/**
 * La cookie `SAPISID`.
 *
 * Solo se usa para firmar las peticiones, nunca se envía como cookie: por eso
 * se guarda aparte. Vive en el dominio de Google, no en el de YouTube, asi
 * que se busca en los dos.
 */
private fun readSapisid(): String? {
    val hosts = listOf(
        "https://www.google.com",
        "https://accounts.google.com",
        "https://music.youtube.com",
        "https://www.youtube.com",
    )
    for (host in hosts) {
        val all = runCatching {
            CookieManager.getInstance().getCookie(host).orEmpty()
        }.getOrDefault("")
        val value = all.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
        if (value != null) return value
    }
    return null
}

/**
 * La foto de la cuenta.
 *
 * Google la guarda en la cookie `PREF`, con la dirección de la imagen
 * dentro. Si no está, no hay foto: se enseña el icono de perfil.
 */
private fun readAvatar(): String? {
    val pref = runCatching {
        CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
    }.getOrDefault("")
    val value = pref.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("PREF=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?: return null
    // La cookie trae la direccion de la foto entre dos comillas.
    val match = Regex("""(https://lh\d[^"'\s]+)""").find(value) ?: return null
    return match.groupValues[1]
}
