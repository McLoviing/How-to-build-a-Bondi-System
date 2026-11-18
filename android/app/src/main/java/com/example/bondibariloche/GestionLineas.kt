package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.AdminApi
import com.example.bondibariloche.data.ParadaAdmin
import com.example.bondibariloche.data.TramoAdmin
import java.util.Locale

// Modifiers estáticos hoistados para evitar asignaciones de memoria durante el scroll
private val MODIFIER_CARD_ITEM = Modifier.fillMaxWidth()
private val MODIFIER_CARD_PADDING = Modifier.padding(12.dp)

private fun ordenarTramosTopologicamente(tramos: List<TramoAdmin>): List<TramoAdmin> {
    if (tramos.size <= 1) return tramos

    val porOrigen = HashMap<String, ArrayDeque<TramoAdmin>>(tramos.size)
    val destinos = HashSet<String>(tramos.size)
    for (t in tramos) {
        porOrigen.getOrPut(t.origen) { ArrayDeque() }.addLast(t)
        destinos.add(t.destino)
    }

    val ordenados = ArrayList<TramoAdmin>(tramos.size)
    val inicios = tramos.asSequence().map { it.origen }.filter { it !in destinos }.distinct().toList()

    fun drenarCadena(nodoInicial: String) {
        var actual: String? = nodoInicial
        while (actual != null) {
            val siguienteTramo = porOrigen[actual]?.removeFirstOrNull()
            if (siguienteTramo != null) {
                ordenados.add(siguienteTramo)
                actual = siguienteTramo.destino
            } else {
                actual = null
            }
        }
    }

    for (inicio in inicios) drenarCadena(inicio)
    for (t in tramos) drenarCadena(t.origen)
    return ordenados
}

/**
 * Chip virtualizado de línea con elevation = null (sin RenderNode shadow pass)
 * y parámetros primitivos para Smart Recomposition Skipping a 60/120 FPS.
 */
