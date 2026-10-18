package com.liora.android.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Avisos de Liora con los colores del tema (Material You).
 *
 * La superficie por defecto de Material 3 es gris oscura y rompe con el resto
 * de la app; acá los mensajes toman el contenedor primario del tema para que
 * "Icono: X" o "N carpetas · N canciones" se sientan parte de Liora.
 */
@Composable
fun LioraSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionColor = MaterialTheme.colorScheme.onPrimaryContainer,
                dismissActionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    )
}
