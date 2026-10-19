package com.liora.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liora.android.data.art.ArtworkRepository
import com.liora.android.data.prefs.AppStyle
import com.liora.android.data.prefs.AppThemeMode
import com.liora.android.data.prefs.LioraPrefs
import com.liora.android.ui.components.MiniPlayer
import com.liora.android.ui.components.MiniPlayerCollapsedHeight
import com.liora.android.ui.components.MiniPlayerHeight
import com.liora.android.ui.components.MiniPlayerSidePadding
import com.liora.android.ui.components.rememberBottomAreaCollapseController
import com.liora.android.ui.navigation.LioraTab
import com.liora.android.ui.navigation.LiquidGlassNavBar
import com.liora.android.ui.navigation.LiquidNavBarCollapsedGap
import com.liora.android.ui.navigation.LiquidNavBarCollapsedSize
import com.liora.android.ui.navigation.LiquidNavBarHeight
import com.liora.android.ui.navigation.LiquidNavBarPadding
import com.liora.android.ui.navigation.SimpleNavBar
import com.liora.android.ui.navigation.SimpleNavBarHeight
import com.liora.android.ui.navigation.navBarSidePadding
import com.liora.android.ui.player.LioraFullPlayer
import com.liora.android.ui.player.LioraPlayerElement
import com.liora.android.ui.player.PlayerMorphSpec
import com.liora.android.ui.player.rememberPlayerMorphClip
import com.liora.android.ui.screens.HomeScreen
import com.liora.android.ui.screens.LibraryScreen
import com.liora.android.ui.screens.SearchScreen
import com.liora.android.ui.screens.SettingsScreen
import com.liora.android.ui.theme.LioraSystemBars
import com.liora.android.ui.theme.LioraTheme
import com.liora.android.ui.theme.neutralScheme
import com.liora.android.ui.theme.rememberDynamicColorScheme
import com.liora.android.ui.viewmodel.MusicViewModel
import dev.chrisbanes.haze.rememberHazeState

/** Alto de la barra de navegación con sus márgenes. */
private val NavBarAreaHeight = 86.dp

/** Aire entre la barra de música y la de navegación cuando todo está abierto. */
private val BottomAreaGap = 8.dp

/** Alto del área inferior cuando hay algo sonando (música + aire + navegación). */
private val BottomAreaWithPlayerHeight = 152.dp + BottomAreaGap

/**
 * Modo simple: la barra de música va pegada arriba de la navegación, así que el
 * bloque inferior mide exactamente la suma de las dos piezas (sin aire).
 */
private val SimpleBottomAreaWithPlayerHeight = MiniPlayerHeight + SimpleNavBarHeight

/**
 * Punto de entrada de la interfaz: lee el estilo y el modo de tema elegidos en
 * Ajustes, aplica el tema a toda la app y monta la cáscara.
 */
@Composable
fun LioraApp(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { LioraPrefs(context) }

    var appStyle by remember { mutableStateOf(prefs.appStyle) }
    var themeMode by remember { mutableStateOf(prefs.appThemeMode) }

    // La canción actual decide la paleta del estilo Dinámico.
    val currentSong by viewModel.currentSong.collectAsState()

    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

    // Cada estilo viste su paleta: Dinámico la saca de la portada de la canción
    // actual, Glass se queda en grises (neutro, para no competir con el
    // cristal) y Material You usa la del teléfono, la de siempre.
    val colorSchemeOverride = when (appStyle) {
        AppStyle.Dynamic -> rememberDynamicColorScheme(song = currentSong, darkTheme = darkTheme)
        AppStyle.Glass -> remember(darkTheme) { neutralScheme(darkTheme) }
        AppStyle.Material -> null
    }

    LioraTheme(darkTheme = darkTheme, colorSchemeOverride = colorSchemeOverride) {
        // Las barras del sistema siguen al modo elegido dentro de la app.
        LioraSystemBars(darkTheme = darkTheme)

        LioraAppContent(
            viewModel = viewModel,
            hasPermission = hasPermission,
            onRequestPermissions = onRequestPermissions,
            appStyle = appStyle,
            themeMode = themeMode,
            onAppStyleChange = { style ->
                appStyle = style
                prefs.appStyle = style
            },
            onThemeModeChange = { mode ->
                themeMode = mode
                prefs.appThemeMode = mode
            },
        )
    }
}

