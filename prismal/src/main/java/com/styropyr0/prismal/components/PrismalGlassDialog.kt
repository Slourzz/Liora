package com.styropyr0.prismal.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassEffectProvider
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.prismalGlassEffects
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.specular.PrismalSpecular

private val DialogCornerRadius = 35.dp
private val DialogWidth = 270.dp
private const val DialogScrimDim = 0.42f

private val DialogSquishEnterSpring = spring<Float>(
    dampingRatio = 0.54f,
    stiffness = 420f,
)

/**
 * Diálogo modal de cristal, centrado en la pantalla.
 *
 * A diferencia del [PrismalGlassMenu], que crece desde un botón, este panel
 * aparece en el centro con un escorzo: se estira en horizontal y se comprime
 * en vertical al entrar, como el diálogo de Crystal. El velo de fondo atenúa la
 * pantalla y se puede tocar fuera para cerrar.
 *
 * @param visible Si el diálogo está en pantalla.
 * @param onDismissRequest Se llama al tocar fuera, al cerrar o con el botón
 *   Atrás.
 * @param backdrop La superficie que se ve a través del cristal. Va en su propia
 *   ventana, así que debe cubrir toda la pantalla.
 * @param surfaceColor Velo que se pinta sobre el cristal para que se lea.
 * @param effects Sustituye la pila de efectos si no es null.
 * @param specular El brillo del borde; null lo apaga.
 * @param content El contenido del panel.
 */
@Composable
fun PrismalGlassDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = DialogWidth,
    surfaceColor: Color = Color.Unspecified,
    effects: (PrismalGlassEffectProvider.() -> Unit)? = null,
    specular: (() -> PrismalSpecular?)? = { PrismalSpecular.Default },
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = updateTransition(visible, label = "diálogo de cristal")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) tween(220) else tween(180)
        },
        label = "velo",
    ) { shown -> if (shown) DialogScrimDim else 0f }

    val squishX by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> DialogSquishEnterSpring
                true isTransitioningTo false -> tween(150)
                else -> snap()
            }
        },
        label = "escorzo horizontal",
    ) { shown -> if (shown) 1f else 1.1f }

    val squishY by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> DialogSquishEnterSpring
                true isTransitioningTo false -> tween(150)
                else -> snap()
            }
        },
        label = "escorzo vertical",
    ) { shown -> if (shown) 1f else 0.72f }

    val contentAlpha by transition.animateFloat(
        transitionSpec = { tween(180) },
        label = "contenido",
    ) { shown -> if (shown) 1f else 0f }

    if (!visible && scrimAlpha <= 0f && contentAlpha <= 0f && !transition.isRunning) return

    val density = LocalDensity.current
    val resolvedSurface =
        if (surfaceColor != Color.Unspecified) {
            surfaceColor
        } else {
            // Cristal normal, como el resto de la app: un velo apenas, que el
            // fondo se vea a través. El tinte lo pone quien lo use.
            Color.White.copy(alpha = 0.16f)
        }
    val shape: () -> Shape = { PrismalRoundedRectangle(DialogCornerRadius) }

    backdrop.readSamplingState()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = squishX
                        scaleY = squishY
                        alpha = contentAlpha
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = modifier
                        .width(width)
                        .drawPrismalGlass(
                            backdrop = backdrop,
                            shape = shape,
                            // La receta estándar de cristal líquido, sin adornos.
                            effects = effects ?: prismalGlassEffects(density),
                            specular = specular,
                            onDrawSurface = { drawRect(resolvedSurface) },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    content = content,
                )
            }
        }
    }
}
