package com.example.bondibariloche

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bondibariloche.data.CalculosGeograficos
import com.example.bondibariloche.data.GestorUbicacion
import com.example.bondibariloche.data.Parada
import com.example.bondibariloche.data.Ruta
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.max
import kotlin.math.min

// Estilo vectorial oscuro original de OpenFreeMap (100% gratuito y sin API key)
private const val URL_ESTILO_MAPA_ORIGINAL = "https://tiles.openfreemap.org/styles/liberty"

private const val SRC_PARADAS = "src-paradas"
private const val SRC_RUTA_BUS = "src-ruta-bus"
private const val SRC_RUTA_WALK = "src-ruta-walk"
private const val SRC_ADMIN = "src-admin"

private const val LAYER_ADMIN = "layer-admin"
private const val LAYER_RUTA_WALK = "layer-ruta-walk"
private const val LAYER_RUTA_BUS = "layer-ruta-bus"
private const val LAYER_PARADAS = "layer-paradas"

// Constantes geodésicas y ergonómicas calibradas para San Carlos de Bariloche
private val CENTRO_BARILOCHE = LatLng(CalculosGeograficos.CENTRO_BARILOCHE_LAT, CalculosGeograficos.CENTRO_BARILOCHE_LON)
private const val ZOOM_INICIAL_BARILOCHE = 13.2
private const val MARGEN_MINIMO_GRADOS = 0.0018 // ~200 m; previene bounds degenerados
private const val RADIO_HIT_TEST_DP = 18f       // Área táctil ergonómica de 36x36 dp

