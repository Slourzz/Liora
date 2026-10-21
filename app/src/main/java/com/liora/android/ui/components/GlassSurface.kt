package com.liora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.depth.PrismalDepthShadow
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.shapes.PrismalRoundedRectangularShape
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.specular.PrismalSpecular
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

/**
 * Modificador de cristal de Liora.
 *
 * - **Liquid Glass** (`liquidGlass = true`): usa PrismalAGSL para copiar el
 *   fondo, saturarlo, desenfocarlo y refractarlo con una lente, más brillo
 *   especular y sombra flotante.
 * - **Simple** (`liquidGlass = false`): solo desenfoque de Haze + tinte.
 *
 * La geometría no vive acá: [shape] se pasa intacta al material, así que las
 * formas, radios y siluetas siguen siendo las de Liora.
 *
 * @param refractionHeight altura de la refracción. `0.dp` desactiva la lente.
 *   También se desactiva sola si [shape] no es una forma de esquinas
 *   redondeadas (Prismal solo refraccta esas siluetas).
 * @param chromaticRefraction dispersión de color en la lente. La reservamos
 *   para piezas chicas (el selector de la navegación): en superficies grandes
 *   casi no se nota y su sombreador cuesta más, así que el mini reproductor y
 *   la píldora van sin ella, igual que antes.
 * @param layerBlock transformaciones que se aplican al cristal y su contenido.
 *   La copia del fondo se muestrea sin transformar, así que el cristal se
 *   estira "encima" del fondo quieto (técnica del tutorial interactivo).
 */
@Composable
fun Modifier.lioraGlass(
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    hazeState: HazeState,
    surfaceColor: Color,
    shape: Shape = RoundedCornerShape(28.dp),
    refractionHeight: Dp = 0.dp,
    refractionAmount: Dp = 32.dp,
    chromaticRefraction: Boolean = false,
    blurRadius: Dp = 4.dp,
    exportedBackdrop: PrismalGlassLayer? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    showHighlight: Boolean = true,
    showShadow: Boolean = true,
    shadowRadius: Dp = 8.dp,
    hazeStyle: HazeBlurStyle = HazeMaterials.thin(),
): Modifier = if (liquidGlass) {
    val canRefract = shape is CornerBasedShape || shape is PrismalRoundedRectangularShape
    drawPrismalGlass(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            applyPrismalGlassEffects(
                density = this,
                adaptiveLuminance = false,
                luminance = 0.5f,
                blurRadiusPx = blurRadius.toPx(),
                refractionHeightPx = if (canRefract) refractionHeight.toPx() else 0f,
                refractionAmountPx = refractionAmount.toPx(),
                chromaticAberration = if (chromaticRefraction) 0.55f else 0f,
                useVibrancy = true,
            )
        },
        specular = if (showHighlight) {
            { PrismalSpecular.Default }
        } else {
            null
        },
        depthShadow = if (showShadow) {
            {
                PrismalDepthShadow(
                    radius = shadowRadius,
                    color = Color.Black.copy(alpha = 0.10f),
                )
            }
        } else {
            null
        },
        nestedGlassSource = exportedBackdrop,
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
 * En modo Liquid Glass el contenido se dibuja en una capa (Prismal); en modo
 * simple se registra como fuente para el desenfoque de Haze.
 */
fun Modifier.glassSource(
    liquidGlass: Boolean,
    backdrop: PrismalGlassLayer,
    hazeState: HazeState,
): Modifier = if (liquidGlass) {
    prismalGlassLayer(backdrop)
} else {
    hazeSource(hazeState)
}
