package com.liora.android.ui.player

import android.app.Activity
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.Song
import com.liora.android.ui.components.MiniPlayerHeight
import com.liora.android.ui.components.PlayPauseMorphIcon
import com.liora.android.ui.components.rememberSongArtwork
import com.liora.android.ui.theme.isDark
import com.liora.android.ui.viewmodel.PlaybackProgress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Milisegundos que tarda la atmósfera en pasar de una portada a otra. */
private const val AtmosphereTransitionMs = 900

/** Milisegundos de una vuelta completa de la nube atmosférica. */
private const val CloudCycleMs = 90_000

/**
 * El ritmo del viaje: algo más de medio segundo con una curva que arranca
 * decidida y se posa despacio, como la expansión del reproductor de Apple
 * Music. Lo comparten el despliegue del panel y el vuelo de la portada, para
 * que viajen pegados.
 */
internal val PlayerPanelSpec: FiniteAnimationSpec<Float> = tween(
    durationMillis = 540,
    easing = CubicBezierEasing(0.33f, 0f, 0.12f, 1f),
)

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
    /** Altura a la que reposa la barra del mini reproductor, en píxeles. */
    miniTopPx: Float = 0f,
    /** Dónde y cómo de grande está la portada en la barra: origen del vuelo. */
    miniArtworkPos: Offset = Offset.Zero,
    miniArtworkSize: Size = Size.Zero,
    /** Progreso del despliegue: 0 es la barra y 1 la pantalla completa. */
    unfold: Animatable<Float, AnimationVector1D>,
    /** Avisa cuando un dedo agarra o suelta el panel (para que viva la barra). */
    onDraggingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)

    // El cuerpo del reproductor es una hoja que se despliega desde la barra:
    // arranca pegada a ella y crece solo hacia arriba, con las esquinas de
    // arriba redondeadas y el pie siempre en el borde inferior de la pantalla.
    // El contenido no se mueve: el panel lo va descubriendo desde abajo, así
    // que cada pieza aparece ya en su sitio y la cabecera es lo último que se
    // ve, igual que en Apple Music. El progreso lo trae el dedo —la barra al
    // subir, el panel al bajar— y lo asienta quien lo conduce.
    //
    // La portada viaja aparte, por encima de todo: una copia vuela desde su
    // sitio en la barra hasta su sitio en el cuerpo siguiendo el mismo
    // progreso, y la de verdad espera oculta a que aterrice.
    val cover = rememberSongArtwork(song, 512)
    var coverEndPos by remember { mutableStateOf(Offset.Zero) }
    var coverEndSize by remember { mutableStateOf(Size.Zero) }

    // La portada del cuerpo se mide una vez: su sitio ya no se mueve nunca.
    val coverMeasure = Modifier.onGloballyPositioned { coords ->
        if (coverEndSize == Size.Zero && coords.size.width > 0) {
            coverEndPos = coords.positionInRoot()
            coverEndSize = coords.size.toSize()
        }
    }

    // Bajar la hoja hasta la barra y, ya invisible, devolver el mando para que
    // el mini reproductor reaparezca con su propia aparición.
    fun closeWithUnfold() {
        scope.launch {
            unfold.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 470, easing = FastOutSlowInEasing),
            )
            currentOnClose()
        }
    }

    var panel by remember { mutableStateOf(PlayerPanel.Artwork) }
    var immersive by remember { mutableStateOf(false) }

    // Los detalles y los controles ya no llegan por reloj: su entrada va
    // cosida al propio despliegue (ver más abajo), así que acompañan al dedo
    // —al abrir y al cerrar— y el arrastre no se siente plano.
    val immersiveProgress by animateFloatAsState(
        targetValue = if (immersive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (immersive) 560 else 280,
            easing = FastOutSlowInEasing,
        ),
        label = "fondo inmersivo",
    )

    val requestClose = { closeWithUnfold() }

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

    // Lavado del fondo: la portada suavizada y nivelada, como la mancha de
    // color de Apple Music. Si ya está preparado (lo hace la pantalla
    // principal), el reproductor nace con él puesto; al cambiar de canción,
    // los dos lavados se funden solos, despacio.
    var wash by remember(song.id) {
        mutableStateOf(ArtworkRepository.cachedAtmosphereWash(song)?.asImageBitmap())
    }
    LaunchedEffect(song.id) {
        ArtworkRepository.atmosphereWash(context, song)?.let { wash = it.asImageBitmap() }
    }

    val toggleImmersive = { immersive = !immersive }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // La ventana del panel: todo lo que queda por debajo de su
                    // borde vivo, con las esquinas de arriba redondeadas.
                    val top = miniTopPx * (1f - unfold.value)
                    val corner = 26.dp.toPx() * (1f - unfold.value)
                    clipPath(
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 0f,
                                    top = top,
                                    right = size.width,
                                    bottom = size.height,
                                    topLeftCornerRadius = CornerRadius(corner, corner),
                                    topRightCornerRadius = CornerRadius(corner, corner),
                                    bottomRightCornerRadius = CornerRadius.Zero,
                                    bottomLeftCornerRadius = CornerRadius.Zero,
                                ),
                            )
                        },
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
                // El fondo del panel vive dentro del recorte: por encima de la
                // ventana se ve la pantalla de atrás con su velo, no un telón.
                .background(PlayerNight)
                .pointerInput(Unit) {
                    // Bajar el panel lo devuelve a la barra siguiéndole el dedo.
                    var pulling = false
                    val range = if (miniTopPx > 1f) miniTopPx else 2000f

                    detectVerticalDragGestures(
                        onDragStart = { pulling = false },
                        onDragEnd = {
                            if (pulling) {
                                pulling = false
                                if (unfold.value >= 0.5f) {
                                    onDraggingChange(false)
                                    scope.launch { unfold.animateTo(1f, PlayerPanelSpec) }
                                } else {
                                    closeWithUnfold()
                                }
                            }
                        },
                        onDragCancel = {
                            if (pulling) {
                                pulling = false
                                onDraggingChange(false)
                                scope.launch { unfold.animateTo(1f, PlayerPanelSpec) }
                            }
                        },
                    ) { change, dragAmount ->
                        if (!pulling && dragAmount > 0f) {
                            pulling = true
                            onDraggingChange(true)
                        }
                        if (pulling) {
                            change.consume()
                            scope.launch {
                                unfold.snapTo(
                                    (unfold.value - dragAmount / range).coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                },
        ) {
            // El contenido vive quieto en su sitio: el panel lo va descubriendo
            // de abajo arriba, así que no hay que desplazarlo.
            Box(modifier = Modifier.fillMaxSize()) {
        Atmosphere(wash = wash, isPlaying = isPlaying)

        if (immersiveProgress > 0.01f) {
            // Como el resto del contenido: entra cuando la ventana ya subió.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (unfold.value / 0.06f).coerceIn(0f, 1f)
                    },
            ) {
                ImmersiveCover(
                    song = song,
                    progress = immersiveProgress,
                    onToggle = toggleImmersive,
                )
            }
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
                // El contenido (portada grande, títulos, controles) entra
                // cuando la ventana ya se ha despegado de la barra; la
                // superficie teñida que hay detrás, en cambio, está viva desde
                // el primer píxel: por eso la barra se "recolorea" en cuanto
                // arranca el gesto, sin que se vea un panel superpuesto.
                .graphicsLayer {
                    alpha = (unfold.value / 0.06f).coerceIn(0f, 1f)
                }
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
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
                        contentProgress = { unfold.value },
                        immersiveProgress = immersiveProgress,
                        onToggleImmersive = toggleImmersive,
                        artworkModifier = coverMeasure,
                        artworkArrived = { unfold.value >= 0.9985f },
                    )

                    PlayerPanel.Queue -> QueuePanel(
                        currentSong = song,
                        queue = queue,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        contentProgress = { unfold.value },
                        onPlaySong = onPlaySong,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                    )
                }
            }

            Column(
                modifier = Modifier.graphicsLayer {
                    // Los controles completan la composición después, también
                    // siguiendo el arrastre: suben y aparecen a medida que el
                    // panel toma la pantalla.
                    val arrival = ((unfold.value - 0.30f) / 0.42f).coerceIn(0f, 1f)
                    alpha = arrival
                    translationY = (1f - arrival) * 22.dp.toPx()
                },
            ) {
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

        // La portada que viaja: sale del sitio de la barra y aterriza en el del
        // cuerpo siguiendo el mismo progreso del panel. En los extremos queda
        // justo encima de la portada de verdad, así que el relevo no se nota.
        if (cover != null && coverEndSize.width > 0f) {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(coverEndPos.x.roundToInt(), coverEndPos.y.roundToInt())
                    }
                    .size(
                        with(density) { coverEndSize.width.toDp() },
                        with(density) { coverEndSize.height.toDp() },
                    )
                    .graphicsLayer {
                        val progress = unfold.value
                        val startWidth = miniArtworkSize.width
                        val startHeight = miniArtworkSize.height
                        val endWidth = coverEndSize.width
                        val endHeight = coverEndSize.height
                        if (endWidth > 0f && endHeight > 0f) {
                            scaleX = (startWidth + (endWidth - startWidth) * progress) / endWidth
                            scaleY = (startHeight + (endHeight - startHeight) * progress) / endHeight
                        }
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = miniArtworkPos.x +
                            (coverEndPos.x - miniArtworkPos.x) * progress - coverEndPos.x
                        translationY = miniArtworkPos.y +
                            (coverEndPos.y - miniArtworkPos.y) * progress - coverEndPos.y
                        // Aterrizada o aún en la barra coincide con la portada
                        // de verdad: se apaga para no dejar un fantasma encima.
                        alpha = if (progress <= 0.0015f || progress >= 0.9985f) 0f else 1f
                        // El borde redondeado también viaja: nace con el de la
                        // barra y termina con el de la portada grande.
                        shape = RoundedCornerShape((12f + 2f * progress).dp)
                        clip = true
                    },
            ) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ atmósfera

/**
 * Fondo ambiental del reproductor: la nube cromática que sale de la portada.
 * Cuatro copias enormes de la imagen —cada una con su escala, su giro y su
 * deriva— se funden en una sola mancha de color viva, con el movimiento
 * lentísimo de una nube. Se funde sola al cambiar de canción y se queda
 * quieta al pausar o con las animaciones del sistema apagadas.
 */
@Composable
private fun Atmosphere(
    wash: ImageBitmap?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = wash,
        animationSpec = tween(AtmosphereTransitionMs, easing = FastOutSlowInEasing),
        label = "atmósfera",
        modifier = modifier.fillMaxSize(),
    ) { image ->
        if (image == null) {
            AtmosphereFallbackField()
        } else {
            AtmosphereCloud(image = image, isPlaying = isPlaying)
        }
    }
}

