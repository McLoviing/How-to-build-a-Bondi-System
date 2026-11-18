package com.example.bondibariloche

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.GestorUbicacion
import com.example.bondibariloche.data.IndiceEspacialParadas
import com.example.bondibariloche.data.ParadaCandidataGps
import com.example.bondibariloche.data.ResultadoUbicacion
import kotlinx.coroutines.launch

/** Ícono vectorial de mira GPS dibujado en Canvas (cero dependencias externas). */
@Composable
fun IconoMiraGps(modifier: Modifier = Modifier.size(18.dp)) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val rExt = size.minDimension * 0.34f
        val rInt = size.minDimension * 0.12f
        val trazo = size.minDimension * 0.09f

        drawCircle(color = color, radius = rExt, center = c, style = Stroke(width = trazo))
        drawCircle(color = color, radius = rInt, center = c)

        val margen = size.minDimension * 0.06f
        drawLine(color, Offset(c.x, margen), Offset(c.x, c.y - rExt), trazo, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y + rExt), Offset(c.x, size.height - margen), trazo, StrokeCap.Round)
        drawLine(color, Offset(margen, c.y), Offset(c.x - rExt, c.y), trazo, StrokeCap.Round)
        drawLine(color, Offset(c.x + rExt, c.y), Offset(size.width - margen, c.y), trazo, StrokeCap.Round)
    }
}

/**
 * Botón completo para capturar la ubicación GPS del usuario, validar permisos,
 * centrar el mapa y devolver las paradas cercanas ordenadas por tiempo a pie.
 */
@Composable
fun BotonUbicacionActual(
    indiceEspacial: IndiceEspacialParadas,
    modifier: Modifier = Modifier,
    radioMaximoMetros: Double = 450.0,
    onUbicacionResuelta: (
        lat: Double,
        lon: Double,
        candidatas: List<ParadaCandidataGps>
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var buscando by remember { mutableStateOf(false) }
    var mensajeError by remember { mutableStateOf<String?>(null) }
    var mostrarDialogoGpsApagado by remember { mutableStateOf(false) }

    fun iniciarCapturaGps() {
        if (buscando) return
        buscando = true
        mensajeError = null

        scope.launch {
            try {
                when (val res = GestorUbicacion.obtenerUbicacionActual(context)) {
                    is ResultadoUbicacion.Exito -> {
                        val cercanas = indiceEspacial.buscarCandidatasGps(
                            lat = res.latitud,
                            lon = res.longitud,
                            radioMetros = radioMaximoMetros,
                            limite = 3
                        )

                        if (cercanas.isEmpty()) {
                            mensajeError = "No hay paradas registradas a menos de ${radioMaximoMetros.toInt()} m de tu ubicación."
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUbicacionResuelta(res.latitud, res.longitud, cercanas)
                        }
                    }

                    ResultadoUbicacion.SinPermiso -> {
                        mensajeError = "Necesitamos permiso de ubicación para encontrar tu parada más cercana."
                    }

                    ResultadoUbicacion.GpsDesactivado -> {
                        mostrarDialogoGpsApagado = true
                    }

                    is ResultadoUbicacion.FueraDeZona -> {
                        mensajeError = "Tu ubicación GPS está fuera del área urbana de Bariloche."
                    }

                    is ResultadoUbicacion.Error -> {
                        mensajeError = res.mensaje
                    }
                }
            } finally {
                buscando = false
            }
        }
    }

    val launcherPermisos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val concedido = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permisos[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            iniciarCapturaGps()
        } else {
            mensajeError = "Permiso de ubicación denegado. Podés elegir tu parada manualmente."
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(
            onClick = {
                if (GestorUbicacion.tienePermisoUbicacion(context)) {
                    iniciarCapturaGps()
                } else {
                    launcherPermisos.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            },
            enabled = !buscando,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (buscando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Obteniendo ubicación GPS...")
                } else {
                    IconoMiraGps()
                    Text("Usar mi ubicación actual")
                }
            }
        }

        mensajeError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (mostrarDialogoGpsApagado) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoGpsApagado = false },
            title = { Text("Ubicación desactivada") },
            text = { Text("Activá el GPS del teléfono para detectar las paradas cercanas a tu posición.") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarDialogoGpsApagado = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text("Abrir ajustes") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogoGpsApagado = false }) { Text("Cancelar") }
            }
        )
    }
}