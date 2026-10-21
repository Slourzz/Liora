package com.liora.android.data.identification

import android.content.Context
import android.util.Log
import com.liora.android.data.model.Song
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Lo que la app sabe de una canción después de verificarla contra los catálogos. */
data class SongIdentification(
    val title: String,
    val artist: String,
    val album: String? = null,
    val itunesTrackId: String? = null,
    val spotifyTrackIds: List<String> = emptyList(),
)

/**
 * Identificación de la canción local para que Spicy Lyrics pueda consultarse.
 *
 * El flujo sigue el de Cloud:
 * 1. Se limpia el título de las marcas de descargadores conocidas.
 * 2. Se busca en iTunes y se elige la mejor coincidencia con Levenshtein
 *    (65 % título / 35 % artista, umbral 78 %). De ahí salen el título, el
 *    artista y el álbum oficiales, y el `trackId` de Apple para Paxsenix.
 * 3. Con el nombre verificado se piden los ids de Spotify a ListenBrainz, solo
 *    con canción y artista. Si la consulta directa no devuelve nada, se usa la
 *    búsqueda de grabaciones de MusicBrainz y su id.
 *
 * Los aciertos se guardan en `filesDir/letras/identifications.json`; los fallos
 * también, con una caducidad de tres días, para no repetir la misma búsqueda en
 * cada reproducción. Nunca lanza: si algo falla, devuelve lo que haya.
 */
class SongIdentificationRepository(context: Context) {

    private val cacheFile = File(context.filesDir, "letras/identifications.json")
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CachedIdentification>()
    private var cacheLoaded = false

    /**
     * Devuelve la identificación de [song], reutilizando la copia local cuando
     * sigue vigente. Es seguro llamarla desde cualquier hilo.
     */
    suspend fun identify(song: Song): SongIdentification = withContext(Dispatchers.IO) {
        val fingerprint = fingerprint(song)
        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock {
            loadCacheLocked()
            cache[song.id.toString()]?.takeIf { it.isFresh(fingerprint, now) }
        }
        if (cached != null) return@withContext cached.identification

        val attempt = identifyUncached(song)
        if (attempt.reachable) {
            cacheMutex.withLock {
                cache[song.id.toString()] = CachedIdentification(
                    fingerprint = fingerprint,
                    identification = attempt.value,
                    resolvedAtMs = System.currentTimeMillis(),
                )
                persistCacheLocked()
            }
        }
        attempt.value
    }

    // --------------------------------------------------------------- consulta

    private fun identifyUncached(song: Song): Attempt<SongIdentification> {
        val cleanedTitle = cleanTitle(song.title).ifBlank { song.title.trim() }
        val localArtist = cleanArtist(song.artist)

        val itunes = findItunesMatch(cleanedTitle, localArtist)
        val officialTitle = itunes.value?.title ?: cleanedTitle
        val officialArtist = itunes.value?.artist ?: localArtist

        val spotify = findSpotifyTrackIds(
            title = officialTitle,
            artist = officialArtist,
            preferWeb = itunes.value == null,
        )

        return Attempt(
            value = SongIdentification(
                title = officialTitle,
                artist = officialArtist,
                album = itunes.value?.album,
                itunesTrackId = itunes.value?.trackId,
                spotifyTrackIds = spotify.value,
            ),
            reachable = itunes.reachable || spotify.reachable,
        )
    }

    /**
     * Busca la canción en iTunes y se queda con la mejor candidata según el
     * mismo criterio de Levenshtein de Cloud.
     */
    private fun findItunesMatch(title: String, artist: String): Attempt<ItunesMatch?> {
        val query = listOf(artist, title)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (query.isBlank()) return Attempt(null, reachable = false)

        val payload = requestJson(
            "https://itunes.apple.com/search" +
                "?term=${encode(query)}&entity=song&media=music&limit=8",
        ) ?: return Attempt(null, reachable = false)

        val results = runCatching { JSONObject(payload).optJSONArray("results") }
            .getOrNull()
            ?: return Attempt(null, reachable = true)

        var best: ItunesMatch? = null
        var bestScore = 0.0
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val candidateTitle = item.optString("trackName").trim()
            val candidateArtist = item.optString("artistName").trim()
            if (candidateTitle.isEmpty() || candidateArtist.isEmpty()) continue

            val score = SearchNameSimilarity.score(
                title = title,
                artist = artist,
                candidateTitle = candidateTitle,
                candidateArtist = candidateArtist,
            )
            if (score <= bestScore) continue

            bestScore = score
            best = ItunesMatch(
                title = candidateTitle,
                artist = candidateArtist,
                album = item.optString("collectionName").trim().takeIf(String::isNotEmpty),
                trackId = item.optLong("trackId").takeIf { it > 0L }?.toString(),
            )
        }

