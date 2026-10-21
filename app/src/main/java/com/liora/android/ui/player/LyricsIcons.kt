package com.liora.android.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Los dos estados del botón de letras: `quote_bubble` y `quote_bubble_fill`.
 *
 * CupertinoIcons es una fuente de Flutter y no existe en Compose, así que sus
 * contornos se incrustan aquí como vectores. No hay dependencia nueva ni se
 * carga ninguna fuente: solo dos ImageVector, dibujados con la misma silueta
 * de 24 que el resto de los iconos del reproductor.
 *
 * Contornos tomados de la fuente CupertinoIcons (paquete cupertino_icons
 * 1.0.8, MIT, © 2016 Vladimir Kharlampidi), codepoints U+F795 y U+F796.
 */
internal val LyricsQuoteBubble: ImageVector by lazy {
    ImageVector.Builder(
        name = "LyricsQuoteBubble",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 512f,
        viewportHeight = 512f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            quoteBubbleOutline()
        }
    }.build()
}

internal val LyricsQuoteBubbleFill: ImageVector by lazy {
    ImageVector.Builder(
        name = "LyricsQuoteBubbleFill",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 512f,
        viewportHeight = 512f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            quoteBubbleFill()
        }
    }.build()
}

/** Glifo U+F795, centrado en el cuadrado de 512. */
private fun PathBuilder.quoteBubbleOutline() {
    moveTo(152f, 478f)
    quadTo(128f, 478f, 128f, 450f)
    lineTo(128f, 395f)
    lineTo(118f, 395f)
    quadTo(70f, 395f, 45f, 369.5f)
    quadTo(20f, 344f, 20f, 297f)
    lineTo(20f, 132f)
    quadTo(20f, 85f, 45f, 59.5f)
    quadTo(70f, 34f, 118f, 34f)
    lineTo(394f, 34f)
    quadTo(442f, 34f, 467f, 59.5f)
    quadTo(492f, 85f, 492f, 132f)
    lineTo(492f, 297f)
    quadTo(492f, 344f, 467f, 369.5f)
    quadTo(442f, 395f, 394f, 395f)
    lineTo(257f, 395f)
    lineTo(183f, 461f)
    quadTo(165f, 478f, 152f, 478f)
    close()
    moveTo(161f, 438f)
    lineTo(229f, 370f)
    quadTo(239f, 360f, 255f, 360f)
    lineTo(394f, 360f)
    quadTo(458f, 360f, 458f, 296f)
    lineTo(458f, 132f)
    quadTo(458f, 69f, 394f, 69f)
    lineTo(118f, 69f)
    quadTo(54f, 69f, 54f, 132f)
    lineTo(54f, 296f)
    quadTo(54f, 360f, 118f, 360f)
    lineTo(144f, 360f)
    quadTo(161f, 360f, 161f, 376f)
    close()
    moveTo(158f, 204f)
    quadTo(158f, 185f, 170f, 173.5f)
    quadTo(182f, 162f, 201f, 162f)
    quadTo(221f, 162f, 233.5f, 176.5f)
    quadTo(246f, 191f, 246f, 212f)
    quadTo(246f, 247f, 225f, 268f)
    quadTo(204f, 289f, 184f, 289f)
    quadTo(173f, 289f, 173f, 280f)
    quadTo(173f, 272f, 183f, 269f)
    quadTo(208f, 264f, 220f, 238f)
    lineTo(218f, 238f)
    quadTo(212f, 246f, 196f, 246f)
    quadTo(178f, 246f, 168f, 234f)
    quadTo(158f, 222f, 158f, 204f)
    close()
    moveTo(269f, 204f)
    quadTo(269f, 185f, 281.5f, 173.5f)
    quadTo(294f, 162f, 313f, 162f)
    quadTo(333f, 162f, 345.5f, 176.5f)
    quadTo(358f, 191f, 358f, 212f)
    quadTo(358f, 247f, 336.5f, 268f)
    quadTo(315f, 289f, 295f, 289f)
    quadTo(285f, 289f, 285f, 280f)
    quadTo(285f, 272f, 295f, 269f)
    quadTo(320f, 264f, 332f, 238f)
    lineTo(330f, 238f)
    quadTo(324f, 246f, 307f, 246f)
    quadTo(289f, 246f, 279f, 234f)
    quadTo(269f, 222f, 269f, 204f)
    close()
}

/** Glifo U+F796, centrado en el cuadrado de 512. */
private fun PathBuilder.quoteBubbleFill() {
    moveTo(147f, 478f)
    quadTo(128f, 478f, 128f, 456f)
    lineTo(128f, 394f)
    lineTo(118f, 394f)
    quadTo(70f, 394f, 45f, 368.5f)
    quadTo(20f, 343f, 20f, 296f)
    lineTo(20f, 132f)
    quadTo(20f, 85f, 45f, 59.5f)
    quadTo(70f, 34f, 118f, 34f)
    lineTo(394f, 34f)
    quadTo(442f, 34f, 467f, 59.5f)
    quadTo(492f, 85f, 492f, 132f)
    lineTo(492f, 296f)
    quadTo(492f, 343f, 467f, 368.5f)
    quadTo(442f, 394f, 394f, 394f)
    lineTo(249f, 394f)
    lineTo(172f, 465f)
    quadTo(158f, 478f, 147f, 478f)
    close()
    moveTo(193f, 245f)
    quadTo(208f, 245f, 216f, 236f)
    lineTo(218f, 236f)
    quadTo(207f, 261f, 180f, 269f)
    quadTo(169f, 272f, 169f, 280f)
    quadTo(169f, 290f, 180f, 290f)
    quadTo(201f, 290f, 223.5f, 268f)
    quadTo(246f, 246f, 246f, 209f)
    quadTo(246f, 187f, 232.5f, 172f)
    quadTo(219f, 157f, 198f, 157f)
    quadTo(178f, 157f, 165.5f, 169.5f)
    quadTo(153f, 182f, 153f, 201f)
    quadTo(153f, 220f, 164f, 232.5f)
    quadTo(175f, 245f, 193f, 245f)
    close()
    moveTo(310f, 245f)
    quadTo(325f, 245f, 333f, 236f)
    lineTo(335f, 236f)
    quadTo(324f, 261f, 297f, 269f)
    quadTo(286f, 272f, 286f, 280f)
    quadTo(286f, 290f, 297f, 290f)
    quadTo(318f, 290f, 340.5f, 268f)
    quadTo(363f, 246f, 363f, 209f)
    quadTo(363f, 187f, 350f, 172f)
    quadTo(337f, 157f, 316f, 157f)
    quadTo(296f, 157f, 283f, 169.5f)
    quadTo(270f, 182f, 270f, 201f)
    quadTo(270f, 220f, 281f, 232.5f)
    quadTo(292f, 245f, 310f, 245f)
    close()
}
