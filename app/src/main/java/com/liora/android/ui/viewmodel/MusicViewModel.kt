package com.liora.android.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.liora.android.data.cache.PlaybackCache
import com.liora.android.data.lyrics.LioraLyricsRepository
import com.liora.android.data.lyrics.LyricsTranslationProgress
import com.liora.android.data.lyrics.LyricsTranslationRepository
import com.liora.android.data.model.AudioFolder
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.LyricsResult
import com.liora.android.data.model.Song
import com.liora.android.data.model.toAudioFolders
import com.liora.android.data.model.withTranslation
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.data.repository.MediaRepository
import com.liora.android.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    object Loading : UiState()
    data class Success(val songs: List<Song>) : UiState()
    data class Error(val message: String) : UiState()
}

/** Estado de avance de la reproducción, para la línea de progreso del mini reproductor. */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    /** Fracción 0..1 lista para dibujar. */
    val fraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/** Pistas vecinas en la cola, para la vista previa al deslizar el mini reproductor. */
data class QueueNeighbors(
    val previous: Song? = null,
    val next: Song? = null,
)

/**
 * En qué punto está la traducción de la letra actual.
 *
 * La traducción la pide el servicio de Unison: primero se le manda el bloque
 * de líneas y después se pintan ya traducidas.
 */
sealed interface LyricsTranslationState {
    /** Nada que traducir: la letra ya viene traducida o nadie lo ha pedido. */
    data object Idle : LyricsTranslationState

    /** Se está traduciendo línea a línea. */
    data class Translating(val done: Int, val total: Int) : LyricsTranslationState

    /** No se pudo traducir. */
    data class Failed(val message: String) : LyricsTranslationState
}

