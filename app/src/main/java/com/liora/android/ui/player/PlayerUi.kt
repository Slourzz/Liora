package com.liora.android.ui.player

import android.app.Activity
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.model.LyricLine
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.Song
import com.liora.android.data.prefs.PlayerBackground
import com.liora.android.ui.components.LyricsCreditsSheet
import com.liora.android.ui.components.MiniPlayerHeight
import com.liora.android.ui.components.PlayPauseMorphIcon
import com.liora.android.ui.components.TranslationErrorDialog
import com.liora.android.ui.components.prismalButtonPress
import com.liora.android.ui.components.prismalButtonScale
import com.liora.android.ui.components.rememberPrismalButtonPhysics
import com.liora.android.ui.components.rememberSongArtwork
import com.liora.android.ui.components.shimmerWhileWorking
import com.liora.android.ui.lyrics.lyrics.lyra.LyraLyricsDisplay
import com.liora.android.ui.lyrics.lyrics.lyra.LyraSingleLinePreview
import com.liora.android.ui.lyrics.lyrics.lyra.renderer.romanizationText
import com.liora.android.ui.viewmodel.LyricsTranslationState
import com.liora.android.data.prefs.LyricAnimationLevel
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.components.PrismalGlassMenu
import com.styropyr0.prismal.components.PrismalGlassMenuItem
import com.styropyr0.prismal.components.prismalMenuAnchor
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.styropyr0.prismal.specular.PrismalSpecular
import com.liora.android.ui.theme.isDark
import com.liora.android.ui.viewmodel.PlaybackProgress
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Milisegundos que tarda la superficie en pasar de una portada a otra. */
private const val AtmosphereTransitionMs = 650

/** Cuánto color de la portada pasa al fondo sólido, y entre qué topes. */
private const val SurfaceSaturation = 0.70f
private const val SurfaceSaturationMin = 0.07f
private const val SurfaceSaturationMax = 0.40f

/** Luz del fondo sólido: se asienta a media luz, venga de donde venga. */
private const val SurfaceLightnessGain = 0.62f
private const val SurfaceLightnessBase = 0.22f
private const val SurfaceLightnessMin = 0.40f
private const val SurfaceLightnessMax = 0.62f

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

/** Superficie neutra mientras llega el campo de la portada. */
private val AtmosphereFallback = Color(0xFF26252B)

/**
 * Cuánto sube el grupo de detalles —título, artista, barra y controles— para
 * apretarlo contra la portada. La portada se queda donde está: el aire que
 * sobra se va al borde de abajo, que no estorba.
 */
private val PlayerDetailsLift = 30.dp

/**
 * Cuánto se encoge la portada al pausar. Un suspiro, no un salto: al volver
 * el play recupera su tamaño con la misma calma.
 */
private const val PausedCoverScale = 0.90f

/** Paneles del reproductor, como los de Cloud: portada, letras o cola. */
private enum class PlayerPanel { Artwork, Lyrics, Queue }

/**
 * Margen de vuelo de la portada: por debajo o por encima de este valor el
 * gesto ya está asentado y no hay nada viajando.
 */
private const val CoverFlightEpsilon = 0.0015f

/** El radio de las esquinas de la portada en cada uno de sus sitios. */
private const val MiniCoverRadius = 12f
private const val HeroCoverRadius = 14f
private const val HeaderCoverRadius = 12f

/** El botón de idioma de la letra y el aire que deja sobre la barra. */
private val LyricsLanguageButtonSize = 36.dp
private val LyricsLanguageButtonGap = 10.dp

/** Cuánto esperan los controles en las letras antes de retirarse solos. */
private const val LyricsControlsTimeoutMs = 4_000L

/**
 * La plaza que ocupa una portada: dónde está y cómo de grande. Cada destino
 * de la copia —la barra, el bloque superior de letras y cola y la portada
 * grande del reproductor central— es una de estas.
 */
private data class CoverRect(
    val pos: Offset,
    val size: Size,
    /** Radio de las esquinas, en dp, para que el viaje también lo cambie. */
    val radius: Float,
)

/** El vuelo de la portada: la plaza que ocupa la copia y su radio. */
private data class CoverFlight(
    val rect: CoverRect,
    val radius: Float,
)

/** Interpola dos plazas: el punto medio de un viaje. */
private fun lerpCoverRect(a: CoverRect, b: CoverRect, t: Float) = CoverRect(
    pos = Offset(
        x = a.pos.x + (b.pos.x - a.pos.x) * t,
        y = a.pos.y + (b.pos.y - a.pos.y) * t,
    ),
    size = Size(
        width = a.size.width + (b.size.width - a.size.width) * t,
        height = a.size.height + (b.size.height - a.size.height) * t,
    ),
    radius = lerpRadius(a.radius, b.radius, t),
)

/** Interpola dos radios: el redondeo también viaja. */
private fun lerpRadius(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Cristal de los controles del reproductor.
 *
 * Usa exactamente los mismos efectos que la navegación y los botones de
 * Liora (`applyPrismalGlassEffects`, especular por defecto), para que el
 * borde translúcido y la refracción se sientan como el resto del tema Glass.
 * La forma y el tamaño llegan intactos desde cada control.
 */
private fun Modifier.playerGlass(
    backdrop: PrismalBackdrop,
    shape: Shape,
    surfaceColor: Color,
    /** Cuánto brilla el canto del cristal: menos, cuando el control está apagado. */
    rimAlpha: Float = 1f,
): Modifier = drawPrismalGlass(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        applyPrismalGlassEffects(
            density = this,
            adaptiveLuminance = false,
            luminance = 0.5f,
            blurRadiusPx = 4.dp.toPx(),
            refractionHeightPx = 16.dp.toPx(),
            refractionAmountPx = 32.dp.toPx(),
            useVibrancy = true,
        )
    },
    specular = { PrismalSpecular.Default.copy(alpha = rimAlpha) },
    depthShadow = null,
    onDrawSurface = { drawRect(surfaceColor) },
)

