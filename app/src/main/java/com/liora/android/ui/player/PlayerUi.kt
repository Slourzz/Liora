package com.liora.android.ui.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song
import com.liora.android.ui.components.MiniPlayerHeight
import com.liora.android.ui.components.PlayPauseMorphIcon
import com.liora.android.ui.components.rememberSongArtwork
import com.liora.android.ui.theme.isDark
import com.liora.android.ui.viewmodel.PlaybackProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Milisegundos que tarda la atmósfera en pasar de una portada a otra. */
private const val AtmosphereTransitionMs = 900

/** Piezas que viajan entre el mini reproductor y el reproductor completo. */
internal enum class LioraPlayerElement {
    /** El cuerpo entero: la barra se despliega hasta llenar la pantalla. */
    Container,

    /** La portada, que es la misma imagen cambiando de sitio y de tamaño. */
    Artwork,
}

/**
 * El ritmo del viaje: algo más de medio segundo con una curva que arranca
 * decidida y se posa despacio, como la expansión del reproductor de Apple
 * Music. La usan el cuerpo y la portada a la vez, para que viajen pegados.
 */
internal val PlayerMorphSpec: FiniteAnimationSpec<Rect> = tween(
    durationMillis = 540,
    easing = CubicBezierEasing(0.33f, 0f, 0.12f, 1f),
)

/**
 * Esquinas del cuerpo durante el viaje: arrancan redondeadas como la píldora y
 * se van enderezando a medida que la barra crece hasta la pantalla completa.
 */
private class PlayerMorphClip(
    private val compactHeightPx: Float,
    private val compactCornerPx: Float,
    private val screenHeightPx: Float,
) : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val grown = ((bounds.height - compactHeightPx) /
            (screenHeightPx - compactHeightPx)).coerceIn(0f, 1f)
        val corner = compactCornerPx * (1f - grown)
        val outline = RoundedCornerShape(corner)
            .createOutline(bounds.size, layoutDirection, density)
        return Path().apply { addOutline(outline) }
    }
}

@Composable
internal fun rememberPlayerMorphClip(): SharedTransitionScope.OverlayClip {
    val density = LocalDensity.current
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val compactHeightPx = with(density) { MiniPlayerHeight.toPx() }
    val compactCornerPx = with(density) { 26.dp.toPx() }

    return remember(compactHeightPx, compactCornerPx, screenHeightPx) {
        PlayerMorphClip(
            compactHeightPx = compactHeightPx,
            compactCornerPx = compactCornerPx,
            screenHeightPx = screenHeightPx,
        )
    }
}

/** Fondo de noche del reproductor; la atmósfera se pinta encima. */
private val PlayerNight = Color(0xFF07070A)

/** Atmósfera neutra mientras llega la paleta de la portada. */
private val AtmosphereFallback = listOf(
    Color(0xFF3A3A44),
    Color(0xFF26262C),
    Color(0xFF17171C),
)

/** Paneles del reproductor, como los de Cloud: portada o cola. */
private enum class PlayerPanel { Artwork, Queue }

/**
 * Reproductor completo, desplegado desde el mini reproductor.
 *
 * Es el mismo lenguaje del reproductor de Cloud —portada grande, controles
 * semi transparentes, panel de cola y modo inmersivo— adaptado a las piezas
 * que Liora ya tiene: su morfología de reproducir/pausar, su portada de 512 px
 * y el análisis de color del álbum. Encima vive la atmósfera: un fondo de
 * degradados radiales sacados de la portada que se queda quieto y solo se
 * funde, despacio, cuando cambia la canción.
 *
 * La entrada y la salida son un viaje compartido con la barra del mini
 * reproductor: el cuerpo se despliega desde ella y la portada viaja como si
 * fuera la misma imagen, con los detalles apareciendo por su cuenta. Al
 * cerrar, todo recorre el camino a la inversa.
 */
