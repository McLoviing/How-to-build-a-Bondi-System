package com.example.bondibariloche

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Diálogo de configuración de preferencias de viaje para el pasajero
 * (radios de caminata) e ingreso oculto a modo Administrador (5 toques rápidos).
 */
@Composable
fun DialogConfiguracionPasajero(
    totalParadas: Int,
    radioCaminataActual: Float,
    onCambiarRadio: (Float) -> Unit,
    distanciaCaminataActual: Float,
    onCambiarDistanciaCaminata: (Float) -> Unit,
    onDesbloquearAdmin: () -> Unit,
    onConfigurarServidor: () -> Unit,
    cerrar: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var toquesConsecutivos by remember { mutableIntStateOf(0) }
    var ultimoToqueMs by remember { mutableLongStateOf(0L) }

    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("Configuración") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Slider 1: Radio de caminata desde GPS hacia la parada inicial
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Radio máximo de caminata desde GPS: ${radioCaminataActual.roundToInt()} m",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = radioCaminataActual,
                        onValueChange = onCambiarRadio,
                        valueRange = 250f..750f,
                        steps = 9
                    )
                    Text(
                        text = "Define hasta qué distancia buscar paradas de subida al usar tu ubicación actual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Slider 2: Distancia máxima de caminata para transbordos y destino
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Distancia máxima de caminata: ${distanciaCaminataActual.roundToInt()} m",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = distanciaCaminataActual,
                        onValueChange = onCambiarDistanciaCaminata,
                        valueRange = 100f..800f,
                        steps = 13
                    )
                    Text(
                        text = "Límite máximo a pie para combinar líneas (transbordos) y para llegar a la parada de destino.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OutlinedButton(onClick = onConfigurarServidor, modifier = Modifier.fillMaxWidth()) {
                    Text("Configurar servidor")
                }

                // Tarjeta de información con gesto oculto de 5 toques en < 2000 ms para abrir Admin
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val ahora = SystemClock.elapsedRealtime()
                            toquesConsecutivos = if (ahora - ultimoToqueMs <= 2_000L) {
                                toquesConsecutivos + 1
                            } else {
                                1
                            }
                            ultimoToqueMs = ahora

                            if (toquesConsecutivos >= 5) {
                                toquesConsecutivos = 0
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDesbloquearAdmin()
                            } else if (toquesConsecutivos >= 3) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Bondi Bariloche · Red Urbana",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$totalParadas paradas activas · Motor lógico SWI-Prolog",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (toquesConsecutivos in 3..4) {
                            Text(
                                text = "Faltan ${5 - toquesConsecutivos} toques para modo gestión...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = cerrar) { Text("Cerrar") }
        }
    )
}