/**
 * Reproductor completo, desplegado desde el mini reproductor.
 *
 * Es el mismo lenguaje del reproductor de Cloud —portada grande, controles
 * semi transparentes, panel de cola y modo inmersivo— adaptado a las piezas
 * que Liora ya tiene: su morfología de reproducir/pausar, su portada de 512 px
 * y el análisis de color del álbum. Encima vive la atmósfera: la portada hecha
 * manchas de color que se mecen despacio —el warp de kawarp— mientras la
 * música suena, y que se quedan quietas al pausar.
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
    /**
     * La intención de sonar. El botón de reproducir/pausar la mira: mientras
     * la canción carga todavía no suena, pero va a sonar, y el icono tiene que
     * prometer la pausa y no el play.
     */
    playWhenReady: Boolean = isPlaying,
    progress: PlaybackProgress,
    queue: List<Song>,
    /** Letras sincronizadas, si la fuente de la canción las trae. */
    lyrics: List<LyricLine> = emptyList(),
    /** Verdadero mientras se buscan las letras de la canción actual. */
    lyricsLoading: Boolean = false,
    /** Proveedor y créditos de las letras, para la atribución del panel. */
    lyricsAttribution: LyricsAttribution? = null,
    /** En qué punto está la traducción de la letra. */
    lyricsTranslation: LyricsTranslationState = LyricsTranslationState.Idle,
    /** El error de traducción pendiente de enseñar, si lo hay. */
    translationError: String? = null,
    /** Cierra el diálogo de error. */
    onDismissTranslationError: () -> Unit = {},
    /** Si la letra que se ve es la traducida. */
    lyricsTranslationVisible: Boolean = false,
    /** Pide la letra traducida u original. */
    onToggleLyricsTranslation: (Boolean) -> Unit = {},
    /** Nivel de animación de las letras: Simple, Normal o Exagerada. */
    lyricsAnimationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
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
    /** Qué se ve detrás de la portada: el campo vivo o un color sólido. */
    backgroundStyle: PlayerBackground = PlayerBackground.Kawarp,
    /** Altura a la que reposa la barra del mini reproductor, en píxeles. */
    miniTopPx: Float = 0f,
    /** Dónde y cómo de grande está la portada en la barra: origen del vuelo. */
    miniArtworkPos: Offset = Offset.Zero,
    miniArtworkSize: Size = Size.Zero,
    /** Progreso del despliegue: 0 es la barra y 1 la pantalla completa. */
    unfold: Animatable<Float, AnimationVector1D>,
    /** Avisa cuando un dedo agarra o suelta el panel (para que viva la barra). */
    onDraggingChange: (Boolean) -> Unit = {},
    /** El tema Glass activa el material de cristal en los controles. */
    liquidGlass: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    // El fondo del reproductor se captura aparte para que los controles de
    // cristal refracten la atmósfera de la portada, no la pantalla de atrás.
    val playerBackdrop = rememberPrismalGlassLayer()

    // El cuerpo del reproductor es una hoja que se despliega desde la barra:
    // arranca pegada a ella y crece solo hacia arriba, con las esquinas de
    // arriba redondeadas y el pie siempre en el borde inferior de la pantalla.
    // El contenido no se mueve: el panel lo va descubriendo desde abajo, así
    // que cada pieza aparece ya en su sitio y la cabecera es lo último que se
    // ve, igual que en Apple Music. El progreso lo trae el dedo —la barra al
    // subir, el panel al bajar— y lo asienta quien lo conduce.
    //
    // La portada viaja aparte, por encima de todo: una copia vuela desde su
    // sitio en la barra hasta el destino de su panel siguiendo el mismo
    // progreso, y la de verdad espera oculta a que aterrice. Hay dos
    // destinos: la portada grande del reproductor central y la del bloque
    // superior que comparten letras y cola. Cada uno con su morph: el central
    // no se asoma en los menús que no son suyos, y al revés.
    val cover = rememberSongArtwork(song, 512)

    // La portada grande del reproductor central: se mide una vez, su sitio ya
    // no se mueve nunca.
    var heroPos by remember { mutableStateOf(Offset.Zero) }
    var heroSize by remember { mutableStateOf(Size.Zero) }
    val heroMeasure = Modifier.onGloballyPositioned { coords ->
        if (heroSize == Size.Zero && coords.size.width > 0) {
            heroPos = coords.positionInRoot()
            heroSize = coords.size.toSize()
        }
    }

    // La del bloque superior, el que comparten letras y cola: también se
    // mide una vez.
    var headerPos by remember { mutableStateOf(Offset.Zero) }
    var headerSize by remember { mutableStateOf(Size.Zero) }
    val headerMeasure = Modifier.onGloballyPositioned { coords ->
        if (headerSize == Size.Zero && coords.size.width > 0) {
            headerPos = coords.positionInRoot()
            headerSize = coords.size.toSize()
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

    // Mientras se busca la letra, las letras no se pueden abrir: el botón
    // brilla para avisar de la búsqueda y, si el panel ya estaba abierto, se
    // vuelve al reproductor principal en vez de dejar un cartel a medias.
    LaunchedEffect(lyricsLoading, lyrics.isEmpty()) {
        if (panel == PlayerPanel.Lyrics && (lyricsLoading || lyrics.isEmpty())) {
            panel = PlayerPanel.Artwork
        }
    }

    // La letra traducida y su romanización: dos capas que la fuente puede
    // traer o no. Cada pista empieza de cero: si su letra no tiene
    // traducción, el interruptor no puede hacer nada.
    var showRomanization by remember { mutableStateOf(false) }
    LaunchedEffect(song.id) {
        showRomanization = false
    }

    // Qué trae la letra de esta canción. La romanización se mira con la misma
    // regla que usa el motor al pintarla, para no prometer lo que no se ve.
    val hasRomanization = lyrics.any { it.romanizationText() != null }

    // El morph entre el bloque superior (letras y cola) y la portada grande
    // del reproductor central: 0 es el bloque, 1 es la portada. Al cambiar de
    // panel la imagen viaja de uno al otro, en vez de fundirse con el otro.
    val panelMorph = remember { Animatable(1f) }
    LaunchedEffect(panel) {
        panelMorph.animateTo(
            targetValue = if (panel == PlayerPanel.Artwork) 1f else 0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        )
    }

    // La plaza que ocupa la copia de la portada mientras viaja. La calculan el
    // gesto del reproductor (la barra ↔ el destino de su panel) y el cambio de
    // panel (el bloque superior ↔ la portada grande).
    val flightState = remember(miniArtworkPos, miniArtworkSize) {
        derivedStateOf {
            val u = unfold.value
            val h = panelMorph.value
            val flying = u > CoverFlightEpsilon && u < 1f - CoverFlightEpsilon
            val morphing = !flying && h > CoverFlightEpsilon && h < 1f - CoverFlightEpsilon
            val hero = CoverRect(heroPos, heroSize, HeroCoverRadius)
            val header = CoverRect(headerPos, headerSize, HeaderCoverRadius)
            val mini = CoverRect(miniArtworkPos, miniArtworkSize, MiniCoverRadius)
            when {
                flying -> {
                    // El destino es el de su panel: la grande del reproductor
                    // central o la del bloque superior de letras y cola.
                    val target = if (panel == PlayerPanel.Artwork) hero else header
                    if (target.size.width <= 0f) {
                        null
                    } else {
                        CoverFlight(
                            rect = lerpCoverRect(mini, target, u),
                            radius = lerpRadius(MiniCoverRadius, target.radius, u),
                        )
                    }
                }

                morphing -> {
                    if (header.size.width <= 0f || hero.size.width <= 0f) {
                        null
                    } else {
                        CoverFlight(
                            rect = lerpCoverRect(header, hero, h),
                            radius = lerpRadius(HeaderCoverRadius, HeroCoverRadius, h),
                        )
                    }
                }

                else -> null
            }
        }
    }

    // Mientras la copia viaja, la portada de verdad se apaga: nunca hay dos
    // a la vez. Si la copia no puede dibujarse (todavía no se midió su
    // destino), la de verdad sigue en su sitio.
    val copyActive by remember(flightState) {
        derivedStateOf { flightState.value != null }
    }

    // En letras, los controles se retiran solos a los cuatro segundos para
    // dejar la portada, el título y la letra a solas. Un toque en el vacío los
    // enciende o los esconde; si los esconde a mano, el temporizador no los
    // revive hasta el siguiente toque.
    var lyricsControlsVisible by remember(song.id) { mutableStateOf(true) }
    var lyricsControlsDismissed by remember(song.id) { mutableStateOf(false) }
    var lyricsControlsWakeToken by remember(song.id) { mutableStateOf(0) }
    LaunchedEffect(panel, song.id, lyricsControlsWakeToken) {
        if (panel == PlayerPanel.Lyrics) {
            if (!lyricsControlsDismissed) {
                lyricsControlsVisible = true
                delay(LyricsControlsTimeoutMs)
                lyricsControlsVisible = false
            }
        } else {
            lyricsControlsVisible = true
        }
    }

    // Los controles se retiran solos en las letras; el botón de idioma no se va
    // con ellos: vive en el panel, arriba del todo no.
    val controlsVisible = panel != PlayerPanel.Lyrics || lyricsControlsVisible

    // Los botones del pie de las letras aparecen junto a la barra de controles
    // y se quedan: cuando la barra se retira, ellos siguen ahí.
    var controlsAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) controlsAppeared = true
    }
    val languageButtonsVisible = controlsVisible || controlsAppeared

    /**
     * Un toque en el vacío de las letras enciende los controles o los
     * esconde, según estén.
     */
    fun toggleLyricsControls() {
        if (panel != PlayerPanel.Lyrics) return
        if (lyricsControlsVisible) {
            lyricsControlsVisible = false
            lyricsControlsDismissed = true
        } else {
            lyricsControlsVisible = true
            lyricsControlsDismissed = false
            lyricsControlsWakeToken += 1
        }
    }

    // El achicado de pausa espera a que el panel esté asentado: mientras la
    // copia de la portada viaja entre la barra y el cuerpo manda ella, y la
    // de verdad está escondida. Leer el progreso con derivedStateOf evita
    // recomponer el reproductor en cada fotograma del despliegue.
    val playerSettled by remember {
        derivedStateOf { unfold.value >= 0.9985f }
    }

    // Lyra también respeta "quitar animaciones" del sistema.
    val lyricsAnimated = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

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

    // La superficie del reproductor: el campo de color de la portada, hecho
    // manchas con la receta de kawarp. La pantalla principal lo adelanta
    // mientras la canción suena en el mini reproductor, así que el reproductor
    // nace casi con él puesto; al cambiar de canción, el campo nuevo entra
    // fundiéndose con el que estaba. Con el fondo sólido no hay nada que
    // preparar: ni campo, ni caché, ni desenfoque.
    var field by remember { mutableStateOf(AtmosphereFields.cached(song)) }
    LaunchedEffect(song.id, backgroundStyle) {
        if (backgroundStyle != PlayerBackground.Kawarp) return@LaunchedEffect
        // Si el campo nuevo no sale, se queda el anterior: un fondo en blanco
        // delata más el fallo que un cambio a destiempo.
        val loaded = AtmosphereFields.load(context, song) ?: return@LaunchedEffect
        if (loaded !== field) field = loaded
    }

    val toggleImmersive = { immersive = !immersive }

    // La capa con todo el contenido del reproductor: el fondo y la letra. Es
    // lo que se ve a través del cristal de los menús, las hojas y los
    // diálogos, para que refracten la letra que tienen detrás y no solo el
    // color del fondo.
    val contentBackdrop = rememberPrismalGlassLayer()

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (liquidGlass) {
                        Modifier.prismalGlassLayer(contentBackdrop)
                    } else {
                        Modifier
                    },
                )
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
                }
                .pointerInput(panel) {
                    // Observa el gesto desde el primer contacto, sin consumirlo:
                    // así sigue llegando al botón, la portada o la letra que
                    // esté debajo.
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            // El contenido vive quieto en su sitio: el panel lo va descubriendo
            // de abajo arriba, así que no hay que desplazarlo.
            Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (liquidGlass) {
                        Modifier.prismalGlassLayer(playerBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Atmosphere(
                song = song,
                field = field,
                style = backgroundStyle,
                isPlaying = isPlaying,
            )

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
        }

        // Recoge los toques que no caen en un control para que no atraviesen
        // hasta la pantalla que queda detrás. En las letras, además, el toque
        // en el vacío enciende los controles o los esconde.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    toggleLyricsControls()
                },
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
                        isPlaying = isPlaying,
                        settled = playerSettled,
                        artworkModifier = heroMeasure,
                        artworkVisible = { !copyActive },
                        lyrics = lyrics,
                        positionMs = progress.positionMs,
                        lyricsAnimationsEnabled = lyricsAnimated,
                        lyricsAnimationLevel = lyricsAnimationLevel,
                        onOpenLyrics = {
                            // Con la letra en camino no se abre el panel: el
                            // brillo del botón de letras avisa de la búsqueda.
                            if (!lyricsLoading) {
                                immersive = false
                                panel = PlayerPanel.Lyrics
                            }
                        },
                    )

                    PlayerPanel.Lyrics -> LyricsPanel(
                        song = song,
                        lyrics = lyrics,
                        attribution = lyricsAttribution,
                        loading = lyricsLoading,
                        positionMs = progress.positionMs,
                        isPlaying = isPlaying,
                        animationsEnabled = lyricsAnimated,
                        lyricsAnimationLevel = lyricsAnimationLevel,
                        onSeek = onSeek,
                        contentProgress = { unfold.value },
                        onOpenArtwork = { panel = PlayerPanel.Artwork },
                        artworkModifier = headerMeasure,
                        artworkVisible = { !copyActive },
                        showRomanization = showRomanization,
                        translationState = lyricsTranslation,
                        hasRomanization = hasRomanization,
                        lyricsTranslationVisible = lyricsTranslationVisible,
                        onToggleLyricsTranslation = onToggleLyricsTranslation,
                        onToggleRomanization = { showRomanization = !showRomanization },
                        buttonsVisible = languageButtonsVisible,
                        liquidGlass = liquidGlass,
                        backdrop = playerBackdrop,
                        contentBackdrop = contentBackdrop,
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
                        liquidGlass = liquidGlass,
                        backdrop = playerBackdrop,
                        artworkModifier = headerMeasure,
                        artworkVisible = { !copyActive },
                    )
                }
            }

            // Los controles se desvanecen en su sitio, sin moverse: el espacio
            // que dejan se encoge después, despacio, para que la letra recupere
            // su sitio sin saltos.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = expandVertically(
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Bottom,
                ) + fadeIn(
                    animationSpec = tween(200, delayMillis = 280, easing = FastOutSlowInEasing),
                ),
                exit = fadeOut(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + shrinkVertically(
                    animationSpec = tween(340, delayMillis = 200, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Bottom,
                ),
            ) {
                Column(
                    modifier = Modifier
                        // El grupo entero sube un poco, para no dejar un hueco
                        // entre la portada y el título.
                        .offset(y = -PlayerDetailsLift)
                        .graphicsLayer {
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
                        isPlaying = playWhenReady,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        liquidGlass = liquidGlass,
                        backdrop = playerBackdrop,
                    )
                    Spacer(Modifier.height(36.dp))
                    BottomControls(
                        lyricsOpen = panel == PlayerPanel.Lyrics,
                        lyricsLoading = lyricsLoading,
                        lyricsAvailable = lyrics.isNotEmpty(),
                        queueOpen = panel == PlayerPanel.Queue,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        onToggleLyrics = {
                            if (panel == PlayerPanel.Lyrics) {
                                panel = PlayerPanel.Artwork
                            } else {
                                // Las letras necesitan el escenario entero: si
                                // veníamos del fondo inmersivo, se recoge.
                                immersive = false
                                panel = PlayerPanel.Lyrics
                            }
                        },
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onToggleQueue = {
                            if (panel == PlayerPanel.Queue) {
                                panel = PlayerPanel.Artwork
                            } else {
                                immersive = false
                                panel = PlayerPanel.Queue
                            }
                        },
                        liquidGlass = liquidGlass,
                        backdrop = playerBackdrop,
                    )
                }
            }
        }
            }
        }

        // El error de traducción: un panel de cristal sobre el reproductor,
        // con la letra refractada detrás.
        translationError?.let { message ->
            TranslationErrorDialog(
                message = message,
                onDismiss = onDismissTranslationError,
                backdrop = contentBackdrop,
            )
        }

        // La portada que viaja: sale del sitio de la barra y aterriza en el
        // destino de su panel —la grande del reproductor central o la del
        // bloque superior de letras y cola— siguiendo el mismo progreso del
        // panel. En los extremos queda justo encima de la portada de verdad,
        // así que el relevo no se nota.
        //
        // La copia se dibuja sobre la plaza más grande que se conoce, para no
        // perder detalle al encogerla; de ahí se escala hasta donde viaje.
        val reference = remember(heroPos, heroSize, headerPos, headerSize) {
            if (heroSize.width >= headerSize.width) {
                CoverRect(heroPos, heroSize, HeroCoverRadius)
            } else {
                CoverRect(headerPos, headerSize, HeaderCoverRadius)
            }
        }
        if (cover != null && reference.size.width > 0f) {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(reference.pos.x.roundToInt(), reference.pos.y.roundToInt())
                    }
                    .size(
                        with(density) { reference.size.width.toDp() },
                        with(density) { reference.size.height.toDp() },
                    )
                    .graphicsLayer {
                        // La plaza y el redondeo los lleva el vuelo: son los
                        // de su destino, no los del reproductor central.
                        val flight = flightState.value
                        if (flight == null) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        val rect = flight.rect
                        val scale = rect.size.width / reference.size.width
                        if (rect.size.width <= 0f || rect.size.height <= 0f || scale <= 0f) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        alpha = 1f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = rect.size.height / reference.size.height
                        translationX = rect.pos.x - reference.pos.x
                        translationY = rect.pos.y - reference.pos.y
                        // El borde redondeado también viaja: nace con el de la
                        // barra y termina con el de su destino. El radio se
                        // compensa con la escala de la capa, que lo encoge
                        // junto con la imagen: en pantalla es siempre el que
                        // toca.
                        val radiusPx = flight.radius.dp.toPx() / scale
                        shape = RoundedCornerShape(radiusPx.toDp())
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
 * El fondo del reproductor, según lo elegido en Ajustes. Los dos salen de la
 * portada: [PlayerBackground.Kawarp] la vuelve manchas que se mecen, y
 * [PlayerBackground.Solid] la deja en un único color, quieto.
 */
@Composable
private fun Atmosphere(
    song: Song,
    field: AtmosphereField?,
    style: PlayerBackground,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    when (style) {
        PlayerBackground.Kawarp ->
            AtmosphereFlow(field = field, isPlaying = isPlaying, modifier = modifier)

        PlayerBackground.Solid ->
            AtmosphereSolid(song = song, modifier = modifier)
    }
}

/**
 * El campo vivo: la portada hecha manchas de color —el campo que prepara
 * [AtmosphereFields] con la receta de kawarp—, meciéndose con el warp de kawarp
 * y una pizca de aberración cromática hacia los bordes. Donde el Android lo
 * permite lo mueve un shader (AGSL); en Android 12 o anterior, y si el shader
 * no compilara, queda el mismo campo quieto.
 *
 * El flujo avanza a veinte pasos por segundo y se pinta sobre una quinta parte
 * de la pantalla —un campo de manchas no tiene detalle que perder, y estirarlo
 * es casi gratis—, así que tenerlo encendido mientras suena la música no pesa.
 */
@Composable
private fun AtmosphereFlow(
    field: AtmosphereField?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val fabric = rememberAtmosphereShader()
    val brush = remember(fabric) { fabric?.let(::ShaderBrush) }
    val moving = isPlaying && !reduceMotion && brush != null

    // El compás del flujo: avanza solo mientras suena, y al pausar se queda
    // donde estaba sin gastar un solo fotograma.
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        while (true) {
            delay(AtmosphereStepMs)
            clock.value += AtmosphereStepMs / 1000f
        }
    }

    // El relevo entre canciones: el campo nuevo entra fundiéndose con el que
    // estaba, como hace kawarp.
    var shown by remember { mutableStateOf(field) }
    var leaving by remember { mutableStateOf<AtmosphereField?>(null) }
    val blend = remember { Animatable(1f) }
    LaunchedEffect(field) {
        if (field != null && field !== shown) {
            leaving = shown
            shown = field
            blend.snapTo(0f)
            blend.animateTo(1f, tween(AtmosphereTransitionMs, easing = LinearEasing))
        }
    }

    Canvas(
        modifier = modifier
            .layout { measurable, constraints ->
                // El fondo se mide pequeño y se estira al pintarse: sobre una
                // mancha de color no se nota, y el shader trabaja una porción
                // mínima de píxeles.
                val width = (constraints.maxWidth / AtmosphereRenderScale).toInt().coerceAtLeast(1)
                val height = (constraints.maxHeight / AtmosphereRenderScale).toInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = AtmosphereRenderScale
                scaleY = AtmosphereRenderScale
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val current = shown
        if (current == null) {
            drawRect(color = AtmosphereFallback)
            return@Canvas
        }

        val previous = leaving ?: current
        if (fabric != null && brush != null) {
            fabric.paintAtmosphere(
                size = size,
                seconds = clock.value,
                blend = blend.value,
                fieldA = previous.shader,
                fieldB = current.shader,
                darken = current.brightness,
            )
            drawRect(brush = brush)
        } else {
            // Respaldo sin AGSL: los mismos campos, quietos, fundidos a mano.
            val target = IntSize(width.toInt(), height.toInt())
            if (previous !== current) {
                drawImage(
                    image = previous.image,
                    dstSize = target,
                    filterQuality = FilterQuality.Low,
                )
                drawImage(
                    image = current.image,
                    dstSize = target,
                    alpha = blend.value,
                    filterQuality = FilterQuality.Low,
                )
            } else {
                drawImage(
                    image = current.image,
                    dstSize = target,
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        drawControlShade()
    }
}

/**
 * El fondo sólido: un único color sacado de la portada —apagado con la misma
 * receta de siempre—, sin degradado y sin animación. Mientras el color llega
 * queda la superficie neutra, y el cambio entra fundido.
 */
@Composable
private fun AtmosphereSolid(
    song: Song,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var seed by remember(song.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(song.id) {
        seed = ArtworkRepository.seedColor(context, song)
    }
    val surface by animateColorAsState(
        targetValue = seed?.let(::mutedSurface) ?: AtmosphereFallback,
        animationSpec = tween(AtmosphereTransitionMs, easing = FastOutSlowInEasing),
        label = "fondo sólido",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = surface)
        drawControlShade()
    }
}

/** La sombra de abajo: donde viven los controles, apenas más honda. */
private fun DrawScope.drawControlShade() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.68f, size.height * 1.02f),
            radius = size.maxDimension,
        ),
    )
}

