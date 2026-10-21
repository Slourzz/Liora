package com.liora.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.components.PrismalGlassSlider
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.liora.android.data.model.AudioFolder
import com.liora.android.data.prefs.AppStyle
import com.liora.android.data.prefs.AppThemeMode
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.data.prefs.LyricAnimationLevel
import com.liora.android.data.prefs.PlayerBackground
import com.liora.android.ui.components.GlassSwitch
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.LioraSnackbarHost
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.icons.IconStyle
import com.liora.android.ui.icons.LauncherIconManager
import com.liora.android.ui.viewmodel.MusicViewModel
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val REPOSITORY_URL = "https://github.com/Slourzz/Liora"
private val SectionShape = RoundedCornerShape(24.dp)

@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    liquidGlass: Boolean,
    appStyle: AppStyle,
    themeMode: AppThemeMode,
    onAppStyleChange: (AppStyle) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    backgroundStyle: PlayerBackground,
    onBackgroundStyleChange: (PlayerBackground) -> Unit,
    /** Nivel de animación de las letras: Simple, Normal o Exagerada. */
    lyricsAnimationLevel: LyricAnimationLevel,
    onLyricsAnimationLevelChange: (LyricAnimationLevel) -> Unit,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
    bottomPadding: Dp,
    collapseConnection: NestedScrollConnection,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedStyle by remember { mutableStateOf(LauncherIconManager.currentStyle(context)) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showStoredLyricsDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }

    val folders by viewModel.folders.collectAsState()
    val excludedFolders by viewModel.excludedFolders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val cacheMaxMb by viewModel.cacheMaxMb.collectAsState()
    val cacheBytes by viewModel.cacheBytes.collectAsState()
    val storedLyricsCount by viewModel.storedLyricsCount.collectAsState()
    val filterSystemSounds by viewModel.filterSystemSounds.collectAsState()

    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    BackHandler(
        enabled = showIconPicker || showFolderPicker || showCacheDialog || showThemeDialog ||
            showBackgroundDialog,
    ) {
        when {
            showThemeDialog -> showThemeDialog = false
            showBackgroundDialog -> showBackgroundDialog = false
            showCacheDialog -> showCacheDialog = false
            else -> {
                showIconPicker = false
                showFolderPicker = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(liquidGlass = liquidGlass, backdrop = backdrop, hazeState = hazeState)
                .nestedScroll(collapseConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomPadding + 8.dp),
        ) {
            if (showIconPicker) {
                IconPicker(
                    context = context,
                    selected = selectedStyle,
                    onSelect = { style ->
                        val result = runCatching {
                            LauncherIconManager.setStyle(context, style)
                        }
                        if (result.isSuccess) {
                            selectedStyle = style
                            scope.launch {
                                snackbarHostState.showSnackbar("Icono: ${style.label}")
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("No se pudo cambiar el icono")
                            }
                        }
                    },
                )
            } else if (showFolderPicker) {
                FolderPicker(
                    folders = folders,
                    excludedFolders = excludedFolders,
                    onToggle = { folder, excluded ->
                        viewModel.setFolderExcluded(folder, excluded)
                    },
                    liquidGlass = liquidGlass,
                )
            } else {
                PersonalizationSection(
                    iconStyle = selectedStyle,
                    onOpenIconPicker = { showIconPicker = true },
                )

                DesignSection(
                    appStyle = appStyle,
                    themeMode = themeMode,
                    onAppStyleChange = { style ->
                        onAppStyleChange(style)
                        scope.launch {
                            snackbarHostState.showSnackbar("Estilo: ${styleTitle(style)}")
                        }
                    },
                    onOpenThemePicker = { showThemeDialog = true },
                )

                PlayerDesignSection(
                    backgroundStyle = backgroundStyle,
                    onOpenBackgroundPicker = { showBackgroundDialog = true },
                )

                LyricsSection(
                    animationLevel = lyricsAnimationLevel,
                    liquidGlass = liquidGlass,
                    backdrop = backdrop,
                    onLevelChange = onLyricsAnimationLevelChange,
                )

                StorageSection(
                    activeFolders = folders.count { it.path !in excludedFolders },
                    totalFolders = folders.size,
                    filterSystemSounds = filterSystemSounds,
                    cacheUsageLabel = Formatter.formatShortFileSize(context, cacheBytes),
                    cacheMaxMb = cacheMaxMb,
                    storedLyricsCount = storedLyricsCount,
                    isScanning = isScanning,
                    liquidGlass = liquidGlass,
                    onOpenFolderPicker = { showFolderPicker = true },
                    onFilterSystemSoundsChange = viewModel::setFilterSystemSounds,
                    onRescan = {
                        viewModel.rescanSongs { folderCount, songCount ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (folderCount < 0) {
                                        "No se pudieron leer las carpetas"
                                    } else {
                                        "$folderCount carpetas · $songCount canciones"
                                    }
                                )
                            }
                        }
                    },
                    onOpenCachePicker = { showCacheDialog = true },
                    onOpenStoredLyrics = { showStoredLyricsDialog = true },
                )

                AboutSection(
                    versionName = versionName,
                    onOpenRepository = { openUrl(context, REPOSITORY_URL) },
                )
            }
        }

        GlassTopBar(
            title = when {
                showIconPicker -> "Logo de la app"
                showFolderPicker -> "Carpetas de audio"
                else -> "Ajustes"
            },
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            navigationIcon = if (showIconPicker || showFolderPicker) {
                {
                    IconButton(
                        onClick = {
                            showIconPicker = false
                            showFolderPicker = false
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        LioraSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding),
        )

        if (showCacheDialog) {
            CacheSizeDialog(
                currentMb = cacheMaxMb,
                usageLabel = Formatter.formatShortFileSize(context, cacheBytes),
                onSelect = { mb ->
                    viewModel.setCacheMaxMb(mb)
                    showCacheDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Caché: ${LioraPrefs.cacheSizeLabel(mb)}")
                    }
                },
                onDismiss = { showCacheDialog = false },
            )
        }

        if (showStoredLyricsDialog) {
            AlertDialog(
                onDismissRequest = { showStoredLyricsDialog = false },
                title = { Text("Borrar las letras guardadas") },
                text = {
                    Text(
                        "Se borrarán las letras de $storedLyricsCount canciones. " +
                            "La próxima vez que suenen, se buscarán otra vez.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearStoredLyrics()
                            showStoredLyricsDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Letras guardadas borradas")
                            }
                        },
                    ) {
                        Text("Borrar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStoredLyricsDialog = false }) {
                        Text("Cancelar")
                    }
                },
            )
        }

        if (showThemeDialog) {
            ThemeModeDialog(
                current = themeMode,
                onSelect = { mode ->
                    onThemeModeChange(mode)
                    showThemeDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Tema: ${themeModeLabel(mode)}")
                    }
                },
                onDismiss = { showThemeDialog = false },
            )
        }

        if (showBackgroundDialog) {
            BackgroundStyleDialog(
                current = backgroundStyle,
                onSelect = { style ->
                    onBackgroundStyleChange(style)
                    showBackgroundDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Fondo: ${backgroundStyleLabel(style)}")
                    }
                },
                onDismiss = { showBackgroundDialog = false },
            )
        }
    }
}

