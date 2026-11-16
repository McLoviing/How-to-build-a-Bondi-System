package com.example.bondibariloche

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.Ruta
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

enum class EstiloVectorial(val url: String, val etiqueta: String, val descripcion: String) {
    LIBERTY(
        "https://tiles.openfreemap.org/styles/liberty",
        "Liberty (Recomendado)",
        "Vectorial moderno de alta definición"
    ),
    POSITRON(
        "https://tiles.openfreemap.org/styles/positron",
        "Positron (Minimalista)",
        "Gris claro minimalista, resalta al máximo las líneas"
    ),
    BRIGHT(
        "https://tiles.openfreemap.org/styles/bright",
        "Bright (Claro)",
        "Cartografía limpia, luminosa y con nombres de calles nítidos"
    )
}

private const val COLOR_ORIGEN = 0xFF2E7D32.toInt()
private const val COLOR_DESTINO = 0xFFD32F2F.toInt()
private const val COLOR_TRANSBORDO = 0xFFF57C00.toInt()
private const val COLOR_PARADA = 0xFF1976D2.toInt()
private const val COLOR_SIN_ETAPAS = "#455A64"
private const val COLOR_CAMINATA_HEX = "#EF6C00"

private const val SRC_BUS = "bondi-source-bus"
private const val SRC_WALK = "bondi-source-walk"
private const val LYR_BUS_CASING = "bondi-layer-bus-casing"
private const val LYR_BUS = "bondi-layer-bus"
private const val LYR_WALK_CASING = "bondi-layer-walk-casing"
private const val LYR_WALK = "bondi-layer-walk"

private val PALETA_LINEAS = intArrayOf(
    0xFF1976D2.toInt(), // Azul
    0xFF388E3C.toInt(), // Verde
    0xFF7B1FA2.toInt(), // Violeta
    0xFFE65100.toInt(), // Naranja profundo
    0xFF00796B.toInt(), // Verde azulado
    0xFF303F9F.toInt(), // Índigo
    0xFF5D4037.toInt(), // Marrón cálido
    0xFFC2185B.toInt()  // Magenta
)

fun colorDeLineaVectorial(linea: String): Int =
    PALETA_LINEAS[(linea.hashCode() and 0x7FFFFFFF) % PALETA_LINEAS.size]

private fun colorHexDeLinea(linea: String): String {
    val rgb = colorDeLineaVectorial(linea) and 0x00FFFFFF
    return String.format("#%06X", rgb)
}

