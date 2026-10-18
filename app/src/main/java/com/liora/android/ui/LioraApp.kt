package com.liora.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.activity.compose.BackHandler
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
import com.liora.android.ui.screens.HomeScreen
import com.liora.android.ui.screens.LibraryScreen
import com.liora.android.ui.screens.SearchScreen
import com.liora.android.ui.screens.SettingsScreen
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
fun LioraApp(
    viewModel: MusicViewModel,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { LioraPrefs(context) }

    var liquidGlass by remember { mutableStateOf(prefs.liquidGlass) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

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

    val collapseController = rememberBottomAreaCollapseController()
    val hasSong = currentSong != null
    SideEffect { collapseController.updateHasMiniPlayer(hasSong) }

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
                    onLiquidGlassChange = { enabled ->
                        liquidGlass = enabled
                        prefs.liquidGlass = enabled
                    },
                    backdrop = backdrop,
                    hazeState = hazeState,
                    bottomPadding = bottomPadding,
                    collapseConnection = collapseController.nestedScrollConnection,
                )
            }
        }

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
                    liquidGlass = liquidGlass,
                    backdrop = backdrop,
                    hazeState = hazeState,
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
                )
            }
        }
    }
}
