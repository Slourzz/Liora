package com.liora.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.liora.android.data.model.Song
import com.liora.android.ui.theme.isDark
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alto del mini reproductor expandido y cuánto se comprime al colapsar. */
internal val MiniPlayerHeight = 62.dp
private val MiniPlayerCollapsedDelta = 16.dp

/** Alto del mini reproductor ya comprimido; lo usa el área inferior para alinear. */
internal val MiniPlayerCollapsedHeight = MiniPlayerHeight - MiniPlayerCollapsedDelta

/** Margen lateral de la barra; el área inferior lo descuenta al comprimir. */
internal val MiniPlayerSidePadding = 12.dp

/** Ancho de los tres controles expandidos (3 botones de 40 dp + margen). */
private val MiniPlayerControlsWidth = 132.dp
private val MiniPlayerButtonSize = 40.dp
private val CompactButtonSize = 36.dp

/** Fracción del ancho que hay que recorrer para confirmar el cambio de pista. */
private const val CommitFraction = 0.28f

/** Velocidad (px/s) que también confirma el cambio al soltar. */
private const val CommitVelocity = 900f

/** Esquinas de la barra flotante de cristal. */
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
 * Barra de "sonando ahora": una sola pieza con portada, título, artista y los
 * controles dentro.
 *
 * Al deslizar sobre la barra, el contenido viaja con el dedo y la canción
 * vecina asoma por el lado hacia el que se desliza (la actual sale hacia un
 * lado y la nueva entra por el otro, como una cinta). Al soltar, si el
 * recorrido alcanza, se confirma el cambio de pista.
 *
 * Con [collapseProgress] > 0 (al bajar por la lista) todo el conjunto se
 * comprime: la barra pierde alto, la portada y el texto se achican y los
 * controles expandidos dejan paso a los compactos.
 */
