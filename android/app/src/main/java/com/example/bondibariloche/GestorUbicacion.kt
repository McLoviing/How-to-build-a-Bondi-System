package com.example.bondibariloche.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

sealed interface ResultadoUbicacion {
    data class Exito(
        val latitud: Double,
        val longitud: Double,
        val precisionMetros: Float
    ) : ResultadoUbicacion

    data object SinPermiso : ResultadoUbicacion
    data object GpsDesactivado : ResultadoUbicacion
    data class FueraDeZona(val latitud: Double, val longitud: Double) : ResultadoUbicacion
    data class Error(val mensaje: String) : ResultadoUbicacion
}

data class ParadaCandidataGps(
    val parada: Parada,
    val distanciaMetros: Int,
    val minutosCaminata: Int
)

object GestorUbicacion {

    // Geocerca de San Carlos de Bariloche
    private const val LAT_MIN = -41.35
    private const val LAT_MAX = -40.95
    private const val LON_MIN = -71.75
    private const val LON_MAX = -71.05

    private const val TIMEOUT_GPS_MS = 8_000L
    private const val PRECISION_MINIMA_METROS = 65.0f
    private const val TORTUOSIDAD_PEATONAL = 1.20
    private const val VELOCIDAD_PEATONAL_M_MIN = 75.0 // 4.5 km/h

    fun tienePermisoUbicacion(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun gpsHabilitado(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun estaEnBariloche(lat: Double, lon: Double): Boolean =
        lat in LAT_MIN..LAT_MAX && lon in LON_MIN..LON_MAX

    /**
     * Calcula los minutos de caminata urbana desde el punto GPS hasta una parada.
     */
    fun estimarMinutosCaminata(distanciaRectaMetros: Double): Int {
        val distanciaReal = distanciaRectaMetros * TORTUOSIDAD_PEATONAL
        return max(1, ceil(distanciaReal / VELOCIDAD_PEATONAL_M_MIN).toInt())
    }

    /**
     * Obtiene la ubicación actual con timeout acotado, apagado automático del sensor
     * al cancelar la corrutina y fallback nativo si Play Services no está disponible.
     */
    @SuppressLint("MissingPermission")
    suspend fun obtenerUbicacionActual(context: Context): ResultadoUbicacion =
        withContext(Dispatchers.Main.immediate) {
            if (!tienePermisoUbicacion(context)) return@withContext ResultadoUbicacion.SinPermiso
            if (!gpsHabilitado(context)) return@withContext ResultadoUbicacion.GpsDesactivado

            val appContext = context.applicationContext
            val tienePlayServices = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

            val loc: Location? = withTimeoutOrNull(TIMEOUT_GPS_MS.milliseconds) {
                if (tienePlayServices) {
                    obtenerViaFused(appContext)
                } else {
                    obtenerViaLocationManager(appContext)
                }
            }

            if (loc == null) {
                return@withContext ResultadoUbicacion.Error(
                    "No se pudo fijar señal GPS a tiempo. Verificá estar en un espacio abierto."
                )
            }

            if (!estaEnBariloche(loc.latitude, loc.longitude)) {
                return@withContext ResultadoUbicacion.FueraDeZona(loc.latitude, loc.longitude)
            }

            ResultadoUbicacion.Exito(
                latitud = loc.latitude,
                longitud = loc.longitude,
                precisionMetros = loc.accuracy
            )
        }

    @SuppressLint("MissingPermission")
    private suspend fun obtenerViaFused(context: Context): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        return try {
            // Intentar primero lastLocation si es reciente (< 25 s) y precisa (< 65 m)
            val ultima = runCatching { client.lastLocation.await() }.getOrNull()
            val edadMs = if (ultima != null) {
                (android.os.SystemClock.elapsedRealtimeNanos() - ultima.elapsedRealtimeNanos) / 1_000_000L
            } else Long.MAX_VALUE

            if (ultima != null && edadMs in 0..25_000L && ultima.accuracy <= PRECISION_MINIMA_METROS) {
                ultima
            } else {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }
        } catch (_: Exception) {
            null
        } finally {
            // Garantiza que el receptor GNSS se apague si ocurre timeout o cancelación de UI
            cts.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtenerViaLocationManager(context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val proveedor = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (proveedor == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(
                    proveedor,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } else {
                @Suppress("DEPRECATION")
                val ultima = lm.getLastKnownLocation(proveedor)
                if (cont.isActive) cont.resume(ultima)
            }
        }
}