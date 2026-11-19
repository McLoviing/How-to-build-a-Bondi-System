package com.example.bondibariloche.data

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Índice espacial O(1) amortizado mediante Spatial Hashing con claves empaquetadas en Long (64 bits).
 * Calibrado para San Carlos de Bariloche (lat ~ -41.13°) con celdas de 300 metros.
 */
class IndiceEspacialParadas(paradas: List<Parada>) {

    companion object {
        private const val TAMANO_CELDA_METROS = 300.0

        private fun celdaX(lon: Double): Int = floor((lon * CalculosGeograficos.K_LON) / TAMANO_CELDA_METROS).toInt()
        private fun celdaY(lat: Double): Int = floor((lat * CalculosGeograficos.K_LAT) / TAMANO_CELDA_METROS).toInt()

        /** Empaqueta dos coordenadas Int (gx, gy) en un único Long sin asignar objetos en el heap. */
        private fun empaquetarClave(gx: Int, gy: Int): Long =
            (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
    }

    private val celdas: Map<Long, List<Parada>>

    init {
        val mapa = HashMap<Long, MutableList<Parada>>(paradas.size)
        for (p in paradas) {
            val clave = empaquetarClave(celdaX(p.longitud), celdaY(p.latitud))
            mapa.getOrPut(clave) { ArrayList(4) }.add(p)
        }
        celdas = mapa
    }

    /**
     * Devuelve las paradas dentro de [radioMetros] (<= 300 m) ordenadas por distancia real.
     * Solo inspecciona las 9 celdas de la vecindad de Moore (3x3).
     */
    fun buscarCercanas(
        lat: Double,
        lon: Double,
        radioMetros: Double = 300.0,
        limite: Int = 5
    ): List<Pair<Parada, Double>> {
        val gxCentro = celdaX(lon)
        val gyCentro = celdaY(lat)
        val radioCuadrado = radioMetros * radioMetros
        val candidatos = ArrayList<Pair<Parada, Double>>(12)

        for (dx in -1..1) {
            for (dy in -1..1) {
                val bucket = celdas[empaquetarClave(gxCentro + dx, gyCentro + dy)] ?: continue
                for (parada in bucket) {
                    val d2 = CalculosGeograficos.distanciaCuadradaMetros(lat, lon, parada.latitud, parada.longitud)
                    if (d2 <= radioCuadrado) {
                        candidatos.add(parada to sqrt(d2))
                    }
                }
            }
        }

        candidatos.sortBy { it.second }
        return if (candidatos.size <= limite) candidatos else candidatos.subList(0, limite)
    }

    /**
     * Devuelve las paradas cercanas encapsuladas como [ParadaCandidataGps] con distancia redondeada y minutos a pie.
     */
    fun buscarCandidatasGps(
        lat: Double,
        lon: Double,
        radioMetros: Double = 450.0,
        limite: Int = 3
    ): List<ParadaCandidataGps> {
        return buscarCercanas(lat, lon, radioMetros, limite).map { (parada, dist) ->
            ParadaCandidataGps(
                parada = parada,
                distanciaMetros = dist.roundToInt(),
                minutosCaminata = GestorUbicacion.estimarMinutosCaminata(dist)
            )
        }
    }
}