package com.liora.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liora.android.R
import com.liora.android.data.model.LyricsAttribution
import com.liora.android.data.model.LyricsContributor
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.components.PrismalGlassBottomSheet

/**
 * Hoja de créditos: de dónde viene la letra y quién la subió o la creó.
 *
 * Es una hoja de cristal que sube desde el borde de abajo. Se cierra
 * arrastrándola hacia abajo, tocando fuera o con el botón Atrás.
 */
@Composable
fun LyricsCreditsSheet(
    attribution: LyricsAttribution,
    backdrop: PrismalBackdrop,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    PrismalGlassBottomSheet(
        visible = true,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        modifier = modifier,
    ) {
        Text(
            text = "Creditos",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
        )

        // De dónde viene la letra. La marca es la de Spicy Lyrics, que es de
        // donde salen los créditos de la comunidad.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attribution.providerName.contains("Spicy", ignoreCase = true)) {
                Icon(
                    painter = painterResource(R.drawable.ic_spicy_lyrics),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = attribution.providerName,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        // De qué catálogo salió la letra: Apple Music, la comunidad, etc.
        val origin = attribution.originName
        if (!origin.isNullOrBlank() && !origin.equals(attribution.providerName, ignoreCase = true)) {
            CreditLine(
                label = "Letra de:",
                value = origin,
            )
        }

        if (attribution.songwriters.isNotEmpty()) {
            CreditLine(
                label = "Escrita por:",
                value = attribution.songwriters.joinToString(", "),
            )
        }

        Spacer(Modifier.size(6.dp))

        CreditLine(
            label = "Subida por:",
            contributor = attribution.uploader,
            onClick = { contributor -> contributor.url?.let(uriHandler::openUri) },
        )

        CreditLine(
            label = "Creada por:",
            contributor = attribution.maker,
            onClick = { contributor -> contributor.url?.let(uriHandler::openUri) },
        )
    }
}

/** Una línea de crédito: la etiqueta y el valor. */
@Composable
private fun CreditLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Una línea de crédito: la etiqueta y el nombre, con su enlace. */
@Composable
private fun CreditLine(
    label: String,
    contributor: LyricsContributor?,
    onClick: (LyricsContributor) -> Unit,
) {    if (contributor == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = contributor.username,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = if (contributor.url != null) {
                Modifier.clickable { onClick(contributor) }
            } else {
                Modifier
            },
        )
    }
}
