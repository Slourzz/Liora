package com.liora.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.liora.android.data.repository.MediaRepository
import com.liora.android.ui.screens.HomeScreen
import com.liora.android.ui.screens.SettingsScreen
import com.liora.android.ui.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MusicViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.loadSongs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = MediaRepository(applicationContext)
        viewModel = MusicViewModel(repository)
        viewModel.initializeMediaController(this)

        if (hasAudioPermission()) {
            viewModel.loadSongs()
        }

        setContent {
            var showSettings by rememberSaveable { mutableStateOf(false) }

            if (showSettings) {
                SettingsScreen(onBack = { showSettings = false })
            } else {
                HomeScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { requestPermissions() },
                    hasPermission = hasAudioPermission(),
                    onOpenSettings = { showSettings = true }
                )
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
