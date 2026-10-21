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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassEffectProvider
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.prismalGlassEffects
import com.styropyr0.prismal.specular.PrismalSpecular

private val SheetCornerRadius = 34.dp
private val SheetHandleWidth = 36.dp
private val SheetHandleHeight = 5.dp
private const val SheetScrimDim = 0.42f

private val SheetSquishEnterSpring = spring<Float>(
    dampingRatio = 0.54f,
    stiffness = 420f,
)

/**
 * Hoja modal de cristal, anclada al borde de abajo.
 *
 * Es la hermana del [PrismalGlassMenu]: el mismo material de cristal, pero en
 * vez de crecer desde un botón, la hoja sube desde abajo con un escorzo (se
 * estira en vertical al entrar) y se desliza al salir. El velo de fondo y la
 * pestaña superior son los de siempre.
 *
 * @param visible Si la hoja está en pantalla.
 * @param onDismissRequest Se llama al tocar fuera o al cerrar.
 * @param backdrop La superficie que se ve a través del cristal. Va en su propia
 *   ventana, así que debe cubrir toda la pantalla.
 * @param title Título del panel, centrado arriba.
 * @param surfaceColor Velo que se pinta sobre el cristal para que se lea.
 * @param effects Sustituye la pila de efectos si no es null.
 * @param specular El brillo del borde; null lo apaga.
 * @param content El contenido de la hoja.
 */
@Composable
fun PrismalGlassBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: PrismalBackdrop,
    title: String? = null,
    modifier: Modifier = Modifier,
    surfaceColor: Color = Color.Unspecified,
    effects: (PrismalGlassEffectProvider.() -> Unit)? = null,
    specular: (() -> PrismalSpecular?)? = { PrismalSpecular.Default },
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = updateTransition(visible, label = "hoja de cristal")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) tween(220) else tween(180)
        },
        label = "velo",
    ) { shown -> if (shown) SheetScrimDim else 0f }

    val slideOffset by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                )

                true isTransitioningTo false -> spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMedium,
                )

                else -> snap()
            }
        },
        label = "deslizamiento",
    ) { shown -> if (shown) 0f else 1f }

    val sheetScaleY by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> SheetSquishEnterSpring
                true isTransitioningTo false -> tween(160)
                else -> snap()
            }
        },
        label = "escorzo",
    ) { shown -> if (shown) 1f else 0.88f }

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
    val shape: () -> Shape = {
        AbsoluteRoundedCornerShape(
            topLeft = SheetCornerRadius,
            topRight = SheetCornerRadius,
            bottomRight = 0.dp,
            bottomLeft = 0.dp,
        )
    }

    backdrop.readSamplingState()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
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

            Column(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = slideOffset * size.height
                        scaleX = 1f
                        scaleY = sheetScaleY
                        alpha = contentAlpha
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
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
                    )
                    // La hoja llega hasta el borde: el contenido no puede quedar
                    // debajo de la barra de gestos.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(SheetHandleWidth)
                            .height(SheetHandleHeight)
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                }

                if (title != null) {
                    BasicText(
                        text = title,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                content()
            }
        }
    }
}