/**
 * Cáscara de la app: contenido + mini reproductor + navegación inferior.
 *
 * El contenido se dibuja por debajo de las barras y se copia al backdrop
 * (cristal líquido) o se registra para Haze (modo simple).
 *
 * El área inferior es adaptativa, igual que en Cloud: al bajar por la lista el
 * mini reproductor se comprime y baja, y la píldora de navegación se convierte
 * en un círculo, de modo que ambas barras terminan fusionadas en una sola fila.
 */
@Composable
private fun LioraAppContent(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
    appStyle: AppStyle,
    themeMode: AppThemeMode,
    onAppStyleChange: (AppStyle) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    // Solo el estilo Glass usa el cristal líquido; Material You y Dinámico se
    // quedan con la navegación simple.
    val liquidGlass = appStyle == AppStyle.Glass
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    /** El reproductor completo se despliega al tocar el mini reproductor. */
    var playerOpen by rememberSaveable { mutableStateOf(false) }

    val hazeState = rememberHazeState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.progress.collectAsState()
    val neighbors by viewModel.neighbors.collectAsState()
    val queueSongs by viewModel.queueSongs.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    /** Esquinas que se desabrochan al abrir y cerrar el reproductor. */
    val morphClip = rememberPlayerMorphClip()

    val collapseController = rememberBottomAreaCollapseController()
    val hasSong = currentSong != null
    SideEffect { collapseController.updateHasMiniPlayer(hasSong) }

    // La paleta ambiental se prepara mientras la canción suena en el mini
    // reproductor: así el reproductor completo nace ya con sus colores.
    val context = LocalContext.current
    LaunchedEffect(currentSong?.id) {
        currentSong?.let { ArtworkRepository.atmosphereColors(context, it) }
    }

    val tabs = LioraTab.entries
    val selectedTab = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)]

    val navigationBarInsets = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val bottomPadding = navigationBarInsets + if (liquidGlass) {
        if (hasSong) BottomAreaWithPlayerHeight else NavBarAreaHeight
    } else {
        if (hasSong) SimpleBottomAreaWithPlayerHeight else SimpleNavBarHeight
    }

    val onSelect: (LioraTab) -> Unit = { tab ->
        searchOpen = false
        selectedIndex = tab.ordinal
    }

    BackHandler(enabled = searchOpen) { searchOpen = false }

    // Cambiar de pantalla devuelve la lista arriba, así que el área inferior
    // vuelve a su sitio: si no, quedaría comprimida sin nada que lo explique.
    LaunchedEffect(selectedTab, searchOpen) { collapseController.expand() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (searchOpen) {
            SearchScreen(
                viewModel = viewModel,
                liquidGlass = liquidGlass,
                backdrop = backdrop,
                hazeState = hazeState,
                bottomPadding = bottomPadding,
                collapseConnection = collapseController.nestedScrollConnection,
            )
        } else {
            when (selectedTab) {
                LioraTab.Home -> HomeScreen(
                    viewModel = viewModel,
                    hasPermission = hasPermission,
                    onRequestPermissions = onRequestPermissions,
                    liquidGlass = liquidGlass,
                    backdrop = backdrop,
                    hazeState = hazeState,
                    bottomPadding = bottomPadding,
                    collapseConnection = collapseController.nestedScrollConnection,
                )

                LioraTab.Library -> LibraryScreen(
                    viewModel = viewModel,
                    liquidGlass = liquidGlass,
                    backdrop = backdrop,
                    hazeState = hazeState,
                    bottomPadding = bottomPadding,
                    collapseConnection = collapseController.nestedScrollConnection,
                )

                LioraTab.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    liquidGlass = liquidGlass,
                    appStyle = appStyle,
                    themeMode = themeMode,
                    onAppStyleChange = onAppStyleChange,
                    onThemeModeChange = onThemeModeChange,
                    backdrop = backdrop,
                    hazeState = hazeState,
                    bottomPadding = bottomPadding,
                    collapseConnection = collapseController.nestedScrollConnection,
                )
            }
        }

        // El mini reproductor y el reproductor completo viven bajo el mismo
        // lienzo de transiciones compartidas: la barra y su portada son las
        // piezas que viajan de uno a otro.
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(
                            if (liquidGlass) {
                                if (hasSong) BottomAreaWithPlayerHeight else NavBarAreaHeight
                            } else {
                                // En modo simple el bloque es la suma de sus piezas (la
                                // navegación ya incluye su inset de gestos).
                                navigationBarInsets +
                                    if (hasSong) SimpleBottomAreaWithPlayerHeight else SimpleNavBarHeight
                            },
                        ),
                ) {
                    // Sigue al dedo mientras se hace scroll y se asienta con un muelle.
                    // El morph del área inferior es exclusivo del cristal líquido: en
                    // modo simple (Haze) las barras quedan pegadas y no se anima nada.
                    val collapseState = animateFloatAsState(
                        targetValue = if (hasSong && liquidGlass) collapseController.target else 0f,
                        animationSpec = if (collapseController.directManipulation) {
                            snap()
                        } else {
                            spring(
                                dampingRatio = .82f,
                                stiffness = 430f,
                                visibilityThreshold = .001f,
                            )
                        },
                        label = "colapso del area inferior",
                    )
                    val collapse = collapseState.value

                    val density = LocalDensity.current
                    // El círculo se centra en la franja de navegación, que va pegada al
                    // borde inferior con su inset de gestos: su centro queda a
                    // margen + media píldora + inset del borde de abajo. El mini
                    // reproductor tiene que caer en esa misma fila, así que baja
                    // (alto del área - centro de la franja - medio reproductor).
                    val navRowCenter =
                        LiquidNavBarPadding + LiquidNavBarHeight / 2 + navigationBarInsets
                    val collapseDrop = (
                        BottomAreaWithPlayerHeight - navRowCenter - MiniPlayerCollapsedHeight / 2
                        ).coerceAtLeast(0.dp)
                    // Al comprimirse, la navegación se abre hacia los bordes (con el
                    // mismo margen que usa la barra), así que el mini reproductor
                    // arranca donde termina el círculo y acaba donde empieza la
                    // búsqueda: los tres comparten fila y respiro.
                    val sideInset = if (liquidGlass) {
                        (
                            navBarSidePadding(collapse) + LiquidNavBarCollapsedSize +
                                LiquidNavBarCollapsedGap - MiniPlayerSidePadding
                            ) * collapse
                    } else {
                        0.dp
                    }
                    val sideInsetPx = with(density) { sideInset.toPx() }
                    val collapseDropPx = with(density) {
                        (if (liquidGlass) collapseDrop else 0.dp).toPx()
                    }
                    val miniPlayerWidth = (maxWidth - sideInset * 2).coerceAtLeast(1.dp)

                    if (liquidGlass) {
                        LiquidGlassNavBar(
                            selected = selectedTab,
                            onSelect = onSelect,
                            backdrop = backdrop,
                            onSearchClick = { searchOpen = true },
                            searchSelected = searchOpen,
                            collapseProgress = collapse,
                            onExpandBottomArea = collapseController::expand,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(1f),
                        )
                    } else {
                        SimpleNavBar(
                            selected = selectedTab,
                            onSelect = onSelect,
                            onSearchClick = { searchOpen = true },
                            searchSelected = searchOpen,
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }

                    currentSong?.let { song ->
                        // La barra se retira cuando el reproductor toma la pantalla;
                        // al cerrar vuelve, deslizándose desde el propio reproductor.
                        AnimatedVisibility(
                            visible = !playerOpen,
                            enter = fadeIn(
                                animationSpec = tween(
                                    durationMillis = 220,
                                    delayMillis = 200,
                                    easing = FastOutSlowInEasing,
                                ),
                            ),
                            exit = fadeOut(
                                animationSpec = tween(
                                    durationMillis = 120,
                                    easing = FastOutSlowInEasing,
                                ),
                            ),
                            modifier = if (liquidGlass) {
                                Modifier
                                    .width(miniPlayerWidth)
                                    .graphicsLayer {
                                        translationX = sideInsetPx
                                        translationY = collapseDropPx * collapseState.value
                                    }
                                    .zIndex(2f)
                            } else {
                                // Pegada a la navegación: ocupa todo el ancho y reposa
                                // justo encima, porque el área mide la suma de las dos.
                                Modifier.fillMaxWidth()
                            },
                        ) {
                            with(this@SharedTransitionLayout) {
                                MiniPlayer(
                                    song = song,
                                    previousSong = neighbors.previous,
                                    nextSong = neighbors.next,
                                    isPlaying = isPlaying,
                                    progressFraction = playbackProgress.fraction,
                                    collapseProgress = collapse,
                                    onTogglePlay = { viewModel.togglePlayPause() },
                                    onPrevious = { viewModel.skipToPrevious() },
                                    onPreviousSong = { viewModel.skipToPreviousSong() },
                                    onNext = { viewModel.skipToNext() },
                                    onOpenPlayer = { playerOpen = true },
                                    liquidGlass = liquidGlass,
                                    backdrop = backdrop,
                                    hazeState = hazeState,
                                    boundsModifier = Modifier.sharedBounds(
                                        sharedContentState = rememberSharedContentState(
                                            LioraPlayerElement.Container,
                                        ),
                                        animatedVisibilityScope = this@AnimatedVisibility,
                                        enter = fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                delayMillis = 190,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ),
                                        exit = fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 130,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ),
                                        boundsTransform = { _, _ -> PlayerMorphSpec },
                                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                                        clipInOverlayDuringTransition = morphClip,
                                    ),
                                    artworkModifier = Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(
                                            LioraPlayerElement.Artwork,
                                        ),
                                        animatedVisibilityScope = this@AnimatedVisibility,
                                        boundsTransform = { _, _ -> PlayerMorphSpec },
                                        clipInOverlayDuringTransition = OverlayClip(
                                            RoundedCornerShape(14.dp),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }

                // Reproductor completo: se despliega desde la barra del mini
                // reproductor y, al cerrar, recorre el camino a la inversa.
                AnimatedVisibility(
                    visible = playerOpen && currentSong != null,
                    enter = EnterTransition.None,
                    exit = fadeOut(
                        animationSpec = tween(
                            durationMillis = 320,
                            delayMillis = 80,
                            easing = FastOutSlowInEasing,
                        ),
                    ),
                    modifier = Modifier.zIndex(10f),
                ) {
                    currentSong?.let { song ->
                        LioraFullPlayer(
                            song = song,
                            isPlaying = isPlaying,
                            progress = playbackProgress,
                            queue = queueSongs,
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            onClose = { playerOpen = false },
                            onPlayPause = { viewModel.togglePlayPause() },
                            onPrevious = { viewModel.skipToPrevious() },
                            onNext = { viewModel.skipToNext() },
                            onSeek = { viewModel.seekTo(it) },
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onCycleRepeat = { viewModel.cycleRepeat() },
                            onPlaySong = { viewModel.playSong(it) },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    }
                }
            }
        }
    }
}
