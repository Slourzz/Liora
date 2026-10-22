package com.liora.android.service

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.liora.android.extensions.audio.ProviderDataSourceFactory
import com.liora.android.extensions.session.SessionStore

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Los origins HTTP pasan por la politica de red de Liora: cada salto
        // de una redireccion se comprueba y las cabeceras de una fuente no
        // viajan a otro dominio. Lo local (ficheros del telefono) sigue
        // usando el comportamiento normal de Media3.
        val dataSourceFactory = ProviderDataSourceFactory(
            context = this,
            sessions = SessionStore(this),
        )
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory),
            )
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
