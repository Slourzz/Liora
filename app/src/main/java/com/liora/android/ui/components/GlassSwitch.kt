package com.liora.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.liora.android.ui.theme.isDark

/** Medidas calcadas del LiquidToggle del catálogo oficial de Backdrop. */
private val TrackWidth = 64.dp
private val TrackHeight = 28.dp
private val ThumbWidth = 40.dp
private val ThumbHeight = 24.dp
private val ThumbPadding = 2.dp
private val ThumbTravel = TrackWidth - ThumbWidth - ThumbPadding * 2
private val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * Interruptor del estilo Glass, adaptado del LiquidToggle del catálogo de
 * Backdrop: una pista en forma de cápsula y un pulgar de vidrio que se derrite
 * al presionarlo (se vuelve translúcido, con brillo y sombra interior).
 *
 * El pulgar copia un fondo propio — el color de la pantalla más la pista
 * ([rememberCanvasBackdrop] + [rememberBackdrop]) y nunca la capa de la
 * pantalla: el contenido de Ajustes vive dentro de esa capa, y copiarla desde
 * dentro cierra un ciclo de dibujo que en la GPU de este teléfono terminaba en
 * un corte nativo.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Colores del catálogo: pista gris translúcida; encendido, el acento neutro
    // del tema (en Glass es grafito, nunca un color de marca).
    val isLight = !MaterialTheme.colorScheme.isDark
    val trackOff = if (isLight) {
        Color(0xFF787878).copy(alpha = .2f)
    } else {
        Color(0xFF787880).copy(alpha = .36f)
    }
    val trackColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else trackOff,
        label = "pista del interruptor",
    )
    val background = MaterialTheme.colorScheme.background

    val offset by animateDpAsState(
        targetValue = if (checked) ThumbTravel else 0.dp,
        animationSpec = spring(dampingRatio = .78f, stiffness = 520f),
        label = "desliz del interruptor",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = .9f, stiffness = 650f),
        label = "presión del interruptor",
    )

    val trackBackdrop = rememberLayerBackdrop()
    val glass = rememberCombinedBackdrop(
        rememberCanvasBackdrop { drawRect(background) },
        rememberBackdrop(trackBackdrop) { drawBackdrop ->
            scale(
                scaleX = lerp(2f / 3f, .75f, press),
                scaleY = lerp(0f, .75f, press),
            ) {
                drawBackdrop()
            }
        },
    )

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        // Pista: es la capa que el pulgar copia por dentro.
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(CapsuleShape)
                .background(trackColor)
                .size(width = TrackWidth, height = TrackHeight),
        )

        // Pulgar: blanco en reposo; al presionar se vuelve vidrio y deja ver el
        // fondo y la pista estirada, con brillo y sombra interior.
        Box(
            Modifier
                .offset(x = ThumbPadding + offset)
                .size(width = ThumbWidth, height = ThumbHeight)
                .graphicsLayer {
                    val growth = lerp(1f, 1.35f, press)
                    scaleX = growth
                    scaleY = growth
                }
                .drawBackdrop(
                    backdrop = glass,
                    shape = { CapsuleShape },
                    effects = {
                        blur(8.dp.toPx() * (1f - press))
                        lens(
                            refractionHeight = 5.dp.toPx() * press,
                            refractionAmount = 10.dp.toPx() * press,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = press,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = .05f),
                        )
                    },
                    innerShadow = {
                        InnerShadow(radius = 4.dp * press, alpha = press)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 1f - press))
                    },
                ),
        )
    }
}