class MusicViewModel(
    private val repository: MediaRepository,
    private val prefs: LioraPrefs,
    private val cache: PlaybackCache,
    private val lyricsRepository: LioraLyricsRepository,
    private val translationRepository: LyricsTranslationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Todas las canciones leídas, antes de aplicar las carpetas excluidas. */
    private var allSongs: List<Song> = emptyList()

    /** Carpetas con audio, para el selector de Ajustes. */
    private val _folders = MutableStateFlow<List<AudioFolder>>(emptyList())
    val folders: StateFlow<List<AudioFolder>> = _folders.asStateFlow()

    private val _excludedFolders = MutableStateFlow(prefs.excludedFolders)
    val excludedFolders: StateFlow<Set<String>> = _excludedFolders.asStateFlow()

    /** Verdadero si Liora oculta los sonidos del sistema de la biblioteca. */
    private val _filterSystemSounds = MutableStateFlow(prefs.filterSystemSounds)
    val filterSystemSounds: StateFlow<Boolean> = _filterSystemSounds.asStateFlow()

    /** Verdadero mientras "Leer carpetas" está releyendo el teléfono. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _cacheMaxMb = MutableStateFlow(prefs.cacheMaxMb)
    val cacheMaxMb: StateFlow<Int> = _cacheMaxMb.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        // Deja la caché dentro del límite elegido y calcula cuánto ocupa.
        viewModelScope.launch(Dispatchers.IO) {
            cache.trimTo(prefs.cacheMaxMb)
            _cacheBytes.value = cache.usageBytes()
        }
        refreshStoredLyricsCount()
    }

    /** Cuenta las pistas que ya tienen sus letras guardadas. */
    fun refreshStoredLyricsCount() {
        viewModelScope.launch(Dispatchers.IO) {
            _storedLyricsCount.value = runCatching { lyricsRepository.storedCount() }.getOrDefault(0)
        }
    }

    /**
     * Borra las letras guardadas: la próxima vez que suene cada pista, sus
     * letras se buscan otra vez.
     */
    fun clearStoredLyrics() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { lyricsRepository.clearStored() }
            }
            // El espejo en memoria se vacía aquí, en el hilo principal, que es
            // el único que lo consulta.
            lyricsBySong.clear()
            _storedLyricsCount.value = 0
        }
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    /** Letras sincronizadas de la canción actual; llegan solas al cambiar de pista. */
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    /** Verdadero mientras se buscan las letras de la canción actual. */
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    /** Proveedor y créditos de las letras actuales, para la atribución del panel. */
    private val _lyricsAttribution = MutableStateFlow<LyricsAttribution?>(null)
    val lyricsAttribution: StateFlow<LyricsAttribution?> = _lyricsAttribution.asStateFlow()

    /** En qué punto está la traducción de la letra actual. */
    private val _lyricsTranslation = MutableStateFlow<LyricsTranslationState>(LyricsTranslationState.Idle)
    val lyricsTranslation: StateFlow<LyricsTranslationState> = _lyricsTranslation.asStateFlow()

    /**
     * El error de traducción pendiente de enseñar.
     *
     * Se limpia en cuanto se cierra el diálogo, así que el mismo fallo no
     * vuelve a aparecer en cada recomposición.
     */
    private val _translationError = MutableStateFlow<String?>(null)
    val translationError: StateFlow<String?> = _translationError.asStateFlow()

    /** Cierra el diálogo de error: el fallo ya se ha visto. */
    fun dismissTranslationError() {
        _translationError.value = null
    }

    /** La traducción en marcha: al cambiar de canción se cancela. */
    private var translationJob: Job? = null

    /** La letra tal como la trae la fuente, sin traducir. */
    private var lyricsOriginal: List<LyricLine> = emptyList()

    /** La letra traducida, si la hay; vacía mientras no la haya. */
    private var lyricsTranslated: List<LyricLine> = emptyList()

    /** Si la letra que se ve es la traducida. */
    private val _lyricsTranslationVisible = MutableStateFlow(false)
    val lyricsTranslationVisible: StateFlow<Boolean> = _lyricsTranslationVisible.asStateFlow()

    /** Cuántas pistas tienen ya sus letras guardadas con ellas. */
    private val _storedLyricsCount = MutableStateFlow(0)
    val storedLyricsCount: StateFlow<Int> = _storedLyricsCount.asStateFlow()

    init {
        // Cada pista nueva dispara su propia búsqueda; el identificador es la
        // clave, de modo que volver a colgarle las letras no la busca otra vez.
        // collectLatest cancela la búsqueda anterior si el usuario salta de
        // canción antes de que termine.
        viewModelScope.launch {
            _currentSong
                .distinctUntilChangedBy { it?.id }
                .collectLatest { song ->
                    // La búsqueda anterior se ha cancelado: el cartel de
                    // "buscando" se limpia antes de empezar con la nueva.
                    _lyricsLoading.value = false
                    // Y con ella la traducción que estuviera en marcha: su
                    // resultado ya no valdría para otra canción.
                    translationJob?.cancel()
                    translationJob = null
                    _lyricsTranslation.value = LyricsTranslationState.Idle
                    lyricsOriginal = emptyList()
                    lyricsTranslated = emptyList()
                    _lyricsTranslationVisible.value = false
                    if (song == null) {
                        _lyrics.value = emptyList()
                        _lyricsAttribution.value = null
                        return@collectLatest
                    }
                    _lyrics.value = emptyList()
                    _lyricsAttribution.value = null

                    // Las letras viajan con la pista: si ya están guardadas con
                    // ella aparecen al instante, sin buscar nada y sin el
                    // "Buscando letras…" de por medio.
                    val stored = runCatching { lyricsRepository.storedLyrics(song) }
                        .getOrNull()
                    if (stored != null) {
                        publishLyrics(song, stored)
                        return@collectLatest
                    }

                    _lyricsLoading.value = true
                    val result = runCatching { lyricsRepository.findLyrics(song) }
                        .getOrDefault(LyricsResult())
                    // La cancelación solo se nota en los puntos de espera: si
                    // mientras tanto sonó otra canción, este resultado es de
                    // la anterior y no se publica.
                    if (_currentSong.value?.id != song.id) return@collectLatest
                    publishLyrics(song, result)
                    _lyricsLoading.value = false
                    // La letra recién guardada cuenta para Ajustes.
                    refreshStoredLyricsCount()
                }
        }
    }

    /**
     * Las letras ya resueltas de cada pista, para que la canción vaya siempre
     * con las suyas. Es el espejo en memoria de lo que está guardado en disco.
     */
    private val lyricsBySong = mutableMapOf<Long, LyricsResult>()

    /**
     * Publica las letras de la pista y se las cuelga encima: a partir de aquí
     * la canción ya lleva las suyas y no hay que volver a buscarlas.
     */
    private fun publishLyrics(song: Song, result: LyricsResult) {
        // Las letras se guardan siempre —sirven para cuando vuelva a sonar—,
        // pero solo se publican si la canción sigue siendo la que suena: un
        // resultado tardío de la anterior no puede sustituir a la actual.
        lyricsBySong[song.id] = result
        if (_currentSong.value?.id != song.id) return
        lyricsOriginal = result.lines
        lyricsTranslated = result.lines.withTranslationText()
        _lyrics.value = result.lines
        _lyricsAttribution.value = result.attribution
        _currentSong.value = song.copy(lyrics = result)
    }

    /**
     * Las líneas con la traducción puesta, si la hay. El original se sustituye
     * por la traducción, con su mismo ritmo.
     *
     * Si ninguna línea trae traducción, la lista queda vacía: así se sabe que
     * todavía no hay traducción que enseñar (y hay que pedirla).
     */
    private fun List<LyricLine>.withTranslationText(): List<LyricLine> {
        if (none { !it.translation.isNullOrBlank() }) return emptyList()
        return map { line ->
            val text = line.translation
            if (text.isNullOrBlank()) line else line.withTranslation(text)
        }
    }

    /**
     * Publica la canción que suena, con sus letras encima si ya las tiene: el
     * reproductor va devolviendo la pista sin ellas y hay que volver a
     * colgárselas.
     */
    private fun publishSong(song: Song?) {
        _currentSong.value = song?.let { current ->
            lyricsBySong[current.id]?.let { current.copy(lyrics = it) } ?: current
        }
    }

    /**
     * Traduce la letra de la canción que suena, con el servicio de Unison.
     *
     * Si la fuente ya trajo traducción, esa se muestra tal cual y aquí no hay
     * nada que hacer. Si no, se le pide la traducción al servicio: la letra
     * original no se toca —la traducción se pega a su línea y los tiempos
     * quedan como estaban— y si el servicio falla, la letra se queda como
     * estaba y se enseña el error.
     */
    fun requestLyricsTranslation() {
        val song = _currentSong.value ?: return
        val lines = lyricsOriginal
        if (lines.isEmpty()) return
        // Si la fuente ya la tradujo, esa es la traducción.
        if (lines.any { !it.translation.isNullOrBlank() }) return

        Log.i("LioraTraduce", "Petición de traducción para ${song.title}")
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            val target = prefs.lyricsTranslationLanguage
            val outcome = runCatching {
                translationRepository.translate(song, lines, target) { progress ->
                    _lyricsTranslation.value = when (progress) {
                        is LyricsTranslationProgress.Translating ->
                            LyricsTranslationState.Translating(progress.done, progress.total)
                    }
                }
            }
            // Si mientras tanto sonó otra canción, este resultado no vale.
            if (_currentSong.value?.id != song.id) return@launch
            outcome
                .onSuccess { translated ->
                    // La traducción se cuelga de la línea original, que no se
                    // toca: en la letra guardada conviven las dos.
                    val withTranslation = lines.zip(translated) { line, text ->
                        if (text.isBlank()) line else line.copy(translation = text)
                    }
                    lyricsOriginal = withTranslation
                    lyricsTranslated = withTranslation.withTranslationText()
                    lyricsBySong[song.id] = lyricsBySong[song.id]
                        ?.copy(lines = withTranslation)
                        ?: LyricsResult(lines = withTranslation)
                    _lyricsTranslationVisible.value = true
                    _lyrics.value = lyricsTranslated
                    _lyricsTranslation.value = LyricsTranslationState.Idle

                    // Y se guarda con la pista: la traducción viaja dentro de la
                    // letra y la próxima vez está aunque no haya conexión.
                    runCatching {
                        lyricsRepository.saveLyrics(
                            song = song,
                            result = LyricsResult(
                                lines = withTranslation,
                                attribution = _lyricsAttribution.value,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    // La letra original se queda: un fallo nunca la borra.
                    _lyricsTranslation.value = LyricsTranslationState.Idle
                    _translationError.value = translationErrorMessage(error)
                }
        }
    }

    /**
     * El motivo de un fallo, en cristiano: lo que dice el servicio viene con
     * códigos y en inglés, así que se traduce a lo que pasó.
     */
    private fun translationErrorMessage(error: Throwable): String {
        val reason = error.message.orEmpty()
        return when {
            reason.isBlank() -> "No se pudo traducir la letra."
            else -> reason
        }
    }

    /**
     * Enseña la letra traducida u original.
     *
     * Cuando se pide la traducción y todavía no la hay, se traduce en el
     * teléfono: el original se retira en cuanto llega la traducción.
     */
    fun setLyricsTranslationVisible(visible: Boolean) {
        if (!visible) {
            _lyricsTranslationVisible.value = false
            _lyrics.value = lyricsOriginal
            return
        }
        if (lyricsTranslated.isNotEmpty()) {
            _lyricsTranslationVisible.value = true
            _lyrics.value = lyricsTranslated
            return
        }
        // La fuente ya trajo traducción: se usa esa.
        if (lyricsOriginal.any { !it.translation.isNullOrBlank() }) {
            lyricsTranslated = lyricsOriginal.withTranslationText()
            _lyricsTranslationVisible.value = true
            _lyrics.value = lyricsTranslated
            return
        }
        // Si no, se traduce aquí mismo.
        requestLyricsTranslation()
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Lo que el usuario quiere oír, más allá de lo que suena ahora mismo.
     *
     * Mientras el reproductor carga, [isPlaying] es false aunque la canción
     * vaya a sonar: el botón tiene que mirar aquí, o una pausa se convertiría
     * en un "play" y la música seguiría sonando.
     */
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    /** Cola de reproducción actual, en el mismo orden que el reproductor. */
    private var queue: List<Song> = emptyList()

    /** Posición y duración de la pista actual, para la barra de progreso. */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** Canciones vecinas en la cola; el mini reproductor las asoma al deslizar. */
    private val _neighbors = MutableStateFlow(QueueNeighbors())
    val neighbors: StateFlow<QueueNeighbors> = _neighbors.asStateFlow()

    /** Cola completa tal como está cargada en el reproductor (panel "Cola"). */
    private val _queueSongs = MutableStateFlow<List<Song>>(emptyList())
    val queueSongs: StateFlow<List<Song>> = _queueSongs.asStateFlow()

    /** Modo aleatorio del reproductor, espejo del controlador. */
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /** Modo de repetición del reproductor (Player.REPEAT_MODE_*). */
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private var progressJob: Job? = null

    private var mediaController: MediaController? = null

    /** Conexión en marcha (o ya hecha) con el servicio de reproducción. */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Mientras no se haya intentado volver a la sesión anterior. */
    private var restorePending = true

    /** Evita guardar el estado a medias mientras se restaura la sesión. */
    private var restoring = false

    fun initializeMediaController(context: Context) {
        // Solo se conecta una vez: si la pantalla se recrea, la conexión que ya
        // está en marcha sirve igual.
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            // El campo se asigna ANTES de usar el controlador: dentro del
            // apply() todavía sería null y la restauración saldría sin hacer nada.
            val controller = future.get()
            mediaController = controller
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    // Pausa o reanudación: el momento exacto que interesa guardar.
                    savePlaybackState()

                    /*
                     * La posición y la pausa van en el mismo par de estado.
                     * Sin esto, al pausar en medio de una palabra la letra
                     * puede reaccionar a una posición de hace hasta 500 ms
                     * (el último poll del ticker): reloj hacia atrás, la
                     * palabra pierde su color, y con el siguiente poll
                     * (1.5 s en pausa) lo recupera. Se ve como el
                     * parpadeo "desaparece y aparece".
                     */
                    refreshProgress()
                }

                /**
                 * Cambia la intención de sonar: el botón tiene que mirar aquí
                 * y no en el estado, porque entre carga y carga hay un rato
                 * en el que la música todavía no suena pero va a sonar.
                 */
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _playWhenReady.value = playWhenReady
                    // Al parar de verdad no queda nada sonando.
                    if (!playWhenReady) _isPlaying.value = false
                }

                /** Se dispara al cambiar de pista (automático o manual). */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    publishSong(songOf(mediaItem))
                    refreshProgress()
                    refreshNeighbors()
                    savePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _isPlaying.value = false
                        refreshProgress()
                        savePlaybackState()
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _shuffleEnabled.value = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value = repeatMode
                }
            })
            _shuffleEnabled.value = controller.shuffleModeEnabled
            _repeatMode.value = controller.repeatMode
            _playWhenReady.value = controller.playWhenReady
            _isPlaying.value = controller.isPlaying
            adoptRunningSession()
            publishSong(songOf(controller.currentMediaItem))
            refreshProgress()
            refreshNeighbors()
            startProgressTicker()
            restorePlaybackIfPossible()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Si el servicio ya venía sonando (la actividad se recreó pero el proceso
     * sobrevivió), reconstruye la cola para que la interfaz sepa qué suena.
     */
    private fun adoptRunningSession() {
        val controller = mediaController ?: return
        if (queue.isNotEmpty() || controller.mediaItemCount == 0) return

        queue = (0 until controller.mediaItemCount)
            .mapNotNull { index -> songById(controller.getMediaItemAt(index).mediaId) }
        _queueSongs.value = queue
    }

    /**
     * Vuelve donde se quedó la última sesión: la misma canción, la misma
     * posición y el mismo estado (si sonaba, sigue; si estaba en pausa,
     * espera al usuario). Solo actúa una vez, con controlador y biblioteca
     * listos, y nunca si ya hay algo cargado en el reproductor.
     */
    private fun restorePlaybackIfPossible() {
        if (!restorePending) return
        val controller = mediaController ?: return
        val songs = (_uiState.value as? UiState.Success)?.songs.orEmpty()
        if (songs.isEmpty()) return

        restorePending = false

        // Con música ya cargada (sesión viva o restaurada) no se toca nada.
        if (controller.mediaItemCount > 0) return

        val savedId = prefs.lastSongId
        val song = songs.firstOrNull { it.id == savedId } ?: return
        val startIndex = songs.indexOfFirst { it.id == savedId }.coerceAtLeast(0)

        restoring = true
        queue = songs
        _queueSongs.value = queue
        publishSong(song)
        controller.setMediaItems(songs.map { it.toMediaItem() }, startIndex, prefs.lastPositionMs)
        controller.prepare()
        if (prefs.lastPlaying) controller.play()
        restoring = false
        refreshNeighbors()
    }

    /** Guarda canción, posición y estado para la próxima vez que se abra Liora. */
    private fun savePlaybackState() {
        if (restoring) return
        val controller = mediaController ?: return
        val id = controller.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        prefs.savePlayback(
            songId = id,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            // La intención, no el estado: si estaba sonando aunque fuera a
            // cargando, al volver debe seguir sonando.
            playing = controller.playWhenReady,
        )
    }

    /** Busca una canción por identificador en lo que hay cargado. */
    private fun songById(mediaId: String?): Song? {
        val id = mediaId?.toLongOrNull() ?: return null
        return (_uiState.value as? UiState.Success)?.songs?.firstOrNull { it.id == id }
            ?: allSongs.firstOrNull { it.id == id }
    }

    /** Recalcula las canciones vecinas según la posición actual en el reproductor. */
    private fun refreshNeighbors() {
        val controller = mediaController
        val index = controller?.currentMediaItemIndex ?: -1
        if (controller == null || index < 0 || controller.mediaItemCount == 0) {
            _neighbors.value = QueueNeighbors()
            return
        }
        val previous = if (index - 1 >= 0) {
            songOf(controller.getMediaItemAt(index - 1))
        } else {
            null
        }
        val next = if (index + 1 < controller.mediaItemCount) {
            songOf(controller.getMediaItemAt(index + 1))
        } else {
            null
        }
        _neighbors.value = QueueNeighbors(previous = previous, next = next)
    }

    fun loadSongs() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                allSongs = repository.getLocalSongs()
                publishSongs()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Error al cargar canciones")
            }
        }
    }

    /**
     * Vuelve a leer el teléfono ("Leer carpetas"): aparecen los audios nuevos,
     * se recalcula la lista de carpetas y se respetan las exclusiones guardadas.
     * [onDone] recibe (carpetas, canciones visibles); -1 si la lectura falló.
     */
    fun rescanSongs(onDone: (Int, Int) -> Unit) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                allSongs = repository.getLocalSongs()
                publishSongs()
                onDone(_folders.value.size, (_uiState.value as? UiState.Success)?.songs?.size ?: 0)
            } catch (e: Exception) {
                onDone(-1, 0)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** Muestra u oculta una carpeta; la decisión queda guardada. */
    fun setFolderExcluded(folder: String, excluded: Boolean) {
        val updated = _excludedFolders.value.toMutableSet().apply {
            if (excluded) add(folder) else remove(folder)
        }
        _excludedFolders.value = updated
        prefs.excludedFolders = updated
        publishSongs()
    }

    /** Activa o desactiva el filtro de sonidos del sistema; queda guardado. */
    fun setFilterSystemSounds(filter: Boolean) {
        prefs.filterSystemSounds = filter
        _filterSystemSounds.value = filter
        publishSongs()
    }

    /** Cambia el máximo de la caché de reproducción y la recorta al momento. */
    fun setCacheMaxMb(maxMb: Int) {
        prefs.cacheMaxMb = maxMb
        _cacheMaxMb.value = maxMb
        viewModelScope.launch(Dispatchers.IO) {
            cache.trimTo(maxMb)
            _cacheBytes.value = cache.usageBytes()
        }
    }

    /** Aplica las carpetas excluidas y el filtro de sonidos, y publica todo. */
    private fun publishSongs() {
        val excluded = _excludedFolders.value
        val filterSystem = _filterSystemSounds.value
        _folders.value = allSongs.toAudioFolders()
        _uiState.value = UiState.Success(
            allSongs.filter { song ->
                song.folder !in excluded && (!filterSystem || !song.systemSound)
            },
        )
        // La biblioteca ya está lista: si faltaba esto para volver a la última
        // sesión (controlador conectado antes que las canciones), se hace ya.
        restorePlaybackIfPossible()
    }

    /**
     * Reproduce [song] armando la cola con toda la lista visible, empezando por
     * la canción elegida. Así funcionan "siguiente", "anterior" y el avance
     * automático al terminar cada pista.
     */
    fun playSong(song: Song) {
        val songs = (_uiState.value as? UiState.Success)?.songs.orEmpty().ifEmpty { listOf(song) }
        val startIndex = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        queue = songs
        _queueSongs.value = queue
        publishSong(song)

        mediaController?.let { controller ->
            controller.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
        refreshNeighbors()
    }

    fun togglePlayPause() {
        mediaController?.let { controller ->
            // Manda la intención, no el estado: mientras el reproductor carga,
            // `isPlaying` es false y una pausa se convertiría en un "play" —
            // la canción seguiría sonando con el botón en pausa.
            if (controller.playWhenReady) controller.pause() else controller.play()
        }
    }

    fun skipToNext() {
        mediaController?.seekToNextMediaItem()
    }

    /** Reinicia la canción actual o salta a la anterior, como en los reproductores clásicos. */
    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    /**
     * Salta a la canción anterior sin la heurística de reinicio; es lo que
     * espera el gesto de deslizar hacia la derecha en el mini reproductor.
     */
    fun skipToPreviousSong() {
        mediaController?.seekToPreviousMediaItem()
    }

    /** Mueve la reproducción a [positionMs] dentro de la pista actual. */
    fun seekTo(positionMs: Long) {
        mediaController?.let { controller ->
            controller.seekTo(positionMs.coerceAtLeast(0L))
            refreshProgress()
        }
    }

    /** Enciende o apaga el modo aleatorio. */
    fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
        _shuffleEnabled.value = controller.shuffleModeEnabled
    }

    /** Rota el modo de repetición: apagado → todo → una → apagado. */
    fun cycleRepeat() {
        val controller = mediaController ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = controller.repeatMode
    }

    /** Lee posición y duración del controlador (operación barata). */
    private fun refreshProgress() {
        val controller = mediaController ?: return
        _progress.value = PlaybackProgress(
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = controller.duration.coerceAtLeast(0L),
        )
    }

    /**
     * Sondea el avance dos veces por segundo mientras suena la música (y más
     * despacio en pausa). Es suficiente para una línea de progreso de 2 dp y
     * no cuesta prácticamente nada.
     */
    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                refreshProgress()
                // Red de seguridad: si la pista que suena no es la que se está
                // enseñando, se corrige. Algún aviso del reproductor se puede
                // escapar por alto (un salto desde la notificación, un
                // cambio de pista en cola) y el título se quedaría en la
                // canción anterior.
                syncCurrentSong()
                val playing = mediaController?.isPlaying == true
                // Cada 5 s de música se guarda el punto exacto por el que va.
                if (playing && ++ticks >= 10) {
                    ticks = 0
                    savePlaybackState()
                }
                delay(if (playing) 500L else 1500L)
            }
        }
    }

    /**
     * Comprueba que la canción que se enseña sea la que suena en el
     * reproductor y, si no lo es, publica la correcta.
     */
    private fun syncCurrentSong() {
        val controller = mediaController ?: return
        val playing = songOf(controller.currentMediaItem) ?: return
        if (_currentSong.value?.id == playing.id) return
        publishSong(playing)
        refreshNeighbors()
    }

    /**
     * Convierte un MediaItem del reproductor de vuelta a su [Song] usando el mediaId.
     *
     * La cola es lo primero, pero si la biblioteca se ha vuelto a leer puede no
     * estar al día: la biblioteca completa hace de respaldo, para no perder la
     * canción que suena y dejar el reproductor en blanco.
     */
    private fun songOf(mediaItem: MediaItem?): Song? {
        val id = mediaItem?.mediaId?.toLongOrNull() ?: return null
        return queue.firstOrNull { it.id == id } ?: songById(mediaItem.mediaId)
    }

    /**
     * Incluye los metadatos para que la notificación del servicio muestre
     * título, artista, álbum y carátula en vez de "Unknown".
     */
    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    override fun onCleared() {
        super.onCleared()
        // Última oportunidad de dejar guardado dónde iba la música.
        savePlaybackState()
        progressJob?.cancel()
        releaseController()
    }

    /**
     * Suelta la conexión con el servicio de reproducción. Si todavía estaba
     * conectando se cancela el intento; si ya estaba hecha, se cierra el
     * controlador. El try/catch cubre un fallo conocido de media3 cuando la app
     * se cierra justo mientras conecta (el servicio aún no estaba registrado).
     */
    private fun releaseController() {
        val future = controllerFuture
        if (future != null && !future.isDone) {
            MediaController.releaseFuture(future)
        } else {
            runCatching { mediaController?.release() }
        }
        controllerFuture = null
        mediaController = null
    }
}