/**
 * La nube: cuatro capas de la portada dibujadas muy grandes y estiradas con
 * suavizado —el desenfoque sale gratis al ampliar una imagen diminuta—, que
 * se mueven despacio en elipses, giros y respiraciones distintas. Todo sale
 * de una sola fase armónica: la composición nunca salta ni cambia de forma
 * de golpe, y al pausar se congela en el fotograma en el que estaba.
 */
@Composable
private fun AtmosphereCloud(
    image: ImageBitmap,
    isPlaying: Boolean,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val active = isPlaying && !reduceMotion
    val phase = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            phase.snapTo(phase.value - floor(phase.value))
            phase.animateTo(
                targetValue = phase.value + 1f,
                animationSpec = tween(durationMillis = CloudCycleMs, easing = LinearEasing),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val span = maxOf(width, height)
        val progress = phase.value

        fun layer(
            index: Int,
            alpha: Float,
            scale: Float,
            drift: Float,
            turn: Float,
            centerX: Float,
            centerY: Float,
        ) {
            val angle = ((progress + index * 0.27f) % 1f) * 2f * PI.toFloat()
            val x = width * (centerX + drift * sin(angle))
            val y = height * (centerY + drift * 0.9f * cos(angle * 0.85f + 0.8f))
            val side = span * scale * (1f + 0.05f * sin(angle * 0.6f + 1.2f))
            rotate(
                degrees = turn * sin(angle * 0.5f + index * 1.3f),
                pivot = Offset(x, y),
            ) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(
                        (x - side / 2f).roundToInt(),
                        (y - side / 2f).roundToInt(),
                    ),
                    dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        // La primera capa cubre la pantalla; las demás se mezclan encima con
        // escalas y rumbos distintos para que el color se enrede.
        layer(0, 1.00f, 1.60f, 0.045f, 4f, 0.50f, 0.44f)
        layer(1, 0.52f, 1.95f, 0.060f, 7f, 0.62f, 0.30f)
        layer(2, 0.44f, 1.30f, 0.055f, 5f, 0.38f, 0.62f)
        layer(3, 0.32f, 2.25f, 0.040f, 3f, 0.55f, 0.78f)
    }

    // Un velo apenas más oscuro en los extremos: la hora, el título y los
    // controles se leen sin esfuerzo y el color sigue vivo en el centro.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.24f to Color.Black.copy(alpha = 0.06f),
                    0.70f to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Black.copy(alpha = 0.24f),
                ),
            ),
    )
}

