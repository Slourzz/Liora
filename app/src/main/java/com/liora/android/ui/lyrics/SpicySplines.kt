package com.liora.android.ui.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El trocito de spicy-lyrics que Lyra necesita para sus puntos de espera: los
 * muelles con los que cada punto persigue su tamaño, su brillo y su altura, y
 * las splines (naturales, a trozos) que dibujan ese viaje. Portado tal cual del
 * motor de Cloud (spicy-lyrics, AGPL-3.0), recortado a lo que usan los puntos.
 */

internal enum class SpicyElementState {
    NotSung,
    Active,
    Sung,
}

internal fun getSpicyElementState(
    currentTimeMs: Double,
    startTimeMs: Double,
    endTimeMs: Double,
): SpicyElementState {
    if (currentTimeMs < startTimeMs) return SpicyElementState.NotSung
    if (currentTimeMs >= endTimeMs) return SpicyElementState.Sung
    return SpicyElementState.Active
}

internal fun getSpicyProgressPercentage(
    currentTimeMs: Double,
    startTimeMs: Double,
    endTimeMs: Double,
): Double {
    if (currentTimeMs <= startTimeMs) return 0.0
    if (currentTimeMs >= endTimeMs) return 1.0
    return (currentTimeMs - startTimeMs) / (endTimeMs - startTimeMs).coerceAtLeast(1.0)
}

/** Muelle sub/sobreamortiguado de spicy: se avanza a mano, fotograma a fotograma. */
internal class SpicySpring(
    startPosition: Double,
    frequency: Double,
    dampingRatio: Double,
    goal: Double = startPosition,
) {
    private var damping = dampingRatio
    private var frequency = frequency
    private var goal = goal
    private var position = startPosition
    private var velocity = 0.0

    val value: Double
        get() = position

    fun step(deltaSeconds: Double): Double {
        val d = damping
        val f = frequency * (2.0 * PI)
        val g = goal
        var p = position
        var v = velocity

        if (d == 1.0) {
            val q = exp(-f * deltaSeconds)
            val w = deltaSeconds * q
            val c0 = q + w * f
            val c2 = q - w * f
            val c3 = w * f * f
            val o = p - g
            p = o * c0 + v * w + g
            v = v * c2 - o * c3
        } else if (d < 1.0) {
            val q = exp(-d * f * deltaSeconds)
            val c = sqrt(1.0 - d * d)
            val i = cos(deltaSeconds * f * c)
            val j = sin(deltaSeconds * f * c)
            val z = if (c > SPICY_SPRING_EPSILON) {
                j / c
            } else {
                val a = deltaSeconds * f
                a + ((a * a) * (c * c) * (c * c) / 20.0 - c * c) * (a * a * a) / 6.0
            }
            val y = if (f * c > SPICY_SPRING_EPSILON) {
                j / (f * c)
            } else {
                val b = f * c
                deltaSeconds +
                    ((deltaSeconds * deltaSeconds) * (b * b) * (b * b) / 20.0 - b * b) *
                    (deltaSeconds * deltaSeconds * deltaSeconds) / 6.0
            }
            val o = p - g
            p = (o * (i + z * d) + v * y) * q + g
            v = (v * (i - z * d) - o * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1.0)
            val r1 = -f * (d + c)
            val r2 = -f * (d - c)
            val ec1 = exp(r1 * deltaSeconds)
            val ec2 = exp(r2 * deltaSeconds)
            val o = p - g
            val co2 = (v - o * r1) / (2.0 * f * c)
            val co1 = ec1 * (o - co2)
            p = co1 + co2 * ec2 + g
            v = co1 * r1 + co2 * ec2 * r2
        }

        position = p
        velocity = v
        return p
    }

    fun setGoal(goal: Double, replacePosition: Boolean = false) {
        this.goal = goal
        if (replacePosition) {
            position = goal
            velocity = 0.0
        }
    }
}

/** Spline natural por tramos: la curva que sigue un punto de spicy. */
internal class SpicySpline(points: List<SpicySplinePoint>) {
    private val xs = points.map { it.time }.toDoubleArray()
    private val ys = points.map { it.value }.toDoubleArray()
    private val ks = computeNaturalKs(xs, ys)

