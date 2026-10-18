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
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liora.android.data.model.AudioFolder
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.LioraSnackbarHost
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.icons.IconStyle
import com.liora.android.ui.icons.LauncherIconManager
import com.liora.android.ui.viewmodel.MusicViewModel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

private const val REPOSITORY_URL = "https://github.com/Slourzz/Liora"
private val SectionShape = RoundedCornerShape(24.dp)

@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    backdrop: LayerBackdrop,
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

    val folders by viewModel.folders.collectAsState()
    val excludedFolders by viewModel.excludedFolders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val cacheMaxMb by viewModel.cacheMaxMb.collectAsState()
    val cacheBytes by viewModel.cacheBytes.collectAsState()
    val filterSystemSounds by viewModel.filterSystemSounds.collectAsState()

    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    BackHandler(enabled = showIconPicker || showFolderPicker || showCacheDialog) {
        if (showCacheDialog) {
            showCacheDialog = false
        } else {
            showIconPicker = false
            showFolderPicker = false
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
                )
            } else {
                PersonalizationSection(
                    iconStyle = selectedStyle,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = onLiquidGlassChange,
                    onOpenIconPicker = { showIconPicker = true },
                )

                StorageSection(
                    activeFolders = folders.count { it.path !in excludedFolders },
                    totalFolders = folders.size,
                    filterSystemSounds = filterSystemSounds,
                    cacheUsageLabel = Formatter.formatShortFileSize(context, cacheBytes),
                    cacheMaxMb = cacheMaxMb,
                    isScanning = isScanning,
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
    }
}

@Composable
private fun PersonalizationSection(
    iconStyle: IconStyle,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
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

        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        ListItem(
            headlineContent = { Text("Activar Liquid Glass") },
            supportingContent = {
                Text("Píldora flotante con refracción, reflejos y borde Fresnel. " +
                    "Si lo desactivas, la navegación pasa a ser simple y translúcida.")
            },
            trailingContent = {
                Switch(
                    checked = liquidGlass,
                    onCheckedChange = onLiquidGlassChange,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clip(SectionShape)
                .clickable { onLiquidGlassChange(!liquidGlass) },
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
    isScanning: Boolean,
    onOpenFolderPicker: () -> Unit,
    onFilterSystemSoundsChange: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onOpenCachePicker: () -> Unit,
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
                Switch(
                    checked = filterSystemSounds,
                    onCheckedChange = onFilterSystemSoundsChange,
                )
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
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked -> onToggle(folder.path, !checked) },
                    )
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
