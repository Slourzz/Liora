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
import com.liora.android.data.model.Song
import com.liora.android.data.repository.MediaRepository
import com.liora.android.service.PlaybackService
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

class MusicViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
                val songs = repository.getLocalSongs()
                _uiState.value = UiState.Success(songs)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Error al cargar canciones")
            }
        }
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
