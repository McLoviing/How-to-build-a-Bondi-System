package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.bondibariloche.data.CalculosGeograficos
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.ParadaAdmin
import com.example.bondibariloche.data.TramoAdmin
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

@Composable
fun DialogMapaAdmin(
    latitud: Double?,
    longitud: Double?,
    paradasExistentes: List<ParadaAdmin> = emptyList(),
    cerrar: () -> Unit,
    elegir: (Double, Double) -> Unit
) {
    var punto by remember {
        mutableStateOf<Pair<Double, Double>?>(
            if (latitud != null && longitud != null && latitud in -90.0..90.0 &&
                longitud in -180.0..180.0
            ) latitud to longitud else null
        )
    }

    // Mostrar TODAS las paradas existentes siempre + el marcador de la nueva ubicación elegida
    val todasLasExistentes = remember(paradasExistentes) {
        paradasExistentes.map { ex ->
            val titulo = if (ex.referencia.isBlank()) ex.calle else "${ex.calle} - ${ex.referencia}"
            Parada(ex.id, "${ex.id} · $titulo", ex.latitud, ex.longitud)
        }
    }

    val marcas = remember(punto, todasLasExistentes) {
        val p = punto
        if (p == null) {
            todasLasExistentes
        } else {
            val lista = ArrayList<Parada>(todasLasExistentes.size + 1)
            lista.addAll(todasLasExistentes)
            lista.add(Parada("seleccion", "📍 Ubicación elegida", p.first, p.second))
            lista
        }
    }

    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elegir ubicación", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Tocá el mapa para ubicar la parada (se muestran las ${paradasExistentes.size} paradas registradas).",
                    style = MaterialTheme.typography.bodySmall
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MapaMapLibre(
                        marcas,
                        null,
                        { paradaTocada ->
                            if (paradaTocada.id != "seleccion") {
                                punto = paradaTocada.latitud to paradaTocada.longitud
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onPuntoTocado = { a, b -> punto = a to b }
                    )
                }
                Text(
                    punto?.let { "Coordenadas: ${"%.5f".format(Locale.US, it.first)}, ${"%.5f".format(Locale.US, it.second)}" }
                        ?: "Sin ubicación seleccionada"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = cerrar) { Text("Cancelar") }
                    Button(
                        onClick = { punto?.let { elegir(it.first, it.second) } },
                        enabled = punto != null
                    ) { Text("Usar ubicación") }
                }
            }
        }
    }
}

@Composable
fun DialogRecorridoAdmin(
    linea: String,
    paradas: List<ParadaAdmin>,
    tramos: List<TramoAdmin>,
    cerrar: () -> Unit
) {
    val porId = remember(paradas) {
        paradas.associate { p ->
            val titulo = if (p.referencia.isBlank()) p.calle else "${p.calle} - ${p.referencia}"
            p.id to Parada(p.id, "${p.id} · $titulo", p.latitud, p.longitud)
        }
    }

    val segmentos = remember(porId, tramos) {
        tramos.mapNotNull { t ->
            val a = porId[t.origen]
            val b = porId[t.destino]
            if (a != null && b != null) a to b else null
        }
    }

    // Subgrafo inducido V(E_L): únicamente las paradas que pertenecen al recorrido de esta línea
    val paradasDeLaLinea = remember(segmentos) {
        val inicioId = segmentos.firstOrNull()?.first?.id
        val finId = segmentos.lastOrNull()?.second?.id
        val unicas = LinkedHashMap<String, Parada>(segmentos.size + 2)

        for ((a, b) in segmentos) {
            if (a.id !in unicas) {
                unicas[a.id] = if (a.id == inicioId) a.copy(nombre = "🟢 INICIO: ${a.nombre}") else a
            }
            if (b.id !in unicas) {
                unicas[b.id] = if (b.id == finId && finId != inicioId) b.copy(nombre = "🏁 FIN: ${b.nombre}") else b
            }
        }
        unicas.values.toList()
    }

    var seleccion by remember { mutableStateOf<Parada?>(null) }

    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mapa · Línea $linea", style = MaterialTheme.typography.headlineSmall)
                Text("${segmentos.size} tramos · ${paradasDeLaLinea.size} paradas en este recorrido")
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MapaMapLibre(
                        paradasDeLaLinea,
                        null,
                        { seleccion = it },
                        modifier = Modifier.fillMaxSize(),
                        onPuntoTocado = null,
                        segmentosAdmin = segmentos
                    )
                }
                Text(seleccion?.nombre ?: "Tocá una parada de la línea para consultar su nombre.")
                Button(onClick = cerrar) { Text("Volver") }
            }
        }
    }
}

/**
 * Constructor visual interactivo (Tap-to-Chain) para trazar recorridos dirigidos sobre el mapa.
 * Muestra TODAS las paradas registradas en todo momento y evita crear puntos azules al tocar.
 */