@Composable
fun LioraFullPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: PlaybackProgress,
    queue: List<Song>,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlaySong: (Song) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)

    var panel by remember { mutableStateOf(PlayerPanel.Artwork) }
    var immersive by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }

    // La aparición interna espera un suspiro al primer fotograma, como Cloud.
    LaunchedEffect(Unit) {
        delay(24L)
        entered = true
    }

    // Los detalles y los controles llegan escalonados, después de que el
    // cuerpo haya tomado la pantalla: primero el título y el artista; un
    // suspiro después, la barra de progreso y el transporte.
    val detailsAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            delayMillis = 150,
            easing = FastOutSlowInEasing,
        ),
        label = "detalles del reproductor",
    )
    val controlsAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 340,
            delayMillis = 230,
            easing = FastOutSlowInEasing,
        ),
        label = "controles del reproductor",
    )
    val immersiveProgress by animateFloatAsState(
        targetValue = if (immersive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (immersive) 560 else 280,
            easing = FastOutSlowInEasing,
        ),
        label = "fondo inmersivo",
    )

    val requestClose = { currentOnClose() }

    // Mientras el reproductor está delante, los iconos del sistema van claros.
    val view = LocalView.current
    val appIsDark = MaterialTheme.colorScheme.isDark
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !appIsDark
            controller?.isAppearanceLightNavigationBars = !appIsDark
        }
    }

    BackHandler { requestClose() }

    // Paleta de la portada; si la canción cambia, los colores se funden solos.
    // Si ya está calculada (la prepara la pantalla principal), el reproductor
    // nace con sus colores desde el primer fotograma.
    var palette by remember(song.id) {
        mutableStateOf(
            ArtworkRepository.cachedAtmosphereColors(song) ?: AtmosphereFallback,
        )
    }
    LaunchedEffect(song.id) {
        palette = ArtworkRepository.atmosphereColors(context, song)
    }

    val toggleImmersive = { immersive = !immersive }

    // Arrastrar hacia abajo devuelve el reproductor a su barra, como en Apple
    // Music; el tirón se sigue con el dedo y, al suelto corto, vuelve a su sitio.
    val pull = remember { Animatable(0f) }
    val morphClip = rememberPlayerMorphClip()

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            LioraPlayerElement.Container,
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = 170,
                                easing = FastOutSlowInEasing,
                            ),
                        ),
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = 240,
                                easing = FastOutSlowInEasing,
                            ),
                        ),
                        boundsTransform = { _, _ -> PlayerMorphSpec },
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                        clipInOverlayDuringTransition = morphClip,
                    )
                },
            )
            .graphicsLayer { translationY = pull.value }
            .background(PlayerNight)
            .pointerInput(Unit) {
                var pulled = 0f
                val closeThreshold = 96.dp.toPx()
                val maxPull = 220.dp.toPx()

                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = {
                        val close = pulled >= closeThreshold
                        scope.launch {
                            if (close) {
                                launch { currentOnClose() }
                                pull.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            } else {
                                pull.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 480f,
                                    ),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        pulled = 0f
                        scope.launch {
                            pull.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                            )
                        }
                    },
                ) { change, dragAmount ->
                    if (dragAmount > 0f || pulled > 0f) {
                        pulled = (pulled + dragAmount).coerceIn(0f, maxPull)
                        change.consume()
                        scope.launch { pull.snapTo(pulled * 0.72f) }
                    }
                }
            },
    ) {
        Atmosphere(colors = palette)

        if (immersiveProgress > 0.01f) {
            ImmersiveCover(
                song = song,
                progress = immersiveProgress,
                onToggle = toggleImmersive,
            )
        }

        // Recoge los toques que no caen en un control para que no atraviesen
        // hasta la pantalla que queda detrás.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .graphicsLayer {
                        alpha = detailsAlpha
                        translationY = (1f - detailsAlpha) * -10.dp.toPx()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleToggle(
                    active = false,
                    icon = Icons.Rounded.ExpandMore,
                    description = "Cerrar",
                    onClick = { requestClose() },
                    size = 38.dp,
                    iconSize = 22.dp,
                )
            }

            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    (
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 300,
                                delayMillis = 36,
                                easing = FastOutSlowInEasing,
                            ),
                        ) togetherWith fadeOut(
                            animationSpec = tween(
                                durationMillis = 240,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                        ) using SizeTransform(clip = false)
                },
                label = "paneles del reproductor",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { selectedPanel ->
                when (selectedPanel) {
                    PlayerPanel.Artwork -> ArtworkPanel(
                        song = song,
                        contentAlpha = detailsAlpha,
                        immersiveProgress = immersiveProgress,
                        onToggleImmersive = toggleImmersive,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )

                    PlayerPanel.Queue -> QueuePanel(
                        currentSong = song,
                        queue = queue,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        contentAlpha = detailsAlpha,
                        onPlaySong = onPlaySong,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                    )
                }
            }

            Column(modifier = Modifier.graphicsLayer { alpha = controlsAlpha }) {
                Spacer(Modifier.height(22.dp))
                PlayerProgressBar(
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    onSeek = onSeek,
                )
                Spacer(Modifier.height(16.dp))
                TransportControls(
                    isPlaying = isPlaying,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
                Spacer(Modifier.height(36.dp))
                BottomControls(
                    immersive = immersive,
                    queueOpen = panel == PlayerPanel.Queue,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onToggleImmersive = toggleImmersive,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onToggleQueue = {
                        panel = if (panel == PlayerPanel.Queue) {
                            PlayerPanel.Artwork
                        } else {
                            PlayerPanel.Queue
                        }
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ atmósfera

/**
 * Fondo ambiental del reproductor: un campo de color que viene de la portada
 * y cubre la pantalla entera —arriba más luminoso, abajo más profundo, pero
 * siempre teñido—, con los destellos repartidos por todo el lienzo. No hay
 * animación continua: solo se funde, en 900 ms, cuando cambia de canción. Al
 * pausar no queda nada en movimiento.
 */
@Composable
private fun Atmosphere(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val bloomA by animateColorAsState(
        targetValue = colors.getOrElse(0) { AtmosphereFallback[0] },
        animationSpec = tween(AtmosphereTransitionMs, easing = FastOutSlowInEasing),
        label = "atmósfera principal",
    )
    val bloomB by animateColorAsState(
        targetValue = colors.getOrElse(1) { AtmosphereFallback[1] },
        animationSpec = tween(AtmosphereTransitionMs, easing = FastOutSlowInEasing),
        label = "atmósfera secundaria",
    )
    val base by animateColorAsState(
        targetValue = colors.getOrElse(2) { AtmosphereFallback[2] },
        animationSpec = tween(AtmosphereTransitionMs, easing = FastOutSlowInEasing),
        label = "atmósfera base",
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .blur(radius = 20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
    ) {
        val width = size.width
        val height = size.height

        // Base de arriba a abajo: el tono de la portada se aclara al sol y se
        // hunde al fondo, pero nunca llega al negro —el color sigue ahí—.
        drawRect(
            brush = Brush.verticalGradient(
                0f to lerp(bloomA, Color.White, 0.10f),
                0.30f to bloomA,
                0.60f to lerp(bloomA, bloomB, 0.45f),
                0.82f to lerp(bloomB, base, 0.30f),
                1f to base,
            ),
        )

        fun bloom(center: Offset, radius: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.45f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Los destellos reparten el color por toda la pantalla: dos arriba, uno
        // en medio y dos que sostienen la parte de abajo.
        bloom(Offset(width * 0.16f, height * 0.06f), width * 1.05f, bloomA, 0.50f)
        bloom(Offset(width * 0.94f, height * 0.30f), width * 0.95f, bloomB, 0.42f)
        bloom(
            center = Offset(width * 0.42f, height * 0.58f),
            radius = width * 1.20f,
            color = lerp(bloomA, bloomB, 0.5f),
            alpha = 0.34f,
        )
        bloom(
            center = Offset(width * 0.10f, height * 0.96f),
            radius = width * 1.00f,
            color = lerp(base, bloomB, 0.35f),
            alpha = 0.42f,
        )
        bloom(
            center = Offset(width * 0.90f, height * 0.88f),
            radius = width * 0.90f,
            color = lerp(base, bloomA, 0.25f),
            alpha = 0.36f,
        )

        // Un aire para que el texto blanco respire y un velo mínimo contra las
        // bandas del degradado, como el dither de Cloud.
        drawRect(Color.Black.copy(alpha = 0.10f))
        drawRect(Color.White.copy(alpha = 0.015f))
    }
}

/**
 * Fondo inmersivo: la portada ocupa la parte alta de la pantalla y se funde
 * hacia abajo con la atmósfera, con la misma máscara y velos del reproductor
 * de Cloud. Se mantiene solo la portada en una capa aparte para que el velo
 * no toque al resto del contenido.
 */
@Composable
private fun ImmersiveCover(
    song: Song,
    progress: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artwork = rememberSongArtwork(song, 512)
    val haptics = LocalHapticFeedback.current
    val currentOnToggle by rememberUpdatedState(onToggle)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val coverHeight = maxHeight * (0.60f + 0.06f * progress)

        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnToggle()
                            },
                        )
                    }
                    .graphicsLayer {
                        alpha = 0.98f * progress
                        val scale = 1.025f - 0.025f * progress
                        scaleX = scale
                        scaleY = scale
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                0.54f to Color.Black,
                                0.68f to Color.Black.copy(alpha = 0.84f),
                                0.80f to Color.Black.copy(alpha = 0.42f),
                                0.94f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }

        // Velos suaves, casi imperceptibles: asientan el contraste sin apagar
        // la portada.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .graphicsLayer { alpha = progress }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.24f to Color.Transparent,
                        0.80f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.02f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.04f),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.05f),
                    ),
                ),
        )
    }
}

