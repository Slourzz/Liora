package com.liora.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.liora.android.ui.components.GlassTopBar
import com.liora.android.ui.components.glassSource
import com.liora.android.ui.icons.IconStyle
import com.liora.android.ui.icons.LauncherIconManager
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

private const val REPOSITORY_URL = "https://github.com/Slourzz/Liora"
private val SectionShape = RoundedCornerShape(24.dp)

@Composable
fun SettingsScreen(
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

    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    BackHandler(enabled = showIconPicker) { showIconPicker = false }

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
            } else {
                PersonalizationSection(
                    iconStyle = selectedStyle,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = onLiquidGlassChange,
                    onOpenIconPicker = { showIconPicker = true },
                )

                AboutSection(
                    versionName = versionName,
                    onOpenRepository = { openUrl(context, REPOSITORY_URL) },
                )
            }
        }

        GlassTopBar(
            title = if (showIconPicker) "Logo de la app" else "Ajustes",
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            navigationIcon = if (showIconPicker) {
                {
                    IconButton(onClick = { showIconPicker = false }) {
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding),
        )
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
