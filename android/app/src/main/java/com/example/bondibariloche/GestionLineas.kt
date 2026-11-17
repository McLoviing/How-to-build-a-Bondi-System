package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.AdminApi
import com.example.bondibariloche.data.ParadaAdmin
import com.example.bondibariloche.data.TramoAdmin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GestionLineas(token: String, paradas: List<ParadaAdmin>, volver: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lineas by remember { mutableStateOf(emptyList<String>()) }
    var tramos by remember { mutableStateOf(emptyList<TramoAdmin>()) }
    var linea by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var dialogoLinea by remember { mutableStateOf(false) }
    var anterior by remember { mutableStateOf<String?>(null) }
    var numero by remember { mutableStateOf("") }
    var dialogoTramo by remember { mutableStateOf(false) }
    var editado by remember { mutableStateOf<TramoAdmin?>(null) }
    var confirmar by remember { mutableStateOf<(() -> Unit)?>(null) }
    var filtro by remember { mutableStateOf("") }
    val nombres = remember(paradas) { paradas.associate { it.id to "${it.calle} - ${it.referencia}" } }

    fun ejecutar(accion: suspend () -> Unit) {
        if (ocupado) return
        ocupado = true
        mensaje = ""
        error = false
        scope.launch {
            try { accion() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = true; mensaje = e.message ?: "Error de conexión" }
            finally { ocupado = false }
        }
    }
    LaunchedEffect(Unit) { ejecutar { lineas = AdminApi.lineas(token) } }
    val seleccion = linea
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (seleccion == null) volver() else { linea = null; filtro = "" } }, enabled = !ocupado) { Text("← Volver") }
            Text(if (seleccion == null) "Gestión de líneas" else "Recorrido · Línea $seleccion", style = MaterialTheme.typography.headlineSmall)
            if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (mensaje.isNotBlank()) Text(mensaje, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (seleccion == null) {
                Text("${lineas.size} líneas registradas")
                Button(onClick = { anterior = null; numero = ""; dialogoLinea = true }, enabled = !ocupado) { Text("Agregar línea") }
                OutlinedButton(onClick = { ejecutar { lineas = AdminApi.lineas(token) } }, enabled = !ocupado) { Text("Actualizar") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lineas) { l ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Línea $l", style = MaterialTheme.typography.titleLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { ejecutar { tramos = AdminApi.tramos(token,l); linea = l } }, enabled = !ocupado) { Text("Recorrido") }
                                    TextButton(onClick = { anterior = l; numero = l; dialogoLinea = true }, enabled = !ocupado) { Text("Editar") }
                                    TextButton(onClick = { confirmar = { ejecutar { AdminApi.eliminarLinea(token,l); lineas = AdminApi.lineas(token); mensaje = "Línea eliminada" } } }, enabled = !ocupado) { Text("Eliminar") }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("${tramos.size} tramos dirigidos. El sentido inverso se administra por separado.")
                Button(onClick = { editado = null; dialogoTramo = true }, enabled = !ocupado) { Text("Agregar tramo") }
                OutlinedTextField(filtro, { filtro = it }, label = { Text("Buscar parada o ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tramos.filter { "${it.origen} ${it.destino} ${nombres[it.origen]} ${nombres[it.destino]}".contains(filtro, true) }) { t ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${t.origen} · ${nombres[t.origen] ?: ""}")
                                Text("↓ ${t.destino} · ${nombres[t.destino] ?: ""}")
                                Text("${t.distancia} m · ${t.tiempo} min")
                                Row {
                                    TextButton(onClick = { editado = t; dialogoTramo = true }, enabled = !ocupado) { Text("Editar") }
                                    TextButton(onClick = { confirmar = { ejecutar { AdminApi.eliminarTramo(token, seleccion,t); tramos = AdminApi.tramos(token,seleccion); mensaje = "Tramo eliminado" } } }, enabled = !ocupado) { Text("Eliminar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (dialogoLinea) AlertDialog(onDismissRequest = { dialogoLinea = false }, title = { Text(if (anterior == null) "Agregar línea" else "Editar línea") }, text = {
        OutlinedTextField(numero, { numero = it }, label = { Text("Número") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = numero.matches(Regex("[0-9]{1,4}[A-Za-z]?")), onClick = {
        dialogoLinea = false
        ejecutar { AdminApi.guardarLinea(token,anterior,numero); lineas = AdminApi.lineas(token); mensaje = "Línea guardada" }
    }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { dialogoLinea = false }) { Text("Cancelar") } })
    if (dialogoTramo && seleccion != null) EditorTramo(editado, paradas, { dialogoTramo = false }) { nuevo ->
        dialogoTramo = false
        ejecutar {
            if (editado == null && tramos.any { it.origen == nuevo.origen && it.destino == nuevo.destino }) {
                error = true; mensaje = "El tramo ya existe. Usá Editar."
            } else {
                AdminApi.guardarTramo(token,seleccion,nuevo)
                tramos = AdminApi.tramos(token,seleccion)
                mensaje = "Tramo guardado"
            }
        }
    }
    confirmar?.let { accion -> AlertDialog(onDismissRequest = { confirmar = null }, title = { Text("Confirmar eliminación") }, text = { Text("¿Eliminar este elemento de la base de conocimiento?") }, confirmButton = { TextButton(onClick = { confirmar = null; accion() }) { Text("Eliminar") } }, dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Cancelar") } }) }
}

@Composable
private fun EditorTramo(t: TramoAdmin?, paradas: List<ParadaAdmin>, cerrar: () -> Unit, guardar: (TramoAdmin) -> Unit) {
    var origen by remember { mutableStateOf(t?.origen ?: "") }
    var destino by remember { mutableStateOf(t?.destino ?: "") }
    var metros by remember { mutableStateOf(t?.distancia?.toString() ?: "") }
    var minutos by remember { mutableStateOf(t?.tiempo?.toString() ?: "") }
    val d = metros.replace(',', '.').toDoubleOrNull()
    val m = minutos.replace(',', '.').toDoubleOrNull()
    val valido = origen != destino && paradas.any { it.id == origen } && paradas.any { it.id == destino } && d != null && d > 0 && d <= 100000 && m != null && m > 0 && m <= 600
    AlertDialog(onDismissRequest = cerrar, title = { Text(if (t == null) "Agregar tramo dirigido" else "Editar tramo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectorParadaAdmin("Origen", origen, paradas, t == null) { origen = it }
            SelectorParadaAdmin("Destino", destino, paradas, t == null) { destino = it }
            OutlinedTextField(metros, { metros = it }, label = { Text("Distancia (metros)") }, singleLine = true)
            OutlinedTextField(minutos, { minutos = it }, label = { Text("Tiempo (minutos)") }, singleLine = true)
            Text("Solo se modifica el sentido origen → destino.")
        }
    }, confirmButton = { TextButton(enabled = valido, onClick = { if (d != null && m != null) guardar(TramoAdmin(origen,destino,d,m)) }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } })
}

@Composable
private fun SelectorParadaAdmin(etiqueta: String, id: String, paradas: List<ParadaAdmin>, editable: Boolean, elegir: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    val seleccion = paradas.find { it.id == id }
    Text("$etiqueta: ${seleccion?.let { "${it.id} · ${it.calle} - ${it.referencia}" } ?: "Sin seleccionar"}")
    if (editable) {
        OutlinedTextField(texto, { texto = it }, label = { Text("Buscar $etiqueta") }, singleLine = true)
        if (texto.isNotBlank()) paradas.filter { "${it.id} ${it.calle} ${it.referencia}".contains(texto,true) }.take(4).forEach { p ->
            TextButton(onClick = { elegir(p.id); texto = "" }) { Text("${p.id} · ${p.calle} - ${p.referencia}") }
        }
    }
}
