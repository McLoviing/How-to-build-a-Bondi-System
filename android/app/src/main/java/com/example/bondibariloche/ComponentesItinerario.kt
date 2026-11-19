package com.example.bondibariloche

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.Etapa
import com.example.bondibariloche.data.Ruta
import com.example.bondibariloche.data.Transbordo
import kotlin.math.roundToInt

/**
 * Vista completa de resultados de búsqueda: carrusel horizontal de alternativas
 * y línea de tiempo vertical paso a paso para el recorrido seleccionado.
 */
@Composable
fun VistaResultadosYTimeline(
    rutas: List<Ruta>,
    rutaSeleccionada: Ruta,
    nombreOrigen: String,
    nombreDestino: String,
    onSeleccionarRuta: (Ruta) -> Unit,
    onVolverABuscar: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.86f)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Barra superior con botón explícito para volver atrás y calcular otra ruta
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onVolverABuscar,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("← Cambiar ruta", fontWeight = FontWeight.SemiBold)
            }

            Text(
                text = "${rutas.size} ${if (rutas.size == 1) "opción" else "opciones"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Resumen Origen -> Destino
        Text(
            text = "$nombreOrigen  →  $nombreDestino",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Carrusel horizontal de recorridos alternativos
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = rutas,
                key = { r -> "${r.lineas.joinToString("-")}_${r.tiempoTotal}_${r.etapas.firstOrNull()?.destino?.id}" }
            ) { ruta ->
                val esActiva = ruta == rutaSeleccionada
                TarjetaAlternativaCompacta(
                    ruta = ruta,
                    seleccionada = esActiva,
                    onClick = { onSeleccionarRuta(ruta) }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Detalle completo de la ruta seleccionada con línea de tiempo vertical
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "resumen_tiempos") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Itinerario paso a paso",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${rutaSeleccionada.tiempoTotal.roundToInt()} min total",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Barra proporcional visual
                    BarraTiempoProporcional(etapas = rutaSeleccionada.etapas)

                    Text(
                        text = "Viaje: ${rutaSeleccionada.tiempoViaje.roundToInt()} min · " +
                                "Espera est.: ${rutaSeleccionada.tiempoEspera.roundToInt()} min · " +
                                "${rutaSeleccionada.transbordos} transbordos · ${rutaSeleccionada.distancia.roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    rutaSeleccionada.mensajeUsuario?.takeIf { it.isNotBlank() }?.let { aviso ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = aviso,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // Etapas en formato Línea de Tiempo Vertical
            items(
                items = rutaSeleccionada.etapas.mapIndexed { idx, etapa -> idx to etapa },
                key = { (idx, etapa) -> "etapa_${idx}_${etapa.linea}_${etapa.origen.id}_${etapa.destino.id}" }
            ) { (idx, etapa) ->
                val esUltima = idx == rutaSeleccionada.etapas.lastIndex
                val transbordoPosterior = rutaSeleccionada.transbordosDetalle.firstOrNull {
                    it.parada.id == etapa.destino.id && it.bajarDe == etapa.linea
                }
                NodoTimelineEtapa(
                    etapa = etapa,
                    esPrimera = idx == 0,
                    esUltima = esUltima,
                    transbordo = transbordoPosterior
                )
            }
        }
    }
}

@Composable
fun TarjetaAlternativaCompacta(
    ruta: Ruta,
    seleccionada: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (seleccionada) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (seleccionada) {
        CardDefaults.outlinedCardBorder().copy(width = 2.dp)
    } else {
        CardDefaults.outlinedCardBorder()
    }

    OutlinedCard(
        onClick = onClick,
        border = border,
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        modifier = Modifier.width(235.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (ruta.lineas.isEmpty()) "A pie" else ruta.lineas.joinToString(" → "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${ruta.tiempoTotal.roundToInt()} min",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            BarraTiempoProporcional(etapas = ruta.etapas, modifier = Modifier.height(20.dp))

            Text(
                text = if (ruta.transbordos == 0) "Directo · ${ruta.distancia.roundToInt()} m"
                else "${ruta.transbordos} transbordo(s) · ${ruta.distancia.roundToInt()} m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dibuja un nodo vertical de la línea de tiempo con marcador circular, conector vertical
 * coloreado según la línea de colectivo y caja de instrucciones detalladas.
 */
@Composable
fun NodoTimelineEtapa(
    etapa: Etapa,
    esPrimera: Boolean,
    esUltima: Boolean,
    transbordo: Transbordo? = null
) {
    val colorLinea = colorParaLinea(etapa.linea)
    val esCaminata = etapa.linea == "CAMINANDO"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Columna izquierda: riel vertical de la línea de tiempo
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
        ) {
            // Punto de origen de la etapa
            Box(
                modifier = Modifier
                    .size(if (esPrimera) 16.dp else 12.dp)
                    .clip(CircleShape)
                    .background(colorLinea)
            )
            // Línea conectora vertical
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .weight(1f)
                    .background(colorLinea.copy(alpha = if (esCaminata) 0.45f else 0.85f))
            )
            // Punto final si es la última etapa del recorrido
            if (esUltima) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                )
            }
        }

        // Columna derecha: tarjeta con paradas, tiempos e indicaciones
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (esUltima) 0.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = colorLinea,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (esCaminata) "🚶 Caminata" else "🚌 Línea ${etapa.linea}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = "${etapa.tiempo.roundToInt()} min · ${etapa.distancia.roundToInt()} m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = if (esCaminata) {
                    "Desde: ${etapa.origen.nombre}"
                } else {
                    "Subir en: ${etapa.origen.nombre} (${etapa.origen.id})"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (!esCaminata && etapa.paradasIntermedias > 0) {
                Text(
                    text = "Recorrer ${etapa.paradasIntermedias} parada(s) hasta:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = if (esCaminata) {
                    "Hasta: ${etapa.destino.nombre} (${etapa.destino.id})"
                } else {
                    "Bajar en: ${etapa.destino.nombre} (${etapa.destino.id})"
                },
                style = MaterialTheme.typography.bodyMedium
            )

            // Instrucción explícita de transbordo cuando corresponde
            transbordo?.let { tb ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = "⇄ ${tb.instruccion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
