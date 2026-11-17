package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.AdminApi
import com.example.bondibariloche.data.ParadaAdmin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Panel de gestión de hechos parada/5 a través de la API REST. */
@Composable
fun GestionParadas(volver: () -> Unit) {
    val alcance = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var verLineas by remember { mutableStateOf(false) }
    var verParadas by remember { mutableStateOf(false) }
    var conectado by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var paradas by remember { mutableStateOf(emptyList<ParadaAdmin>()) }
    var filtro by remember { mutableStateOf("") }
    var editar by remember { mutableStateOf<ParadaAdmin?>(null) }
    var formulario by remember { mutableStateOf(false) }
    var eliminar by remember { mutableStateOf<ParadaAdmin?>(null) }

    fun ejecutar(accion: suspend () -> Unit) {
        if (ocupado) return
        ocupado = true
        mensaje = ""
        error = false
        alcance.launch {
            try {
                accion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                mensaje = e.message ?: "No se pudo completar la operación"
            } finally {
                ocupado = false
            }
        }
    }

    if (verLineas) {
        GestionLineas(token, paradas) {
            verLineas = false
            ejecutar { paradas = AdminApi.listar(token) }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { if (verParadas) verParadas = false else volver() }, enabled = !ocupado) { Text("← Volver") }
        Text("Modo Administrador", style = MaterialTheme.typography.headlineMedium)
        if (!conectado) {
            Text("Ingresá la clave configurada en el servidor.")
            OutlinedTextField(value = token, onValueChange = { token = it },
                label = { Text("Clave de administrador") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { ejecutar { paradas = AdminApi.listar(token); conectado = true } },
                enabled = !ocupado && token.isNotBlank()) { Text("Ingresar") }
        } else if (!verParadas) {
            Text("Gestión del transporte público")
            Button(onClick = { verParadas = true }, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) { Text("Gestionar paradas · ${paradas.size}") }
            Button(onClick = { verLineas = true }, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) { Text("Gestionar líneas y recorridos") }
        } else {
            Text("${paradas.size} paradas registradas")
            OutlinedTextField(value = filtro, onValueChange = { filtro = it }, singleLine = true,
                label = { Text("Buscar por calle, referencia o ID") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editar = null; formulario = true }, enabled = !ocupado) { Text("Agregar parada") }
                OutlinedButton(onClick = { ejecutar { paradas = AdminApi.listar(token) } }, enabled = !ocupado) { Text("Actualizar") }
            }
        }
        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (mensaje.isNotEmpty()) Text(mensaje, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (conectado && verParadas) {
            val visibles = paradas.filter { "${it.id} ${it.calle} ${it.referencia}".contains(filtro.trim(), ignoreCase = true) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibles, key = { it.id }) { p ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.calle + if (p.referencia.isBlank()) "" else " - ${p.referencia}", style = MaterialTheme.typography.titleMedium)
                            Text("${p.id} · ${p.latitud}, ${p.longitud}", style = MaterialTheme.typography.bodySmall)
                            if (p.vinculada) Text("Vinculada a un recorrido", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editar = p; formulario = true }, enabled = !ocupado) { Text("Editar") }
                                TextButton(onClick = { eliminar = p }, enabled = !ocupado && !p.vinculada) { Text("Eliminar") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (formulario) {
        val p = editar
        FormularioParada(p, cerrar = { formulario = false }, guardar = { calle, referencia, lat, lon ->
            formulario = false
            ejecutar {
                AdminApi.guardar(token, p?.id, calle, referencia, lat, lon)
                paradas = AdminApi.listar(token)
                mensaje = "Cambios guardados en la base de conocimiento."
            }
        })
    }
    eliminar?.let { p ->
        AlertDialog(onDismissRequest = { eliminar = null }, title = { Text("Eliminar parada") },
            text = { Text("¿Eliminar ${p.calle} (${p.id})?") },
            confirmButton = { TextButton(onClick = {
                eliminar = null
                ejecutar { AdminApi.eliminar(token, p.id); paradas = AdminApi.listar(token); mensaje = "Parada eliminada." }
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { eliminar = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun FormularioParada(p: ParadaAdmin?, cerrar: () -> Unit,
                             guardar: (String, String, Double, Double) -> Unit) {
    var calle by remember { mutableStateOf(p?.calle ?: "") }
    var referencia by remember { mutableStateOf(p?.referencia ?: "") }
    var latitud by remember { mutableStateOf(p?.latitud?.toString() ?: "") }
    var longitud by remember { mutableStateOf(p?.longitud?.toString() ?: "") }
    var elegirMapa by remember { mutableStateOf(false) }
    if (elegirMapa) {
        DialogMapaAdmin(latitud.toDoubleOrNull(), longitud.toDoubleOrNull(), cerrar = { elegirMapa = false }) { a, b ->
            latitud = a.toString(); longitud = b.toString(); elegirMapa = false
        }
        return
    }
    val lat = latitud.replace(',', '.').toDoubleOrNull()
    val lon = longitud.replace(',', '.').toDoubleOrNull()
    val valido = calle.isNotBlank() && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
    AlertDialog(onDismissRequest = cerrar,
        title = { Text(if (p == null) "Agregar parada" else "Editar ${p.id}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(calle, { calle = it }, label = { Text("Calle") }, singleLine = true)
                OutlinedTextField(referencia, { referencia = it }, label = { Text("Referencia") }, singleLine = true)
                OutlinedTextField(latitud, { latitud = it }, label = { Text("Latitud") }, enabled = p?.vinculada != true, singleLine = true)
                OutlinedTextField(longitud, { longitud = it }, label = { Text("Longitud") }, enabled = p?.vinculada != true, singleLine = true)
                if (p?.vinculada != true) OutlinedButton(onClick = { elegirMapa = true }) { Text("Elegir ubicación en el mapa") }
                if (p?.vinculada == true) Text("Las coordenadas de paradas vinculadas están protegidas para conservar los tramos.")
            }
        },
        confirmButton = { TextButton(onClick = { if (lat != null && lon != null) guardar(calle.trim(), referencia.trim(), lat, lon) }, enabled = valido) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } })
}
