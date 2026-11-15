package com.example.bondibariloche

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.osmdroid.config.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.ApiException
import com.example.bondibariloche.data.BondiApi
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.Ruta
import com.example.bondibariloche.ui.theme.BondiBarilocheTheme
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 BondiBariloche/1.0"
        Configuration.getInstance().apply {
            userAgentValue = userAgent
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = userAgent
        }

        setContent {
            BondiBarilocheTheme {
                AplicacionBondi()
            }
        }
    }
}

@Composable
fun AplicacionBondi() {
    var pantalla by remember { mutableStateOf("inicio") }

    when (pantalla) {
        "inicio" -> PantallaInicio(
            usuario = { pantalla = "usuario" },
            administrador = { pantalla = "administrador" }
        )

        "usuario" -> PantallaUsuario(
            volver = { pantalla = "inicio" }
        )

        "administrador" -> PantallaAdministrador(
            volver = { pantalla = "inicio" }
        )
    }
}

@Composable
fun PantallaInicio(
    usuario: () -> Unit,
    administrador: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Bondi Bariloche",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))
        Text("Sistema de recomendación de colectivos")
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = usuario,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ingresar como Usuario")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = administrador,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Ingresar como Administrador")
        }
    }
}

// ======================================================================
// PANTALLA DEL USUARIO: buscador + llamada a la API + mapa + itinerario
// ======================================================================

