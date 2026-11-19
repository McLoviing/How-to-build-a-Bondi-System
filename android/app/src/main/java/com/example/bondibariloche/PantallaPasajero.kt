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
    abrirAdmin: () -> Unit,
    configurarServidor: () -> Unit
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
    var distanciaCaminataMax by remember { mutableFloatStateOf(400f) }

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
                        destino = dest,
                        maxCaminata = distanciaCaminataMax
                    )
                } else if (orig != null) {
                    BondiApi.obtenerRutas(
                        origenId = orig.id,
                        destinoId = dest.id,
                        maxCaminata = distanciaCaminataMax
                    )
                } else {
                    emptyList()
                }

                // Filtrar para respetar estrictamente el límite de caminata en transbordos y llegada a destino
                val resultadosFiltrados = resultados.filter { r ->
                    val transbordoValido = r.transbordosDetalle.all { tb ->
                        !tb.requiereCaminata || tb.metrosCaminata <= (distanciaCaminataMax + 1.0)
                    }
                    val destinoValido = r.distanciaCaminataFinal <= (distanciaCaminataMax + 1.0)
                    val etapasValidas = r.etapas.all { etapa ->
                        !etapa.esCaminata || etapa == r.etapas.firstOrNull() || etapa.distancia <= (distanciaCaminataMax + 15.0)
                    }
                    transbordoValido && destinoValido && etapasValidas
                }

                rutas = resultadosFiltrados
                rutaSeleccionada = resultadosFiltrados.firstOrNull()
                if (resultadosFiltrados.isEmpty()) {
                    errorBusqueda = if (resultados.isNotEmpty()) {
                        "Se encontraron rutas pero requieren caminatas mayores a ${distanciaCaminataMax.roundToInt()} m. Podés aumentar la distancia en Configuración."
                    } else {
                        "No se encontraron recorridos disponibles entre estos puntos."
                    }
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
                    val esOrigen = (origenSeleccionado == null && !usandoModoGps)
                    val radioBusqueda = if (esOrigen) radioCaminataMax.toDouble() else distanciaCaminataMax.toDouble()

                    // Buscar hasta 3 paradas cercanas en O(1) dentro del radio peatonal correspondiente
                    val cercanas = indiceEspacial.buscarCandidatasGps(
                        lat = latTocada,
                        lon = lonTocada,
                        radioMetros = radioBusqueda,
                        limite = 3
                    )

                    if (cercanas.isEmpty()) {
                        errorBusqueda = if (esOrigen) {
                            "No hay paradas a menos de ${radioCaminataMax.roundToInt()} m de tu punto de partida en el mapa."
                        } else {
                            "No hay paradas a menos de ${distanciaCaminataMax.roundToInt()} m del destino seleccionado en el mapa."
                        }
                        return@MapaMapLibre
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (mostrandoResultados) volverABusqueda()

                    val principal = cercanas.first()
                    if (esOrigen) {
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
            distanciaCaminataActual = distanciaCaminataMax,
            onCambiarDistanciaCaminata = { distanciaCaminataMax = it },
            onDesbloquearAdmin = {
                mostrarConfiguracion = false
                abrirAdmin()
            },
            onConfigurarServidor = {
                mostrarConfiguracion = false
                configurarServidor()
            },
            cerrar = {
                mostrarConfiguracion = false
                if (origenSeleccionado != null && destinoSeleccionado != null) {
                    ejecutarBusquedaRutas()
                }
            }
        )
    }
}
