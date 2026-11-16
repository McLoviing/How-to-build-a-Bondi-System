package com.example.bondibariloche

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.data.ApiConfig
import com.example.bondibariloche.data.ApiException
import com.example.bondibariloche.data.BondiApi
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.Ruta
import com.example.bondibariloche.ui.theme.BondiBarilocheTheme
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import java.text.Normalizer
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.inicializar(this)
        MapLibre.getInstance(this)

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

        Spacer(Modifier.height(8.dp))
        Text(
            "Transporte público inteligente",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = usuario,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Ingresar como Usuario", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = administrador,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Ingresar como Administrador", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ======================================================================
// PANTALLA DEL USUARIO: ESTILO UBER / GOOGLE MAPS
// ======================================================================

@Composable
fun PantallaUsuario(volver: () -> Unit) {
    val alcance = rememberCoroutineScope()

    // ---- Datos que vienen de la API ----
    var paradas by remember { mutableStateOf<List<Parada>>(emptyList()) }
    var cargandoParadas by remember { mutableStateOf(true) }
    var errorParadas by remember { mutableStateOf<String?>(null) }
    var intento by remember { mutableStateOf(0) }

    // ---- Estilo de Mapa ----
    var estiloVectorial by rememberSaveable { mutableStateOf(EstiloVectorial.LIBERTY.name) }
    var mostrarDialogoEstilo by remember { mutableStateOf(false) }

    // ---- Lo que elige el usuario ----
    var textoOrigen by rememberSaveable { mutableStateOf("") }
    var textoDestino by rememberSaveable { mutableStateOf("") }
    var origenId by rememberSaveable { mutableStateOf<String?>(null) }
    var destinoId by rememberSaveable { mutableStateOf<String?>(null) }
    val origen = paradas.firstOrNull { it.id == origenId }
    val destino = paradas.firstOrNull { it.id == destinoId }

    var buscadorExpandido by remember { mutableStateOf(true) }

    // ---- Resultado de la búsqueda ----
    var rutas by remember { mutableStateOf<List<Ruta>>(emptyList()) }
    var seleccion by remember { mutableStateOf(0) }
    var buscando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }

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

    fun buscar() {
        val o = origen
        val d = destino
        mensaje = ""
        if (o == null || d == null) {
            mensaje = "Elegí una parada de origen y una de destino."
            buscadorExpandido = true
        } else if (o.id == d.id) {
            mensaje = "El origen y el destino corresponden a la misma parada."
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
                    } else {
                        buscadorExpandido = false
                    }
                } catch (e: ApiException) {
                    mensaje = e.message ?: "Ocurrió un error inesperado."
                } finally {
                    buscando = false
                }
            }
        }
    }

    fun alTocarParada(p: Parada) {
        if (origenId == null || destinoId != null) {
            origenId = p.id
            textoOrigen = p.nombre
            destinoId = null
            textoDestino = ""
            rutas = emptyList()
            buscadorExpandido = true
        } else {
            destinoId = p.id
            textoDestino = p.nombre
            buscar()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // 1. MAPA VECTORIAL NATIVO EN EL FONDO
        MapaMapLibre(
            paradas = paradas,
            ruta = rutas.getOrNull(seleccion),
            onParadaTocada = { alTocarParada(it) },
            modifier = Modifier.fillMaxSize(),
            estilo = runCatching { EstiloVectorial.valueOf(estiloVectorial) }.getOrDefault(EstiloVectorial.LIBERTY)
        )

        // 2. PANEL SUPERIOR FLOTANTE
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { volver() }
                    ) {
                        Text("←", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Bariloche Bondi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { mostrarDialogoEstilo = true }) {
                            Text("🗺️", style = MaterialTheme.typography.titleSmall)
                        }
                        IconButton(onClick = { buscadorExpandido = !buscadorExpandido }) {
                            Text(if (buscadorExpandido) "▲" else "▼", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

                if (buscadorExpandido) {
                    Spacer(Modifier.height(6.dp))

                    if (errorParadas != null) {
                        Text(errorParadas!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { intento++ }, modifier = Modifier.height(36.dp)) {
                            Text("Reintentar conexión", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    CampoParada(
                        etiqueta = "Origen",
                        ejemplo = "Escribí calle o tocá en el mapa...",
                        texto = textoOrigen,
                        onTextoChange = { textoOrigen = it; origenId = null; rutas = emptyList() },
                        seleccionada = origen,
                        paradas = paradas,
                        onSeleccion = { textoOrigen = it.nombre; origenId = it.id },
                        iconoColor = Color(0xFF2E7D32)
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            CampoParada(
                                etiqueta = "Destino",
                                ejemplo = "Escribí calle o tocá en el mapa...",
                                texto = textoDestino,
                                onTextoChange = { textoDestino = it; destinoId = null; rutas = emptyList() },
                                seleccionada = destino,
                                paradas = paradas,
                                onSeleccion = { textoDestino = it.nombre; destinoId = it.id },
                                iconoColor = Color(0xFFC62828)
                            )
                        }
                        IconButton(
                            onClick = {
                                val t = textoOrigen; textoOrigen = textoDestino; textoDestino = t
                                val i = origenId; origenId = destinoId; destinoId = i
                                rutas = emptyList()
                                mensaje = ""
                            }
                        ) {
                            Text("⇅", style = MaterialTheme.typography.titleLarge)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = { buscar() },
                        enabled = !buscando && !cargandoParadas,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (buscando) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Calculando...")
                        } else {
                            Text("Buscar recorrido")
                        }
                    }

                    if (mensaje.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(mensaje, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        text = "${origen?.nombre ?: "Sin origen"} → ${destino?.nombre ?: "Sin destino"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // 3. PANEL INFERIOR FLOTANTE CON LOS RESULTADOS DEL RECORRIDO
        AnimatedVisibility(
            visible = rutas.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "${rutas.size} alternativa(s) recomendada(s)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    SelectorAlternativasRuta(
                        rutas = rutas,
                        seleccion = seleccion,
                        onSeleccionar = { nuevoIndice -> seleccion = nuevoIndice }
                    )

                    Spacer(Modifier.height(10.dp))

                    rutas.getOrNull(seleccion)?.let { ruta ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            DetalleRuta(ruta)
                        }
                    }
                }
            }
        }

        if (mostrarDialogoEstilo) {
            DialogoEstiloMapa(
                estiloActual = runCatching { EstiloVectorial.valueOf(estiloVectorial) }.getOrDefault(EstiloVectorial.LIBERTY),
                onSeleccionar = { nuevoEstilo ->
                    estiloVectorial = nuevoEstilo.name
                    mostrarDialogoEstilo = false
                },
                onDismiss = { mostrarDialogoEstilo = false }
            )
        }
    }
}

/**
 * Carrusel horizontal perezoso (LazyRow) que muestra las alternativas de recorrido
 * con sus líneas de colectivo, tiempo total e indicador de caminata.
 */
@Composable
fun SelectorAlternativasRuta(
    rutas: List<Ruta>,
    seleccion: Int,
    onSeleccionar: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        itemsIndexed(
            items = rutas,
            key = { index, ruta -> "${index}_${ruta.lineas.joinToString("-")}_${ruta.tiempoTotal}" }
        ) { i, r ->
            val lineasBus = remember(r.lineas) {
                r.lineas.filter { !it.equals("CAMINANDO", ignoreCase = true) }
            }
            val incluyeCaminata = remember(r.etapas) {
                r.etapas.any { it.linea.equals("CAMINANDO", ignoreCase = true) }
            }
            val resumenLineas = remember(lineasBus) {
                if (lineasBus.isEmpty()) "A pie" else lineasBus.joinToString(" → ")
            }

            FilterChip(
                selected = (i == seleccion),
                onClick = { onSeleccionar(i) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$resumenLineas · ${r.tiempoTotal.roundToInt()} min",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (i == seleccion) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                        if (incluyeCaminata) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "🚶",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            )
        }
    }
}

/** Campo de texto con sugerencias de paradas acotadas. */
@Composable
fun CampoParada(
    etiqueta: String,
    ejemplo: String,
    texto: String,
    onTextoChange: (String) -> Unit,
    seleccionada: Parada?,
    paradas: List<Parada>,
    onSeleccion: (Parada) -> Unit,
    iconoColor: Color = MaterialTheme.colorScheme.primary
) {
    val sugerencias = remember(texto, seleccionada, paradas) {
        if (seleccionada != null || texto.trim().length < 2) {
            emptyList()
        } else {
            val buscado = normalizar(texto)
            paradas.filter { normalizar(it.nombre).contains(buscado) || it.id.equals(texto.trim(), true) }
                .take(4)
        }
    }

    Column {
        OutlinedTextField(
            value = texto,
            onValueChange = onTextoChange,
            label = { Text(etiqueta) },
            placeholder = { Text(ejemplo) },
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(iconoColor)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (seleccionada != null) {
            Text(
                "✓ ${seleccionada.nombre} (${seleccionada.id})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp)
            )
        }

        if (sugerencias.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Column {
                    sugerencias.forEach { p ->
                        Text(
                            text = "${p.nombre} (${p.id})",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeleccion(p) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

fun normalizar(texto: String): String =
    Normalizer.normalize(texto.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

/**
 * Modelo de proyección UI (Sealed Interface) que convierte una Ruta compleja
 * en una lista lineal y sin ambigüedades de pasos visuales.
 */
private sealed interface PasoItinerario {
    data class Inicio(val paradaNombre: String) : PasoItinerario

    data class ViajeColectivo(
        val linea: String,
        val paradasIntermedias: Int,
        val tiempoMin: Int,
        val colorLinea: Color
    ) : PasoItinerario

    data class Caminata(
        val metros: Int,
        val tiempoMin: Int,
        val esHaciaDestinoFinal: Boolean
    ) : PasoItinerario

    data class ParadaTransbordo(
        val paradaBajada: String,
        val paradaSubida: String?, // No nulo si hay que caminar a otra esquina
        val metrosCaminata: Int,
        val lineaSiguiente: String,
        val esperaMin: Int
    ) : PasoItinerario

    data class DestinoFinal(val paradaNombre: String) : PasoItinerario
}

/**
 * Proyecta el DTO [Ruta] en una lista secuencial de [PasoItinerario] (O(N)).
 * Elimina repeticiones de nombres de paradas y fusiona bajada + caminata + subida.
 */
private fun construirPasosTimeline(ruta: Ruta): List<PasoItinerario> {
    if (ruta.etapas.isEmpty()) return emptyList()
    val pasos = mutableListOf<PasoItinerario>()

    // 1. Nodo inicial
    pasos.add(PasoItinerario.Inicio(ruta.etapas.first().origen.nombre))

    var idx = 0
    while (idx < ruta.etapas.size) {
        val actual = ruta.etapas[idx]
        val siguiente = ruta.etapas.getOrNull(idx + 1)
        val subsiguiente = ruta.etapas.getOrNull(idx + 2)

        if (actual.esCaminata) {
            // Caminata al inicio del viaje o al final hacia destino
            val esFinal = (idx == ruta.etapas.lastIndex)
            pasos.add(
                PasoItinerario.Caminata(
                    metros = actual.distancia.roundToInt(),
                    tiempoMin = maxOf(1, actual.tiempo.roundToInt()),
                    esHaciaDestinoFinal = esFinal
                )
            )
            if (esFinal) {
                pasos.add(PasoItinerario.DestinoFinal(actual.destino.nombre))
            } else if (siguiente != null) {
                // Llegó caminando a la parada donde toma el primer colectivo
                pasos.add(PasoItinerario.Inicio(actual.destino.nombre))
            }
            idx++
        } else {
            // Tramo en colectivo
            pasos.add(
                PasoItinerario.ViajeColectivo(
                    linea = actual.linea,
                    paradasIntermedias = actual.paradasIntermedias,
                    tiempoMin = maxOf(1, actual.tiempo.roundToInt()),
                    colorLinea = Color(colorDeLineaVectorial(actual.linea))
                )
            )

            when {
                // Caso A: Última etapa -> Llegó a destino
                siguiente == null -> {
                    pasos.add(PasoItinerario.DestinoFinal(actual.destino.nombre))
                    idx++
                }
                // Caso B: Transbordo directo en la misma parada física
                !siguiente.esCaminata -> {
                    pasos.add(
                        PasoItinerario.ParadaTransbordo(
                            paradaBajada = actual.destino.nombre,
                            paradaSubida = null,
                            metrosCaminata = 0,
                            lineaSiguiente = siguiente.linea,
                            esperaMin = 8
                        )
                    )
                    idx++
                }
                // Caso C: Baja del colectivo, camina y toma otro colectivo (fusionado en un solo bloque claro)
                siguiente.esCaminata && subsiguiente != null && !subsiguiente.esCaminata -> {
                    pasos.add(
                        PasoItinerario.ParadaTransbordo(
                            paradaBajada = actual.destino.nombre,
                            paradaSubida = siguiente.destino.nombre,
                            metrosCaminata = siguiente.distancia.roundToInt(),
                            lineaSiguiente = subsiguiente.linea,
                            esperaMin = 8
                        )
                    )
                    idx += 2 // Saltamos la etapa intermedia de caminata porque ya quedó fusionada
                }
                // Caso D: Baja del último colectivo y camina al destino final
                else -> {
                    pasos.add(PasoItinerario.Inicio(actual.destino.nombre))
                    idx++
                }
            }
        }
    }
    return pasos
}

/**
 * Vista de Timeline Vertical estilo Google Maps / Moovit.
 */
@Composable
fun DetalleRuta(ruta: Ruta) {
    val pasos = remember(ruta) { construirPasosTimeline(ruta) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Cabecera resumen de métricas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tipo = if (ruta.transbordos == 0) "Directo" else "${ruta.transbordos} transbordo(s)"
            Text(
                text = "${ruta.tiempoTotal.roundToInt()} min · ${"%.1f".format(ruta.distancia / 1000)} km",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = tipo,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (ruta.transbordos > 0) {
            Text(
                text = "Incluye ~${ruta.tiempoEspera.roundToInt()} min de espera estimada en combinación",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        // Renderizado del Timeline Vertical
        pasos.forEach { paso ->
            when (paso) {
                is PasoItinerario.Inicio -> {
                    FilaNodoTimeline(
                        colorNodo = Color(0xFF2E7D32),
                        esDestino = false
                    ) {
                        Text(
                            text = paso.paradaNombre,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is PasoItinerario.ViajeColectivo -> {
                    FilaAristaTimeline(
                        colorLinea = paso.colorLinea,
                        punteada = false
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            EtiquetaLinea(paso.linea)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Viajá ${paso.paradasIntermedias} parada(s)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "~${paso.tiempoMin} min en unidad",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is PasoItinerario.Caminata -> {
                    FilaAristaTimeline(
                        colorLinea = Color(0xFFEF6C00),
                        punteada = true
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            EtiquetaLinea("CAMINANDO")
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Caminá ${paso.metros} m (~${paso.tiempoMin} min)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                is PasoItinerario.ParadaTransbordo -> {
                    FilaNodoTimeline(
                        colorNodo = Color(0xFFF57C00),
                        esDestino = false
                    ) {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Bajá en ${paso.paradaBajada}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFBF360C)
                                )
                                if (paso.paradaSubida != null) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "🚶 Caminá ${paso.metrosCaminata} m hasta ${paso.paradaSubida}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE65100)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "🚌 Tomá la Línea ${paso.lineaSiguiente} (espera ~${paso.esperaMin} min)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4E342E)
                                )
                            }
                        }
                    }
                }

                is PasoItinerario.DestinoFinal -> {
                    FilaNodoTimeline(
                        colorNodo = Color(0xFFD32F2F),
                        esDestino = true
                    ) {
                        Column {
                            Text(
                                text = paso.paradaNombre,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Destino final",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFD32F2F),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dibuja un nodo circular en el riel izquierdo (Origen, Transbordo o Destino). */
@Composable
private fun FilaNodoTimeline(
    colorNodo: Color,
    esDestino: Boolean,
    contenido: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (esDestino) 14.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (esDestino) colorNodo else Color.White)
                    .border(3.dp, colorNodo, CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            contenido()
        }
    }
}

/** Dibuja el conector vertical (sólido para colectivo, punteado para caminata) en el riel izquierdo. */
@Composable
private fun FilaAristaTimeline(
    colorLinea: Color,
    punteada: Boolean,
    contenido: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
        ) {
            val xCentro = size.width / 2f
            drawLine(
                color = colorLinea,
                start = Offset(xCentro, 0f),
                end = Offset(xCentro, size.height),
                strokeWidth = if (punteada) 3.dp.toPx() else 4.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (punteada) {
                    PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                } else null
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            contenido()
        }
    }
}

@Composable
fun EtiquetaLinea(linea: String) {
    val esCaminata = linea.equals("CAMINANDO", ignoreCase = true)
    val fondo = if (esCaminata) Color(0xFFEF6C00) else Color(colorDeLineaVectorial(linea))
    val texto = if (esCaminata) "🚶 A pie" else "Línea $linea"

    Surface(
        color = fondo,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = texto,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
        Text("Modo Administrador", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("Gestión de líneas y paradas.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = volver) {
            Text("Volver al inicio")
        }
    }
}

/** Diálogo para elegir entre estilos vectoriales MapLibre GL libres y sin API Key. */
@Composable
fun DialogoEstiloMapa(
    estiloActual: EstiloVectorial,
    onSeleccionar: (EstiloVectorial) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗺️", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("Estilo Vectorial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "Estilos de visualización del mapa:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                EstiloVectorial.values().forEach { estilo ->
                    val seleccionado = (estilo == estiloActual)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSeleccionar(estilo) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (seleccionado) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        tonalElevation = if (seleccionado) 4.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = seleccionado,
                                onClick = { onSeleccionar(estilo) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    estilo.etiqueta,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (seleccionado) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    estilo.descripcion,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
