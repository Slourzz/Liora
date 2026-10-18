package com.liora.android.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SelectorPadding = 6.dp

/**
 * Geometría de la píldora de navegación. Es `internal` porque el área inferior
 * la necesita para que el mini reproductor comprimido caiga exactamente en la
 * misma fila que el círculo (y no dependa de números mágicos duplicados).
 */
internal val LiquidNavBarHeight = 62.dp
internal val LiquidNavBarPadding = 12.dp
internal val LiquidNavBarSidePadding = 44.dp

/** Tamaño de la píldora de navegación cuando el área inferior está comprimida. */
internal val LiquidNavBarCollapsedSize = 42.dp

/** Botón de búsqueda: círculo grande que se encoge junto con la píldora. */
internal val LiquidNavBarSearchSize = 56.dp
internal val LiquidNavBarSearchGap = 8.dp

/**
 * Margen lateral de la navegación cuando la fila está comprimida. Se abre
 * mucho más que el de la píldora para que el círculo y la búsqueda se vayan a
 * los bordes y el mini reproductor gane largo entre ellos.
 */
internal val LiquidNavBarCollapsedSidePadding = 12.dp

/** Respiro entre el círculo, el mini reproductor y la búsqueda comprimidos. */
internal val LiquidNavBarCollapsedGap = 5.dp

/** Margen lateral de la navegación para un progreso de compresión dado. */
internal fun navBarSidePadding(collapse: Float): Dp =
    LiquidNavBarSidePadding +
        (LiquidNavBarCollapsedSidePadding - LiquidNavBarSidePadding) * collapse

/** Alto de la barra simple sin el inset de gestos: 8 + icono + etiqueta + 8. */
internal val SimpleNavBarHeight = 72.dp

/**
 * Navegación simple (estilo Apple Music): barra translúcida con las pestañas
 * (y la búsqueda) apoyada sobre un desenfoque suave de Haze.
 */
@Composable
fun SimpleNavBar(
    selected: LioraTab,
    onSelect: (LioraTab) -> Unit,
    onSearchClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    searchSelected: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier
            .fillMaxWidth()
            .hazeBlur(input = HazeInput.Sources(hazeState), style = HazeMaterials.thin())
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        LioraTab.entries.forEach { tab ->
            // La búsqueda va antes de Ajustes, como en Apple Music.
            if (tab == LioraTab.Settings) {
                SimpleNavItem(
                    icon = Icons.Default.Search,
                    label = "Buscar",
                    isSelected = searchSelected,
                    onClick = onSearchClick,
                    modifier = Modifier.weight(1f),
                )
            }

            val isSelected = tab == selected
            SimpleNavItem(
                icon = if (isSelected) tab.selectedIcon else tab.icon,
                label = tab.label,
                isSelected = isSelected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Icono + etiqueta de la barra simple. */
@Composable
private fun SimpleNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
        )
    }
}

/**
 * Navegación con cristal líquido (librería Backdrop de Kyant0).
 *
 * Réplica de la barra de Cloud:
 * - píldora flotante con saturación, desenfoque y lente, que crece un poco
 *   mientras se la mantiene pulsada;
 * - una copia invisible de las pestañas alimenta un backdrop combinado, así el
 *   selector refracta los iconos y las etiquetas al deslizarse sobre ellos
 *   (aberración cromática incluida);
 * - con [collapseProgress] la píldora se encoge hasta un círculo con una flecha
 *   para volver a mostrar la navegación, las pestañas se desvanecen y el botón
 *   de búsqueda se encoge a juego, de modo que el mini reproductor quede
 *   encajado entre ambos (igual que en Cloud).
 *
 * Los cristales se recuerdan para que el morph no reconstruya su caché de
 * sombreadores en cada fotograma.
 */
