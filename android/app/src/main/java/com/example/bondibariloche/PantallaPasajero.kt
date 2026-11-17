package com.example.bondibariloche

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.BondiApi
import com.example.bondibariloche.data.BuscadorRutasGps
import com.example.bondibariloche.data.Etapa
import com.example.bondibariloche.data.GestorUbicacion
import com.example.bondibariloche.data.IndiceEspacialParadas
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.ParadaCandidataGps
import com.example.bondibariloche.data.ResultadoUbicacion
import com.example.bondibariloche.data.Ruta
import com.example.bondibariloche.data.Transbordo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPasajero(
    paradas: List<Parada>,
    abrirAdmin: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded
        )
    )

    val indiceEspacial = remember(paradas) { IndiceEspacialParadas(paradas) }
    val indiceTextoParadas = remember(paradas) {
        paradas.map { p -> p to "${p.id} ${p.nombre}".normalizarBusqueda() }
    }

    // Configuración de usuario
    var mostrarConfiguracion by remember { mutableStateOf(false) }
    var radioCaminataMax by remember { mutableFloatStateOf(450f) }

    // Estados de formulario de búsqueda
    var textoOrigen by remember { mutableStateOf("") }
    var textoDestino by remember { mutableStateOf("") }
    var origenSeleccionado by remember { mutableStateOf<Parada?>(null) }
    var destinoSeleccionado by remember { mutableStateOf<Parada?>(null) }
    var campoActivo by remember { mutableStateOf<String?>(null) }

    var ubicacionGps by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var candidatasGps by remember { mutableStateOf<List<ParadaCandidataGps>>(emptyList()) }
    var usandoModoGps by remember { mutableStateOf(false) }

    // Estados de resultados y navegación entre búsqueda <-> detalle de ruta
    var mostrandoResultados by remember { mutableStateOf(false) }
    var rutas by remember { mutableStateOf<List<Ruta>>(emptyList()) }
    var rutaSeleccionada by remember { mutableStateOf<Ruta?>(null) }
    var buscandoRutas by remember { mutableStateOf(false) }
    var errorBusqueda by remember { mutableStateOf<String?>(null) }
    var busquedaJob by remember { mutableStateOf<Job?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current


    LaunchedEffect(Unit) {
        if (GestorUbicacion.tienePermisoUbicacion(context)) {
            val res = GestorUbicacion.obtenerUbicacionActual(context)
            if (res is ResultadoUbicacion.Exito) {
                ubicacionGps = res.latitud to res.longitud
            }
        }
    }

    fun volverABusqueda() {
        busquedaJob?.cancel()
        mostrandoResultados = false
        rutas = emptyList()
        rutaSeleccionada = null
        origenSeleccionado = null
        destinoSeleccionado = null
        textoOrigen = ""
        textoDestino = ""
        usandoModoGps = false
        candidatasGps = emptyList()
        campoActivo = null
        errorBusqueda = null
        scope.launch {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }

    // Interceptar el botón físico/gesto Atrás cuando se están viendo resultados de ruta
    BackHandler(enabled = mostrandoResultados) {
        volverABusqueda()
    }

    val sugerenciasOrigen by remember(indiceTextoParadas) {
        derivedStateOf {
            if (campoActivo != "origen") emptyList()
            else {
                val q = textoOrigen.normalizarBusqueda()
                if (q.isEmpty()) emptyList()
                else indiceTextoParadas.asSequence()
                    .filter { (_, clave) -> clave.contains(q) }
                    .map { (p, _) -> p }
                    .take(5)
                    .toList()
            }
        }
    }

    val sugerenciasDestino by remember(indiceTextoParadas) {
        derivedStateOf {
            if (campoActivo != "destino") emptyList()
            else {
                val q = textoDestino.normalizarBusqueda()
                if (q.isEmpty()) emptyList()
                else indiceTextoParadas.asSequence()
                    .filter { (_, clave) -> clave.contains(q) }
                    .map { (p, _) -> p }
                    .take(5)
                    .toList()
            }
        }
    }

    val paradasParaMapa = remember(
        paradas,
        rutaSeleccionada,
        ubicacionGps,
        origenSeleccionado,
        destinoSeleccionado,
        usandoModoGps
    ) {
        val rActiva = rutaSeleccionada
        if (rActiva != null) {
            // Modo Ruta Activa: recolectar ÚNICAMENTE los nodos involucrados en el recorrido
            val nodosRuta = LinkedHashMap<String, Parada>(rActiva.paradas.size + 4)

            val idOrigen = rActiva.etapas.firstOrNull()?.origen?.id ?: origenSeleccionado?.id
            val idDestino = rActiva.etapas.lastOrNull()?.destino?.id ?: destinoSeleccionado?.id
            val idsTransbordo = HashSet<String>(rActiva.transbordosDetalle.size * 2)
            for ((parada, paradaSubida) in rActiva.transbordosDetalle) {
                idsTransbordo.add(parada.id)
                paradaSubida?.id?.let { idsTransbordo.add(it) }
            }

            fun registrarNodo(p: Parada) {
                val etiqueta = when (p.id) {
                    "gps_origen" -> "🟢 TU UBICACIÓN (Inicio)"
                    idOrigen -> "🟢 SUBIDA: ${p.nombre}"
                    idDestino -> "🏁 DESTINO: ${p.nombre}"
                    in idsTransbordo -> "⇄ TRANSBORDO: ${p.nombre}"
                    else -> p.nombre
                }
                nodosRuta[p.id] = p.copy(nombre = etiqueta)
            }

            // 1. Incluir ubicación GPS actual si existe
            ubicacionGps?.let { (lat, lon) ->
                nodosRuta["gps_origen"] = Parada("gps_origen", "🟢 TU UBICACIÓN", lat, lon)
            }

            // 2. Incluir toda la secuencia de paradas del recorrido y extremos de etapas
            rActiva.paradas.forEach(::registrarNodo)
            rActiva.etapas.forEach { (_, origen, destino) ->
                registrarNodo(origen)
                registrarNodo(destino)
            }

            nodosRuta.values.toList()
        } else {
            // Modo Exploración / Búsqueda: mostrar las 526 paradas con Origen/Destino destacados
            val idOrigen = origenSeleccionado?.id
            val idDestino = destinoSeleccionado?.id

            val decoradas = paradas.mapTo(ArrayList(paradas.size + 1)) { p ->
                when {
                    (p.id == idOrigen) && (p.id == idDestino) ->
                        p.copy(nombre = "🟢🏁 ORIGEN Y DESTINO: ${p.nombre}")
                    p.id == idOrigen && !usandoModoGps ->
                        p.copy(nombre = "🟢 ORIGEN: ${p.nombre}")
                    p.id == idOrigen && usandoModoGps ->
                        p.copy(nombre = "🚌 SUBIDA: ${p.nombre}")
                    p.id == idDestino ->
                        p.copy(nombre = "🏁 DESTINO: ${p.nombre}")
                    else -> p
                }
            }

            ubicacionGps?.let { (lat, lon) ->
                decoradas.add(
                    Parada(
                        id = "gps_origen",
                        nombre = "🟢 TU UBICACIÓN (Origen)",
                        latitud = lat,
                        longitud = lon
                    )
                )
            }
            decoradas
        }
    }

    val previewOrigenDestino = remember(
        rutaSeleccionada,
        ubicacionGps,
        usandoModoGps,
        origenSeleccionado,
        destinoSeleccionado
    ) {
        if (rutaSeleccionada != null) {
            emptyList()
        } else {
            val puntoA = if (usandoModoGps && ubicacionGps != null) {
                Parada("gps_origen", "Tu ubicación", ubicacionGps!!.first, ubicacionGps!!.second)
            } else {
                origenSeleccionado
            }
            val puntoB = destinoSeleccionado
            if (puntoA != null && puntoB != null && puntoA.id != puntoB.id) {
                listOf(puntoA to puntoB)
            } else {
                emptyList()
            }
        }
    }

    fun ejecutarBusquedaRutas() {
        val dest = destinoSeleccionado ?: return
        val orig = origenSeleccionado
        if (!usandoModoGps && orig == null) return

        busquedaJob?.cancel()
        campoActivo = null
        buscandoRutas = true
        errorBusqueda = null

        busquedaJob = scope.launch {
            try {
                val resultados = if (usandoModoGps && ubicacionGps != null && candidatasGps.isNotEmpty()) {
                    val (lat, lon) = ubicacionGps!!
                    BuscadorRutasGps.buscarDesdeUbicacion(
                        latUsuario = lat,
                        lonUsuario = lon,
                        candidatas = candidatasGps,
                        destino = dest
                    )
                } else if (orig != null) {
                    BondiApi.obtenerRutas(orig.id, dest.id)
                } else {
                    emptyList()
                }

                rutas = resultados
                rutaSeleccionada = resultados.firstOrNull()
                if (resultados.isEmpty()) {
                    errorBusqueda = "No se encontraron recorridos disponibles entre estos puntos."
                } else {
                    mostrandoResultados = true
                    scaffoldState.bottomSheetState.expand()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBusqueda = e.message ?: "No se pudieron calcular las rutas"
            } finally {
                buscandoRutas = false
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (mostrandoResultados) 180.dp else 270.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        sheetContent = {
            if (mostrandoResultados && rutas.isNotEmpty()) {
                // =========================================================================
                // VISTA 2: RESULTADOS, ALTERNATIVAS Y LÍNEA DE TIEMPO COMPLETA
                // =========================================================================
                VistaResultadosYTimeline(
                    rutas = rutas,
                    rutaSeleccionada = rutaSeleccionada ?: rutas.first(),
                    nombreOrigen = if (usandoModoGps) "Tu ubicación GPS" else (origenSeleccionado?.nombre ?: ""),
                    nombreDestino = destinoSeleccionado?.nombre ?: "",
                    onSeleccionarRuta = { rutaSeleccionada = it },
                    onVolverABuscar = { volverABusqueda() }
                )
            } else {
                // =========================================================================
                // VISTA 1: FORMULARIO DE BÚSQUEDA Y GEOLOCALIZACIÓN
                // =========================================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "¿A dónde vas en Bariloche?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        // Acceso discreto a Configuración (donde se esconde el modo Admin)
                        IconButton(onClick = { mostrarConfiguracion = true }) {
                            Text("⚙", style = MaterialTheme.typography.titleLarge)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = textoOrigen,
                            onValueChange = {
                                textoOrigen = it
                                origenSeleccionado = null
                                usandoModoGps = false
                                candidatasGps = emptyList()
                                campoActivo = "origen"
                            },
                            label = { Text("Origen (parada o GPS)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalIconButton(
                            onClick = {
                                val tempParada = origenSeleccionado
                                val tempTexto = textoOrigen
                                origenSeleccionado = destinoSeleccionado
                                textoOrigen = textoDestino
                                destinoSeleccionado = tempParada
                                textoDestino = tempTexto
                                usandoModoGps = false
                                candidatasGps = emptyList()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (origenSeleccionado != null && destinoSeleccionado != null) {
                                    ejecutarBusquedaRutas()
                                }
                            },
                            enabled = origenSeleccionado != null || destinoSeleccionado != null
                        ) {
                            Text("⇅", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (sugerenciasOrigen.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                sugerenciasOrigen.forEach { p ->
                                    Text(
                                        text = "${p.id} · ${p.nombre}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                origenSeleccionado = p
                                                textoOrigen = p.nombre
                                                usandoModoGps = false
                                                candidatasGps = emptyList()
                                                campoActivo = null
                                                if (destinoSeleccionado != null) ejecutarBusquedaRutas()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    BotonUbicacionActual(
                        indiceEspacial = indiceEspacial,
                        radioMaximoMetros = radioCaminataMax.toDouble(),
                        onUbicacionResuelta = { lat, lon, candidatas ->
                            ubicacionGps = lat to lon
                            candidatasGps = candidatas
                            usandoModoGps = true
                            val principal = candidatas.first()
                            origenSeleccionado = principal.parada
                            textoOrigen = "📍 Mi ubicación (${principal.parada.nombre})"
                            campoActivo = null
                            if (destinoSeleccionado != null) {
                                ejecutarBusquedaRutas()
                            }
                        }
                    )

                    if (usandoModoGps && candidatasGps.size > 1) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = candidatasGps,
                                key = { it.parada.id },
                                contentType = { "chip_gps" }
                            ) { cand ->
                                FilterChip(
                                    selected = origenSeleccionado?.id == cand.parada.id,
                                    onClick = {
                                        origenSeleccionado = cand.parada
                                        textoOrigen = "📍 Mi ubicación (${cand.parada.nombre})"
                                        usandoModoGps = false
                                        if (destinoSeleccionado != null) ejecutarBusquedaRutas()
                                    },
                                    elevation = null,
                                    label = {
                                        Text(
                                            "${cand.parada.nombre} · ${cand.minutosCaminata}m a pie",
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = textoDestino,
                        onValueChange = {
                            textoDestino = it
                            destinoSeleccionado = null
                            campoActivo = "destino"
                        },
                        label = { Text("Destino") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (sugerenciasDestino.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                sugerenciasDestino.forEach { p ->
                                    Text(
                                        text = "${p.id} · ${p.nombre}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                destinoSeleccionado = p
                                                textoDestino = p.nombre
                                                campoActivo = null
                                                if (origenSeleccionado != null || usandoModoGps) {
                                                    ejecutarBusquedaRutas()
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { ejecutarBusquedaRutas() },
                        enabled = !buscandoRutas && destinoSeleccionado != null && (origenSeleccionado != null || usandoModoGps),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (buscandoRutas) "Calculando mejores recorridos..." else "Buscar rutas")
                    }

                    if (buscandoRutas) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    errorBusqueda?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MapaMapLibre(
                paradasParaMapa,
                rutaSeleccionada,
                { paradaTocada ->
                    if (paradaTocada.id == "gps_origen") return@MapaMapLibre
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (mostrandoResultados) volverABusqueda()

                    if (origenSeleccionado == null && !usandoModoGps) {
                        origenSeleccionado = paradaTocada
                        textoOrigen = paradaTocada.nombre
                        if (destinoSeleccionado != null) ejecutarBusquedaRutas()
                    } else {
                        destinoSeleccionado = paradaTocada
                        textoDestino = paradaTocada.nombre
                        ejecutarBusquedaRutas()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onPuntoTocado = { latTocada, lonTocada ->
                    // Buscar hasta 3 paradas cercanas en O(1) dentro de un radio peatonal de 500 m
                    val cercanas = indiceEspacial.buscarCandidatasGps(
                        lat = latTocada,
                        lon = lonTocada,
                        radioMetros = 500.0,
                        limite = 3
                    )

                    if (cercanas.isEmpty()) {
                        errorBusqueda = "No hay paradas a menos de 500 m del punto tocado en el mapa."
                        return@MapaMapLibre
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (mostrandoResultados) volverABusqueda()

                    val principal = cercanas.first()
                    if (origenSeleccionado == null && !usandoModoGps) {
                        // Fijar el punto tocado como origen peatonal con caminata hacia las paradas cercanas
                        ubicacionGps = latTocada to lonTocada
                        candidatasGps = cercanas
                        usandoModoGps = true
                        origenSeleccionado = principal.parada
                        textoOrigen = "📍 Punto en mapa (${principal.parada.nombre})"
                        if (destinoSeleccionado != null) {
                            ejecutarBusquedaRutas()
                        }
                    } else {
                        // Fijar la parada más cercana al punto tocado como destino y calcular rutas
                        destinoSeleccionado = principal.parada
                        textoDestino = principal.parada.nombre
                        ejecutarBusquedaRutas()
                    }
                },
                segmentosAdmin = previewOrigenDestino
            )
        }
    }

    if (mostrarConfiguracion) {
        DialogConfiguracionPasajero(
            totalParadas = paradas.size,
            radioCaminataActual = radioCaminataMax,
            onCambiarRadio = { radioCaminataMax = it },
            onDesbloquearAdmin = {
                mostrarConfiguracion = false
                abrirAdmin()
            },
            cerrar = { mostrarConfiguracion = false }
        )
    }
}

// =============================================================================
// VISTA DE RESULTADOS: ALTERNATIVAS + LÍNEA DE TIEMPO VERTICAL PASO A PASO
// =============================================================================

@Composable
private fun VistaResultadosYTimeline(
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
private fun TarjetaAlternativaCompacta(
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
private fun NodoTimelineEtapa(
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

// =============================================================================
// SECCIÓN DE CONFIGURACIÓN CON ACCESO OCULTO A MODO ADMINISTRADOR (5 TOQUES)
// =============================================================================

@Composable
private fun DialogConfiguracionPasajero(
    totalParadas: Int,
    radioCaminataActual: Float,
    onCambiarRadio: (Float) -> Unit,
    onDesbloquearAdmin: () -> Unit,
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
                        text = "Define hasta qué distancia buscar paradas cercanas al usar tu ubicación actual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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