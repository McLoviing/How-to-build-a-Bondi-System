package com.example.bondibariloche

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.ApiConfig
import com.example.bondibariloche.data.BondiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DialogServidor(cerrar: () -> Unit, guardado: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var direccion by remember { mutableStateOf(ApiConfig.baseUrl) }
    var mensaje by remember { mutableStateOf("") }
    var probando by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("Configuración del servidor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = direccion,
                    onValueChange = { direccion = it; mensaje = "" },
                    label = { Text("Dirección IP y puerto") },
                    placeholder = { Text("192.168.1.34:8000") },
                    singleLine = true,
                    enabled = !probando,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Conectá el celular y el servidor a la misma red.")
                OutlinedButton(enabled = !probando, onClick = {
                    probando = true
                    mensaje = "Probando conexión..."
                    scope.launch {
                        try {
                            val cantidad = BondiApi.probarServidor(direccion)
                            mensaje = "Conexión correcta: $cantidad paradas."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mensaje = e.message ?: "Error de conexión."
                        } finally {
                            probando = false
                        }
                    }
                }) { Text("Probar conexión") }
                if (mensaje.isNotEmpty()) Text(mensaje)
            }
        },
        confirmButton = {
            TextButton(enabled = !probando, onClick = {
                try {
                    ApiConfig.guardarUrl(contexto, direccion)
                    guardado()
                } catch (e: Exception) {
                    mensaje = e.message ?: "Dirección inválida."
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } }
    )
}