/**
 * Apaga el color de la portada hasta dejarlo en una superficie serena: poco
 * color y media luz, para que cualquier portada deje un fondo tranquilo sobre
 * el que se lean los controles.
 */
private fun mutedSurface(seed: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(
        (seed.red * 255f).roundToInt(),
        (seed.green * 255f).roundToInt(),
        (seed.blue * 255f).roundToInt(),
        hsl,
    )
    hsl[1] = (hsl[1] * SurfaceSaturation)
        .coerceIn(SurfaceSaturationMin, SurfaceSaturationMax)
    hsl[2] = (hsl[2] * SurfaceLightnessGain + SurfaceLightnessBase)
        .coerceIn(SurfaceLightnessMin, SurfaceLightnessMax)
    return Color(ColorUtils.HSLToColor(hsl))
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
 * En inmersivo la portada cuadrada se apaga y solo queda el fondo. Al pausar,
 * la portada se encoge un poco y vuelve a crecer cuando suena la música.
 *
 * La portada lleva el elemento compartido: es la misma imagen que viaja desde
 * el mini reproductor y se posa aquí, sin fundidos ni saltos. El achicado de
 * pausa solo entra cuando el panel ya está asentado, para no pelear con la
 * copia que viaja durante la transición.
 */
@Composable
private fun ArtworkPanel(
    song: Song,
    contentProgress: () -> Float,
    immersiveProgress: Float,
    onToggleImmersive: () -> Unit,
    isPlaying: Boolean,
    settled: Boolean,
    /** Mide la portada en su sitio de reposo y la esconde mientras viaja su copia. */
    artworkModifier: Modifier = Modifier,
    /** Verdadero cuando la portada de verdad está en su sitio y no viaja su copia. */
    artworkVisible: () -> Boolean = { true },
    /** Letra sincronizada para el preview sobre el título. */
    lyrics: List<LyricLine> = emptyList(),
    positionMs: Long = 0L,
    lyricsAnimationsEnabled: Boolean = true,
    /** Nivel de animación de Lyra que decide qué se anima en el preview. */
    lyricsAnimationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    onOpenLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val artwork = rememberSongArtwork(song, 512)
    val haptics = LocalHapticFeedback.current
    val currentOnToggle by rememberUpdatedState(onToggleImmersive)

    val coverScale by animateFloatAsState(
        targetValue = if (settled && !isPlaying) PausedCoverScale else 1f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "portada en pausa",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val squareAlpha = ((0.24f - immersiveProgress) / 0.24f).coerceIn(0f, 1f)
        // La portada ocupa el mismo ancho que la barra de progreso: de pared a
        // pared, con el mismo margen a los lados. Así todo el reproductor se
        // lee como una sola columna y la portada no queda como un rectángulo
        // suelto en medio.
        val coverSize = maxWidth
        // La portada vive centrada en el aire que dejan los detalles de abajo.
        val detailsReserve = 74.dp
        val coverTop = ((maxHeight - detailsReserve - coverSize) / 2f).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(y = coverTop)
                .size(coverSize)
                .then(artworkModifier)
                .graphicsLayer {
                    alpha = squareAlpha * if (artworkVisible()) 1f else 0f
                    scaleX = coverScale
                    scaleY = coverScale
                }
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
                .padding(bottom = PlayerDetailsLift),
        ) {
            if (lyrics.isNotEmpty()) {
                LyraSingleLinePreview(
                    lyrics = lyrics,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    animationsEnabled = lyricsAnimationsEnabled,
                    animationLevel = lyricsAnimationLevel,
                    onClick = onOpenLyrics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // El preview sube cosido al resto de los detalles.
                            val arrival = ((contentProgress() - 0.18f) / 0.36f).coerceIn(0f, 1f)
                            alpha = arrival
                            translationY = (1f - arrival) * 28.dp.toPx()
                        },
                )
                Spacer(Modifier.height(6.dp))
            }
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

// --------------------------------------------------------------------- letras

/**
 * Panel de letras: el mismo encabezado de la cola y, debajo, Lyra —el motor de
 * letras de Cloud— cuando la canción trae líneas sincronizadas. Si no las
 * trae, un estado vacío tranquilo en lugar de una lista en blanco.
 */
@Composable
private fun LyricsPanel(
    song: Song,
    lyrics: List<LyricLine>,
    attribution: LyricsAttribution?,
    loading: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    animationsEnabled: Boolean,
    /** Nivel de animación de Lyra: Simple, Normal o Exagerada. */
    lyricsAnimationLevel: LyricAnimationLevel = LyricAnimationLevel.Normal,
    onSeek: (Long) -> Unit,
    contentProgress: () -> Float,
    onOpenArtwork: () -> Unit = {},
    /** Mide la portada del bloque superior: es el destino del morph de este menú. */
    artworkModifier: Modifier = Modifier,
    /** Verdadero cuando la portada de verdad está en su sitio y no viaja su copia. */
    artworkVisible: () -> Boolean = { true },
    /** Pinta la romanización de la letra, si la fuente la trajo. */
    showRomanization: Boolean = false,
    /** En qué punto está la traducción de la letra. */
    translationState: LyricsTranslationState = LyricsTranslationState.Idle,
    /** Si la letra trae romanización, para poder ofrecerla. */
    hasRomanization: Boolean = false,
    /** Si la letra que se ve es la traducida. */
    lyricsTranslationVisible: Boolean = false,
    /** Pide la letra traducida u original. */
    onToggleLyricsTranslation: (Boolean) -> Unit = {},
    /** Enciende o apaga la romanización. */
    onToggleRomanization: () -> Unit = {},
    /** Si los botones del pie están en pantalla. */
    buttonsVisible: Boolean = true,
    liquidGlass: Boolean = false,
    backdrop: PrismalBackdrop,
    /** La capa con todo el reproductor: el cristal refracta la letra. */
    contentBackdrop: PrismalBackdrop = backdrop,
    modifier: Modifier = Modifier,
) {
    // La hoja de créditos se abre y se cierra desde aquí.
    var creditsSheetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Las letras aterrizan con el gesto, como la cola.
                val arrival = ((contentProgress() - 0.16f) / 0.36f).coerceIn(0f, 1f)
                alpha = arrival
                translationY = (1f - arrival) * 26.dp.toPx()
            },
    ) {
        QueueHeader(
            song = song,
            onArtworkClick = onOpenArtwork,
            artworkModifier = artworkModifier,
            artworkVisible = artworkVisible,
        )

        Spacer(Modifier.height(18.dp))

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.72f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Buscando letras…",
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        } else if (lyrics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = LyricsQuoteBubble,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.size(46.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Aún no hay letras",
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "No encontramos letras para esta canción.",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LyraLyricsDisplay(
                lyrics = lyrics,
                positionMs = positionMs,
                isPlaying = isPlaying,
                animationsEnabled = animationsEnabled,
                animationLevel = lyricsAnimationLevel,
                onSeek = onSeek,
                // La traducción no se pinta debajo: sustituye a la letra
                // original, que ya viene traducida en la propia línea.
                showTranslation = false,
                showRomanization = showRomanization,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Al pie del panel: el idioma de la letra a la izquierda y los
            // créditos a la derecha. Los dos viven aquí y no en el bloque de
            // controles, así que no se retiran con ellos.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LyricsLanguageButton(
                    hasRomanization = hasRomanization,
                    showTranslation = lyricsTranslationVisible,
                    showRomanization = showRomanization,
                    translating = translationState is LyricsTranslationState.Translating,
                    onToggleTranslation = { onToggleLyricsTranslation(!lyricsTranslationVisible) },
                    onToggleRomanization = onToggleRomanization,
                    liquidGlass = liquidGlass,
                    // El botón va dentro de la capa que contiene la letra, así
                    // que no puede muestrearla: toma la del fondo.
                    backdrop = backdrop,
                    visible = buttonsVisible,
                )

                if (attribution != null) {
                    LyricsCreditsButton(
                        onClick = { creditsSheetOpen = true },
                        liquidGlass = liquidGlass,
                        // Igual que el botón de idioma: va dentro de la capa
                        // de la letra, así que muestrea la del fondo.
                        backdrop = backdrop,
                        visible = buttonsVisible,
                    )
                }
            }

            // El estado de la traducción, con el mismo aire discreto que los
            // créditos: avisa de lo que está pasando.
            LyricsTranslationStatus(translationState)

            // Los créditos viven en su propia hoja de cristal.
            if (creditsSheetOpen && attribution != null) {
                LyricsCreditsSheet(
                    attribution = attribution,
                    backdrop = contentBackdrop,
                    onDismiss = { creditsSheetOpen = false },
                )
            }
        }
    }
}