// -------------------------------------------------------------------- portada

/**
 * Panel principal: la portada grande —con el gesto de mantener pulsado para
 * entrar al fondo inmersivo, como en Cloud— y, abajo, título y subtítulo.
 * En inmersivo la portada cuadrada se apaga y solo queda el fondo.
 *
 * La portada lleva el elemento compartido: es la misma imagen que viaja desde
 * el mini reproductor y se posa aquí, sin fundidos ni saltos.
 */
@Composable
private fun ArtworkPanel(
    song: Song,
    contentAlpha: Float,
    immersiveProgress: Float,
    onToggleImmersive: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val artwork = rememberSongArtwork(song, 512)
    val haptics = LocalHapticFeedback.current
    val currentOnToggle by rememberUpdatedState(onToggleImmersive)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val squareAlpha = ((0.24f - immersiveProgress) / 0.24f).coerceIn(0f, 1f)
        val coverSize = (maxWidth - 16.dp).coerceAtLeast(52.dp)
        // La portada vive centrada en el aire que dejan los detalles de abajo.
        val detailsReserve = 74.dp
        val coverTop = ((maxHeight - detailsReserve - coverSize) / 2f).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(x = 8.dp, y = coverTop)
                .size(coverSize)
                .then(
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(
                                LioraPlayerElement.Artwork,
                            ),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> PlayerMorphSpec },
                            clipInOverlayDuringTransition = OverlayClip(
                                RoundedCornerShape(14.dp),
                            ),
                        )
                    },
                )
                .graphicsLayer { alpha = squareAlpha }
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnToggle()
                        },
                    )
                },
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = (1f - contentAlpha) * 18.dp.toPx()
                },
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.subtitle,
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------- cola