    fun at(time: Double): Double {
        val x = time.coerceIn(xs.first(), xs.last())
        var i = 1
        while (i < xs.lastIndex && xs[i] < x) i += 1

        val width = xs[i] - xs[i - 1]
        if (abs(width) <= SPICY_SPLINE_EPSILON) return ys[i]

        val t = (x - xs[i - 1]) / width
        val a = ks[i - 1] * width - (ys[i] - ys[i - 1])
        val b = -ks[i] * width + (ys[i] - ys[i - 1])
        return (1.0 - t) * ys[i - 1] +
            t * ys[i] +
            t * (1.0 - t) * (a * (1.0 - t) + b * t)
    }

    private fun computeNaturalKs(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.lastIndex
        val matrix = Array(n + 1) { DoubleArray(n + 2) }

        matrix[0][0] = 2.0 / (xs[1] - xs[0])
        matrix[0][1] = 1.0 / (xs[1] - xs[0])
        matrix[0][n + 1] = 3.0 * (ys[1] - ys[0]) / ((xs[1] - xs[0]) * (xs[1] - xs[0]))

        for (i in 1 until n) {
            matrix[i][i - 1] = 1.0 / (xs[i] - xs[i - 1])
            matrix[i][i] = 2.0 * (1.0 / (xs[i] - xs[i - 1]) + 1.0 / (xs[i + 1] - xs[i]))
            matrix[i][i + 1] = 1.0 / (xs[i + 1] - xs[i])
            matrix[i][n + 1] =
                3.0 *
                (
                    (ys[i] - ys[i - 1]) / ((xs[i] - xs[i - 1]) * (xs[i] - xs[i - 1])) +
                        (ys[i + 1] - ys[i]) / ((xs[i + 1] - xs[i]) * (xs[i + 1] - xs[i]))
                    )
        }

        matrix[n][n - 1] = 1.0 / (xs[n] - xs[n - 1])
        matrix[n][n] = 2.0 / (xs[n] - xs[n - 1])
        matrix[n][n + 1] = 3.0 * (ys[n] - ys[n - 1]) / ((xs[n] - xs[n - 1]) * (xs[n] - xs[n - 1]))

        solve(matrix)
        return DoubleArray(n + 1) { matrix[it][n + 1] }
    }

    private fun solve(matrix: Array<DoubleArray>) {
        val rowCount = matrix.size
        val columnCount = matrix[0].size
        for (column in 0 until rowCount) {
            var maxRow = column
            for (row in column + 1 until rowCount) {
                if (abs(matrix[row][column]) > abs(matrix[maxRow][column])) {
                    maxRow = row
                }
            }

            if (maxRow != column) {
                val temp = matrix[column]
                matrix[column] = matrix[maxRow]
                matrix[maxRow] = temp
            }

            val pivot = matrix[column][column]
            if (abs(pivot) <= SPICY_SPLINE_EPSILON) continue

            for (i in column until columnCount) {
                matrix[column][i] /= pivot
            }

            for (row in 0 until rowCount) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (i in column until columnCount) {
                    matrix[row][i] -= factor * matrix[column][i]
                }
            }
        }
    }
}

internal data class SpicySplinePoint(
    val time: Double,
    val value: Double,
)

internal val DotScaleSpline = SpicySpline(
    listOf(
        SpicySplinePoint(time = 0.0, value = 0.75),
        SpicySplinePoint(time = 0.7, value = 1.05),
        SpicySplinePoint(time = 1.0, value = 1.0),
    ),
)

internal val DotYOffsetSpline = SpicySpline(
    listOf(
        SpicySplinePoint(time = 0.0, value = 0.0),
        SpicySplinePoint(time = 0.9, value = -0.12),
        SpicySplinePoint(time = 1.0, value = 0.0),
    ),
)

internal val DotGlowSpline = SpicySpline(
    listOf(
        SpicySplinePoint(time = 0.0, value = 0.0),
        SpicySplinePoint(time = 0.6, value = 1.0),
        SpicySplinePoint(time = 1.0, value = 1.0),
    ),
)

internal val DotOpacitySpline = SpicySpline(
    listOf(
        SpicySplinePoint(time = 0.0, value = 0.35),
        SpicySplinePoint(time = 0.6, value = 1.0),
        SpicySplinePoint(time = 1.0, value = 1.0),
    ),
)

private const val SPICY_SPRING_EPSILON = 1e-5
private const val SPICY_SPLINE_EPSILON = 1e-9
