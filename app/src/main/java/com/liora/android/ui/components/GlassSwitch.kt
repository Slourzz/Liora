package com.liora.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * al tocarlo y que **se puede arrastrar** con el dedo (al soltar se acomoda al
 * lado más cercano), igual que en el catálogo.
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

    val scope = rememberCoroutineScope()
    val travelPx = with(LocalDensity.current) { ThumbTravel.toPx() }
    // 0 = apagado, 1 = encendido. Durante el arrastre sigue al dedo; al soltar
    // (o cuando cambie [checked] desde fuera) se acomoda con un resorte.
    val fraction = remember { Animatable(if (checked) 1f else 0f) }
    val press = remember { Animatable(0f) }
    // Cada gesto lleva su número: el derretido solo se apaga si nadie lo
    // retomó mientras el pulgar se acomodaba.
    var gesture by remember { mutableIntStateOf(0) }

    val settleSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f)
    val meltSpec = spring<Float>(dampingRatio = .9f, stiffness = 650f)

    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (fraction.targetValue != target) {
            fraction.animateTo(target, settleSpec)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val glass = rememberCombinedBackdrop(
        rememberCanvasBackdrop { drawRect(background) },
        rememberBackdrop(trackBackdrop) { drawBackdrop ->
            scale(
                scaleX = lerp(2f / 3f, .75f, press.value),
                scaleY = lerp(0f, .75f, press.value),
            ) {
                drawBackdrop()
            }
        },
    )

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .pointerInput(travelPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    gesture++
                    val myGesture = gesture
                    var dragged = false
                    var canceled = false
                    var slop = 0f
                    scope.launch { press.animateTo(1f, meltSpec) }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            // El dedo se fue sin soltar: se cancela en silencio.
                            canceled = true
                            break
                        }
                        if (!change.pressed) {
                            // Se suelta: se consume para que la fila no dispare
                            // su propio toque encima del interruptor.
                            change.consume()
                            break
                        }
                        if (change.isConsumed) {
                            // Otro gesto (la lista al desplazarse) tomó el
                            // control: nada de encender ni apagar.
                            canceled = true
                            break
                        }
                        val positionChange = change.positionChange()
                        if (positionChange.x != 0f || positionChange.y != 0f) {
                            slop += abs(positionChange.x) + abs(positionChange.y)
                            if (!dragged && slop > viewConfiguration.touchSlop) {
                                dragged = true
                            }
                            if (dragged) {
                                scope.launch {
                                    fraction.snapTo(
                                        (fraction.value + positionChange.x / travelPx)
                                            .coerceIn(0f, 1f),
                                    )
                                }
                                change.consume()
                            }
                        }
                    }

                    // Al soltar: el lado más cercano si fue arrastre, el
                    // contrario si fue un toque; si se canceló (la lista tomó
                    // el gesto), el pulgar vuelve a su lado actual.
                    val target = when {
                        canceled -> if (checked) 1f else 0f
                        dragged -> if (fraction.value >= .5f) 1f else 0f
                        else -> if (checked) 0f else 1f
                    }
                    if (!canceled && (target == 1f) != checked) {
                        onCheckedChange(target == 1f)
                    }
                    scope.launch {
                        fraction.animateTo(target, settleSpec)
                        if (myGesture == gesture) {
                            press.animateTo(0f, meltSpec)
                        }
                    }
                }
            }
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                onClick(
                    label = if (checked) "Apagar" else "Encender",
                    action = {
                        onCheckedChange(!checked)
                        true
                    },
                )
            },
    ) {
        // Pista: es la capa que el pulgar copia por dentro.
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(CapsuleShape)
                .background(trackColor)
                .size(width = TrackWidth, height = TrackHeight),
        )

        // Pulgar: blanco en reposo; al presionar o arrastrar se vuelve vidrio y
        // deja ver el fondo y la pista estirada, con brillo y sombra interior.
        Box(
            Modifier
                .offset(x = ThumbPadding)
                .size(width = ThumbWidth, height = ThumbHeight)
                .graphicsLayer {
                    translationX = lerp(0f, travelPx, fraction.value)
                    val growth = lerp(1f, 1.35f, press.value)
                    scaleX = growth
                    scaleY = growth
                }
                .drawBackdrop(
                    backdrop = glass,
                    shape = { CapsuleShape },
                    effects = {
                        val progress = press.value
                        blur(8.dp.toPx() * (1f - progress))
                        lens(
                            refractionHeight = 5.dp.toPx() * progress,
                            refractionAmount = 10.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val progress = press.value
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = .05f),
                        )
                    },
                    innerShadow = {
                        val progress = press.value
                        InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 1f - press.value))
                    },
                ),
        )
    }
}
