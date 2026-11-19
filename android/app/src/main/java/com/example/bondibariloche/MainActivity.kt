package com.example.bondibariloche

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.ApiConfig
import com.example.bondibariloche.data.BondiApi
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.ui.theme.BondiBarilocheTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre

private sealed interface EstadoArranque {
    data object Cargando : EstadoArranque
    data class Listo(val paradas: List<Parada>) : EstadoArranque
    data class ErrorFatal(val mensaje: String) : EstadoArranque
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        MapLibre.getInstance(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiConfig.inicializar(applicationContext)

        setContent {
            BondiBarilocheTheme {
                AplicacionBondiBariloche()
            }
        }
    }
}

@Composable
private fun AplicacionBondiBariloche() {
    var estado by remember { mutableStateOf<EstadoArranque>(EstadoArranque.Cargando) }
    var mostrarAdmin by rememberSaveable { mutableStateOf(false) }
    var refrescarTrigger by remember { mutableIntStateOf(0) }
    var mostrarServidor by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = mostrarAdmin) {
        mostrarAdmin = false
        refrescarTrigger++
    }

    LaunchedEffect(refrescarTrigger) {
        if (estado !is EstadoArranque.Listo) {
            estado = EstadoArranque.Cargando
        }
        estado = descargarParadasConReintentos()
    }

    if (mostrarServidor) {
        DialogServidor(
            cerrar = { mostrarServidor = false },
            guardado = {
                mostrarServidor = false
                mostrarAdmin = false
                estado = EstadoArranque.Cargando
                refrescarTrigger++
            }
        )
    }

    if (mostrarAdmin) {
        GestionParadas(
            volver = {
                mostrarAdmin = false
                refrescarTrigger++
            }
        )
        return
    }

    when (val actual = estado) {
        is EstadoArranque.Cargando -> {
            PantallaCargaInicial(onConfigurar = { mostrarServidor = true })
        }

        is EstadoArranque.ErrorFatal -> {
            PantallaErrorConexion(
                mensaje = actual.mensaje,
                onReintentar = {
                    estado = EstadoArranque.Cargando
                    refrescarTrigger++
                },
                onConfigurar = { mostrarServidor = true }
            )
        }

        is EstadoArranque.Listo -> {
            PantallaPasajero(
                paradas = actual.paradas,
                abrirAdmin = { mostrarAdmin = true },
                configurarServidor = { mostrarServidor = true }
            )
        }
    }
}

@Composable
private fun PantallaCargaInicial(onConfigurar: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Conectando con la red de colectivos de Bariloche...",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onConfigurar) { Text("Configurar servidor") }
        }
    }
}

@Composable
private fun PantallaErrorConexion(
    mensaje: String,
    onReintentar: () -> Unit,
    onConfigurar: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = "No se pudo conectar con el servidor",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = mensaje,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onReintentar) {
                Text("Reintentar conexión")
            }
            TextButton(onClick = onConfigurar) {
                Text("Configurar servidor")
            }
        }
    }
}

private suspend fun descargarParadasConReintentos(): EstadoArranque =
    withContext(Dispatchers.IO) {
        var ultimoError: Exception? = null
        for (intento in 0 until 3) {
            try {
                // Ajustá el nombre si en tu BondiApi se llama paradas()
                val paradas = BondiApi.obtenerParadas()
                return@withContext EstadoArranque.Listo(paradas = paradas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ultimoError = e
                if (intento < 2) delay(300L * (1 shl intento))
            }
        }
        EstadoArranque.ErrorFatal(
            ultimoError?.message ?: "Verificá tu conexión a internet o la dirección del servidor."
        )
    }