@Composable
fun MapaMapLibre(
    paradas: List<Parada>,
    ruta: Ruta?,
    onParadaClick: (Parada) -> Unit,
    modifier: Modifier = Modifier,
    onPuntoTocado: ((Double, Double) -> Unit)? = null,
    segmentosAdmin: List<Pair<Parada, Parada>> = emptyList()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    // Aritmética directa sobre density.density (evita ambigüedades genéricas y autoboxing en remember)
    val hitRadiusPx: Float = remember(density) { RADIO_HIT_TEST_DP * density.density }
    val bottomPaddingPx: Int = remember(density) { (260f * density.density).toInt() }

    val paradasActuales by rememberUpdatedState(paradas)
    val rutaActual by rememberUpdatedState(ruta)
    val segmentosActuales by rememberUpdatedState(segmentosAdmin)
    val onParadaClickActual by rememberUpdatedState(onParadaClick)
    val onPuntoTocadoActual by rememberUpdatedState(onPuntoTocado)

    val paradasPorId = remember(paradas) { paradas.associateBy { it.id } }
    val paradasPorIdActual by rememberUpdatedState(paradasPorId)

    var mapaInstancia by remember { mutableStateOf<MapLibreMap?>(null) }
    var estiloListo by remember { mutableStateOf(false) }

    val mapView = remember<MapView> {
        MapView(context).apply {
            onCreate(null)
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
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // 1. Actualizar fuentes GeoJSON en GPU sin mover la cámara (evita zoom out errático)
    LaunchedEffect(estiloListo, paradas, ruta, segmentosAdmin) {
        val map = mapaInstancia ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!estiloListo || !style.isFullyLoaded) return@LaunchedEffect

        actualizarFuentesGeoJson(
            style = style,
            paradas = paradas,
            ruta = ruta,
            segmentosAdmin = segmentosAdmin
        )
        activarIndicadorUbicacionNativo(context, map, style)
    }

    // 2. Encuadrar cámara ÚNICAMENTE cuando cambia la ruta seleccionada o los segmentos activos
    LaunchedEffect(estiloListo, ruta, segmentosAdmin) {
        val map = mapaInstancia ?: return@LaunchedEffect
        if (!estiloListo) return@LaunchedEffect

        val puntosFoco: List<LatLng> = when {
            ruta != null && ruta.paradas.isNotEmpty() -> {
                ruta.paradas.map { LatLng(it.latitud, it.longitud) }
            }
            segmentosAdmin.isNotEmpty() -> {
                segmentosAdmin.flatMap { (a, b) ->
                    listOf(LatLng(a.latitud, a.longitud), LatLng(b.latitud, b.longitud))
                }
            }
            else -> emptyList()
        }

        if (puntosFoco.isNotEmpty()) {
            encuadrarPuntosSeguro(
                map = map,
                puntos = puntosFoco,
                paddingBottomPx = bottomPaddingPx
            )
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    mapaInstancia = map
                    map.uiSettings.apply {
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = false
                        isCompassEnabled = true
                        isAttributionEnabled = false
                        isLogoEnabled = false
                    }

                    map.cameraPosition = CameraPosition.Builder()
                        .target(CENTRO_BARILOCHE)
                        .zoom(ZOOM_INICIAL_BARILOCHE)
                        .build()

                    // Cargar el mismo mapa vectorial oscuro original (sin API key)
                    map.setStyle(Style.Builder().fromUri(URL_ESTILO_MAPA_ORIGINAL)) { style ->
                        inicializarCapasStyle(style)
                        actualizarFuentesGeoJson(
                            style = style,
                            paradas = paradasActuales,
                            ruta = rutaActual,
                            segmentosAdmin = segmentosActuales
                        )
                        activarIndicadorUbicacionNativo(context, map, style)
                        estiloListo = true
                    }

                    map.addOnMapClickListener { point ->
                        val screenPoint: PointF = map.projection.toScreenLocation(point)
                        val cajaTactil = RectF(
                            screenPoint.x - hitRadiusPx,
                            screenPoint.y - hitRadiusPx,
                            screenPoint.x + hitRadiusPx,
                            screenPoint.y + hitRadiusPx
                        )

                        val features = map.queryRenderedFeatures(cajaTactil, LAYER_PARADAS)
                        val paradaEncontrada = features.asSequence()
                            .mapNotNull { feat ->
                                val id = feat.getStringProperty("id")
                                if (id.isNullOrBlank()) null else paradasPorIdActual[id]
                            }
                            .firstOrNull()

                        if (paradaEncontrada != null) {
                            onParadaClickActual(paradaEncontrada)
                            true
                        } else {
                            val listenerLibre = onPuntoTocadoActual
                            if (listenerLibre != null) {
                                listenerLibre(point.latitude, point.longitude)
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    )
}

private fun inicializarCapasStyle(style: Style) {
    if (style.getSource(SRC_ADMIN) == null) style.addSource(GeoJsonSource(SRC_ADMIN))
    if (style.getSource(SRC_RUTA_WALK) == null) style.addSource(GeoJsonSource(SRC_RUTA_WALK))
    if (style.getSource(SRC_RUTA_BUS) == null) style.addSource(GeoJsonSource(SRC_RUTA_BUS))
    if (style.getSource(SRC_PARADAS) == null) style.addSource(GeoJsonSource(SRC_PARADAS))

    if (style.getLayer(LAYER_ADMIN) == null) {
        style.addLayer(
            LineLayer(LAYER_ADMIN, SRC_ADMIN).withProperties(
                lineColor("#4FC3F7"),
                lineWidth(3.5f),
                lineOpacity(0.85f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    if (style.getLayer(LAYER_RUTA_WALK) == null) {
        style.addLayer(
            LineLayer(LAYER_RUTA_WALK, SRC_RUTA_WALK).withProperties(
                lineColor("#81C784"),
                lineWidth(4.5f),
                lineDasharray(arrayOf(2.0f, 2.0f)),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    if (style.getLayer(LAYER_RUTA_BUS) == null) {
        style.addLayer(
            LineLayer(LAYER_RUTA_BUS, SRC_RUTA_BUS).withProperties(
                lineColor(get("color")),
                lineWidth(6.0f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    if (style.getLayer(LAYER_PARADAS) == null) {
        style.addLayer(
            CircleLayer(LAYER_PARADAS, SRC_PARADAS).withProperties(
                circleRadius(
                    switchCase(
                        eq(get("rol"), literal("origen")), literal(9.5f),
                        eq(get("rol"), literal("destino")), literal(9.5f),
                        eq(get("rol"), literal("gps")), literal(9.0f),
                        eq(get("rol"), literal("transbordo")), literal(8.5f),
                        literal(4.2f) // Radio discreto para paradas de fondo
                    )
                ),
                circleColor(
                    switchCase(
                        eq(get("rol"), literal("origen")), literal("#2E7D32"),     // Verde Origen/Subida
                        eq(get("rol"), literal("destino")), literal("#D84315"),    // Rojo/Naranja Destino
                        eq(get("rol"), literal("gps")), literal("#00C853"),        // Verde brillante GPS
                        eq(get("rol"), literal("transbordo")), literal("#F57C00"), // Ámbar Transbordo
                        literal("#5C93B8")                                         // Azul acero suave (bajo contraste)
                    )
                ),
                circleOpacity(
                    switchCase(
                        eq(get("rol"), literal("normal")), literal(0.62f), // Semitransparente para no tapar calles
                        literal(0.96f)                                     // Opaco y nítido en nodos activos
                    )
                ),
                circleBlur(
                    switchCase(
                        eq(get("rol"), literal("normal")), literal(0.25f), // Anti-aliasing subpíxel SDF sin borde
                        literal(0.0f)
                    )
                ),
                circleStrokeWidth(
                    switchCase(
                        eq(get("rol"), literal("normal")), literal(0.0f), // Sin borde negro en paradas estándar
                        literal(2.2f)                                     // Halo solo en marcadores activos
                    )
                ),
                circleStrokeColor("#FFFFFF") // Halo blanco limpio para resaltar los pines activos
            )
        )
    }
}

private fun esCoordenadaValida(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

private fun actualizarFuentesGeoJson(
    style: Style,
    paradas: List<Parada>,
    ruta: Ruta?,
    segmentosAdmin: List<Pair<Parada, Parada>>
) {
    val featuresParadas = ArrayList<Feature>(paradas.size)
    for (p in paradas) {
        if (!esCoordenadaValida(p.latitud, p.longitud)) continue
        val rol = when {
            p.id == "gps_origen" || p.nombre.startsWith("🟢 TU UBICACIÓN") -> "gps"
            p.nombre.startsWith("🟢") || p.nombre.startsWith("🚌 SUBIDA") -> "origen"
            p.nombre.startsWith("🏁") -> "destino"
            p.nombre.startsWith("⇄") -> "transbordo"
            else -> "normal"
        }
        val feat = Feature.fromGeometry(Point.fromLngLat(p.longitud, p.latitud)).apply {
            addStringProperty("id", p.id)
            addStringProperty("nombre", p.nombre)
            addStringProperty("rol", rol)
        }
        featuresParadas.add(feat)
    }
    style.getSourceAs<GeoJsonSource>(SRC_PARADAS)
        ?.setGeoJson(FeatureCollection.fromFeatures(featuresParadas))

    val featuresWalk = ArrayList<Feature>()
    val featuresBus = ArrayList<Feature>()

    if (ruta != null) {
        if (ruta.etapas.isNotEmpty()) {
            val paradasRuta = ruta.paradas
            for (etapa in ruta.etapas) {
                val puntosEtapa = extraerSubtrayecto(paradasRuta, etapa.origen, etapa.destino)
                if (puntosEtapa.size >= 2) {
                    val lineString = LineString.fromLngLats(puntosEtapa)
                    if (etapa.linea == "CAMINANDO") {
                        featuresWalk.add(Feature.fromGeometry(lineString))
                    } else {
                        val colorInt = colorParaLinea(etapa.linea).toArgb()
                        val hexColor = String.format("#%06X", 0xFFFFFF and colorInt)
                        val featBus = Feature.fromGeometry(lineString).apply {
                            addStringProperty("color", hexColor)
                        }
                        featuresBus.add(featBus)
                    }
                }
            }
        } else if (ruta.paradas.size >= 2) {
            val pts = ruta.paradas
                .filter { esCoordenadaValida(it.latitud, it.longitud) }
                .map { Point.fromLngLat(it.longitud, it.latitud) }
            if (pts.size >= 2) {
                val featBus = Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                    addStringProperty("color", "#4FC3F7")
                }
                featuresBus.add(featBus)
            }
        }
    }

    style.getSourceAs<GeoJsonSource>(SRC_RUTA_WALK)
        ?.setGeoJson(FeatureCollection.fromFeatures(featuresWalk))
    style.getSourceAs<GeoJsonSource>(SRC_RUTA_BUS)
        ?.setGeoJson(FeatureCollection.fromFeatures(featuresBus))

    val featuresAdmin = ArrayList<Feature>(segmentosAdmin.size)
    for ((a, b) in segmentosAdmin) {
        if (!esCoordenadaValida(a.latitud, a.longitud) || !esCoordenadaValida(b.latitud, b.longitud)) continue
        val line = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(a.longitud, a.latitud),
                Point.fromLngLat(b.longitud, b.latitud)
            )
        )
        featuresAdmin.add(Feature.fromGeometry(line))
    }
    style.getSourceAs<GeoJsonSource>(SRC_ADMIN)
        ?.setGeoJson(FeatureCollection.fromFeatures(featuresAdmin))
}

private fun extraerSubtrayecto(
    paradasRuta: List<Parada>,
    origen: Parada,
    destino: Parada
): List<Point> {
    val idxInicio = paradasRuta.indexOfFirst { it.id == origen.id }
    val idxFin = if (idxInicio >= 0) {
        paradasRuta.drop(idxInicio + 1).indexOfFirst { it.id == destino.id }
            .let { rel -> if (rel >= 0) idxInicio + 1 + rel else -1 }
    } else {
        -1
    }

    val seleccionados = if (idxInicio >= 0 && idxFin > idxInicio) {
        paradasRuta.subList(idxInicio, idxFin + 1)
    } else {
        listOf(origen, destino)
    }

    return seleccionados
        .filter { esCoordenadaValida(it.latitud, it.longitud) }
        .map { Point.fromLngLat(it.longitud, it.latitud) }
}

private fun encuadrarPuntosSeguro(
    map: MapLibreMap,
    puntos: List<LatLng>,
    paddingBottomPx: Int
) {
    val validos = puntos.filter { esCoordenadaValida(it.latitude, it.longitude) }
    if (validos.isEmpty()) return

    if (validos.size == 1) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(validos.first(), 15.2), 450)
        return
    }

    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY

    for (p in validos) {
        minLat = min(minLat, p.latitude)
        maxLat = max(maxLat, p.latitude)
        minLon = min(minLon, p.longitude)
        maxLon = max(maxLon, p.longitude)
    }

    if (maxLat - minLat < MARGEN_MINIMO_GRADOS) {
        val centroLat = (minLat + maxLat) * 0.5
        minLat = centroLat - MARGEN_MINIMO_GRADOS * 0.5
        maxLat = centroLat + MARGEN_MINIMO_GRADOS * 0.5
    }
    if (maxLon - minLon < MARGEN_MINIMO_GRADOS) {
        val centroLon = (minLon + maxLon) * 0.5
        minLon = centroLon - MARGEN_MINIMO_GRADOS * 0.5
        maxLon = centroLon + MARGEN_MINIMO_GRADOS * 0.5
    }

    val bounds = LatLngBounds.Builder()
        .include(LatLng(minLat, minLon))
        .include(LatLng(maxLat, maxLon))
        .build()

    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            80,
            130,
            80,
            paddingBottomPx
        ),
        550
    )
}

@SuppressLint("MissingPermission")
private fun activarIndicadorUbicacionNativo(context: Context, map: MapLibreMap, style: Style) {
    if (!GestorUbicacion.tienePermisoUbicacion(context)) return
    runCatching {
        val locationComponent = map.locationComponent
        if (!locationComponent.isLocationComponentActivated) {
            val opciones = LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .build()
            locationComponent.activateLocationComponent(opciones)
        }
        locationComponent.isLocationComponentEnabled = true
        locationComponent.renderMode = RenderMode.COMPASS
        locationComponent.cameraMode = CameraMode.NONE
    }
}