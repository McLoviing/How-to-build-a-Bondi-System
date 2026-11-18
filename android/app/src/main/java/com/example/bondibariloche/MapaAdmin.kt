package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.ParadaAdmin
import com.example.bondibariloche.data.TramoAdmin
import org.maplibre.android.geometry.LatLng

@Composable
fun DialogMapaAdmin(latitud: Double?, longitud: Double?, cerrar: () -> Unit,
                    elegir: (Double, Double) -> Unit) {
    var punto by remember { mutableStateOf<Pair<Double, Double>?>(
        if (latitud != null && longitud != null && latitud in -90.0..90.0 &&
            longitud in -180.0..180.0) latitud to longitud else null
    ) }
    val marcas = punto?.let { listOf(Parada("seleccion", "Ubicación elegida", it.first, it.second)) } ?: emptyList()
    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elegir ubicación", style = MaterialTheme.typography.headlineSmall)
                Text("Tocá el mapa para marcar la ubicación de la parada.")
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MapaMapLibre(marcas, null, {}, modifier = Modifier.fillMaxSize(),
                        onPuntoTocado = { a, b -> punto = a to b })
                }
                Text(punto?.let { "${it.first}, ${it.second}" } ?: "Sin ubicación seleccionada")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = cerrar) { Text("Cancelar") }
                    Button(onClick = { punto?.let { elegir(it.first, it.second) } },
                        enabled = punto != null) { Text("Usar ubicación") }
                }
            }
        }
    }
}

@Composable
fun DialogRecorridoAdmin(linea: String, paradas: List<ParadaAdmin>,
                         tramos: List<TramoAdmin>, cerrar: () -> Unit) {
    val porId = remember(paradas) { paradas.associate { p ->
        p.id to Parada(p.id, "${p.calle} - ${p.referencia}", p.latitud, p.longitud)
    } }
    val segmentos = remember(porId, tramos) { tramos.mapNotNull { t ->
        val a = porId[t.origen]
        val b = porId[t.destino]
        if (a != null && b != null) a to b else null
    } }
    val marcas = remember(segmentos) { segmentos.flatMap { listOf(it.first, it.second) }.distinctBy { it.id } }
    var seleccion by remember { mutableStateOf<Parada?>(null) }
    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mapa · Línea $linea", style = MaterialTheme.typography.headlineSmall)
                Text("${segmentos.size} tramos · conexiones rectas, no trazado vial")
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MapaMapLibre(marcas, null, { seleccion = it }, modifier = Modifier.fillMaxSize(), segmentosAdmin = segmentos)
                }
                Text(seleccion?.nombre ?: "Tocá una parada para consultar su nombre.")
                Button(onClick = cerrar) { Text("Volver") }
            }
        }
    }
}

/** Distancia geográfica estimada, no distancia real de circulación. */
fun distanciaAdmin(a: ParadaAdmin, b: ParadaAdmin): Double =
    LatLng(a.latitud, a.longitud).distanceTo(LatLng(b.latitud, b.longitud))
