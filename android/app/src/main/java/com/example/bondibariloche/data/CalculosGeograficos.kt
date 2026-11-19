package com.example.bondibariloche.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Constantes y utilidades geoespaciales unificadas para San Carlos de Bariloche.
 * Proyección equirectangular WGS84 calibrada a latitud media -41.13°.
 */
object CalculosGeograficos {
    const val CENTRO_BARILOCHE_LAT = -41.1335
    const val CENTRO_BARILOCHE_LON = -71.3103

    // Factores métricos locales (metros por grado)
    const val K_LAT = 111132.95
    const val K_LON = 83715.4 // 111132.95 * cos(-41.13°)

    // Geocerca urbana
    const val LAT_MIN = -41.35
    const val LAT_MAX = -40.95
    const val LON_MIN = -71.75
    const val LON_MAX = -71.05

    // Parámetros de caminata urbana
    const val TORTUOSIDAD_PEATONAL = 1.20
    const val VELOCIDAD_PEATONAL_M_MIN = 75.0 // 4.5 km/h

    fun distanciaCuadradaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat1 - lat2) * K_LAT
        val dLon = (lon1 - lon2) * K_LON
        return (dLat * dLat + dLon * dLon)
    }

    fun distanciaRapidaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return sqrt(distanciaCuadradaMetros(lat1, lon1, lat2, lon2))
    }

    fun estimarMinutosCaminata(distanciaRectaMetros: Double): Int {
        val distanciaRealEstimada = distanciaRectaMetros * TORTUOSIDAD_PEATONAL
        val minutos = ceil(distanciaRealEstimada / VELOCIDAD_PEATONAL_M_MIN).toInt()
        return max(1, minutos)
    }
}
