package com.liora.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Controla el progreso de colapso del área inferior (mini reproductor +
 * navegación), igual que Cloud: al bajar por la lista se comprime todo y al
 * subir se vuelve a expandir.
 *
 * @param collapseDistancePx cuántos píxeles de scroll hacen falta para pasar
 *   del estado expandido al comprimido.
 */
@Stable
class BottomAreaCollapseController(
    private val collapseDistancePx: Float,
) {
    /** Solo se colapsa cuando hay algo sonando. */
    var hasMiniPlayer by mutableStateOf(false)
        private set

    /** Progreso objetivo: 0 = expandido, 1 = comprimido. */
    var target by mutableFloatStateOf(0f)
        private set

    /** Verdadero mientras el dedo arrastra: se sigue al dedo sin animación. */
    var directManipulation by mutableStateOf(false)
        private set

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!hasMiniPlayer || source != NestedScrollSource.UserInput) return Offset.Zero
            if (abs(available.y) < .5f) return Offset.Zero
            directManipulation = true
            // Bajar por la lista (Y negativa) comprime; subir expande.
            target = (target - available.y / collapseDistancePx).coerceIn(0f, 1f)
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!hasMiniPlayer) return Velocity.Zero
            directManipulation = false
            target = when {
                available.y < -420f -> 1f
                available.y > 420f -> 0f
                target >= .42f -> 1f
                else -> 0f
            }
            return Velocity.Zero
        }
    }

    fun updateHasMiniPlayer(value: Boolean) {
        hasMiniPlayer = value
        if (!value) {
            directManipulation = false
            target = 0f
        }
    }

    /** Expande el área inferior (al tocar el círculo compacto). */
    fun expand() {
        directManipulation = false
        target = 0f
    }
}

@Composable
fun rememberBottomAreaCollapseController(
    collapseDistance: Dp = 176.dp,
): BottomAreaCollapseController {
    val density = LocalDensity.current
    val distancePx = with(density) { collapseDistance.toPx() }
    return remember(distancePx) { BottomAreaCollapseController(distancePx) }
}