        return Attempt(
            value = best?.takeIf { bestScore >= SearchNameSimilarity.MatchThreshold },
            reachable = true,
        )
    }

    /**
     * Pide los ids de Spotify. Primero la consulta por metadatos (la que
     * recomienda Spicy), y si no hay resultados, la búsqueda de grabaciones de
     * MusicBrainz seguida de su mapeo a Spotify.
     */
    private fun findSpotifyTrackIds(
        title: String,
        artist: String,
        preferWeb: Boolean,
    ): Attempt<List<String>> {
        if (title.isBlank()) return Attempt(emptyList(), reachable = false)

        // Si iTunes no conoce la canción, el índice web suele distinguir la
        // edición exacta de Spotify mejor que ListenBrainz. Se prueba primero.
        val earlyWeb = if (preferWeb) spotifyIdsFromWebSearch(title, artist) else null
        if (earlyWeb != null && earlyWeb.value.isNotEmpty()) return earlyWeb

        val direct = spotifyIdsFromMetadata(title, artist)
        if (direct.value.isNotEmpty()) {
            return Attempt(
                value = direct.value,
                reachable = direct.reachable || (earlyWeb?.reachable == true),
            )
        }

        val byRecording = spotifyIdsFromRecording(title, artist)
        if (byRecording.value.isNotEmpty()) {
            return Attempt(
                value = byRecording.value,
                reachable = direct.reachable ||
                    byRecording.reachable ||
                    (earlyWeb?.reachable == true),
            )
        }

        val byWebSearch = earlyWeb ?: spotifyIdsFromWebSearch(title, artist)
        return Attempt(
            value = byWebSearch.value,
            reachable = direct.reachable || byRecording.reachable || byWebSearch.reachable,
        )
    }

    private fun spotifyIdsFromMetadata(title: String, artist: String): Attempt<List<String>> {
        // Solo canción y artista; el álbum se resuelve por iTunes, no aquí.
        val body = JSONArray()
            .put(
                JSONObject()
                    .put("track_name", title)
                    .put("artist_name", artist)
                    .put("release_name", ""),
            )
            .toString()
        val payload = requestJson(
            url = "$ListenBrainzBase/spotify-id-from-metadata/json",
            body = body,
        ) ?: return Attempt(emptyList(), reachable = false)

        return Attempt(value = parseSpotifyIds(payload), reachable = true)
    }

    private fun spotifyIdsFromRecording(title: String, artist: String): Attempt<List<String>> {
        val query = listOf(artist, title)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (query.isBlank()) return Attempt(emptyList(), reachable = false)

        val searchBody = JSONArray().put(JSONObject().put("query", query)).toString()
        val searchPayload = requestJson(
            url = "$ListenBrainzBase/recording-search/json",
            body = searchBody,
        ) ?: return Attempt(emptyList(), reachable = false)

        val recordings = runCatching { JSONArray(searchPayload) }.getOrNull()
            ?: return Attempt(emptyList(), reachable = true)

        var bestMbid: String? = null
        var bestScore = 0.0
        for (index in 0 until recordings.length()) {
            val item = recordings.optJSONObject(index) ?: continue
            val mbid = item.optString("recording_mbid").trim()
            if (mbid.isEmpty()) continue

            val score = SearchNameSimilarity.score(
                title = title,
                artist = artist,
                candidateTitle = item.optString("recording_name"),
                candidateArtist = item.optString("artist_credit_name"),
            )
            if (score <= bestScore) continue

            bestScore = score
            bestMbid = mbid
        }

        val mbid = bestMbid?.takeIf { bestScore >= SearchNameSimilarity.MatchThreshold }
            ?: return Attempt(emptyList(), reachable = true)

        return spotifyIdsFromMbid(mbid)
    }

    private fun spotifyIdsFromMbid(mbid: String): Attempt<List<String>> {
        val body = JSONArray().put(JSONObject().put("recording_mbid", mbid)).toString()
        val payload = requestJson(
            url = "$ListenBrainzBase/spotify-id-from-mbid/json",
            body = body,
        ) ?: return Attempt(emptyList(), reachable = false)

        return Attempt(value = parseSpotifyIds(payload), reachable = true)
    }

    /**
     * Último recurso para pistas que no están en iTunes, ListenBrainz ni en la
     * búsqueda anónima de Spotify. DuckDuckGo suele tener indexadas las páginas
     * de Spotify de canciones nuevas o de catálogos pequeños; se abren las
     * candidatas y se verifica título/artista antes de aceptar el id.
     *
     * Algunas redes móviles reciben un 202 de DuckDuckGo. En ese caso se usa
     * Jina Reader como proxy de lectura de la misma búsqueda: devuelve el HTML
     * como texto y conserva los enlaces de Spotify. La consulta se codifica dos
     * veces porque Jina decodifica un nivel antes de pedirla a DuckDuckGo.
     */
    private fun spotifyIdsFromWebSearch(title: String, artist: String): Attempt<List<String>> {
        if (title.isBlank()) return Attempt(emptyList(), reachable = false)

        // El símbolo # se interpreta como fragmento en las URLs de búsqueda.
        // Para buscar se quita; el título oficial se conserva para mostrar.
        val searchTitle = title.replace('#', ' ').replace(ExtraSpaces, " ").trim()
        val quoted = buildString {
            append("site:open.spotify.com/track \"")
            append(searchTitle.replace('"', ' ').trim())
            append('"')
            if (artist.isNotBlank()) {
                append(" \"")
                append(artist.replace('"', ' ').trim())
                append('"')
            }
        }
        val plain = listOf(
            "site:open.spotify.com/track",
            searchTitle,
            artist.trim(),
        ).filter(String::isNotBlank).joinToString(" ")
        val natural = listOf("\"$searchTitle\"", "\"${artist.trim()}\"", "spotify")
            .filter { !it.contains("\"\"") }
            .joinToString(" ")

        var reachable = false
        var candidates = emptyList<String>()
        for (query in listOf(plain, natural, quoted).distinct()) {
            val html = requestText(
                url = "https://html.duckduckgo.com/html/?q=${encode(query)}",
                accept = "text/html,application/xhtml+xml",
            )
            if (html != null) {
                reachable = true
                candidates = SpotifyTrackUrlPattern.findAll(html)
                    .map { it.groupValues[1] }
                    .distinct()
                    .take(MaxWebCandidates)
                    .toList()
                Log.w(LogTag, "DuckDuckGo q=$query bytes=${html.length} candidates=$candidates")
            }

            if (candidates.isEmpty()) {
                val text = requestText(
                    url = "$JinaReaderBase/https://html.duckduckgo.com/html/?q=${encode(encode(query))}",
                    accept = "text/plain,text/markdown,text/html",
                )
                if (text != null) {
                    reachable = true
                    candidates = SpotifyTrackUrlPattern.findAll(text)
                        .map { it.groupValues[1] }
                        .distinct()
                        .take(MaxWebCandidates)
                        .toList()
                    Log.w(LogTag, "Jina q=$query bytes=${text.length} candidates=$candidates")
                }
            }

            if (candidates.isNotEmpty()) break
        }

        if (candidates.isEmpty()) return Attempt(emptyList(), reachable = reachable)

        val ids = mutableListOf<String>()
        for (candidate in candidates) {
            val metadata = fetchSpotifyTrackMetadata(candidate)
            if (metadata == null) {
                Log.w(LogTag, "Spotify $candidate: sin metadatos")
                continue
            }
            val score = SearchNameSimilarity.score(
                title = title,
                artist = artist,
                candidateTitle = metadata.title,
                candidateArtist = metadata.artist,
            )
            Log.d(
                LogTag,
                "Spotify $candidate: '${metadata.title}' / '${metadata.artist}' score=$score",
            )
            if (score < SearchNameSimilarity.MatchThreshold) continue
            ids += candidate
            if (ids.size >= MaxSpotifyCandidates) break
        }
        return Attempt(ids, reachable = true)
    }

    private fun fetchSpotifyTrackMetadata(id: String): SpotifyTrackMetadata? {
        val html = requestText(
            url = "https://open.spotify.com/track/$id",
            accept = "text/html,application/xhtml+xml",
            userAgent = SpotifyPreviewUserAgent,
        )
        if (html == null) {
            Log.w(LogTag, "Spotify $id: request failed")
            return null
        }

        val title = metaContents(html, "og:title")
            .firstOrNull()
            ?.let(::htmlUnescape)
            ?.trim()
            .orEmpty()
        val artists = metaContents(html, "music:musician_description")
            .map(::htmlUnescape)
            .map { it.trim() }
            .filter(String::isNotEmpty)
            .distinct()
        val artist = artists.joinToString(", ").ifBlank {
            metaContents(html, "og:description")
                .firstOrNull()
                ?.let(::htmlUnescape)
                ?.substringBefore(" · ")
                ?.trim()
                .orEmpty()
        }
        if (title.isEmpty() || artist.isEmpty()) return null

        return SpotifyTrackMetadata(title = title, artist = artist)
    }

    private fun metaContents(html: String, property: String): List<String> {
        val key = Regex(
            """(?:property|name)\s*=\s*["']${Regex.escape(property)}["']""",
            RegexOption.IGNORE_CASE,
        )
        return MetaTagPattern.findAll(html).mapNotNull { tag ->
            if (!key.containsMatchIn(tag.value)) return@mapNotNull null
            MetaContentPattern.find(tag.value)?.groupValues?.get(1)
        }.toList()
    }

    private fun htmlUnescape(value: String): String = value
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    private fun parseSpotifyIds(payload: String): List<String> {
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        val ids = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val values = item.optJSONArray("spotify_track_ids") ?: continue
            for (idIndex in 0 until values.length()) {
                val id = values.optString(idIndex)
                if (SpotifyIdPattern.matches(id)) ids += id
            }
        }
        return ids.distinct().take(MaxSpotifyCandidates)
    }

    // ---------------------------------------------------------------- HTTP

    private fun requestJson(url: String, body: String? = null): String? {
        val endpoint = URI.create(url).toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun requestText(
        url: String,
        accept: String,
        userAgent: String = BrowserUserAgent,
    ): String? {
        val endpoint = URI.create(url).toURL()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("User-Agent", userAgent)
        }

        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(LogTag, "HTTP $status $url")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            Log.w(LogTag, "HTTP error $url: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())

    // --------------------------------------------------------------- limpieza

    /** Quita las marcas de descargadores que suelen acabar en el título. */
    private fun cleanTitle(raw: String): String = raw
        .replace(JunkBrackets, " ")
        .replace(JunkTokens, " ")
        .replace(ExtraSpaces, " ")
        .trim()
        .trim('-', '–', '—', '|', '·')
        .trim()

    private fun cleanArtist(raw: String): String {
        val value = raw.trim()
        return if (value.isBlank() || value.equals("<unknown>", ignoreCase = true)) "" else value
    }

    private fun fingerprint(song: Song): String =
        SearchNameSimilarity.normalize(song.title) + "|" +
            SearchNameSimilarity.normalize(song.artist)

    // ---------------------------------------------------------------- caché

    private fun loadCacheLocked() {
        if (cacheLoaded) return
        cacheLoaded = true
        if (!cacheFile.isFile) return

        val payload = runCatching { cacheFile.readText(StandardCharsets.UTF_8) }
            .getOrNull() ?: return
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (root.optInt(CacheVersionKey, 0) < CacheVersion) return

        val keys = root.keys()
        while (keys.hasNext()) {
            val songId = keys.next()
            if (songId == CacheVersionKey) continue
            val item = root.optJSONObject(songId) ?: continue
            val title = item.optString("title").trim()
            val artist = item.optString("artist").trim()
            if (title.isEmpty()) continue

            val ids = item.optJSONArray("spotifyTrackIds")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val id = array.optString(index)
                        if (SpotifyIdPattern.matches(id)) add(id)
                    }
                }
            }.orEmpty()

            cache[songId] = CachedIdentification(
                fingerprint = item.optString("fingerprint"),
                identification = SongIdentification(
                    title = title,
                    artist = artist,
                    album = item.optString("album").trim().takeIf(String::isNotEmpty),
                    itunesTrackId = item.optString("itunesTrackId").trim().takeIf(String::isNotEmpty),
                    spotifyTrackIds = ids,
                ),
                resolvedAtMs = item.optLong("resolvedAtMs", 0L),
            )
        }
    }

    private fun persistCacheLocked() {
        val root = JSONObject().put(CacheVersionKey, CacheVersion)
        cache.forEach { (songId, cached) ->
            root.put(
                songId,
                JSONObject()
                    .put("fingerprint", cached.fingerprint)
                    .put("title", cached.identification.title)
                    .put("artist", cached.identification.artist)
                    .put("album", cached.identification.album.orEmpty())
                    .put("itunesTrackId", cached.identification.itunesTrackId.orEmpty())
                    .put("spotifyTrackIds", JSONArray().apply {
                        cached.identification.spotifyTrackIds.forEach { put(it) }
                    })
                    .put("resolvedAtMs", cached.resolvedAtMs),
            )
        }
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(root.toString(), StandardCharsets.UTF_8)
        }
    }

    private fun CachedIdentification.isFresh(fingerprint: String, nowMs: Long): Boolean {
        if (this.fingerprint != fingerprint) return false
        if (identification.spotifyTrackIds.isNotEmpty()) return true
        return nowMs - resolvedAtMs <= EmptyMatchTtlMs
    }

    // ------------------------------------------------------------- auxiliares

    private data class Attempt<T>(val value: T, val reachable: Boolean)

    private data class ItunesMatch(
        val title: String,
        val artist: String,
        val album: String?,
        val trackId: String?,
    )

    private data class SpotifyTrackMetadata(
        val title: String,
        val artist: String,
    )

    private data class CachedIdentification(
        val fingerprint: String,
        val identification: SongIdentification,
        val resolvedAtMs: Long,
    )

    private companion object {
        const val LogTag = "LioraIdentify"
        const val ListenBrainzBase = "https://labs.api.listenbrainz.org"
        const val JinaReaderBase = "https://r.jina.ai"
        const val ConnectTimeoutMs = 8_000
        const val ReadTimeoutMs = 12_000
        const val MaxSpotifyCandidates = 4
        const val MaxWebCandidates = 8
        const val EmptyMatchTtlMs = 72 * 60 * 60 * 1_000L
        const val CacheVersion = 4
        const val CacheVersionKey = "cacheVersion"
        const val BrowserUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
        const val SpotifyPreviewUserAgent =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

        val SpotifyIdPattern = Regex("^[A-Za-z0-9]{22}$")
        val SpotifyTrackUrlPattern = Regex(
            """open\.spotify\.com(?:%2F|/)(?:intl-[^%/\s]+(?:%2F|/))?track(?:%2F|/)([A-Za-z0-9]{22})""",
            RegexOption.IGNORE_CASE,
        )
        val MetaTagPattern = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        val MetaContentPattern = Regex(
            """content\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        val ExtraSpaces = Regex("\\s+")
        val JunkBrackets = Regex(
            """[\[(]\s*[^)\]]*(?:spotisaver|y2meta|spotdown(?:\.org)?|ytmp3|ssyoutube)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        val JunkTokens = Regex(
            """\b(?:spotisaver|y2meta|spotdown(?:\.org)?|ytmp3|ssyoutube)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
