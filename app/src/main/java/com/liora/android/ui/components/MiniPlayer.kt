package com.liora.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.liora.android.data.model.Song
import dev.chrisbanes.haze.HazeState

/** Alto del mini reproductor expandido y cuánto se comprime al colapsar. */
internal val MiniPlayerHeight = 62.dp
private val MiniPlayerCollapsedDelta = 16.dp

/** Alto del mini reproductor ya comprimido; lo usa el área inferior para alinear. */
internal val MiniPlayerCollapsedHeight = MiniPlayerHeight - MiniPlayerCollapsedDelta

/** Margen lateral de la barra; el área inferior lo descuenta al comprimir. */
internal val MiniPlayerSidePadding = 12.dp

/** Ancho de la sección de controles (3 botones de 40 dp + 6 dp de margen). */
private val MiniPlayerControlsWidth = 132.dp
private val MiniPlayerGap = 9.dp
private val MiniPlayerButtonSize = 40.dp
private val CompactButtonSize = 36.dp

/** Esquinas de las dos piezas del mini reproductor. */
private val MiniPlayerShape = RoundedCornerShape(28.dp)

/**
 * Modo simple (Haze): una sola barra a todo lo ancho, con las esquinas de
 * arriba redondeadas y las de abajo rectas para quedar pegada a la navegación.
 */
