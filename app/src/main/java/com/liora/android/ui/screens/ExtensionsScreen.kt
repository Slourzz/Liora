package com.liora.android.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liora.android.extensions.ExtensionsService
import com.liora.android.extensions.model.ExtensionException
import com.liora.android.extensions.model.ProviderType
import com.liora.android.extensions.repository.CatalogEntry
import kotlinx.coroutines.launch

private val SectionShape = RoundedCornerShape(24.dp)

/**
 * La sección de extensiones.
 *
 * Aquí se añaden los repositorios externos, se consulta su catálogo y se
 * instalan las extensiones. Una extensión instalada aporta sus proveedores a
 * Liora: por eso se ven sus capacidades y sus permisos antes de instalarla.
 */
@Composable
fun ExtensionsScreen(
    service: ExtensionsService,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var repositories by remember { mutableStateOf(service.repositories()) }
    var catalog by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var installed by remember { mutableStateOf(service.installedEntries()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    /** Recarga el catálogo y el estado de las instaladas. */
    suspend fun refresh() {
        working = true
        runCatching {
            catalog = service.catalog()
            repositories = service.repositories()
            installed = service.installedEntries()
        }.onFailure { failure ->
            error = failure.message ?: "No se pudo consultar el catálogo"
        }
        working = false
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 92.dp, bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Extensiones",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { refresh() } }) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Actualizar el catálogo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (error != null) {
            Surface(
                shape = SectionShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }

        // La pantalla vive dentro de la columna con scroll de Ajustes, asi que
        // aqui no cabe un LazyColumn: su alto seria infinito.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            ExtensionsSection(title = "Repositorios") {
                repositories.forEach { url ->
                    RepositoryRow(
                        url = url,
                        onRemove = {
                            scope.launch {
                                runCatching { service.removeRepository(url) }
                                refresh()
                            }
                        },
                    )
                }
                ActionRow(
                    icon = Icons.Rounded.Add,
                    title = "Añadir repositorio",
                    subtitle = "La dirección del registro JSON del repositorio",
                    onClick = { showAdd = true },
                )
            }

            ExtensionsSection(title = "Instaladas") {
                if (installed.isEmpty()) {
                    Text(
                        text = "Todavía no hay extensiones instaladas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
                installed.forEach { entry ->
                    InstalledRow(
                        name = entry.displayName,
                        version = entry.version,
                        types = entry.types,
                        enabled = entry.enabled,
                        error = entry.error,
                        onToggle = { enabled ->
                            service.setEnabled(entry.extensionId, enabled)
                            installed = service.installedEntries()
                        },
                        onUninstall = {
                            scope.launch {
                                runCatching { service.uninstall(entry.extensionId) }
                                refresh()
                            }
                        },
                    )
                }
            }

            ExtensionsSection(title = "Disponibles") {
                if (working) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else if (catalog.isEmpty()) {
                    Text(
                        text = "No hay extensiones en los repositorios añadidos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
            }

            catalog.forEach { entry ->
                CatalogCard(
                    entry = entry,
                    onInstall = {
                        scope.launch {
                            working = true
                            error = null
                            runCatching { service.install(entry.extension) }
                                .onFailure { failure ->
                                    error = failure.message ?: "No se pudo instalar"
                                }
                            refresh()
                        }
                    },
                    onUninstall = {
                        scope.launch {
                            runCatching { service.uninstall(entry.id) }
                            refresh()
                        }
                    },
                )
            }
        }
    }

    if (showAdd) {
        AddRepositoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { url ->
                showAdd = false
                scope.launch {
                    working = true
                    runCatching { service.addRepository(url) }
                        .onFailure { failure ->
                            Log.w("LioraExt", "No se pudo añadir el repositorio: ${failure.message}")
                            error = failure.message ?: "No se pudo añadir el repositorio"
                        }
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun ExtensionsSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
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
private fun RepositoryRow(
    url: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Quitar el repositorio",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstalledRow(
    name: String,
    version: String,
    types: Set<ProviderType>,
    enabled: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "v$version · " + types.joinToString(", ") { it.wire },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
        IconButton(onClick = onUninstall) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Desinstalar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogEntry,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val extension = entry.extension
    Surface(
        shape = SectionShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (entry.isInstalled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = extension.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "v${extension.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (extension.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = extension.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Permisos: " + permissionsLabel(extension),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (entry.isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Instalada (v${entry.installedVersion})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.hasUpdate) {
                        TextButton(onClick = onInstall) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Actualizar")
                        }
                    } else {
                        TextButton(onClick = onUninstall) { Text("Desinstalar") }
                    }
                }
            } else {
                Button(onClick = onInstall) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Instalar")
                }
            }
        }
    }
}

private fun permissionsLabel(extension: com.liora.android.extensions.model.RegistryExtension): String {
    val parts = mutableListOf<String>()
    if (extension.sha256.isNotBlank()) parts += "hash verificado"
    parts += if (extension.minLioraVersion.isNotBlank()) {
        "Liora ${extension.minLioraVersion}+"
    } else {
        "cualquier versión"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir repositorio") },
        text = {
            Column {
                Text(
                    text = "La dirección del registro JSON del repositorio de extensiones. " +
                        "También vale la forma github.com/usuario/repositorio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Dirección del repositorio") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text("Añadir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