/**
 * Panel de cola: la canción que suena arriba, las píldoras de aleatorio y
 * repetir, y lo que viene después. Tocar una pista la reproduce.
 */
@Composable
private fun QueuePanel(
    currentSong: Song,
    queue: List<Song>,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    contentAlpha: Float,
    onPlaySong: (Song) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = queue.indexOfFirst { it.id == currentSong.id }
    val nextSongs = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = contentAlpha },
    ) {
        QueueHeader(song = currentSong)

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ModePill(
                icon = Icons.Rounded.Shuffle,
                description = "Aleatorio",
                active = shuffleEnabled,
                onClick = onToggleShuffle,
            )
            ModePill(
                icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                },
                description = "Repetir",
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "A continuación",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
        )

        Spacer(Modifier.height(7.dp))

        if (nextSongs.isEmpty()) {
            Text(
                text = "No queda nada más en la cola",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(nextSongs, key = { it.id }) { song ->
                    QueueSongRow(song = song, onClick = { onPlaySong(song) })
                }
            }
        }
    }
}

/** Encabezado de la cola: la que suena ahora. */
@Composable
private fun QueueHeader(song: Song) {
    val artwork = rememberSongArtwork(song, 256)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist.ifBlank { song.album },
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Fila de una pista que viene en la cola. */
@Composable
private fun QueueSongRow(song: Song, onClick: () -> Unit) {
    val artwork = rememberSongArtwork(song, 128)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(41.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist.ifBlank { song.album },
                color = Color.White.copy(alpha = 0.50f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Píldora de una acción (aleatorio, repetir), al estilo de Cloud. */
@Composable
private fun ModePill(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(96.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = if (active) 0.28f else 0.16f))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { alpha = if (active) 1f else 0.72f },
        )
    }
}

// ------------------------------------------------------------------ controles

/** Línea de progreso con tiempos: se toca o se arrastra para buscar. */
@Composable
private fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progressFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    var draggingFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)

    val tickedFraction by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "avance de la pista",
    )
    val displayFraction = draggingFraction ?: tickedFraction

    fun fractionToMs(fraction: Float): Long = (safeDuration * fraction).toLong()

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f))
                    .pointerInput(safeDuration) {
                        detectTapGestures { position ->
                            val fraction = (position.x / size.width).coerceIn(0f, 1f)
                            currentOnSeek(fractionToMs(fraction))
                        }
                    }
                    .pointerInput(safeDuration) {
                        detectHorizontalDragGestures(
                            onDragStart = { position ->
                                draggingFraction =
                                    (position.x / size.width).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                draggingFraction = (
                                    (draggingFraction ?: 0f) + dragAmount / size.width
                                    ).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                draggingFraction?.let { currentOnSeek(fractionToMs(it)) }
                                draggingFraction = null
                            },
                            onDragCancel = { draggingFraction = null },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.76f)),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
        ) {
            val shownMs = if (draggingFraction != null) {
                fractionToMs(displayFraction)
            } else {
                positionMs.coerceIn(0L, safeDuration)
            }
            Text(
                text = formatTime(shownMs),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = "-" + formatTime(safeDuration - shownMs),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Fila de transporte: anterior, reproducir/pausar y siguiente. */
@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionalSkipButton(
            direction = -1,
            onClick = onPrevious,
            icon = Icons.Rounded.SkipPrevious,
            description = "Anterior",
        )

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(62.dp),
        ) {
            PlayPauseMorphIcon(
                isPlaying = isPlaying,
                modifier = Modifier.size(58.dp),
                color = Color.White,
            )
        }

        DirectionalSkipButton(
            direction = 1,
            onClick = onNext,
            icon = Icons.Rounded.SkipNext,
            description = "Siguiente",
        )
    }
}