@Composable
fun DialogCargaVisualRecorrido(
    linea: String,
    paradas: List<ParadaAdmin>,
    tramos: List<TramoAdmin>,
    ocupado: Boolean,
    onAgregarTramoVisual: (TramoAdmin) -> Unit,
    onDeshacerUltimoTramo: (TramoAdmin) -> Unit,
    cerrar: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    val adminPorId = remember(paradas) { paradas.associateBy { it.id } }
    val todasLasParadas = remember(paradas) {
        paradas.map { p ->
            val titulo = if (p.referencia.isBlank()) p.calle else "${p.calle} - ${p.referencia}"
            Parada(p.id, "${p.id} · $titulo", p.latitud, p.longitud)
        }
    }
    val paradaMapaPorId = remember(todasLasParadas) {
        todasLasParadas.associateBy { it.id }
    }

    var nodoActivoId by remember(linea) {
        mutableStateOf(tramos.lastOrNull()?.destino)
    }
    var busquedaSalto by remember { mutableStateOf("") }
    var avisoVisual by remember { mutableStateOf("") }

    val indiceBusqueda = remember(paradas) {
        paradas.map { p -> p to "${p.id} ${p.calle} ${p.referencia}".normalizarBusqueda() }
    }
    val sugerenciasSalto by remember(indiceBusqueda) {
        derivedStateOf {
            val q = busquedaSalto.normalizarBusqueda()
            if (q.isEmpty()) emptyList()
            else indiceBusqueda.asSequence()
                .filter { (_, clave) -> clave.contains(q) }
                .map { (p, _) -> p }
                .take(3)
                .toList()
        }
    }

    val segmentos = remember(paradaMapaPorId, tramos) {
        tramos.mapNotNull { t ->
            val a = paradaMapaPorId[t.origen]
            val b = paradaMapaPorId[t.destino]
            if (a != null && b != null) a to b else null
        }
    }

    // Mostrar TODAS las paradas siempre, marcando el nodo activo en su etiqueta
    val marcasVisibles = remember(todasLasParadas, nodoActivoId) {
        val activo = nodoActivoId
        if (activo == null) {
            todasLasParadas
        } else {
            todasLasParadas.map { p ->
                if (p.id == activo) p.copy(nombre = "🚩 ACTIVA: ${p.nombre}") else p
            }
        }
    }

    fun procesarParadaSeleccionada(destinoId: String) {
        if (ocupado) return
        val origenActual = nodoActivoId
        if (origenActual == null) {
            nodoActivoId = destinoId
            avisoVisual = "Origen fijado en $destinoId. Tocá la siguiente parada."
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }
        if (origenActual == destinoId) {
            avisoVisual = "Ya estás posicionado en $destinoId. Tocá la parada siguiente."
            return
        }
        if (tramos.any { it.origen == origenActual && it.destino == destinoId }) {
            nodoActivoId = destinoId
            avisoVisual = "El tramo $origenActual → $destinoId ya existe. Cursor movido a $destinoId."
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }

        val pOrig = adminPorId[origenActual] ?: return
        val pDest = adminPorId[destinoId] ?: return

        val distRecta = CalculosGeograficos.distanciaRapidaMetros(pOrig.latitud, pOrig.longitud, pDest.latitud, pDest.longitud)
        val distVial = max(10.0, round(distRecta * 1.25))
        val tiempoEst = round(max(0.5, (distVial / 366.67) + 0.35) * 10.0) / 10.0

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        nodoActivoId = destinoId
        avisoVisual = if (distVial > 4500.0) {
            "Aviso: tramo largo (${distVial.toInt()} m). Verificá si faltó una parada intermedia."
        } else {
            "Encadenado: $origenActual → $destinoId (${distVial.toInt()} m · $tiempoEst min)"
        }

        onAgregarTramoVisual(
            TramoAdmin(
                origen = origenActual,
                destino = destinoId,
                distancia = distVial,
                tiempo = tiempoEst
            )
        )
    }

    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Carga visual · Línea $linea", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = nodoActivoId?.let { "Origen activo: $it · Tocá la parada destino" }
                                ?: "Tocá la parada de inicio en el mapa (${paradas.size} paradas visibles)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = cerrar, enabled = !ocupado) { Text("Listo") }
                }

                OutlinedTextField(
                    value = busquedaSalto,
                    onValueChange = { busquedaSalto = it },
                    label = { Text("Saltar o conectar por calle / ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (sugerenciasSalto.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sugerenciasSalto.forEach { p ->
                            AssistChip(
                                onClick = {
                                    busquedaSalto = ""
                                    procesarParadaSeleccionada(p.id)
                                },
                                elevation = null,
                                label = { Text("${p.id} · ${p.calle}", maxLines = 1) }
                            )
                        }
                    }
                }

                if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MapaMapLibre(
                        marcasVisibles,
                        null,
                        { paradaTocada ->
                            procesarParadaSeleccionada(paradaTocada.id)
                        },
                        modifier = Modifier.fillMaxSize(),
                        onPuntoTocado = null, // Evita que MapLibre dibuje puntos azules al tocar fuera de un marcador
                        segmentosAdmin = segmentos
                    )
                }

                if (avisoVisual.isNotBlank()) {
                    Text(
                        text = avisoVisual,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            nodoActivoId = null
                            avisoVisual = "Cursor reiniciado. Tocá una nueva parada de inicio."
                        },
                        enabled = !ocupado && nodoActivoId != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Nuevo inicio") }

                    val ultimoTramo = tramos.lastOrNull()
                    OutlinedButton(
                        onClick = {
                            if (ultimoTramo != null) {
                                nodoActivoId = ultimoTramo.origen
                                avisoVisual = "Revertido ${ultimoTramo.origen} → ${ultimoTramo.destino}"
                                onDeshacerUltimoTramo(ultimoTramo)
                            }
                        },
                        enabled = !ocupado && ultimoTramo != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("↩ Quitar último") }
                }
            }
        }
    }
}

/** Distancia geográfica estimada en metros entre dos paradas. */
fun distanciaAdmin(a: ParadaAdmin, b: ParadaAdmin): Double =
    LatLng(a.latitud, a.longitud).distanceTo(LatLng(b.latitud, b.longitud))