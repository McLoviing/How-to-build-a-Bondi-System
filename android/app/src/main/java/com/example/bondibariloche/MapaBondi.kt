package com.example.bondibariloche

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.Ruta
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme

// Colores de los marcadores (formato ARGB)
private const val COLOR_ORIGEN = 0xFF2E7D32.toInt()      // verde
private const val COLOR_DESTINO = 0xFFC62828.toInt()     // rojo
private const val COLOR_TRANSBORDO = 0xFFEF6C00.toInt()  // naranja
private const val COLOR_PARADA = 0xFF1565C0.toInt()      // azul
private const val COLOR_SIN_ETAPAS = 0xFF546E7A.toInt()  // gris azulado

// Paleta para distinguir las líneas de colectivo
private val PALETA_LINEAS = intArrayOf(
    0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(),
    0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt()
)

// Servidor gratuito de baldosas OpenStreetMap (OSM France) - no requiere API Key ni bloquea peticiones
private val TILE_SOURCE_OSM_FR = XYTileSource(
    "OpenStreetMapFrance",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.fr/osmfr/",
        "https://b.tile.openstreetmap.fr/osmfr/",
        "https://c.tile.openstreetmap.fr/osmfr/"
    ),

)

/** Cada línea siempre recibe el mismo color (sirve para el mapa y para las etiquetas de la lista). */
fun colorDeLinea(linea: String): Int =
    PALETA_LINEAS[(linea.hashCode() and 0x7FFFFFFF) % PALETA_LINEAS.size]

/**
 * Mapa con:
 *  1. TODAS las paradas como puntos azules (se tocan para elegirlas como origen/destino).
 *  2. La ruta seleccionada: un tramo de color por línea + marcadores de origen, destino y transbordos.
 */