@Composable
fun LiquidGlassNavBar(
    selected: LioraTab,
    onSelect: (LioraTab) -> Unit,
    backdrop: Backdrop,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    collapseProgress: Float = 0f,
    searchSelected: Boolean = false,
    onExpandBottomArea: () -> Unit = {},
) {
    val tabs = LioraTab.entries
    val collapse = collapseProgress.coerceIn(0f, 1f)
    val selectedIndex = tabs.indexOf(selected)
    // El selector se guarda en "unidades de pestaña" (0..2) para que siga bien
    // el ancho de la píldora mientras ésta se comprime.
    val selectorX = remember { Animatable(selectedIndex.toFloat()) }
    val pressProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pressSpec = remember {
        spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
    }

    var barWidth by remember { mutableIntStateOf(0) }
    var settleKey by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val barShape = RoundedCornerShape(31.dp)
    val selectorShape = RoundedCornerShape(26.dp)

    val tabsBackdrop = rememberLayerBackdrop()
    val barBackdrop = rememberLayerBackdrop()
    // El selector copia el cristal ya desenfocado de la barra + los iconos:
    // así refracta los iconos sin mostrar el fondo "crudo".
    val selectorBackdrop = rememberCombinedBackdrop(barBackdrop, tabsBackdrop)

    val darkMode = isSystemInDarkTheme()
    val surfaceColor = if (darkMode) {
        Color(0xFF121212).copy(alpha = 0.28f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.34f)
    }
    val accent = MaterialTheme.colorScheme.primary
    val selectorColor = accent.copy(alpha = 0.26f)

    val paddingPx = with(density) { SelectorPadding.toPx() }
    val itemWidth = if (barWidth > 0) {
        (barWidth - paddingPx * 2f) / tabs.size
    } else {
        0f
    }

    val contentAlpha = (1f - collapse * 2f).coerceIn(0f, 1f)
    val tabsEnabled = collapse < .45f
    // Con las pestañas desvanecidas el selector tampoco se ve: lo sacamos de la
    // escena para no pagar su copia del fondo ni su lente.
    val showSelector = contentAlpha > .02f

    // El botón de búsqueda sólo reacciona con una escala de presión (sin jalón
    // elástico): la lente se lee en fase de dibujo y no recomputa nada.
    var searchPressed by remember { mutableStateOf(false) }
    val searchPress = animateFloatAsState(
        targetValue = if (searchPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "presion busqueda",
    )
    val searchGlass = remember(backdrop, barShape, surfaceColor, searchPress) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { barShape },
            effects = {
                vibrancy()
                blur(4.dp.toPx())
                lens(16.dp.toPx(), 32.dp.toPx())
            },
            layerBlock = {
                val maxScale = (size.width + 16.dp.toPx()) / size.width.coerceAtLeast(1f)
                val scale = 1f + (maxScale - 1f) * searchPress.value
                scaleX = scale
                scaleY = scale
            },
            highlight = { Highlight.Default },
            onDrawSurface = { drawRect(surfaceColor) },
        )
    }

    val barGlass = remember(backdrop, barShape, surfaceColor) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { barShape },
            effects = {
                vibrancy()
                blur(4.dp.toPx())
                lens(16.dp.toPx(), 32.dp.toPx())
            },
            layerBlock = {
                val maxScale = (size.width + 16.dp.toPx()) / size.width.coerceAtLeast(1f)
                val scale = 1f + (maxScale - 1f) * pressProgress.value
                scaleX = scale
                scaleY = scale
            },
            exportedBackdrop = barBackdrop,
            onDrawSurface = { drawRect(surfaceColor) },
        )
    }

    val selectorGlass = remember(selectorBackdrop, selectorShape, selectorColor) {        Modifier.drawBackdrop(
            backdrop = selectorBackdrop,
            shape = { selectorShape },
            effects = {
                val strength = 0.45f + 0.55f * pressProgress.value
                lens(
                    refractionHeight = 10.dp.toPx() * strength,
                    refractionAmount = 14.dp.toPx() * strength,
                    chromaticAberration = true,
                )
            },
            highlight = {
                Highlight.Default.copy(alpha = 0.5f + 0.5f * pressProgress.value)
            },
            shadow = {
                Shadow(radius = 6.dp, alpha = pressProgress.value)
            },
            innerShadow = {
                InnerShadow(
                    radius = 8.dp * pressProgress.value,
                    alpha = pressProgress.value,
                )
            },
            onDrawSurface = { drawRect(selectorColor) },
        )
    }

    // El selector viaja al asentarse la pestaña elegida.
    LaunchedEffect(selectedIndex, settleKey) {
        selectorX.animateTo(
            targetValue = selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // La presión sube al arrastrar o mientras el selector viaja, y baja al asentar.
    LaunchedEffect(dragging, selectorX.isRunning) {
        if (dragging || selectorX.isRunning) {
            pressProgress.animateTo(1f, pressSpec)
        } else {
            delay(50)
            pressProgress.animateTo(0f, pressSpec)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                horizontal = navBarSidePadding(collapse),
                vertical = LiquidNavBarPadding,
            )
            .height(LiquidNavBarHeight),
    ) {
        // La búsqueda vive en su propio círculo a la derecha; la píldora se
        // queda con el resto y, al comprimirse, las dos se van a los bordes
        // (el margen se cierra con [navBarSidePadding]) para dejarle el centro
        // entero al mini reproductor.
        val searchSize = LiquidNavBarSearchSize +
            (LiquidNavBarCollapsedSize - LiquidNavBarSearchSize) * collapse
        val pillFullWidth = (maxWidth - searchSize - LiquidNavBarSearchGap).coerceAtLeast(1.dp)
        val pillWidth = pillFullWidth + (LiquidNavBarCollapsedSize - pillFullWidth) * collapse
        val pillHeight = LiquidNavBarHeight + (LiquidNavBarCollapsedSize - LiquidNavBarHeight) * collapse

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(pillWidth)
                .height(pillHeight)
                .then(barGlass)
                .onSizeChanged { barWidth = it.width }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (itemWidth > 0f) {
                                val maxX = (tabs.size - 1).toFloat()
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    selectorX.snapTo(
                                        (selectorX.value + dragAmount / itemWidth).coerceIn(0f, maxX),
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            if (itemWidth > 0f) {
                                val index = selectorX.value.roundToInt()
                                    .coerceIn(0, tabs.size - 1)
                                settleKey++
                                onSelect(tabs[index])
                            }
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            // Pestañas visibles, encima del cristal base.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha }
                    .padding(horizontal = SelectorPadding),
            ) {
                tabs.forEach { tab ->
                    NavBarTabItem(
                        tab = tab,
                        isSelected = tab == selected,
                        selectedColor = MaterialTheme.colorScheme.onSurface,
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = if (tabsEnabled) {
                            { onSelect(tab) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

        // +) Selector y su copia refractada. Cuando el área está comprimida no
        // se ven, así que no se componen ni gastan una copia del fondo.
        if (showSelector) {
            // Copia invisible: se captura en su propia capa y el selector la refracta.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { colorFilter = ColorFilter.tint(accent) }
                    .fillMaxSize()
                    .padding(horizontal = SelectorPadding),
            ) {
                tabs.forEach { tab ->
                    NavBarTabItem(
                        tab = tab,
                        isSelected = tab == selected,
                        selectedColor = Color.White,
                        unselectedColor = Color.White,
                        onClick = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // Selector de cristal, por encima de los iconos.
            Box(
                modifier = Modifier
                    .padding(horizontal = SelectorPadding)
                    .offset { IntOffset((selectorX.value * itemWidth).roundToInt(), 0) }
                    .width(with(density) { itemWidth.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer { alpha = contentAlpha }
                    .padding(horizontal = 2.dp, vertical = SelectorPadding)
                    .then(selectorGlass),
            )
        }
        }

        // Botón de búsqueda: mismo cristal que la píldora, encogiéndose a juego.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(searchSize)
                .then(searchGlass)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            searchPressed = true
                            tryAwaitRelease()
                            searchPressed = false
                        },
                        onTap = { onSearchClick() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Buscar",
                tint = if (searchSelected) {
                    accent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp - 5.dp * collapse),
            )
        }

        // Afijo del estado comprimido: al tocarlo se vuelve a mostrar la navegación.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(LiquidNavBarCollapsedSize)
                .zIndex(3f)
                .graphicsLayer { alpha = ((collapse - .35f) / .45f).coerceIn(0f, 1f) }
                .then(
                    if (collapse > .45f) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExpandBottomArea,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Mostrar navegación",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Icono + etiqueta de una pestaña de la barra de cristal. */
@Composable
private fun NavBarTabItem(
    tab: LioraTab,
    isSelected: Boolean,
    selectedColor: Color,
    unselectedColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val color = if (isSelected) selectedColor else unselectedColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            imageVector = if (isSelected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
        )
    }
}
