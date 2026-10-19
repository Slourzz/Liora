package com.liora.android.ui.viewmodel

import android.content.ComponentName
import android.content.Context
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
import com.liora.android.data.model.AudioFolder
import com.liora.android.data.model.Song
import com.liora.android.data.model.toAudioFolders
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.data.repository.MediaRepository
import com.liora.android.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

class MusicViewModel(
    private val repository: MediaRepository,
    private val prefs: LioraPrefs,
    private val cache: PlaybackCache,
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
    }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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
                }

                /** Se dispara al cambiar de pista (automático o manual). */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentSong.value = songOf(mediaItem)
                    refreshProgress()
                    refreshNeighbors()
                    savePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _isPlaying.value = false
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
            adoptRunningSession()
            _currentSong.value = songOf(controller.currentMediaItem)
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
        _currentSong.value = song
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
            playing = controller.isPlaying,
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
        _currentSong.value = song

        mediaController?.let { controller ->
            controller.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
        refreshNeighbors()
    }

    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.isPlaying) controller.pause() else controller.play()
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

    /** Convierte un MediaItem del reproductor de vuelta a su [Song] usando el mediaId. */
    private fun songOf(mediaItem: MediaItem?): Song? {
        val id = mediaItem?.mediaId?.toLongOrNull() ?: return null
        return queue.firstOrNull { it.id == id }
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
