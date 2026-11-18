package com.example.bondibariloche

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.Etapa
import kotlin.math.abs

/** Asigna un color determinístico y legible a cada línea de colectivo mediante hash de ángulo de tono. */
fun colorParaLinea(linea: String): Color {
    if (linea == "CAMINANDO") return Color(0xFF78909C)
    val hue = (abs(linea.hashCode()) * 137.508f) % 360f // Ángulo áureo para máxima separación cromática
    return Color.hsv(hue = hue, saturation = 0.68f, value = 0.82f)
}

/**
 * Barra horizontal proporcional donde cada segmento ocupa un ancho exacto
 * según su fracción del tiempo total de viaje (etapa.tiempo / tiempoTotal).
 */
@Composable
fun BarraTiempoProporcional(
    etapas: List<Etapa>,
    modifier: Modifier = Modifier
) {
    if (etapas.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        etapas.forEach { etapa ->
            val peso = etapa.tiempo.toFloat().coerceAtLeast(0.5f)
            val esCaminata = etapa.linea == "CAMINANDO"
            val etiqueta = if (esCaminata) "🚶 ${etapa.tiempo.toInt()}m" else "🚌 ${etapa.linea}"

            Box(
                modifier = Modifier
                    .weight(peso)
                    .fillMaxHeight()
                    .background(colorParaLinea(etapa.linea)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = etiqueta,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}