private val SimplePlayerShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)
/**
 * Barra de "sonando ahora", flotante por encima de la navegación.
 *
 * Es un port del mini reproductor de Cloud, con dos cristales:
 * - izquierda: portada, título y línea de progreso; se puede arrastrar para
 *   estirarla (el cristal se deforma mientras el fondo queda quieto) y al
 *   soltar con un desliz horizontal cambia de canción;
 * - derecha: controles, con el icono de reproducir/pausar que hace morph.
 *
 * Con [collapseProgress] > 0 (al bajar por la lista) todo el conjunto se
 * comprime: la barra pierde alto, la portada y el texto se achican, el cristal
 * de controles se desvanece y aparecen controles compactos a la derecha.
 *
 * En modo simple (Haze) no hay morph ni dos cristales: es una sola barra a
 * todo lo ancho, pegada arriba de la navegación, con los controles dentro de
 * ella y la línea de progreso de lado a lado.
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progressFraction: Float,
    collapseProgress: Float,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    liquidGlass: Boolean,
    backdrop: Backdrop,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val collapse = collapseProgress.coerceIn(0f, 1f)
    val playerHeight = MiniPlayerHeight - MiniPlayerCollapsedDelta * collapse

    // Modo simple (Haze): no hay morph ni dos cristales; la barra es una sola
    // pieza a todo lo ancho que se apoya directamente en la navegación.
    val simpleBar = !liquidGlass
    val barShape = if (simpleBar) SimplePlayerShape else MiniPlayerShape

    val darkMode = isSystemInDarkTheme()
    val infoTint = if (darkMode) {
        Color(0xFF121212).copy(alpha = 0.24f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.30f)
    }
    val controlsTint = if (darkMode) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = LinearEasing),
        label = "progreso mini reproductor",
    )

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = if (simpleBar) 0.dp else MiniPlayerSidePadding)
            .height(playerHeight),
    ) {
        val expandedInfoWidth = (maxWidth - MiniPlayerControlsWidth - MiniPlayerGap)
            .coerceAtLeast(1.dp)
        // La sección de información se come el espacio que sueltan los controles;
        // en modo simple ya ocupa todo el ancho (los controles van encima).
        val infoWidth = if (simpleBar) {
            maxWidth
        } else {
            expandedInfoWidth + (maxWidth - expandedInfoWidth) * collapse
        }
        val collapsingControlsWidth = MiniPlayerControlsWidth * (1f - collapse) + 1.dp * collapse

        val expandedGlassAlpha = (1f - collapse * 1.35f).coerceIn(0f, 1f)
        val expandedControlsAlpha = (1f - collapse / .58f).coerceIn(0f, 1f)
        val compactControlsAlpha = ((collapse - .34f) / .58f).coerceIn(0f, 1f)
        val expandedEnabled = collapse < .45f
        val compactEnabled = collapse >= .45f

        // 1) Cristal de información: acá vive la portada, el título y la
        // deformación elástica al arrastrar.
        ElasticGlassSection(
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            hazeState = hazeState,
            shape = barShape,
            surfaceColor = infoTint,
            modifier = Modifier
                .width(infoWidth)
                .fillMaxHeight(),
            onStretchLeft = onPrevious,
            onStretchRight = onNext,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp - 6.dp * collapse,
                        // En modo simple hay que dejarle sitio a los controles,
                        // que ahora viven dentro de esta misma barra.
                        end = if (simpleBar) {
                            MiniPlayerControlsWidth + 12.dp
                        } else {
                            12.dp + 72.dp * collapse
                        },
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp - 10.dp * collapse)
                        .clip(RoundedCornerShape(12.dp - 3.dp * collapse))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                            ),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp - 5.dp * collapse),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp - 3.dp * collapse))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        fontSize = (15f - 2f * collapse).sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artist.ifBlank { song.album },
                        fontSize = (12f - 1.5f * collapse).sp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 2) Cristal de controles: se encoge hasta desaparecer. Por debajo del
        // umbral dejamos de dibujarlo: un cristal invisible no debe costar GPU.
        // En modo simple no existe: los controles se apoyan en la barra única.
        if (!simpleBar && expandedGlassAlpha > 0.02f) {
            Box(
                modifier = Modifier
                    .offset(x = maxWidth - collapsingControlsWidth)
                    .width(collapsingControlsWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = expandedGlassAlpha }
                    .lioraGlass(
                        liquidGlass = liquidGlass,
                        backdrop = backdrop,
                        hazeState = hazeState,
                        surfaceColor = controlsTint,
                        shape = MiniPlayerShape,
                        refractionHeight = 12.dp,
                        refractionAmount = 24.dp,
                        blurRadius = 2.dp,
                        shadowRadius = 6.dp,
                    ),
            )
        }

        // 3) Controles expandidos (anterior / reproducir / siguiente).
        if (expandedControlsAlpha > 0.02f) {
            val buttonY = (playerHeight - MiniPlayerButtonSize) / 2f
            val previousX = maxWidth - MiniPlayerControlsWidth + 6.dp

            IconButton(
                onClick = onPrevious,
                enabled = expandedEnabled,
                modifier = Modifier
                    .offset(x = previousX, y = buttonY)
                    .size(MiniPlayerButtonSize)
                    .graphicsLayer { alpha = expandedControlsAlpha },
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Canción anterior",
                    modifier = Modifier.size(24.dp),
                )
            }

            FilledIconButton(
                onClick = onTogglePlay,
                enabled = expandedEnabled,
                modifier = Modifier
                    .offset(x = previousX + MiniPlayerButtonSize, y = buttonY)
                    .size(MiniPlayerButtonSize)
                    .graphicsLayer { alpha = expandedControlsAlpha },
            ) {
                PlayPauseMorphIcon(
                    isPlaying = isPlaying,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }

            IconButton(
                onClick = onNext,
                enabled = expandedEnabled,
                modifier = Modifier
                    .offset(x = previousX + MiniPlayerButtonSize * 2, y = buttonY)
                    .size(MiniPlayerButtonSize)
                    .graphicsLayer { alpha = expandedControlsAlpha },
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Canción siguiente",
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 4) Controles compactos (reproducir / siguiente), pegados a la derecha.
        if (compactControlsAlpha > 0.02f) {
            val buttonY = (playerHeight - CompactButtonSize) / 2f

            FilledIconButton(
                onClick = onTogglePlay,
                enabled = compactEnabled,
                modifier = Modifier
                    .offset(x = maxWidth - 76.dp, y = buttonY)
                    .size(CompactButtonSize)
                    .graphicsLayer { alpha = compactControlsAlpha },
            ) {
                PlayPauseMorphIcon(
                    isPlaying = isPlaying,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = onNext,
                enabled = compactEnabled,
                modifier = Modifier
                    .offset(x = maxWidth - 38.dp, y = buttonY)
                    .size(CompactButtonSize)
                    .graphicsLayer { alpha = compactControlsAlpha },
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Canción siguiente",
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 5) Línea de progreso: una sola, recta y de lado a lado. Va al final,
        // por encima de las piezas, para que nada la parta en dos (sin cortes).
        val progressHeight = 1.5.dp + .5.dp * collapse
        Box(
            modifier = Modifier
                .offset(y = playerHeight - progressHeight - 1.dp)
                .width(maxWidth)
                .height(progressHeight)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.85f)),
            )
        }
    }
}