@Composable
private fun PersonalizationSection(
    iconStyle: IconStyle,
    onOpenIconPicker: () -> Unit,
) {
    SettingsSection(title = "Personalización") {
        ListItem(
            headlineContent = { Text("Logo de la app") },
            supportingContent = {
                Text(
                    text = "Estilo actual: ${iconStyle.label}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Image(
                    painter = painterResource(iconStyle.preview),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenIconPicker),
        )
    }
}

/**
 * Sección "Diseño": el estilo visual de Liora (solo uno a la vez) y el modo de
 * tema de la app.
 */
@Composable
private fun DesignSection(
    appStyle: AppStyle,
    themeMode: AppThemeMode,
    onAppStyleChange: (AppStyle) -> Unit,
    onOpenThemePicker: () -> Unit,
) {
    SettingsSection(title = "Diseño") {
        AppStyle.entries.forEach { style ->
            ListItem(
                headlineContent = { Text(styleTitle(style)) },
                supportingContent = { Text(styleDescription(style)) },
                trailingContent = {
                    RadioButton(
                        selected = style == appStyle,
                        onClick = { onAppStyleChange(style) },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(SectionShape)
                    .clickable { onAppStyleChange(style) },
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        ListItem(
            headlineContent = { Text("Tema de la app") },
            supportingContent = { Text(themeModeLabel(themeMode)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenThemePicker),
        )
    }
}

/** Nombre visible de cada estilo visual. */
private fun styleTitle(style: AppStyle): String = when (style) {
    AppStyle.Material -> "Material You"
    AppStyle.Dynamic -> "Dinámico"
    AppStyle.Glass -> "Glass"
}

/** Explicación corta de cada estilo visual. */
private fun styleDescription(style: AppStyle): String = when (style) {
    AppStyle.Material -> "El aspecto de siempre, con los colores del sistema."
    AppStyle.Dynamic ->
        "Toma los colores de la portada de la canción actual y los cambia " +
            "con un degradado suave."
    AppStyle.Glass ->
        "Cristal líquido: navegación, barra de música y controles con refracción."
}

/** Nombre visible de cada modo de tema. */
private fun themeModeLabel(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.System -> "Según el sistema"
    AppThemeMode.Light -> "Claro"
    AppThemeMode.Dark -> "Oscuro"
}

/** Nombre visible de cada fondo del reproductor. */
private fun backgroundStyleLabel(style: PlayerBackground): String = when (style) {
    PlayerBackground.Kawarp -> "Kawarp"
    PlayerBackground.Solid -> "Sólido"
}

/** Explicación corta de cada fondo del reproductor. */
private fun backgroundStyleDescription(style: PlayerBackground): String = when (style) {
    PlayerBackground.Kawarp ->
        "La portada hecha manchas de color, meciéndose despacio mientras suena " +
            "la música."
    PlayerBackground.Solid ->
        "Un único color sacado de la portada, sin degradado ni movimiento."
}

/**
 * Sección "Diseño del reproductor": cómo se viste el reproductor completo.
 */
@Composable
private fun PlayerDesignSection(
    backgroundStyle: PlayerBackground,
    onOpenBackgroundPicker: () -> Unit,
) {
    SettingsSection(title = "Diseño del reproductor") {
        ListItem(
            headlineContent = { Text("Estilo del fondo") },
            supportingContent = { Text(backgroundStyleLabel(backgroundStyle)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenBackgroundPicker),
        )
    }
}

/** Nombre en español de cada nivel de animación de las letras. */
private fun lyricsAnimationLabel(level: LyricAnimationLevel): String = when (level) {
    LyricAnimationLevel.Simple -> "Simple"

    LyricAnimationLevel.Normal -> "Normal"

    LyricAnimationLevel.Exaggerated -> "Exagerada"
}

@Composable
private fun LyricsSection(
    animationLevel: LyricAnimationLevel,
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    onLevelChange: (LyricAnimationLevel) -> Unit,
) {
    /*
     * El slider tiene 3 paradas —Simple, Normal, Exagerada—, en el orden
     * natural del nivel: 0 apaga la ola, 2 la dispara con todo. Solo el
     * estilo Glass lleva el material de Prismal; el resto usa el Slider
     * de Material sin ruido.
     */
    SettingsSection(title = "Letras") {
        ListItem(
            headlineContent = { Text("Animación de letras") },
            supportingContent = {
                Column {
                    Text(lyricsAnimationLabel(animationLevel))

                    if (liquidGlass) {
                        PrismalGlassSlider(
                            value = { animationLevel.ordinal.toFloat() },
                            onValueChange = { value ->
                                val level = LyricAnimationLevel
                                    .entries[value.roundToInt()]
                                    .coerceIn(
                                        LyricAnimationLevel.Simple,
                                        LyricAnimationLevel.Exaggerated,
                                    )

                                if (level != animationLevel) {
                                    onLevelChange(level)
                                }
                            },
                            valueRange = 0f..2f,
                            visibilityThreshold = 0.25f,
                            backdrop = backdrop,
                            stops = listOf(0f, 1f, 2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 6.dp),
                        )
                    } else {
                        Slider(
                            value = animationLevel.ordinal.toFloat(),
                            onValueChange = { value ->
                                val level = LyricAnimationLevel
                                    .entries[value.roundToInt()]
                                    .coerceIn(
                                        LyricAnimationLevel.Simple,
                                        LyricAnimationLevel.Exaggerated,
                                    )

                                if (level != animationLevel) {
                                    onLevelChange(level)
                                }
                            },
                            valueRange = 0f..2f,
                            steps = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clip(SectionShape),
        )
    }
}

@Composable
private fun StorageSection(
    activeFolders: Int,
    totalFolders: Int,
    filterSystemSounds: Boolean,
    cacheUsageLabel: String,
    cacheMaxMb: Int,
    storedLyricsCount: Int,
    isScanning: Boolean,
    liquidGlass: Boolean,
    onOpenFolderPicker: () -> Unit,
    onFilterSystemSoundsChange: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onOpenCachePicker: () -> Unit,
    onOpenStoredLyrics: () -> Unit,
) {
    SettingsSection(title = "Almacenamiento") {
        ListItem(
            headlineContent = { Text("Excluir carpetas de audio") },
            supportingContent = {
                Text(
                    if (totalFolders == 0) {
                        "Todavía no hay carpetas leídas"
                    } else {
                        "$activeFolders de $totalFolders carpetas activas"
                    },
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenFolderPicker),
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Filtrar sonidos del sistema") },
            supportingContent = {
                Text(
                    "Oculta alarmas, timbres y avisos del teléfono que se cuelan " +
                        "en la biblioteca.",
                )
            },
            trailingContent = {
                if (liquidGlass) {
                    GlassSwitch(
                        checked = filterSystemSounds,
                        onCheckedChange = onFilterSystemSoundsChange,
                    )
                } else {
                    Switch(
                        checked = filterSystemSounds,
                        onCheckedChange = onFilterSystemSoundsChange,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable { onFilterSystemSoundsChange(!filterSystemSounds) },
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Leer carpetas") },
            supportingContent = {
                Text(
                    "Vuelve a leer el teléfono: encuentra audios nuevos y " +
                        "respeta las carpetas excluidas.",
                )
            },
            trailingContent = {
                if (isScanning) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(enabled = !isScanning, onClick = onRescan),
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Caché de reproducción") },
            supportingContent = {
                Text(
                    "Actualmente $cacheUsageLabel · " +
                        "Máximo ${LioraPrefs.cacheSizeLabel(cacheMaxMb)}",
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenCachePicker),
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Letras guardadas") },
            supportingContent = {
                Text(
                    if (storedLyricsCount == 0) {
                        "Todavía no hay ninguna letra guardada"
                    } else {
                        "$storedLyricsCount canciones con su letra · " +
                            "Toca para borrarlas"
                    },
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(enabled = storedLyricsCount > 0, onClick = onOpenStoredLyrics),
        )
    }
}

/**
 * Lista vertical de carpetas con audio. Cada interruptor decide si Liora la
 * reproduce (y la muestra) o la deja fuera por completo.
 */
@Composable
private fun FolderPicker(
    folders: List<AudioFolder>,
    excludedFolders: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    liquidGlass: Boolean,
) {
    Text(
        text = "Elige qué carpetas puede reproducir Liora. Las que apagues no " +
            "aparecen en las listas ni entran en la cola de reproducción.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )

    if (folders.isEmpty()) {
        Text(
            text = "Todavía no se encontraron carpetas con audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
        )
        return
    }

    val activeCount = folders.count { it.path !in excludedFolders }

    SettingsSection(title = "$activeCount de ${folders.size} carpetas activas") {
        folders.forEachIndexed { index, folder ->
            val enabled = folder.path !in excludedFolders

            ListItem(
                headlineContent = { Text(folder.name) },
                supportingContent = {
                    Text(
                        text = "${folder.songCount} " +
                            "${if (folder.songCount == 1) "canción" else "canciones"} · " +
                            prettyPath(folder.path),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    if (liquidGlass) {
                        GlassSwitch(
                            checked = enabled,
                            onCheckedChange = { checked -> onToggle(folder.path, !checked) },
                        )
                    } else {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked -> onToggle(folder.path, !checked) },
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(SectionShape)
                    .clickable { onToggle(folder.path, enabled) },
            )

            if (index != folders.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** Resumen corto de una ruta larga: "…/emulated/0/Download". */
private fun prettyPath(path: String): String {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 3) path else "…/" + parts.takeLast(3).joinToString("/")
}

/** Selector del tamaño máximo de la caché de reproducción. */
@Composable
private fun CacheSizeDialog(
    currentMb: Int,
    usageLabel: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caché de reproducción") },
        text = {
            Column {
                Text(
                    text = "Máximo de espacio que Liora puede usar para copias " +
                        "temporales de la música. Ahora mismo ocupa $usageLabel.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                LioraPrefs.CACHE_SIZES_MB.forEach { mb ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(mb) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = mb == currentMb,
                            onClick = { onSelect(mb) },
                        )
                        Text(
                            text = LioraPrefs.cacheSizeLabel(mb),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Listo") }
        },
    )
}

/** Selector del modo de tema: según el sistema, claro u oscuro. */
@Composable
private fun ThemeModeDialog(
    current: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tema de la app") },
        text = {
            Column {
                Text(
                    text = "Elige si Liora sigue el modo del teléfono o si se ve " +
                        "siempre clara u oscura.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                AppThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = mode == current,
                            onClick = { onSelect(mode) },
                        )
                        Text(
                            text = themeModeLabel(mode),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Listo") }
        },
    )
}

/** Selector del fondo del reproductor: el campo vivo o un único color. */
@Composable
private fun BackgroundStyleDialog(
    current: PlayerBackground,
    onSelect: (PlayerBackground) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Estilo del fondo") },
        text = {
            Column {
                Text(
                    text = "Elige qué se ve detrás de la portada en el reproductor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlayerBackground.entries.forEach { style ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(style) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = style == current,
                            onClick = { onSelect(style) },
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = backgroundStyleLabel(style),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = backgroundStyleDescription(style),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Listo") }
        },
    )
}

@Composable
private fun AboutSection(
    versionName: String,
    onOpenRepository: () -> Unit,
) {
    SettingsSection(title = "Acerca de") {
        ListItem(
            headlineContent = { Text("Versión") },
            supportingContent = { Text(versionName) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clip(SectionShape),
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Licencia") },
            supportingContent = { Text("AGPL-3.0 · software libre") },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clip(SectionShape),
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Repositorio") },
            supportingContent = { Text("github.com/Slourzz/Liora") },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable(onClick = onOpenRepository),
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(
            shape = SectionShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun IconPicker(
    context: Context,
    selected: IconStyle,
    onSelect: (IconStyle) -> Unit,
) {
    Text(
        text = "Elige cómo quieres que se vea Liora en tu launcher. " +
            "Algunos lanzadores tardan unos segundos en refrescar el icono.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )

    IconStyle.entries.forEach { style ->
        IconRow(
            style = style,
            selected = style == selected,
            onClick = { onSelect(style) },
        )
    }
}

@Composable
private fun IconRow(
    style: IconStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 3.dp else 1.dp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Image(
                painter = painterResource(style.preview),
                contentDescription = null,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(15.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
