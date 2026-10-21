package com.liora.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.styropyr0.prismal.interactive.PrismalPressRipple
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Física de presión de Prismal para botones de Liora.
 *
 * No crea un sistema de resortes nuevo: [PrismalPressRipple] es quien mide la
 * presión, el desplazamiento del dedo, la iluminación por shader y el retorno
 * amortiguado. Este archivo sólo adapta ese estado a la escala y al brillo que
 * ya tenía el botón, sin tocar su `clickable` ni consumir eventos.
 *
 * El modificador se parte en dos para respetar el orden de dibujo:
 * - [prismalButtonScale] va antes del fondo para escalar el botón completo.
 * - [prismalButtonPress] va después del fondo (y antes del icono/contenido)
 *   para que la iluminación quede por encima del cristal o del tinte.
 *
 * Pensado exclusivamente para botones. Las tarjetas, paneles, barras y demás
 * superficies no deben usar estos modificadores.
 */

/** Recuerda una única instancia de la física de Prismal para un botón. */
@Composable
fun rememberPrismalButtonPhysics(): PrismalPressRipple {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        PrismalPressRipple(animationScope = scope)
    }
}

/** Iluminación de contacto y seguimiento del gesto, provistos por Prismal. */
fun Modifier.prismalButtonPress(physics: PrismalPressRipple): Modifier =
    this
        .then(physics.modifier)
        .then(physics.gestureModifier)

/**
 * Expansión elástica y arrastre del dedo, con la receta exacta de
 * `PrismalGlassButton`.
 *
 * La escala y el desplazamiento salen de [PrismalPressRipple.pressProgress] y
 * [PrismalPressRipple.offset]; no hay resortes ni curvas propias compitiendo
 * con las de Prismal. La deformación anisótropa del borde al arrastrar es la
 * misma que aplica el botón de cristal de la librería.
 *
 * @param pressLift cuánto crece el botón al presionarlo, en dp. Se convierte a
 *   fracción usando la altura real del botón, igual que `PrismalGlassButton`
 *   (cuyo valor por defecto también es `4.dp`).
 */
fun Modifier.prismalButtonScale(
    physics: PrismalPressRipple,
    pressLift: Dp = 4.dp,
): Modifier = graphicsLayer {
    val height = size.height.coerceAtLeast(1f)
    val width = size.width.coerceAtLeast(1f)
    val pressLiftPx = pressLift.toPx()

    val progress = physics.pressProgress
    val scale = lerp(1f, 1f + pressLiftPx / height, progress)

    val offset = physics.offset
    val maxOffset = size.minDimension.coerceAtLeast(1f)
    val maxDimension = size.maxDimension.coerceAtLeast(1f)

    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

    val maxDragScale = pressLiftPx / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX =
        scale +
            maxDragScale * abs(cos(offsetAngle) * offset.x / maxDimension) *
            (width / height).fastCoerceAtMost(1f)
    scaleY =
        scale +
            maxDragScale * abs(sin(offsetAngle) * offset.y / maxDimension) *
            (height / width).fastCoerceAtMost(1f)

    transformOrigin = TransformOrigin.Center
}
