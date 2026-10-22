package com.liora.android.extensions

import com.liora.android.extensions.net.NetworkPolicy
import com.liora.android.extensions.net.UrlConnectionHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La política de red, comprobada contra servidores HTTP de verdad.
 *
 * Cada test levanta un servidor local que responde como quiere (una
 * redirección, un 404, un cuerpo) y se comprueba que el cliente hace lo que
 * debe con cada salto.
 */
class NetworkPolicyTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    /** Lo que el servidor vio en la ultima peticion. */
    private val lastAuthorization = AtomicReference<String?>(null)
    private val lastPath = AtomicReference<String?>(null)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = null
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:$port"

    private fun serve(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            lastAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            lastPath.set(exchange.requestURI.path)
            handler(exchange)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `una redireccion a un dominio permitido se sigue`() {
        serve("/inicio") { exchange ->
            exchange.responseHeaders.add("Location", "${baseUrl()}/final")
            respond(exchange, 302, "")
        }
        serve("/final") { exchange -> respond(exchange, 200, "llegado") }

        val client = UrlConnectionHttpClient()
        val policy = NetworkPolicy(allowedDomains = listOf("127.0.0.1"), requireHttps = false)
        val response = runBlocking { client.get("${baseUrl()}/inicio", policy = policy) }

        assertEquals(200, response.statusCode)
        assertEquals("llegado", response.body)
        assertEquals(1, response.redirects.size)
    }

    @Test
    fun `una redireccion a un dominio no permitido se corta`() {
        // El servidor de "otro" esta en otro puerto: otro dominio (distinto
        // origen) al que la extension no tiene permiso.
        val otro = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        otro.createContext("/secreto") { exchange -> respond(exchange, 200, "secreto") }
        otro.start()
        try {
            serve("/inicio") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://localhost:${otro.address.port}/secreto",
                )
                respond(exchange, 302, "")
            }

            val client = UrlConnectionHttpClient()
            val policy = NetworkPolicy(
                allowedDomains = listOf("127.0.0.1"),
                requireHttps = false,
            )
            val error = runBlocking {
                runCatching { client.get("${baseUrl()}/inicio", policy = policy) }
            }.exceptionOrNull()

            assertTrue("la redireccion deberia fallar: $error", error is IOException)
            assertTrue(
                "el error deberia decir que no hay permiso: $error",
                error?.message?.contains("permiso") == true,
            )
        } finally {
            otro.stop(0)
        }
    }

    @Test
    fun `las cabeceras sensibles no viajan a otro dominio`() {
        val otro = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val recibidoEnOtro = AtomicReference<String?>(null)
        otro.createContext("/destino") { exchange ->
            recibidoEnOtro.set(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, "ok")
        }
        otro.start()
        try {
            serve("/inicio") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://localhost:${otro.address.port}/destino",
                )
                respond(exchange, 302, "")
            }

            val client = UrlConnectionHttpClient()
            val policy = NetworkPolicy(
                allowedDomains = listOf("127.0.0.1", "localhost"),
                requireHttps = false,
            )
            val response = runBlocking {
                client.get(
                    "${baseUrl()}/inicio",
                    headers = mapOf("Authorization" to "Bearer secreto", "X-Api" to "1"),
                    policy = policy,
                )
            }
            assertEquals(200, response.statusCode)
            // En el salto a otro dominio no puede haber salido la cabecera
            // sensible.
            assertNull(
                "la cabecera de autorizacion no debe viajar a otro dominio",
                recibidoEnOtro.get(),
            )
        } finally {
            otro.stop(0)
        }
    }

    @Test
    fun `no se puede bajar de https a http`() {
        val policy = NetworkPolicy(allowedDomains = listOf("ejemplo.com"))
        assertTrue(policy.rejectionReason("http://ejemplo.com/x")!!.contains("https"))
        assertNull(policy.rejectionReason("https://ejemplo.com/x"))
    }

    @Test
    fun `un dominio no permitido se rechaza antes de abrir la conexion`() {
        val policy = NetworkPolicy(allowedDomains = listOf("permitido.com", "*.permitido.com"))
        assertNull(policy.rejectionReason("https://permitido.com/x"))
        assertNull(policy.rejectionReason("https://cdn.permitido.com/x"))
        assertTrue(policy.rejectionReason("https://otro.com/x")!!.contains("permiso"))
        assertTrue(policy.rejectionReason("no es una url")!!.contains("URL"))
    }

    @Test
    fun `se limitan las redirecciones`() {
        serve("/bucle") { exchange ->
            exchange.responseHeaders.add("Location", "${baseUrl()}/bucle")
            respond(exchange, 302, "")
        }
        val client = UrlConnectionHttpClient()
        val policy = NetworkPolicy(allowedDomains = listOf("127.0.0.1"), requireHttps = false, maxRedirects = 3)
        val error = runBlocking {
            runCatching { client.get("${baseUrl()}/bucle", policy = policy) }
        }.exceptionOrNull()
        assertTrue("deberia fallar: $error", error is IOException)
        assertTrue(error!!.message!!.contains("redirecciones"))
    }

    @Test
    fun `una descarga con codigo de error falla`() {
        serve("/error") { exchange -> respond(exchange, 404, "no esta") }
        val client = UrlConnectionHttpClient()
        val policy = NetworkPolicy(allowedDomains = listOf("127.0.0.1"), requireHttps = false)
        val error = runCatching {
            runBlocking {
                client.download(
                    "${baseUrl()}/error",
                    target = java.io.ByteArrayOutputStream(),
                    policy = policy,
                )
            }
        }.exceptionOrNull()
        assertTrue("deberia fallar: $error", error is IOException)
        assertTrue(error!!.message!!.contains("404"))
    }

    @Test
    fun `una descarga incompleta se detecta con el tamano declarado`() {
        // El servidor declara mas bytes de los que manda.
        serve("/corto") { exchange ->
            val body = "12345"
            exchange.responseHeaders.add("Content-Length", "999")
            exchange.sendResponseHeaders(200, 999)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val client = UrlConnectionHttpClient()
        val policy = NetworkPolicy(allowedDomains = listOf("127.0.0.1"), requireHttps = false)
        val error = runBlocking {
            runCatching {
                client.download(
                    "${baseUrl()}/corto",
                    target = java.io.ByteArrayOutputStream(),
                    policy = policy,
                )
            }
        }.exceptionOrNull()
        // O el cliente lo ve por el tamano declarado, o el corte salta al
        // leer: en los dos casos la descarga NO se da por buena.
        assertTrue("la descarga cortada no puede pasar: $error", error != null)
        assertTrue(
            "el error debe explicar el corte: $error",
            error?.message?.contains("cortado") == true,
        )
    }
}
