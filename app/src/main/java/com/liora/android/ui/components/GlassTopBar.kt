package com.liora.android.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import com.kyant.backdrop.Backdrop
import dev.chrisbanes.haze.HazeState

/**
 * Barra superior translúcida: el contenido se ve difuminado al pasar por debajo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    title: String,
    liquidGlass: Boolean,
    backdrop: Backdrop,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier.lioraGlass(
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            shape = RectangleShape,
        ),
    )
}