@Composable
fun MapaBondi(
    paradas: List<Parada>,
    ruta: Ruta?,
    onParadaTocada: (Parada) -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current

    // Siempre usa la última versión del callback, aunque el overlay se haya creado antes.
    val alTocarParada by rememberUpdatedState(onParadaTocada)
    val paradasPorId = remember(paradas) { paradas.associateBy { it.id } }

    // El MapView se crea una sola vez.
    val mapa = remember(contexto) {
        val userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 BondiBariloche/1.0"
        Configuration.getInstance().apply {
            userAgentValue = userAgent
            load(contexto, contexto.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = userAgent
        }

        // Eliminar base de datos SQLite y caché previa para purgar imágenes con marca de agua o error
        try {
            contexto.deleteDatabase("osmdroid.db")
            contexto.deleteDatabase("osmdroid-sqlite.db")
            val osmdroidCache = Configuration.getInstance().osmdroidTileCache
            if (osmdroidCache.exists()) osmdroidCache.deleteRecursively()
            val osmdroidBase = Configuration.getInstance().osmdroidBasePath
            if (osmdroidBase.exists()) osmdroidBase.deleteRecursively()
        } catch (_: Exception) {}

        MapView(contexto).apply {
            setTileSource(TILE_SOURCE_OSM_FR)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(-41.1335, -71.3103)) // centro de Bariloche
            tileProvider.clearTileCache()

            // El mapa vive dentro de una pantalla con scroll vertical: sin esto,
            // al arrastrar el mapa se desplazaría la pantalla en lugar del mapa.
            setOnTouchListener { vista, evento ->
                if (evento.actionMasked == MotionEvent.ACTION_DOWN ||
                    evento.actionMasked == MotionEvent.ACTION_MOVE
                ) {
                    vista.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

    // -------- Paso 1: capa con todas las paradas --------
    // Se arma una sola vez por lista de paradas (no en cada recomposición).
    val capaParadas = remember(mapa, paradas) {
        if (paradas.isEmpty()) {
            null
        } else {
            // La etiqueta de cada punto es el id de la parada: así, al tocar un punto,
            // sabemos qué parada es y buscamos sus datos en paradasPorId.
            val puntos = ArrayList<IGeoPoint>()
            paradas.forEach { puntos.add(LabelledGeoPoint(it.latitud, it.longitud, it.id)) }

            val pintura = Paint().apply {
                style = Paint.Style.FILL
                color = COLOR_PARADA
                isAntiAlias = true
            }
            val opciones = SimpleFastPointOverlayOptions.getDefaultStyle()
                .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                .setRadius(6f)
                .setIsClickable(true)
                .setCellSize(15)
                .setPointStyle(pintura)

            SimpleFastPointOverlay(SimplePointTheme(puntos, false), opciones).apply {
                setOnClickListener { puntosAdaptados, indice ->
                    val id = (puntosAdaptados.get(indice) as? LabelledGeoPoint)?.label
                    val parada = id?.let { paradasPorId[it] }
                    if (parada != null) alTocarParada(parada)
                }
            }
        }
    }

    // Ciclo de vida del mapa (igual que en la versión anterior).
    DisposableEffect(mapa) {
        mapa.onResume()
        onDispose {
            mapa.onPause()
            mapa.onDetach()
        }
    }

    // -------- Paso 3: al cambiar de ruta, encuadrar el mapa sobre el recorrido --------
    LaunchedEffect(ruta) {
        if (ruta != null && ruta.paradas.size >= 2) {
            val caja = BoundingBox.fromGeoPoints(ruta.paradas.map { GeoPoint(it.latitud, it.longitud) })
            // post: espera a que el mapa tenga tamaño antes de calcular el zoom
            mapa.post { mapa.zoomToBoundingBox(caja, true, 100) }
        }
    }

    Column {
        AndroidView(
            factory = { mapa },
            // -------- Paso 2: update se ejecuta cada vez que cambia la ruta --------
            update = { vista ->
                vista.overlays.clear()                       // limpia lo anterior
                capaParadas?.let { vista.overlays.add(it) }  // 1º las paradas (quedan debajo)
                if (ruta != null) dibujarRuta(vista, ruta)   // 2º la ruta (queda encima)
                vista.invalidate()                           // pide redibujar
            },
            modifier = modifier
        )
        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Agrega al mapa los tramos de color y los marcadores de una ruta. */
private fun dibujarRuta(vista: MapView, ruta: Ruta) {
    // A) Un tramo (polilínea) por etapa, del color de su línea.
    if (ruta.etapas.isNotEmpty()) {
        ruta.etapas.forEach { etapa ->
            val puntos = ruta.paradasDeEtapa(etapa).map { GeoPoint(it.latitud, it.longitud) }
            vista.overlays.add(crearTramo(puntos, colorDeLinea(etapa.linea)))
        }
    } else {
        // Plan B (servidor sin etapas): un solo tramo gris con todo el recorrido.
        val puntos = ruta.paradas.map { GeoPoint(it.latitud, it.longitud) }
        vista.overlays.add(crearTramo(puntos, COLOR_SIN_ETAPAS))
    }

    // B) Marcadores de transbordo (naranja).
    ruta.transbordosDetalle.forEach { t ->
        vista.overlays.add(
            crearMarcador(
                vista, t.parada, COLOR_TRANSBORDO, 18,
                "Transbordo: bajá de la ${t.bajarDe}, subí a la ${t.subirA}"
            )
        )
    }

    // C) Origen (verde) y destino (rojo), encima de todo lo demás.
    ruta.paradas.firstOrNull()?.let {
        vista.overlays.add(crearMarcador(vista, it, COLOR_ORIGEN, 22, "Origen"))
    }
    ruta.paradas.lastOrNull()?.let {
        vista.overlays.add(crearMarcador(vista, it, COLOR_DESTINO, 22, "Destino"))
    }
}

private fun crearTramo(puntos: List<GeoPoint>, color: Int): Polyline =
    Polyline().apply {
        setPoints(puntos)
        outlinePaint.color = color
        outlinePaint.strokeWidth = 10f
    }

private fun crearMarcador(
    vista: MapView,
    parada: Parada,
    color: Int,
    tamanoDp: Int,
    titulo: String
): Marker =
    Marker(vista).apply {
        position = GeoPoint(parada.latitud, parada.longitud)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)  // el círculo queda centrado en la parada
        icon = crearCirculo(vista.context, color, tamanoDp)
        title = titulo
        snippet = parada.nombre
    }

/** Círculo de color con borde blanco, dibujado por código (no hace falta ningún archivo de imagen). */
private fun crearCirculo(contexto: Context, color: Int, tamanoDp: Int): Drawable {
    val densidad = contexto.resources.displayMetrics.density
    val px = (tamanoDp * densidad).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * densidad).toInt(), android.graphics.Color.WHITE)
        setSize(px, px)
    }
}