@Composable
private fun ChipLineaItem(
    numeroLinea: String,
    seleccionada: Boolean,
    habilitado: Boolean,
    onSeleccionar: (String) -> Unit
) {
    FilterChip(
        selected = seleccionada,
        onClick = { onSeleccionar(numeroLinea) },
        enabled = habilitado,
        elevation = null,
        label = { Text("Línea $numeroLinea", maxLines = 1) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaLineaItem(
    numeroLinea: String,
    habilitado: Boolean,
    onAbrirRecorrido: (String) -> Unit,
    onAbrirCargaVisual: (String) -> Unit,
    onRenombrar: (String) -> Unit,
    onEliminar: (String) -> Unit
) {
    OutlinedCard(MODIFIER_CARD_ITEM) {
        Column(MODIFIER_CARD_PADDING, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Línea $numeroLinea", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                TextButton(
                    onClick = { onAbrirRecorrido(numeroLinea) },
                    enabled = habilitado,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Recorrido")
                }
                TextButton(
                    onClick = { onAbrirCargaVisual(numeroLinea) },
                    enabled = habilitado,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Trazar en mapa")
                }
                TextButton(
                    onClick = { onRenombrar(numeroLinea) },
                    enabled = habilitado,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Renombrar")
                }
                TextButton(
                    onClick = { onEliminar(numeroLinea) },
                    enabled = habilitado,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Eliminar")
                }
            }
        }
    }
}

@Composable
private fun TarjetaTramoItem(
    tramo: TramoAdmin,
    nombreOrigen: String,
    nombreDestino: String,
    habilitado: Boolean,
    onEditar: (TramoAdmin) -> Unit,
    onEliminar: (TramoAdmin) -> Unit
) {
    OutlinedCard(MODIFIER_CARD_ITEM) {
        Column(MODIFIER_CARD_PADDING) {
            Text("${tramo.origen} · $nombreOrigen", fontWeight = FontWeight.SemiBold)
            Text("↓ ${tramo.destino} · $nombreDestino")
            Text(
                "${tramo.distancia} m · ${tramo.tiempo} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = { onEditar(tramo) }, enabled = habilitado) { Text("Editar") }
                TextButton(onClick = { onEliminar(tramo) }, enabled = habilitado) { Text("Eliminar") }
            }
        }
    }
}

@Composable
fun GestionLineas(token: String, paradas: List<ParadaAdmin>, volver: () -> Unit) {
    val op = rememberEstadoOperacionAdmin()

    var lineas by remember { mutableStateOf(emptyList<String>()) }
    var tramos by remember { mutableStateOf(emptyList<TramoAdmin>()) }
    var linea by remember { mutableStateOf<String?>(null) }

    var dialogoLinea by remember { mutableStateOf(false) }
    var anterior by remember { mutableStateOf<String?>(null) }
    var numero by remember { mutableStateOf("") }

    var dialogoTramo by remember { mutableStateOf(false) }
    var editado by remember { mutableStateOf<TramoAdmin?>(null) }
    var origenEncadenado by remember { mutableStateOf("") }

    var confirmar by remember { mutableStateOf<(() -> Unit)?>(null) }
    var filtro by remember { mutableStateOf("") }
    var verMapa by remember { mutableStateOf(false) }
    var cargaVisualMapa by remember { mutableStateOf(false) }

    val nombres = remember(paradas) {
        paradas.associate {
            it.id to (if (it.referencia.isBlank()) it.calle else "${it.calle} - ${it.referencia}")
        }
    }

    val indiceSelectorParadas = remember(paradas) {
        paradas.map { p ->
            val clave = "${p.id} ${p.calle} ${p.referencia}".normalizarBusqueda()
            p to clave
        }
    }

    val deshacerLineaOTramo: suspend () -> Unit = {
        AdminApi.rollback(token)
        lineas = AdminApi.lineas(token)
        val linActiva = linea
        if (linActiva != null) {
            if (linActiva in lineas) {
                tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, linActiva))
            } else {
                linea = null
                tramos = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        op.ejecutar { lineas = AdminApi.lineas(token) }
    }

    val seleccion = linea
    val habilitado = !op.ocupado

    val indiceTramos = remember(tramos, nombres) {
        tramos.map { t ->
            val clave = "${t.origen} ${t.destino} ${nombres[t.origen].orEmpty()} ${nombres[t.destino].orEmpty()}"
                .normalizarBusqueda()
            t to clave
        }
    }

    val tramosVisibles by remember(indiceTramos) {
        derivedStateOf {
            val q = filtro.normalizarBusqueda()
            if (q.isEmpty()) tramos
            else indiceTramos.mapNotNull { (t, clave) -> if (clave.contains(q)) t else null }
        }
    }

    val seleccionarLineaCallback = remember(token) {
        { target: String ->
            op.ejecutar {
                tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, target))
                linea = target
                filtro = ""
            }
        }
    }

    val abrirCargaVisualCallback = remember(token) {
        { target: String ->
            op.ejecutar {
                tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, target))
                linea = target
                cargaVisualMapa = true
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(op.snackbarHostState) }
    ) { padding ->
        Surface(
            Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        if (seleccion == null) volver() else {
                            linea = null
                            filtro = ""
                            op.limpiarMensaje()
                        }
                    },
                    enabled = habilitado
                ) { Text("← Volver") }

                Text(
                    if (seleccion == null) "Gestión de líneas" else "Recorrido · Línea $seleccion",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Barra virtualizada de chips de líneas (LazyRow a 60/120 FPS sin sobre-renderizado)
                if (lineas.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = lineas,
                            key = { it },
                            contentType = { "chip_linea" }
                        ) { l ->
                            ChipLineaItem(
                                numeroLinea = l,
                                seleccionada = l == seleccion,
                                habilitado = habilitado,
                                onSeleccionar = seleccionarLineaCallback
                            )
                        }
                    }
                }

                if (op.ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (op.mensaje.isNotBlank()) {
                    Text(
                        op.mensaje,
                        color = if (op.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                if (seleccion == null) {
                    Text("${lineas.size} líneas registradas")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { anterior = null; numero = ""; dialogoLinea = true },
                            enabled = habilitado
                        ) { Text("+ Agregar línea") }

                        OutlinedButton(
                            onClick = { op.ejecutar { lineas = AdminApi.lineas(token) } },
                            enabled = habilitado
                        ) { Text("Actualizar") }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = lineas,
                            key = { it },
                            contentType = { "tarjeta_linea" }
                        ) { l ->
                            TarjetaLineaItem(
                                numeroLinea = l,
                                habilitado = habilitado,
                                onAbrirRecorrido = seleccionarLineaCallback,
                                onAbrirCargaVisual = abrirCargaVisualCallback,
                                onRenombrar = { target ->
                                    anterior = target
                                    numero = target
                                    dialogoLinea = true
                                },
                                onEliminar = { target ->
                                    confirmar = {
                                        op.ejecutar(
                                            ofrecerDeshacer = true,
                                            textoExito = "Línea $target eliminada",
                                            alDeshacer = deshacerLineaOTramo
                                        ) {
                                            AdminApi.eliminarLinea(token, target)
                                            lineas = AdminApi.lineas(token)
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        "${tramos.size} tramos dirigidos (ordenados por secuencia).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { cargaVisualMapa = true },
                            enabled = habilitado
                        ) { Text("🗺 Carga visual") }

                        OutlinedButton(
                            onClick = {
                                editado = null
                                origenEncadenado = tramos.lastOrNull()?.destino ?: ""
                                dialogoTramo = true
                            },
                            enabled = habilitado
                        ) { Text("+ Manual") }

                        OutlinedButton(
                            onClick = { verMapa = true },
                            enabled = tramos.isNotEmpty() && habilitado
                        ) { Text("Ver mapa") }
                    }

                    OutlinedTextField(
                        value = filtro,
                        onValueChange = { filtro = it },
                        label = { Text("Buscar parada o ID en el recorrido") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = tramosVisibles,
                            key = { "${it.origen}->${it.destino}" },
                            contentType = { "tarjeta_tramo" }
                        ) { t ->
                            TarjetaTramoItem(
                                tramo = t,
                                nombreOrigen = nombres[t.origen].orEmpty(),
                                nombreDestino = nombres[t.destino].orEmpty(),
                                habilitado = habilitado,
                                onEditar = { target ->
                                    editado = target
                                    origenEncadenado = ""
                                    dialogoTramo = true
                                },
                                onEliminar = { target ->
                                    confirmar = {
                                        op.ejecutar(
                                            ofrecerDeshacer = true,
                                            textoExito = "Tramo ${target.origen} → ${target.destino} eliminado",
                                            alDeshacer = deshacerLineaOTramo
                                        ) {
                                            AdminApi.eliminarTramo(token, seleccion, target)
                                            tramos = ordenarTramosTopologicamente(
                                                AdminApi.tramos(token, seleccion)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (verMapa && seleccion != null) {
        DialogRecorridoAdmin(seleccion, paradas, tramos) { verMapa = false }
    }

    if (cargaVisualMapa && seleccion != null) {
        DialogCargaVisualRecorrido(
            linea = seleccion,
            paradas = paradas,
            tramos = tramos,
            ocupado = op.ocupado,
            onAgregarTramoVisual = { nuevoTramo ->
                op.ejecutar(ofrecerDeshacer = false) {
                    AdminApi.guardarTramo(token, seleccion, nuevoTramo)
                    tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, seleccion))
                }
            },
            onDeshacerUltimoTramo = { tramoABorrar ->
                op.ejecutar(ofrecerDeshacer = false) {
                    AdminApi.eliminarTramo(token, seleccion, tramoABorrar)
                    tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, seleccion))
                }
            },
            cerrar = { cargaVisualMapa = false }
        )
    }

    if (dialogoLinea) {
        AlertDialog(
            onDismissRequest = { dialogoLinea = false },
            title = { Text(if (anterior == null) "Agregar línea" else "Renombrar línea $anterior") },
            text = {
                OutlinedTextField(
                    value = numero,
                    onValueChange = { numero = it.trim().uppercase(Locale.ROOT) },
                    label = { Text("Número (ej. 20, 51A)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = numero.matches(Regex("[0-9]{1,4}[A-Za-z]?")),
                    onClick = {
                        dialogoLinea = false
                        op.ejecutar(
                            ofrecerDeshacer = true,
                            textoExito = "Línea $numero guardada",
                            alDeshacer = deshacerLineaOTramo
                        ) {
                            AdminApi.guardarLinea(token, anterior, numero)
                            lineas = AdminApi.lineas(token)
                        }
                    }
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { dialogoLinea = false }) { Text("Cancelar") } }
        )
    }

    if (dialogoTramo && seleccion != null) {
        EditorTramo(
            t = editado,
            origenInicial = origenEncadenado,
            paradas = paradas,
            indiceParadas = indiceSelectorParadas,
            cerrar = {
                dialogoTramo = false
                origenEncadenado = ""
            },
            guardar = { nuevo, encadenarSiguiente ->
                if (editado == null && tramos.any { it.origen == nuevo.origen && it.destino == nuevo.destino }) {
                    op.reportarError("El tramo ya existe. Usá Editar.")
                } else {
                    op.ejecutar(
                        ofrecerDeshacer = !encadenarSiguiente,
                        textoExito = "Tramo ${nuevo.origen} → ${nuevo.destino} guardado",
                        alDeshacer = deshacerLineaOTramo
                    ) {
                        AdminApi.guardarTramo(token, seleccion, nuevo)
                        tramos = ordenarTramosTopologicamente(AdminApi.tramos(token, seleccion))
                        if (encadenarSiguiente) {
                            origenEncadenado = nuevo.destino
                        } else {
                            dialogoTramo = false
                            origenEncadenado = ""
                        }
                    }
                }
            }
        )
    }

    confirmar?.let { accion ->
        AlertDialog(
            onDismissRequest = { confirmar = null },
            title = { Text("Confirmar eliminación") },
            text = { Text("¿Eliminar este elemento? Podrás revertirlo luego con Deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirmar = null; accion() }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun EditorTramo(
    t: TramoAdmin?,
    origenInicial: String = "",
    paradas: List<ParadaAdmin>,
    indiceParadas: List<Pair<ParadaAdmin, String>>,
    cerrar: () -> Unit,
    guardar: (TramoAdmin, encadenarSiguiente: Boolean) -> Unit
) {
    var origen by remember(t, origenInicial) { mutableStateOf(t?.origen ?: origenInicial) }
    var destino by remember(t, origenInicial) { mutableStateOf(t?.destino ?: "") }
    var metros by remember(t, origenInicial) { mutableStateOf(t?.distancia?.toString() ?: "") }
    var minutos by remember(t, origenInicial) { mutableStateOf(t?.tiempo?.toString() ?: "") }

    LaunchedEffect(origen, destino) {
        if (t == null && origen != destino) {
            val a = paradas.find { it.id == origen }
            val b = paradas.find { it.id == destino }
            if (a != null && b != null) {
                val distVial = kotlin.math.round(distanciaAdmin(a, b) * 1.25)
                val tiempoEst = maxOf(0.5, (distVial / 366.67) + 0.35)
                metros = distVial.toLong().toString()
                minutos = "%.1f".format(Locale.US, tiempoEst)
            }
        }
    }

    val d = metros.replace(',', '.').toDoubleOrNull()
    val m = minutos.replace(',', '.').toDoubleOrNull()
    val valido = origen != destino &&
            paradas.any { it.id == origen } &&
            paradas.any { it.id == destino } &&
            d != null && d > 0 && d <= 100000 &&
            m != null && m > 0 && m <= 600

    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text(if (t == null) "Agregar tramo dirigido" else "Editar tramo") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectorParadaAdmin("Origen", origen, paradas, indiceParadas, t == null) { origen = it }
                SelectorParadaAdmin("Destino", destino, paradas, indiceParadas, t == null) { destino = it }

                OutlinedTextField(
                    value = metros,
                    onValueChange = { metros = it },
                    label = { Text("Distancia vial estimada (metros)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = minutos,
                    onValueChange = { minutos = it },
                    label = { Text("Tiempo estimado (minutos)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    "Estimación automática con factor de calle ×1.25 y 22 km/h urbanos (editable).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (t == null) {
                    TextButton(
                        enabled = valido,
                        onClick = { if (d != null && m != null) guardar(TramoAdmin(origen, destino, d, m), true) }
                    ) { Text("Guardar y seguir →") }
                }
                TextButton(
                    enabled = valido,
                    onClick = { if (d != null && m != null) guardar(TramoAdmin(origen, destino, d, m), false) }
                ) { Text("Guardar") }
            }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } }
    )
}

@Composable
private fun SelectorParadaAdmin(
    etiqueta: String,
    id: String,
    paradas: List<ParadaAdmin>,
    indiceParadas: List<Pair<ParadaAdmin, String>>,
    editable: Boolean,
    elegir: (String) -> Unit
) {
    var texto by remember { mutableStateOf("") }
    val seleccion = remember(id, paradas) { paradas.find { it.id == id } }

    Text(
        "$etiqueta: ${seleccion?.let { "${it.id} · ${it.calle} - ${it.referencia}" } ?: "Sin seleccionar"}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
    )
    if (editable) {
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Buscar $etiqueta") },
            singleLine = true
        )
        val sugerencias by remember(indiceParadas) {
            derivedStateOf {
                val q = texto.normalizarBusqueda()
                if (q.isEmpty()) emptyList()
                else indiceParadas.asSequence()
                    .filter { (_, clave) -> clave.contains(q) }
                    .map { (p, _) -> p }
                    .take(4)
                    .toList()
            }
        }
        sugerencias.forEach { p ->
            TextButton(onClick = { elegir(p.id); texto = "" }) {
                Text("${p.id} · ${p.calle} - ${p.referencia}")
            }
        }
    }
}