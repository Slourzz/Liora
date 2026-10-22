package com.liora.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.liora.android.data.cache.PlaybackCache
import com.liora.android.data.identification.SongIdentificationRepository
import com.liora.android.data.lyrics.AppleMusicLyricsRepository
import com.liora.android.data.lyrics.LioraLyricsRepository
import com.liora.android.data.lyrics.LyricsStore
import com.liora.android.data.lyrics.LyricsTranslationRepository
import com.liora.android.data.lyrics.SpicyLyricsRepository
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.data.repository.MediaRepository
import com.liora.android.extensions.ExtensionsService
import com.liora.android.ui.LioraApp
import com.liora.android.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MusicViewModel

    /** Se mantiene como estado de Compose para que la UI reaccione al conceder el permiso. */
    private val audioPermissionState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        audioPermissionState.value = checkAudioPermission()
        if (permissions.values.any { it }) {
            viewModel.loadSongs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = MediaRepository(applicationContext)
        val prefs = LioraPrefs(applicationContext)
        // El sistema de extensiones: el motor, el instalador y el registro de
        // proveedores. Las extensiones ya instaladas se cargan al abrir la app.
        val extensions = ExtensionsService(applicationContext, prefs)
        viewModel = MusicViewModel(
            repository = repository,
            prefs = prefs,
            cache = PlaybackCache(applicationContext),
            lyricsRepository = LioraLyricsRepository(
                spicyLyrics = SpicyLyricsRepository(applicationContext),
                appleMusicLyrics = AppleMusicLyricsRepository(applicationContext),
                songIdentification = SongIdentificationRepository(applicationContext),
                store = LyricsStore(applicationContext),
            ),
            translationRepository = LyricsTranslationRepository(applicationContext),
            extensions = extensions,
        )
        viewModel.initializeMediaController(this)

        lifecycleScope.launch {
            runCatching { extensions.loadInstalled() }
        }

        audioPermissionState.value = checkAudioPermission()
        if (audioPermissionState.value) {
            viewModel.loadSongs()
        }

        setContent {
            val hasPermission by audioPermissionState
            LioraApp(
                viewModel = viewModel,
                extensions = extensions,
                hasPermission = hasPermission,
                onRequestPermissions = { requestPermissions() },
            )
        }
    }

    private fun checkAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
