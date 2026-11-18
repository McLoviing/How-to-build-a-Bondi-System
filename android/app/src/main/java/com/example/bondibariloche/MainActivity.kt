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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre

// Paleta oscura calibrada para evitar OLED smearing (#0F1419 en lugar de #000000)
// y maximizar el contraste de polilíneas e indicadores de tiempo.
val BondiDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),             // Celeste patagónico luminoso
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF004D68),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFF81C784),           // Verde esmeralda para transbordos/caminatas
    onSecondary = Color(0xFF003919),
    secondaryContainer = Color(0xFF1B4D2E),
    onSecondaryContainer = Color(0xFFA8F5B5),
    tertiary = Color(0xFFFFB74D),            // Ámbar para alertas y tiempos de espera
    onTertiary = Color(0xFF462A00),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE1E6EB),
    surface = Color(0xFF141A20),
    onSurface = Color(0xFFE1E6EB),
    surfaceVariant = Color(0xFF202A33),
    onSurfaceVariant = Color(0xFF9FB0BF),
    surfaceContainerLow = Color(0xFF182026),
    surfaceContainer = Color(0xFF1D262E),
    surfaceContainerHigh = Color(0xFF25303A),
    outline = Color(0xFF455564),
    outlineVariant = Color(0xFF2C3843),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5F0008)
)

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
            MaterialTheme(colorScheme = BondiDarkColorScheme) {
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