@Composable
fun PantallaUsuario(volver: () -> Unit) {
    val alcance = rememberCoroutineScope()

    // ---- Datos que vienen de la API ----
    var paradas by remember { mutableStateOf<List<Parada>>(emptyList()) }
    var cargandoParadas by remember { mutableStateOf(true) }
    var errorParadas by remember { mutableStateOf<String?>(null) }
    var intento by remember { mutableStateOf(0) }          // subirlo = volver a pedir las paradas

    // ---- Lo que elige el usuario ----
    // Se guardan el texto y el ID (rememberSaveable sobrevive a rotar el celular).
    var textoOrigen by rememberSaveable { mutableStateOf("") }
    var textoDestino by rememberSaveable { mutableStateOf("") }
    var origenId by rememberSaveable { mutableStateOf<String?>(null) }
    var destinoId by rememberSaveable { mutableStateOf<String?>(null) }
    val origen = paradas.firstOrNull { it.id == origenId }
    val destino = paradas.firstOrNull { it.id == destinoId }

    // ---- Resultado de la búsqueda ----
    var rutas by remember { mutableStateOf<List<Ruta>>(emptyList()) }
    var seleccion by remember { mutableStateOf(0) }        // qué alternativa se está viendo
    var buscando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }

    // PASO 1: al abrir la pantalla, pedir las paradas a la API (GET /paradas).
    // LaunchedEffect corre una corrutina; se repite cuando cambia "intento".
    LaunchedEffect(intento) {
        cargandoParadas = true
        errorParadas = null
        try {
            paradas = BondiApi.obtenerParadas()
        } catch (e: ApiException) {
            errorParadas = e.message
        } finally {
            cargandoParadas = false
        }
    }

    // PASO 2: al tocar "Buscar", pedir los recorridos (GET /rutas).
    fun buscar() {
        val o = origen
        val d = destino
        mensaje = ""
        if (o == null || d == null) {
            mensaje = "Elegí una parada de origen y una de destino de la lista de sugerencias."
        } else if (o.id == d.id) {
            mensaje = "El origen y el destino son la misma parada."
        } else {
            alcance.launch {
                buscando = true
                rutas = emptyList()
                seleccion = 0
                try {
                    val encontradas = BondiApi.obtenerRutas(o.id, d.id, 5)
                    rutas = encontradas
                    if (encontradas.isEmpty()) {
                        mensaje = "No se encontró ningún recorrido entre esas paradas."
                    }
                } catch (e: ApiException) {
                    mensaje = e.message ?: "Ocurrió un error inesperado."
                } finally {
                    buscando = false
                }
            }
        }
    }

    // PASO 3: tocar una parada en el mapa la elige como origen y después como destino.
    fun alTocarParada(p: Parada) {
        if (origenId == null || destinoId != null) {
            origenId = p.id
            textoOrigen = p.nombre
            destinoId = null
            textoDestino = ""
            rutas = emptyList()
        } else {
            destinoId = p.id
            textoDestino = p.nombre
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TextButton(onClick = volver) {
            Text("← Volver")
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "¿A dónde vamos?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text("Escribí una calle o tocá una parada en el mapa.")

        Spacer(Modifier.height(16.dp))

        // Estado de la carga de paradas
        val errorCarga = errorParadas
        if (cargandoParadas) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Cargando paradas…")
            }
            Spacer(Modifier.height(12.dp))
        } else if (errorCarga != null) {
            Text(errorCarga, color = MaterialTheme.colorScheme.error)
            Button(onClick = { intento++ }) { Text("Reintentar") }
            Spacer(Modifier.height(12.dp))
        }

        CampoParada(
            etiqueta = "Parada de origen",
            ejemplo = "Ej: Terminal",
            texto = textoOrigen,
            onTextoChange = { textoOrigen = it; origenId = null; rutas = emptyList() },
            seleccionada = origen,
            paradas = paradas,
            onSeleccion = { textoOrigen = it.nombre; origenId = it.id }
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                val t = textoOrigen; textoOrigen = textoDestino; textoDestino = t
                val i = origenId; origenId = destinoId; destinoId = i
                rutas = emptyList()
                mensaje = ""
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("⇅ Intercambiar")
        }

        Spacer(Modifier.height(12.dp))

        CampoParada(
            etiqueta = "Parada de destino",
            ejemplo = "Ej: Centro",
            texto = textoDestino,
            onTextoChange = { textoDestino = it; destinoId = null; rutas = emptyList() },
            seleccionada = destino,
            paradas = paradas,
            onSeleccion = { textoDestino = it.nombre; destinoId = it.id }
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { buscar() },
            enabled = !buscando && !cargandoParadas,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (buscando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Buscando…")
            } else {
                Text("Buscar recorrido")
            }
        }

        if (mensaje.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(mensaje, color = MaterialTheme.colorScheme.error)
        }

        // Selector de alternativas (una "chip" por cada recorrido)
        if (rutas.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Se encontraron ${rutas.size} alternativa(s)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rutas.forEachIndexed { i, r ->
                    FilterChip(
                        selected = i == seleccion,
                        onClick = { seleccion = i },
                        label = { Text("Opción ${i + 1} · ${r.tiempoTotal.roundToInt()} min") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // PASO 4: el mapa recibe todas las paradas y la alternativa elegida.
        MapaBondi(
            paradas = paradas,
            ruta = rutas.getOrNull(seleccion),
            onParadaTocada = { alTocarParada(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
        )

        rutas.getOrNull(seleccion)?.let { ruta ->
            Spacer(Modifier.height(16.dp))
            DetalleRuta(ruta)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Campo de texto con sugerencias: filtra las paradas ya descargadas, sin llamar a la red. */
@Composable
fun CampoParada(
    etiqueta: String,
    ejemplo: String,
    texto: String,
    onTextoChange: (String) -> Unit,
    seleccionada: Parada?,
    paradas: List<Parada>,
    onSeleccion: (Parada) -> Unit
) {
    // Sugerencias: aparecen solo si todavía no se eligió una parada y hay 2+ letras.
    val sugerencias = remember(texto, seleccionada, paradas) {
        if (seleccionada != null || texto.trim().length < 2) {
            emptyList()
        } else {
            val buscado = normalizar(texto)
            paradas.filter { normalizar(it.nombre).contains(buscado) || it.id.equals(texto.trim(), true) }
                .take(6)
        }
    }

    Column {
        OutlinedTextField(
            value = texto,
            onValueChange = onTextoChange,
            label = { Text(etiqueta) },
            placeholder = { Text(ejemplo) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (seleccionada != null) {
            Text(
                "✓ ${seleccionada.nombre} (${seleccionada.id})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        sugerencias.forEach { p ->
            Text(
                text = "${p.nombre}  (${p.id})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeleccion(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            HorizontalDivider()
        }
    }
}

/** Sin mayúsculas ni tildes, para que "sán martín" encuentre "SAN MARTIN". */
fun normalizar(texto: String): String =
    Normalizer.normalize(texto.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

/** Resumen y paso a paso de una alternativa. */
@Composable
fun DetalleRuta(ruta: Ruta) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val tipo = if (ruta.transbordos == 0) "Directo" else "${ruta.transbordos} transbordo(s)"
            Text(
                "${ruta.tiempoTotal.roundToInt()} min · ${"%.1f".format(ruta.distancia / 1000)} km · $tipo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (ruta.transbordos > 0) {
                Text(
                    "Incluye unos ${ruta.tiempoEspera.roundToInt()} min de espera por transbordo.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(12.dp))

            if (ruta.etapas.isEmpty()) {
                // Plan B: si el servidor no mandó las etapas, al menos mostramos las líneas.
                Text("Líneas: " + ruta.lineas.joinToString(" → ") { "Línea $it" })
            }

            ruta.etapas.forEachIndexed { i, etapa ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EtiquetaLinea(etapa.linea)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${etapa.paradasIntermedias} paradas · ~${etapa.tiempo.roundToInt()} min",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(4.dp))

                if (i == 0) {
                    Text("Subí en: ${etapa.origen.nombre}", style = MaterialTheme.typography.bodyMedium)
                }

                val transbordo = ruta.transbordosDetalle.getOrNull(i)
                if (transbordo != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "🔁 ${transbordo.instruccion}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text("Bajá en: ${etapa.destino.nombre}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Etiqueta redonda con el número de línea, del mismo color que su tramo en el mapa. */
@Composable
fun EtiquetaLinea(linea: String) {
    Surface(
        color = Color(colorDeLinea(linea)),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "Línea $linea",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun PantallaAdministrador(volver: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Modo Administrador",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(16.dp))

        Text("Próximamente: gestión de líneas y paradas.")

        Spacer(Modifier.height(24.dp))

        Button(onClick = volver) {
            Text("Volver al inicio")
        }
    }
}