/** Campo neutro para cuando la portada todavía no tiene lavado. */
@Composable
private fun AtmosphereFallbackField(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to lerp(AtmosphereFallback[0], Color.White, 0.08f),
                    0.5f to AtmosphereFallback[1],
                    1f to AtmosphereFallback[2],
                ),
            ),
    )
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
    contentProgress: () -> Float,
    immersiveProgress: Float,
    onToggleImmersive: () -> Unit,
    /** Mide la portada en su sitio de reposo y la esconde mientras viaja su copia. */
    artworkModifier: Modifier = Modifier,
    artworkArrived: () -> Boolean = { true },
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
                .then(artworkModifier)
                .graphicsLayer { alpha = squareAlpha * if (artworkArrived()) 1f else 0f }
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
                .fillMaxWidth(),
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    // El texto sube con el propio gesto —cuanto más alto el
                    // panel, más cerca de su sitio—, así no se siente plano.
                    val arrival = ((contentProgress() - 0.16f) / 0.36f).coerceIn(0f, 1f)
                    alpha = arrival
                    translationY = (1f - arrival) * 26.dp.toPx()
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.subtitle,
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    // El artista llega un suspiro después, para dar profundidad.
                    val arrival = ((contentProgress() - 0.20f) / 0.36f).coerceIn(0f, 1f)
                    alpha = arrival
                    translationY = (1f - arrival) * 32.dp.toPx()
                },
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
    contentProgress: () -> Float,
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
            .graphicsLayer {
                // La cola también aterriza con el gesto, como los detalles.
                val arrival = ((contentProgress() - 0.16f) / 0.36f).coerceIn(0f, 1f)
                alpha = arrival
                translationY = (1f - arrival) * 26.dp.toPx()
            },
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