@Composable
fun MiniPlayer(
    song: Song,
    previousSong: Song?,
    nextSong: Song?,
    isPlaying: Boolean,
    progressFraction: Float,
    collapseProgress: Float,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onPreviousSong: () -> Unit,
    onNext: () -> Unit,
    liquidGlass: Boolean,
    backdrop: Backdrop,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val collapse = collapseProgress.coerceIn(0f, 1f)
    val playerHeight = MiniPlayerHeight - MiniPlayerCollapsedDelta * collapse

    // Modo simple (Haze): la barra es una sola pieza pegada a la navegación.
    val simpleBar = !liquidGlass
    val barShape = if (simpleBar) SimplePlayerShape else MiniPlayerShape

    // El resplandor de la barra sigue al tema activo de la app, no al modo del
    // teléfono: con el tema forzado a claro u oscuro cambia igual.
    val darkMode = MaterialTheme.colorScheme.isDark
    val surfaceTint = if (darkMode) {
        Color(0xFF121212).copy(alpha = 0.24f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.30f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = LinearEasing),
        label = "progreso mini reproductor",
    )

    // --- Deslizar para cambiar de canción ----------------------------------
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    /** Un cambio confirmado por desliz ya dejó la pista nueva centrada. */
    var pendingCommit by remember { mutableStateOf(false) }

    /** +1 entra desde la derecha, -1 desde la izquierda; lo fijan los botones. */
    var hintedDirection by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    val currentSongId = rememberUpdatedState(song.id)
    val density = LocalDensity.current

    // En cristal el asentamiento es con muelle; en material, un desliz seco.
    val settleSpec = if (liquidGlass) {
        spring<Float>(dampingRatio = .74f, stiffness = 400f)
    } else {
        tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
    }

    // La elasticidad es cosa del cristal: al agarrar la barra se hincha un poco.
    val pillScale by animateFloatAsState(
        targetValue = if (dragging && liquidGlass) 1.02f else 1f,
        animationSpec = spring(dampingRatio = .62f, stiffness = 420f),
        label = "escala del reproductor",
    )

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = if (simpleBar) 0.dp else MiniPlayerSidePadding)
            .height(playerHeight),
    ) {
        val windowStart = 12.dp
        val windowWidth = (maxWidth - windowStart - MiniPlayerControlsWidth - 8.dp)
            .coerceAtLeast(1.dp)
        // El ancho se captura acá: dentro del Box hijo, su receiver tapa al de
        // BoxWithConstraints y maxWidth ya no sería accesible.
        val pillWidth = maxWidth
        val windowWidthPx = with(density) { windowWidth.toPx() }
        val windowWidthState = rememberUpdatedState(windowWidthPx)
        val commitThresholdState = rememberUpdatedState(windowWidthPx * CommitFraction)

        val expandedControlsAlpha = (1f - collapse / .58f).coerceIn(0f, 1f)
        val compactControlsAlpha = ((collapse - .34f) / .58f).coerceIn(0f, 1f)
        val expandedEnabled = collapse < .45f
        val compactEnabled = collapse >= .45f

        // Cambio de pista. Si vino de un desliz confirmado, la canción nueva ya
        // está centrada (la vecina ocupó el sitio), así que solo se reancla el
        // deslizamiento; si vino de fuera (botones, fin de pista), entra
        // deslizándose desde el lado que toca.
        LaunchedEffect(song.id) {
            if (!initialized) {
                initialized = true
                return@LaunchedEffect
            }
            if (pendingCommit) {
                pendingCommit = false
                slide.snapTo(0f)
                return@LaunchedEffect
            }
            val direction = if (hintedDirection != 0) hintedDirection else 1
            hintedDirection = 0
            slide.snapTo(direction * windowWidthPx * 0.35f)
            slide.animateTo(0f, settleSpec)
        }

        val pillModifier = Modifier
            .fillMaxSize()
            .then(if (simpleBar) Modifier.clip(barShape) else Modifier)
            .lioraGlass(
                liquidGlass = liquidGlass,
                backdrop = backdrop,
                hazeState = hazeState,
                surfaceColor = surfaceTint,
                shape = barShape,
                refractionHeight = if (simpleBar) 0.dp else 16.dp,
                refractionAmount = 32.dp,
                blurRadius = 4.dp,
                shadowRadius = 8.dp,
                layerBlock = if (liquidGlass) {
                    {
                        val scale = pillScale
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    null
                },
            )

        Box(
            modifier = pillModifier.pointerInput(nextSong?.id, previousSong?.id, liquidGlass) {
                val velocityTracker = VelocityTracker()

                fun settleBack() {
                    scope.launch { slide.animateTo(0f, settleSpec) }
                }

                detectDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                        dragging = true
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val width = windowWidthState.value
                        val raw = slide.value + dragAmount.x
                        // Sin vecino de ese lado casi no se estira: frena seco.
                        val resisted = when {
                            raw < 0f && nextSong == null -> raw * 0.18f
                            raw > 0f && previousSong == null -> raw * 0.18f
                            else -> raw
                        }
                        scope.launch {
                            slide.snapTo(resisted.coerceIn(-width * 1.05f, width * 1.05f))
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        val velocity = velocityTracker.calculateVelocity().x
                        val offset = slide.value
                        val threshold = commitThresholdState.value
                        val goNext = (offset <= -threshold || velocity <= -CommitVelocity) &&
                            nextSong != null
                        val goPrevious = (offset >= threshold || velocity >= CommitVelocity) &&
                            previousSong != null

                        if (goNext || goPrevious) {
                            scope.launch {
                                val direction = if (goNext) 1 else -1
                                val width = windowWidthState.value
                                val fromId = currentSongId.value

                                pendingCommit = true
                                // La cinta completa: la actual acaba de salir y
                                // la vecina queda centrada, sin saltos.
                                slide.animateTo(
                                    targetValue = direction * width,
                                    animationSpec = tween(
                                        durationMillis = 240,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                                if (direction > 0) onNext() else onPreviousSong()

                                val changed = withTimeoutOrNull(600L) {
                                    snapshotFlow { currentSongId.value }.first { it != fromId }
                                } != null
                                if (!changed) {
                                    // No cambió de pista (p. ej. "anterior"
                                    // reinició la canción): vuelve a su sitio.
                                    pendingCommit = false
                                    slide.animateTo(0f, settleSpec)
                                }
                            }
                        } else {
                            settleBack()
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        settleBack()
                    },
                )
            },
        ) {
            // 1) Ventana deslizante: la pista anterior, la actual y la
            // siguiente viajan juntas; solo se ve la que toca.
            Box(
                modifier = Modifier
                    .offset(x = windowStart)
                    .width(windowWidth)
                    .fillMaxHeight()
                    .clipToBounds(),
            ) {
                previousSong?.let { previous ->
                    MiniPlayerInfoRow(
                        song = previous,
                        collapse = collapse,
                        translationX = { slide.value - windowWidthState.value },
                    )
                }

                MiniPlayerInfoRow(
                    song = song,
                    collapse = collapse,
                    translationX = { slide.value },
                )

                nextSong?.let { next ->
                    MiniPlayerInfoRow(
                        song = next,
                        collapse = collapse,
                        translationX = { slide.value + windowWidthState.value },
                    )
                }
            }

            // 2) Controles expandidos (anterior / reproducir / siguiente).
            if (expandedControlsAlpha > 0.02f) {
                val buttonY = (playerHeight - MiniPlayerButtonSize) / 2f
                val previousX = pillWidth - MiniPlayerControlsWidth + 6.dp

                IconButton(
                    onClick = {
                        hintedDirection = -1
                        onPrevious()
                    },
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

                if (liquidGlass) {
                    // En cristal no hay círculo: solo el icono.
                    IconButton(
                        onClick = onTogglePlay,
                        enabled = expandedEnabled,
                        modifier = Modifier
                            .offset(x = previousX + MiniPlayerButtonSize, y = buttonY)
                            .size(MiniPlayerButtonSize)
                            .graphicsLayer { alpha = expandedControlsAlpha },
                    ) {
                        PlayPauseMorphIcon(
                            isPlaying = isPlaying,
                            color = contentColor,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                } else {
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
                }

                IconButton(
                    onClick = {
                        hintedDirection = 1
                        onNext()
                    },
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

            // 3) Controles compactos (reproducir / siguiente), pegados a la derecha.
            if (compactControlsAlpha > 0.02f) {
                val buttonY = (playerHeight - CompactButtonSize) / 2f

                if (liquidGlass) {
                    IconButton(
                        onClick = onTogglePlay,
                        enabled = compactEnabled,
                        modifier = Modifier
                            .offset(x = pillWidth - 76.dp, y = buttonY)
                            .size(CompactButtonSize)
                            .graphicsLayer { alpha = compactControlsAlpha },
                    ) {
                        PlayPauseMorphIcon(
                            isPlaying = isPlaying,
                            color = contentColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onTogglePlay,
                        enabled = compactEnabled,
                        modifier = Modifier
                            .offset(x = pillWidth - 76.dp, y = buttonY)
                            .size(CompactButtonSize)
                            .graphicsLayer { alpha = compactControlsAlpha },
                    ) {
                        PlayPauseMorphIcon(
                            isPlaying = isPlaying,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                IconButton(
                    onClick = {
                        hintedDirection = 1
                        onNext()
                    },
                    enabled = compactEnabled,
                    modifier = Modifier
                        .offset(x = pillWidth - 38.dp, y = buttonY)
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

            // 4) Línea de progreso: dentro de la barra, pegada al borde de
            // abajo. En modo simple va de lado a lado; en cristal es una
            // cápsula con aire a los costados, a juego con las esquinas.
            val progressHeight = if (simpleBar) 1.5.dp + .5.dp * collapse else 3.dp
            if (simpleBar) {
                Box(
                    modifier = Modifier
                        .offset(y = playerHeight - progressHeight - 1.dp)
                        .width(pillWidth)
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
            } else {
                // Nivel de líquido: de borde a borde y pegado al fondo de la
                // píldora. La forma del cristal recorta las esquinas, así que
                // el progreso sigue su curvatura sin salirse nunca.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(progressHeight)
                        .background(contentColor.copy(alpha = 0.10f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction)
                            .fillMaxHeight()
                            .background(contentColor.copy(alpha = 0.75f)),
                    )
                }
            }
        }
    }
}

/**
 * Portada, título y artista de una pista. Se comparte entre la canción actual
 * y sus vecinas: cada una se desplaza con la cinta del deslizamiento.
 */
@Composable
private fun MiniPlayerInfoRow(
    song: Song,
    collapse: Float,
    translationX: () -> Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.translationX = translationX() },
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
            val artwork = rememberSongArtwork(song, sizePx = 132)
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp - 5.dp * collapse),
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp - 3.dp * collapse))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = (15f - 2f * collapse).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist.ifBlank { song.album },
                fontSize = (12f - 1.5f * collapse).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