/**
 * Botón de créditos: abre la hoja con de dónde viene la letra y quién la
 * subió o la creó.
 *
 * Es una gota de cristal en el tema Glass y un círculo translúcido en el modo
 * simple, igual que el botón de idioma.
 */
@Composable
private fun LyricsCreditsButton(
    onClick: () -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val physics = rememberPrismalButtonPhysics()
    val shape = CircleShape
    val fill = Color.White.copy(alpha = 0.16f)
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "botón de créditos",
    )

    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .then(
                if (liquidGlass) {
                    Modifier
                        .prismalButtonScale(
                            physics = physics,
                            pressLift = 2.dp,
                        )
                        .size(LyricsLanguageButtonSize)
                        .clip(shape)
                        .playerGlass(
                            backdrop = backdrop,
                            shape = shape,
                            surfaceColor = fill,
                        )
                        .prismalButtonPress(physics)
                } else {
                    Modifier
                        .size(LyricsLanguageButtonSize)
                        .clip(shape)
                        .background(fill)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = "Créditos",
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * El estado de la traducción de la letra: el motivo por el que no se pudo.
 * Mientras se traduce no hace falta ningún cartel: el botón de idioma brilla
 * para que se sepa que la cosa está en marcha.
 */
@Composable
private fun LyricsTranslationStatus(
    state: LyricsTranslationState,
    modifier: Modifier = Modifier,
) {
    val message = when (state) {
        is LyricsTranslationState.Idle -> null
        is LyricsTranslationState.Translating -> null
        is LyricsTranslationState.Failed -> state.message
    } ?: return

    Text(
        text = message,
        color = Color.White.copy(alpha = 0.42f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

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
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    /** Mide la portada del bloque superior: es el destino del morph de este menú. */
    artworkModifier: Modifier = Modifier,
    /** Verdadero cuando la portada de verdad está en su sitio y no viaja su copia. */
    artworkVisible: () -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val currentIndex = queue.indexOfFirst { it.id == currentSong.id }
    val nextSongs = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = PlayerDetailsLift)
            .graphicsLayer {
                // La cola también aterriza con el gesto, como los detalles.
                val arrival = ((contentProgress() - 0.16f) / 0.36f).coerceIn(0f, 1f)
                alpha = arrival
                translationY = (1f - arrival) * 26.dp.toPx()
            },
    ) {
        QueueHeader(
            song = currentSong,
            artworkModifier = artworkModifier,
            artworkVisible = artworkVisible,
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ModePill(
                icon = Icons.Rounded.Shuffle,
                description = "Aleatorio",
                active = shuffleEnabled,
                onClick = onToggleShuffle,
                liquidGlass = liquidGlass,
                backdrop = backdrop,
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
                liquidGlass = liquidGlass,
                backdrop = backdrop,
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

/**
 * Encabezado de la cola: la que suena ahora. Es también el bloque superior
 * de las letras, así que su portada es el destino del morph de esos dos
 * menús: cuando la copia viaja, la de verdad se apaga.
 */
@Composable
private fun QueueHeader(
    song: Song,
    /** En letras, tocar la portada vuelve al panel principal. */
    onArtworkClick: (() -> Unit)? = null,
    /** Mide la portada en su sitio de reposo. */
    artworkModifier: Modifier = Modifier,
    /** Verdadero cuando la portada de verdad está en su sitio y no viaja su copia. */
    artworkVisible: () -> Boolean = { true },
) {
    val artwork = rememberSongArtwork(song, 256)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .then(artworkModifier)
                .graphicsLayer { alpha = if (artworkVisible()) 1f else 0f }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .then(
                    if (onArtworkClick != null) {
                        Modifier
                            .clickable(onClick = onArtworkClick)
                            .semantics { contentDescription = "Abrir portada" }
                    } else {
                        Modifier
                    },
                ),
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
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .width(96.dp)
            .height(36.dp)
            .clip(shape)
            .then(
                if (liquidGlass) {
                    // Aleatorio y repetir llevan material, pero no elasticidad:
                    // la cápsula se queda quieta y sólo se ilumina el cristal.
                    Modifier.playerGlass(
                        backdrop = backdrop,
                        shape = shape,
                        surfaceColor = Color.White.copy(alpha = if (active) 0.28f else 0.16f),
                    )
                } else {
                    Modifier.background(
                        Color.White.copy(alpha = if (active) 0.28f else 0.16f),
                    )
                },
            )
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
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    val playPhysics = rememberPrismalButtonPhysics()
    val playShape = CircleShape

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
            liquidGlass = liquidGlass,
            backdrop = backdrop,
        )

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .then(
                    if (liquidGlass) {
                        Modifier
                            .prismalButtonScale(
                                physics = playPhysics,
                                pressLift = 2.5.dp,
                            )
                            .size(62.dp)
                            .clip(playShape)
                            .playerGlass(
                                backdrop = backdrop,
                                shape = playShape,
                                surfaceColor = Color.White.copy(alpha = 0.12f),
                            )
                            .prismalButtonPress(playPhysics)
                    } else {
                        Modifier.size(62.dp)
                    },
                ),
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
            liquidGlass = liquidGlass,
            backdrop = backdrop,
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
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val offsetX = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val physics = rememberPrismalButtonPhysics()
    val shape = CircleShape

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
        modifier = modifier.then(
            if (liquidGlass) {
                Modifier
                    .prismalButtonScale(
                        physics = physics,
                        pressLift = 1.5.dp,
                    )
                    .size(52.dp)
                    .clip(shape)
                    .playerGlass(
                        backdrop = backdrop,
                        shape = shape,
                        surfaceColor = Color.White.copy(alpha = 0.10f),
                    )
                    .prismalButtonPress(physics)
            } else {
                Modifier.size(52.dp)
            },
        ),
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

/** Fila inferior: letras, aleatorio/repetir y cola. */
@Composable
private fun BottomControls(
    lyricsOpen: Boolean,
    /** Verdadero mientras se busca la letra: el botón brilla y no se abre. */
    lyricsLoading: Boolean = false,
    /** Si la canción tiene letra, el botón de letras se puede abrir. */
    lyricsAvailable: Boolean = true,
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onToggleLyrics: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleQueue: () -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
) {
    val modePillShape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El fondo inmersivo ya vive en el gesto de mantener pulsada la
        // portada; el hueco del antiguo botón de pantalla completa es ahora
        // la puerta a las letras.
        CircleToggle(
            active = lyricsOpen,
            icon = if (lyricsOpen) LyricsQuoteBubbleFill else LyricsQuoteBubble,
            description = if (lyricsOpen) "Ocultar letras" else "Letras",
            onClick = onToggleLyrics,
            liquidGlass = liquidGlass,
            backdrop = backdrop,
            // Buscando letra no se puede abrir el panel: el icono avisa con
            // su brillo de que la búsqueda está en marcha. Y sin letra no hay
            // nada que abrir: el botón se apaga.
            busy = lyricsLoading,
            enabled = lyricsAvailable,
        )

        Row(
            modifier = Modifier
                .width(104.dp)
                .height(36.dp)
                .clip(modePillShape)
                .then(
                    if (liquidGlass) {
                        // La cápsula entera es una sola pieza de cristal: las
                        // mitades no se estiran, así que el borde no se rompe.
                        Modifier.playerGlass(
                            backdrop = backdrop,
                            shape = modePillShape,
                            surfaceColor = Color.White.copy(alpha = 0.16f),
                        )
                    } else {
                        Modifier.background(Color.White.copy(alpha = 0.16f))
                    },
                ),
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
            liquidGlass = liquidGlass,
            backdrop = backdrop,
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
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
    /** Verdadero mientras trabaja: el icono brilla y el botón no responde. */
    busy: Boolean = false,
    /** Si no está disponible, el botón se apaga y no responde. */
    enabled: Boolean = true,
) {
    val physics = rememberPrismalButtonPhysics()
    val shape = CircleShape
    // Mientras trabaja, el cristal baja su opacidad y su canto: el brillo es
    // solo del icono, como cuando no hay letras.
    val dim = when {
        busy -> 0.45f
        !enabled -> 0.6f
        else -> 1f
    }
    val fill = if (active) {
        Color.White.copy(alpha = 0.92f)
    } else {
        Color.White.copy(alpha = 0.16f * dim)
    }

    Box(
        modifier = modifier
            .then(
                if (liquidGlass) {
                    Modifier
                        .prismalButtonScale(
                            physics = physics,
                            pressLift = 2.dp,
                        )
                        .size(size)
                        .clip(shape)
                        .playerGlass(
                            backdrop = backdrop,
                            shape = shape,
                            surfaceColor = fill,
                            rimAlpha = dim,
                        )
                        .prismalButtonPress(physics)
                } else {
                    Modifier
                        .size(size)
                        .clip(shape)
                        .background(fill)
                },
            )
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier = if (busy) {
            Modifier.size(iconSize).shimmerWhileWorking()
        } else {
            Modifier
                .size(iconSize)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) Color.Black else Color.White,
            modifier = iconModifier,
        )
    }
}

/**
 * Botón del idioma de la letra: abre el menú con la traducción y la
 * romanización.
 *
 * Es una gota de cristal en el tema Glass —con el menú de Prismal, que crece
 * desde el botón— y un círculo translúcido en el modo simple, con un menú
 * sencillo encima. Lo que la letra no trae sale opaco y no se puede activar.
 */
@Composable
private fun LyricsLanguageButton(
    hasRomanization: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    /** Verdadero mientras se traduce la letra: el icono brilla. */
    translating: Boolean = false,
    onToggleTranslation: () -> Unit,
    onToggleRomanization: () -> Unit,
    liquidGlass: Boolean,
    backdrop: PrismalBackdrop,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val physics = rememberPrismalButtonPhysics()
    val shape = CircleShape
    val fill = Color.White.copy(alpha = 0.16f)
    // Los botones aparecen con la barra de controles y se quedan.
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "botones del pie de las letras",
    )

    Row(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .prismalMenuAnchor { anchorBounds = it }
                .then(
                    if (liquidGlass) {
                        Modifier
                            .prismalButtonScale(
                                physics = physics,
                                pressLift = 2.dp,
                            )
                            .size(LyricsLanguageButtonSize)
                            .clip(shape)
                            .playerGlass(
                                backdrop = backdrop,
                                shape = shape,
                                surfaceColor = fill,
                            )
                            .prismalButtonPress(physics)
                    } else {
                        Modifier
                            .size(LyricsLanguageButtonSize)
                            .clip(shape)
                            .background(fill)
                    },
                )
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = "Idioma de la letra",
                tint = Color.White,
                modifier = if (translating) {
                    // Mientras se traduce, el icono brilla: el botón avisa de
                    // que la cosa está en marcha sin taparle la letra.
                    Modifier.size(19.dp).shimmerWhileWorking()
                } else {
                    Modifier.size(19.dp)
                },
            )
        }

        // En el modo simple el menú es una lista translúcida sobre la propia
        // pantalla; el cristal lo pone Prismal, en su propia ventana.
        if (!liquidGlass) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1C1C1E).copy(alpha = 0.92f)),
            ) {
                DropdownMenuItem(
                    text = { Text("Mostrar Traducción", color = Color.White) },
                    enabled = true,
                    onClick = {
                        onToggleTranslation()
                        expanded = false
                    },
                    leadingIcon = {
                        if (showTranslation) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Mostrar pronunciación", color = Color.White) },
                    enabled = hasRomanization,
                    onClick = {
                        onToggleRomanization()
                        expanded = false
                    },
                    leadingIcon = {
                        if (showRomanization) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    },
                )
            }
        }
    }

    if (liquidGlass) {
        PrismalGlassMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorBounds = anchorBounds,
            backdrop = backdrop,
            width = 220.dp,
            // El menú sube sobre el botón: vive sobre las letras, no sobre la
            // barra de progreso.
            opensAbove = true,
            // Cristal normal, como el resto de la app: sin tinte encima.
            surfaceColor = Color.White.copy(alpha = 0.16f),
        ) {
            PrismalGlassMenuItem(
                text = "Mostrar Traducción",
                enabled = true,
                selected = showTranslation,
                onClick = {
                    onToggleTranslation()
                    expanded = false
                },
            )
            PrismalGlassMenuItem(
                text = "Mostrar pronunciación",
                enabled = hasRomanization,
                selected = showRomanization,
                onClick = {
                    onToggleRomanization()
                    expanded = false
                },
            )
        }
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
