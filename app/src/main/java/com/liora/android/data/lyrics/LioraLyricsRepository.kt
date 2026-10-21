package com.liora.android.data.lyrics

import com.liora.android.data.identification.SongIdentificationRepository
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.LyricsResult
import com.liora.android.data.model.Song

/**
 * Encadena las fuentes de letras de Liora:
 *
 * 1. Spicy Lyrics, usando el id de Spotify de la canción o el que resuelve
 *    [SongIdentificationRepository] contra iTunes, ListenBrainz y MusicBrainz.
 * 2. Apple Music a través de Paxsenix, reutilizando el `trackId` de iTunes que
 *    ya haya verificado la identificación.
 *
 * Si la clave de Spicy no está configurada, ni siquiera se intenta identificar
 * la canción: se va directo a Apple Music, como antes.
 *
 * El resultado no se tira al terminar: se guarda con la propia pista (ver
 * [LyricsStore]) y a partir de ahí la canción ya se lo lleva encima. Una
 * pista se busca una sola vez: la segunda vez que suena, sus letras están
 * ahí y no se vuelve a preguntar a nadie.
 */
class LioraLyricsRepository(
    private val spicyLyrics: SpicyLyricsRepository,
    private val appleMusicLyrics: AppleMusicLyricsRepository,
    private val songIdentification: SongIdentificationRepository,
    private val store: LyricsStore,
) {

    /**
     * Lo que la pista ya lleva encima: sus letras guardadas, si las tiene.
     *
     * No toca la red: solo lee lo que hay en el disco. Es lo que permite
     * enseñar la letra al instante, sin el "Buscando letras…" de por medio.
     */
    suspend fun storedLyrics(song: Song): LyricsResult? =
        song.lyrics ?: store.read(song)

    /** Cuántas pistas tienen ya sus letras guardadas. */
    suspend fun storedCount(): Int = store.count()

    /**
     * Borra las letras guardadas: la próxima vez que suene cada pista, sus
     * letras se vuelven a buscar.
     */
    suspend fun clearStored() = store.clear()

    /**
     * Las letras ya resueltas, con su traducción si la hay.
     *
     * Se guardan con la pista: la traducción viaja dentro de la letra y está
     * disponible la próxima vez, aunque no haya conexión.
     */
    suspend fun saveLyrics(song: Song, result: LyricsResult) {
        store.write(song, result)
    }

    /**
     * Las letras de [song] con su atribución, o el resultado vacío si ninguna
     * fuente las tiene. Nunca lanza.
     *
     * Si la pista ya las traía consigo, se devuelven tal cual. Si no, se
     * buscan y se guardan con ella para no volver a buscarlas.
     */
    suspend fun findLyrics(song: Song): LyricsResult {
        storedLyrics(song)?.let { return it }

        val result = searchLyrics(song)
        // Solo se guarda lo que se pudo confirmar: un fallo de la red no
        // significa que la pista no tenga letra.
        if (result.confirmed) store.write(song, result.result)
        return result.result
    }

    /**
     * La búsqueda de verdad, sin tocar lo guardado. Devuelve el resultado y si
     * es fiable: solo es fiable si alguna fuente respondió de verdad.
     */
    private suspend fun searchLyrics(song: Song): SearchOutcome {
        val directSpotifyId = song.spotifyTrackId?.takeIf(String::isNotBlank)

        val identification = if (spicyLyrics.isConfigured && directSpotifyId == null) {
            songIdentification.identify(song)
        } else {
            null
        }

        val spotifyIds = buildList {
            directSpotifyId?.let(::add)
            identification?.spotifyTrackIds?.let(::addAll)
        }.distinct()

        var confirmed = false

        if (spicyLyrics.isConfigured) {
            // ListenBrainz puede devolver varias ediciones de la misma
            // grabación; se prueban unas pocas por si alguna no tiene sync.
            for (spotifyId in spotifyIds.take(MaxSpicyAttempts)) {
                val attempt = spicyLyrics.findLyrics(spotifyId, song.duration)
                if (attempt.reachable) confirmed = true
                attempt.result?.let { return SearchOutcome(it, confirmed) }
            }
        }

        val apple = appleMusicLyrics.findLyrics(
            song = song,
            preferredTrackId = identification?.itunesTrackId,
        )
        if (apple.reachable) confirmed = true

        return if (apple.lines.isEmpty()) {
            SearchOutcome(LyricsResult(), confirmed)
        } else {
            SearchOutcome(
                result = LyricsResult(
                    lines = apple.lines,
                    attribution = LyricsAttribution(providerName = "Apple Music"),
                ),
                confirmed = confirmed,
            )
        }
    }

    /** El resultado de la búsqueda y si se puede dar por bueno. */
    private data class SearchOutcome(
        val result: LyricsResult,
        /** Verdadero si alguna fuente respondió: el "no hay letra" es real. */
        val confirmed: Boolean,
    )

    private companion object {
        const val MaxSpicyAttempts = 3
    }
}