@Composable
fun MapaMapLibre(
    paradas: List<Parada>,
    ruta: Ruta?,
    onParadaTocada: (Parada) -> Unit,
    modifier: Modifier = Modifier,
    estilo: EstiloVectorial = EstiloVectorial.LIBERTY
) {
    val contexto = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alTocarParada by rememberUpdatedState(onParadaTocada)

    val paradasRef = remember { mutableStateOf(paradas) }
    paradasRef.value = paradas

    LaunchedEffect(Unit) {
        MapLibre.getInstance(contexto)
    }

    var maplibreMapInstancia by remember { mutableStateOf<MapLibreMap?>(null) }
    var estiloCargado by remember { mutableStateOf(false) }

    val iconoParada = remember(contexto) {
        IconFactory.getInstance(contexto).fromBitmap(crearBitmapCirculo(COLOR_PARADA, 14, contexto, conBorde = true))
    }
    val iconoOrigen = remember(contexto) {
        IconFactory.getInstance(contexto).fromBitmap(crearBitmapCirculo(COLOR_ORIGEN, 24, contexto, conBorde = true))
    }
    val iconoDestino = remember(contexto) {
        IconFactory.getInstance(contexto).fromBitmap(crearBitmapCirculo(COLOR_DESTINO, 24, contexto, conBorde = true))
    }
    val iconoTransbordo = remember(contexto) {
        IconFactory.getInstance(contexto).fromBitmap(crearBitmapCirculo(COLOR_TRANSBORDO, 20, contexto, conBorde = true))
    }

    val mapView = remember(contexto) {
        MapLibre.getInstance(contexto)
        MapView(contexto).apply {
            getMapAsync { map ->
                maplibreMapInstancia = map
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-41.1335, -71.3103))
                    .zoom(13.5)
                    .build()

                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = true

                map.setStyle(estilo.url) { style ->
                    inicializarCapasRuta(style)
                    estiloCargado = true
                }

                map.addOnMapClickListener { puntoTocado ->
                    val listaActual = paradasRef.value
                    if (listaActual.isEmpty()) return@addOnMapClickListener false

                    var paradaCercana: Parada? = null
                    var distanciaMinima = Double.MAX_VALUE
                    listaActual.forEach { p ->
                        val dist = puntoTocado.distanceTo(LatLng(p.latitud, p.longitud))
                        if (dist < distanciaMinima) {
                            distanciaMinima = dist
                            paradaCercana = p
                        }
                    }

                    val p = paradaCercana
                    if (p != null && distanciaMinima <= 300.0) {
                        alTocarParada(p)
                        true
                    } else {
                        false
                    }
                }

                @Suppress("DEPRECATION")
                map.setOnMarkerClickListener { marker ->
                    val idParada = marker.snippet
                    if (idParada != null) {
                        val parada = paradasRef.value.firstOrNull { it.id == idParada }
                        if (parada != null) {
                            alTocarParada(parada)
                            return@setOnMarkerClickListener true
                        }
                    }
                    false
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(estilo, maplibreMapInstancia) {
        val map = maplibreMapInstancia ?: return@LaunchedEffect
        estiloCargado = false
        map.setStyle(estilo.url) { style ->
            inicializarCapasRuta(style)
            estiloCargado = true
        }
    }

    LaunchedEffect(maplibreMapInstancia, estiloCargado, paradas, ruta) {
        val map = maplibreMapInstancia ?: return@LaunchedEffect
        if (!estiloCargado) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        map.clear()

        val srcBus = style.getSourceAs<GeoJsonSource>(SRC_BUS)
        val srcWalk = style.getSourceAs<GeoJsonSource>(SRC_WALK)

        if (ruta == null) {
            srcBus?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            srcWalk?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))

            paradas.forEach { p ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(p.latitud, p.longitud))
                        .title(p.nombre)
                        .snippet(p.id)
                        .icon(iconoParada)
                )
            }
            return@LaunchedEffect
        }

        // Construir features GeoJSON separadas por modo (Colectivo vs Caminata)
        val featuresBus = mutableListOf<Feature>()
        val featuresWalk = mutableListOf<Feature>()

        if (ruta.etapas.isNotEmpty()) {
            var cursorParadas = 0
            ruta.etapas.forEach { etapa ->
                val tramoParadas = ruta.paradasDeEtapa(etapa, cursorParadas)
                if (tramoParadas.size >= 2) {
                    cursorParadas += (tramoParadas.size - 1).coerceAtLeast(0)
                    val coords = tramoParadas.map { Point.fromLngLat(it.longitud, it.latitud) }
                    val feature = Feature.fromGeometry(LineString.fromLngLats(coords))

                    if (etapa.esCaminata) {
                        featuresWalk.add(feature)
                    } else {
                        feature.addStringProperty("color", colorHexDeLinea(etapa.linea))
                        featuresBus.add(feature)
                    }
                }
            }
        } else if (ruta.paradas.size >= 2) {
            val coords = ruta.paradas.map { Point.fromLngLat(it.longitud, it.latitud) }
            val feature = Feature.fromGeometry(LineString.fromLngLats(coords)).apply {
                addStringProperty("color", COLOR_SIN_ETAPAS)
            }
            featuresBus.add(feature)
        }

        srcBus?.setGeoJson(FeatureCollection.fromFeatures(featuresBus))
        srcWalk?.setGeoJson(FeatureCollection.fromFeatures(featuresWalk))

        // Marcadores de transbordo (doble marcador si hay caminata intermedia)
        ruta.transbordosDetalle.forEach { t ->
            if (t.requiereCaminata && t.paradaSubida != null) {
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(t.parada.latitud, t.parada.longitud))
                        .title("Bajar de Línea ${t.bajarDe} · Caminar ${t.metrosCaminata.toInt()} m")
                        .snippet(t.parada.id)
                        .icon(iconoTransbordo)
                )
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(t.paradaSubida.latitud, t.paradaSubida.longitud))
                        .title("Subir a Línea ${t.subirA} (${t.paradaSubida.nombre})")
                        .snippet(t.paradaSubida.id)
                        .icon(iconoTransbordo)
                )
            } else {
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(t.parada.latitud, t.parada.longitud))
                        .title("Transbordo: ${t.bajarDe} → ${t.subirA}")
                        .snippet(t.parada.id)
                        .icon(iconoTransbordo)
                )
            }
        }

        ruta.paradas.firstOrNull()?.let {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(it.latitud, it.longitud))
                    .title("Origen: ${it.nombre}")
                    .snippet(it.id)
                    .icon(iconoOrigen)
            )
        }
        ruta.paradas.lastOrNull()?.let {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(it.latitud, it.longitud))
                    .title("Destino: ${it.nombre}")
                    .snippet(it.id)
                    .icon(iconoDestino)
            )
        }

        if (ruta.paradas.size >= 2) {
            val builder = LatLngBounds.Builder()
            ruta.paradas.forEach { builder.include(LatLng(it.latitud, it.longitud)) }
            val bounds = builder.build()

            mapView.post {
                if (mapView.width > 0 && mapView.height > 0) {
                    try {
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140), 900)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Inicializa las fuentes GeoJSON y capas GL en GPU para dibujar:
 * 1) Líneas de colectivo sólidas con borde blanco.
 * 2) Tramos peatonales punteados (lineDasharray) en naranja.
 */
private fun inicializarCapasRuta(style: Style) {
    if (style.getSource(SRC_BUS) == null) {
        style.addSource(GeoJsonSource(SRC_BUS))
    }
    if (style.getSource(SRC_WALK) == null) {
        style.addSource(GeoJsonSource(SRC_WALK))
    }

    if (style.getLayer(LYR_BUS_CASING) == null) {
        style.addLayer(
            LineLayer(LYR_BUS_CASING, SRC_BUS).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(8.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    if (style.getLayer(LYR_BUS) == null) {
        style.addLayer(
            LineLayer(LYR_BUS, SRC_BUS).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    if (style.getLayer(LYR_WALK_CASING) == null) {
        style.addLayer(
            LineLayer(LYR_WALK_CASING, SRC_WALK).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(7.0f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        )
    }

    if (style.getLayer(LYR_WALK) == null) {
        style.addLayer(
            LineLayer(LYR_WALK, SRC_WALK).withProperties(
                PropertyFactory.lineColor(COLOR_CAMINATA_HEX),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineDasharray(arrayOf(1.6f, 1.4f))
            )
        )
    }
}

private fun crearBitmapCirculo(color: Int, tamanoDp: Int, contexto: Context, conBorde: Boolean = true): Bitmap {
    val densidad = contexto.resources.displayMetrics.density
    val px = (tamanoDp * densidad).toInt().coerceAtLeast(10)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centro = px / 2f

    if (conBorde) {
        val paintBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centro, centro, centro, paintBorde)
    }

    val paintRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    val radioRelleno = if (conBorde) centro - (2.2f * densidad) else centro
    canvas.drawCircle(centro, centro, radioRelleno.coerceAtLeast(2f), paintRelleno)

    return bitmap
}
