package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.AdminApi
import com.example.bondibariloche.data.BackupAdmin
import com.example.bondibariloche.data.ParadaAdmin
import java.text.Normalizer
import java.util.Locale

private val REGEX_DIACRITICOS = "\\p{Mn}+".toRegex()

/** Normaliza texto eliminando tildes y mayúsculas para búsqueda rápida en español. */
internal fun String.normalizarBusqueda(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(REGEX_DIACRITICOS, "")
        .lowercase(Locale.ROOT)
        .trim()

/** Panel de gestión de hechos parada/5 y control de respaldos atómicos. */
@Composable
fun GestionParadas(volver: () -> Unit) {
    val op = rememberEstadoOperacionAdmin()

    var token by rememberSaveable { mutableStateOf("") }
    var conectado by rememberSaveable { mutableStateOf(false) }
    var vistaActual by rememberSaveable { mutableStateOf("menu") } // "menu", "paradas", "lineas", "backups"

    var paradas by remember { mutableStateOf(emptyList<ParadaAdmin>()) }
    var backups by remember { mutableStateOf(emptyList<BackupAdmin>()) }
    var filtro by remember { mutableStateOf("") }
    var soloSinRecorrido by remember { mutableStateOf(false) }
    var editar by remember { mutableStateOf<ParadaAdmin?>(null) }
    var formulario by remember { mutableStateOf(false) }
    var eliminar by remember { mutableStateOf<ParadaAdmin?>(null) }
    var confirmarResetFabrica by remember { mutableStateOf(false) }

    val deshacerParada: suspend () -> Unit = {
        AdminApi.rollback(token)
        paradas = AdminApi.listar(token)
    }

    LaunchedEffect(conectado) {
        if (conectado && paradas.isEmpty() && token.isNotBlank()) {
            op.ejecutar { paradas = AdminApi.listar(token) }
        }
    }

    if (vistaActual == "lineas") {
        GestionLineas(token, paradas) {
            vistaActual = "menu"
            op.ejecutar { paradas = AdminApi.listar(token) }
        }
        return
    }

    val indiceParadas = remember(paradas) {
        paradas.map { p ->
            val clave = buildString(p.id.length + p.calle.length + p.referencia.length + 2) {
                append(p.id).append(' ')
                append(p.calle).append(' ')
                append(p.referencia)
            }.normalizarBusqueda()
            p to clave
        }
    }

    val visibles by remember(indiceParadas, soloSinRecorrido) {
        derivedStateOf {
            val q = filtro.normalizarBusqueda()
            indiceParadas.mapNotNull { (p, clave) ->
                val pasaVinculo = !soloSinRecorrido || !p.vinculada
                val pasaTexto = q.isEmpty() || clave.contains(q)
                if (pasaVinculo && pasaTexto) p else null
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(op.snackbarHostState) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = {
                    if (vistaActual != "menu" && conectado) {
                        vistaActual = "menu"
                        filtro = ""
                        soloSinRecorrido = false
                        op.limpiarMensaje()
                    } else {
                        volver()
                    }
                },
                enabled = !op.ocupado
            ) { Text("← Volver") }

            Text("Modo Administrador", style = MaterialTheme.typography.headlineMedium)

            if (!conectado) {
                Text("Ingresá la clave de administrador.")
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Clave de administrador") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        op.ejecutar {
                            paradas = AdminApi.listar(token)
                            conectado = true
                        }
                    },
                    enabled = !op.ocupado && token.isNotBlank()
                ) { Text("Ingresar") }
            } else {
                when (vistaActual) {
                    "menu" -> {
                        Text(
                            "Cada edición genera un respaldo automático.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { vistaActual = "paradas" },
                            enabled = !op.ocupado,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Gestionar paradas (${paradas.size})") }

                        Button(
                            onClick = { vistaActual = "lineas" },
                            enabled = !op.ocupado,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Gestionar líneas y recorridos") }

                        OutlinedButton(
                            onClick = {
                                op.ejecutar {
                                    backups = AdminApi.listarBackups(token)
                                    vistaActual = "backups"
                                }
                            },
                            enabled = !op.ocupado,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Historial de cambios y respaldos") }
                    }

                    "paradas" -> {
                        Text("${visibles.size} de ${paradas.size} paradas")
                        OutlinedTextField(
                            value = filtro,
                            onValueChange = { filtro = it },
                            singleLine = true,
                            label = { Text("Buscar por calle, referencia o ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { editar = null; formulario = true },
                                enabled = !op.ocupado
                            ) { Text("+ Parada") }

                            FilterChip(
                                selected = soloSinRecorrido,
                                onClick = { soloSinRecorrido = !soloSinRecorrido },
                                label = { Text("Sin recorrido") }
                            )

                            OutlinedButton(
                                onClick = { op.ejecutar { paradas = AdminApi.listar(token) } },
                                enabled = !op.ocupado
                            ) { Text("Refrescar") }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 84.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = visibles,
                                key = { it.id },
                                contentType = { "tarjeta_parada" }
                            ) { p ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            p.calle + if (p.referencia.isBlank()) "" else " - ${p.referencia}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${p.id} · ${"%.5f".format(Locale.US, p.latitud)}, ${"%.5f".format(Locale.US, p.longitud)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (p.vinculada) {
                                            Text(
                                                "Vinculada a un recorrido",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Row {
                                            TextButton(
                                                onClick = { editar = p; formulario = true },
                                                enabled = !op.ocupado
                                            ) { Text("Editar") }
                                            TextButton(
                                                onClick = { eliminar = p },
                                                enabled = !op.ocupado && !p.vinculada
                                            ) { Text("Eliminar") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "backups" -> {
                        Text(
                            "Últimos estados guardados antes de cada mutación:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    op.ejecutar(textoExito = "Último cambio revertido") {
                                        AdminApi.rollback(token)
                                        paradas = AdminApi.listar(token)
                                        backups = AdminApi.listarBackups(token)
                                    }
                                },
                                enabled = !op.ocupado && backups.isNotEmpty()
                            ) { Text("↩ Deshacer último") }

                            OutlinedButton(
                                onClick = { confirmarResetFabrica = true },
                                enabled = !op.ocupado,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Restaurar fábrica") }
                        }

                        if (backups.isEmpty()) {
                            Text(
                                "Aún no hay modificaciones registradas sobre base.pl.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 84.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = backups,
                                    key = { it.archivo },
                                    contentType = { "tarjeta_backup" }
                                ) { b ->
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    b.accion.replace("_", " ").uppercase(Locale.ROOT),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "UTC: ${b.fechaUtc}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    op.ejecutar(textoExito = "Estado restaurado") {
                                                        AdminApi.rollback(token, b.archivo)
                                                        paradas = AdminApi.listar(token)
                                                        backups = AdminApi.listarBackups(token)
                                                    }
                                                },
                                                enabled = !op.ocupado
                                            ) { Text("Restaurar") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (op.ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (op.mensaje.isNotEmpty()) {
                Text(
                    op.mensaje,
                    color = if (op.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (formulario) {
        val p = editar
        FormularioParada(
            p = p,
            paradasExistentes = paradas,
            cerrar = { formulario = false },
            guardar = { calle, referencia, lat, lon ->
                formulario = false
                op.ejecutar(
                    ofrecerDeshacer = true,
                    textoExito = "Parada guardada",
                    alDeshacer = deshacerParada
                ) {
                    AdminApi.guardar(token, p?.id, calle, referencia, lat, lon)
                    paradas = AdminApi.listar(token)
                }
            }
        )
    }

    eliminar?.let { p ->
        AlertDialog(
            onDismissRequest = { eliminar = null },
            title = { Text("Eliminar parada") },
            text = { Text("¿Eliminar ${p.calle} (${p.id})? Podrás revertirlo con Deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    eliminar = null
                    op.ejecutar(
                        ofrecerDeshacer = true,
                        textoExito = "Parada ${p.id} eliminada",
                        alDeshacer = deshacerParada
                    ) {
                        AdminApi.eliminar(token, p.id)
                        paradas = AdminApi.listar(token)
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { eliminar = null }) { Text("Cancelar") } }
        )
    }

    if (confirmarResetFabrica) {
        var confirmacionTexto by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmarResetFabrica = false },
            title = { Text("Restaurar base original") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se descartarán las ediciones. Escribí RESTAURAR para confirmar:")
                    OutlinedTextField(
                        value = confirmacionTexto,
                        onValueChange = { confirmacionTexto = it },
                        singleLine = true,
                        label = { Text("RESTAURAR") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmacionTexto.trim().equals("RESTAURAR", ignoreCase = true),
                    onClick = {
                        confirmarResetFabrica = false
                        op.ejecutar(textoExito = "Base original restaurada") {
                            AdminApi.restaurarFabrica(token)
                            paradas = AdminApi.listar(token)
                            backups = AdminApi.listarBackups(token)
                        }
                    }
                ) { Text("Confirmar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarResetFabrica = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun FormularioParada(
    p: ParadaAdmin?,
    paradasExistentes: List<ParadaAdmin> = emptyList(),
    cerrar: () -> Unit,
    guardar: (String, String, Double, Double) -> Unit
) {
    var calle by remember { mutableStateOf(p?.calle ?: "") }
    var referencia by remember { mutableStateOf(p?.referencia ?: "") }
    var latitud by remember { mutableStateOf(p?.latitud?.toString() ?: "") }
    var longitud by remember { mutableStateOf(p?.longitud?.toString() ?: "") }
    var elegirMapa by remember { mutableStateOf(false) }

    if (elegirMapa) {
        DialogMapaAdmin(
            latitud = latitud.toDoubleOrNull(),
            longitud = longitud.toDoubleOrNull(),
            paradasExistentes = paradasExistentes,
            cerrar = { elegirMapa = false }
        ) { a, b ->
            latitud = "%.6f".format(Locale.US, a)
            longitud = "%.6f".format(Locale.US, b)
            elegirMapa = false
        }
        return
    }

    val lat = latitud.replace(',', '.').toDoubleOrNull()
    val lon = longitud.replace(',', '.').toDoubleOrNull()
    val valido = calle.isNotBlank() && lat != null && lon != null &&
            lat in -90.0..90.0 && lon in -180.0..180.0

    val paradaCercana = remember(p, lat, lon, paradasExistentes) {
        if (p != null || lat == null || lon == null) null
        else {
            paradasExistentes.firstOrNull { existente ->
                val dLat = (existente.latitud - lat) * 111132.95
                val dLon = (existente.longitud - lon) * 83715.4
                (dLat * dLat + dLon * dLon) < 625.0
            }
        }
    }

    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text(if (p == null) "Agregar parada" else "Editar ${p.id}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = calle,
                    onValueChange = { calle = it },
                    label = { Text("Calle") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = referencia,
                    onValueChange = { referencia = it },
                    label = { Text("Referencia / Esquina") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = latitud,
                    onValueChange = { latitud = it },
                    label = { Text("Latitud") },
                    enabled = p?.vinculada != true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = longitud,
                    onValueChange = { longitud = it },
                    label = { Text("Longitud") },
                    enabled = p?.vinculada != true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                if (p?.vinculada != true) {
                    OutlinedButton(
                        onClick = { elegirMapa = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Elegir ubicación en el mapa") }
                }
                if (paradaCercana != null) {
                    Text(
                        text = "Aviso: ya existe ${paradaCercana.id} (${paradaCercana.calle}) a menos de 25 m.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (p?.vinculada == true) {
                    Text(
                        text = "Las coordenadas de paradas vinculadas están protegidas para conservar los tramos.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (lat != null && lon != null) {
                        guardar(calle.trim(), referencia.trim(), lat, lon)
                    }
                },
                enabled = valido
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } }
    )
}