package com.liora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

/**
 * Modificador de cristal de Liora.
 *
 * - **Liquid Glass** (`liquidGlass = true`): usa la librería Backdrop (Kyant0)
 *   para copiar el fondo, saturarlo, desenfocarlo y refractarlo con una lente,
 *   más brillo especular y sombra flotante.
 * - **Simple** (`liquidGlass = false`): solo desenfoque de Haze + tinte.
 *
 * @param refractionHeight altura de la refracción. `0.dp` desactiva la lente
 *   (obligatorio si [shape] no es una forma de esquinas redondeadas).
 * @param chromaticRefraction dispersión de color en la lente. La reservamos
 *   para piezas chicas (el selector de la navegación): en superficies grandes
 *   casi no se nota y su sombreador cuesta el triple, así que el mini
 *   reproductor y la píldora van sin ella, igual que en Cloud.
 * @param layerBlock transformaciones que se aplican al cristal y su contenido.
 *   La copia del fondo se muestrea sin transformar, así que el cristal se
 *   estira "encima" del fondo quieto (técnica del tutorial interactivo).
 */
@Composable
fun Modifier.lioraGlass(
    liquidGlass: Boolean,
    backdrop: Backdrop,
    hazeState: HazeState,
    surfaceColor: Color,
    shape: Shape = RoundedCornerShape(28.dp),
    refractionHeight: Dp = 0.dp,
    refractionAmount: Dp = 32.dp,
    chromaticRefraction: Boolean = false,
    blurRadius: Dp = 4.dp,
    exportedBackdrop: LayerBackdrop? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    showHighlight: Boolean = true,
    showShadow: Boolean = true,
    shadowRadius: Dp = 8.dp,
    hazeStyle: HazeBlurStyle = HazeMaterials.thin(),
): Modifier = if (liquidGlass) {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (refractionHeight > 0.dp) {
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    chromaticAberration = chromaticRefraction,
                )
            }
        },
        highlight = if (showHighlight) {
            { Highlight.Default }
        } else {
            null
        },
        shadow = if (showShadow) {
            { Shadow(radius = shadowRadius) }
        } else {
            null
        },
        exportedBackdrop = exportedBackdrop,
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(surfaceColor) },
    )
} else {
    (if (layerBlock != null) graphicsLayer(layerBlock) else this)
        .hazeBlur(input = HazeInput.Sources(hazeState), style = hazeStyle)
        .background(surfaceColor)
}

/**
 * Marca el contenido que las capas de cristal van a copiar.
 *
 * En modo Liquid Glass el contenido se dibuja en una capa (Backdrop); en modo
 * simple se registra como fuente para el desenfoque de Haze.
 */
fun Modifier.glassSource(
    liquidGlass: Boolean,
    backdrop: LayerBackdrop,
    hazeState: HazeState,
): Modifier = if (liquidGlass) {
    layerBackdrop(backdrop)
} else {
    hazeSource(hazeState)
}
