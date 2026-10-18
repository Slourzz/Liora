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

    private var progressJob: Job? = null

    private var mediaController: MediaController? = null

    fun initializeMediaController(context: Context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    /** Se dispara al cambiar de pista (automático o manual). */
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _currentSong.value = songOf(mediaItem)
                        refreshProgress()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            _isPlaying.value = false
                        }
                    }
                })
                _currentSong.value = songOf(currentMediaItem)
                refreshProgress()
                startProgressTicker()
            }
        }, MoreExecutors.directExecutor())
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

    /** Cambia el máximo de la caché de reproducción y la recorta al momento. */
    fun setCacheMaxMb(maxMb: Int) {
        prefs.cacheMaxMb = maxMb
        _cacheMaxMb.value = maxMb
        viewModelScope.launch(Dispatchers.IO) {
            cache.trimTo(maxMb)
            _cacheBytes.value = cache.usageBytes()
        }
    }

    /** Aplica las carpetas excluidas y publica canciones y carpetas. */
    private fun publishSongs() {
        val excluded = _excludedFolders.value
        _folders.value = allSongs.toAudioFolders()
        _uiState.value = UiState.Success(allSongs.filter { it.folder !in excluded })
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
        _currentSong.value = song

        mediaController?.let { controller ->
            controller.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
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
            while (isActive) {
                refreshProgress()
                delay(if (mediaController?.isPlaying == true) 500L else 1500L)
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
        progressJob?.cancel()
        mediaController?.release()
    }
}