/**
 * Botón de salto con el empujoncito de Cloud: el icono se asoma hacia donde
 * va la música y regresa. La canción cambia al instante; esto es solo caricia.
 */
@Composable
private fun DirectionalSkipButton(
    direction: Int,
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val offsetX = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    IconButton(
        onClick = {
            currentOnClick()
            scope.launch {
                launch {
                    offsetX.animateTo(
                        targetValue = direction * 5.5f,
                        animationSpec = tween(230, easing = FastOutSlowInEasing),
                    )
                    offsetX.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    scale.animateTo(
                        targetValue = 0.94f,
                        animationSpec = tween(120, easing = FastOutSlowInEasing),
                    )
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
                    )
                }
            }
        },
        modifier = modifier.size(52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier
                .size(38.dp)
                .graphicsLayer {
                    translationX = offsetX.value.dp.toPx()
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

/** Fila inferior: inmersivo, aleatorio/repetir y cola. */
@Composable
private fun BottomControls(
    immersive: Boolean,
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onToggleImmersive: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleToggle(
            active = immersive,
            icon = if (immersive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            description = if (immersive) "Salir del fondo inmersivo" else "Fondo inmersivo",
            onClick = onToggleImmersive,
        )

        Row(
            modifier = Modifier
                .width(104.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillHalf(active = shuffleEnabled, onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = Color.White,
                    modifier = Modifier
                        .size(17.dp)
                        .graphicsLayer { alpha = if (shuffleEnabled) 1f else 0.55f },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.20f)),
            )
            PillHalf(
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
            ) {
                val active = repeatMode != Player.REPEAT_MODE_OFF
                Icon(
                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = "Repetir",
                    tint = Color.White,
                    modifier = Modifier
                        .size(17.dp)
                        .graphicsLayer { alpha = if (active) 1f else 0.55f },
                )
            }
        }

        CircleToggle(
            active = queueOpen,
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            description = "Cola",
            onClick = onToggleQueue,
        )
    }
}

/** Mitad de una píldora inferior. */
@Composable
private fun RowScope.PillHalf(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Círculo semi transparente de los controles. Encendido se invierte a blanco
 * pleno con el icono en negro: la marca neutra de Liora para "activo".
 */
@Composable
private fun CircleToggle(
    active: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (active) {
                    Color.White.copy(alpha = 0.92f)
                } else {
                    Color.White.copy(alpha = 0.16f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) Color.Black else Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Milisegundos a "m:ss" —o "h:mm:ss", si la pista es larga—. */
private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
