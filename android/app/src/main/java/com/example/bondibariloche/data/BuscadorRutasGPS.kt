package com.example.bondibariloche.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlin.math.round

object BuscadorRutasGps {

    private const val UMBRAL_MINIMO_CAMINATA_METROS = 15

    /**
     * Evalúa en paralelo las [candidatas] cercanas al GPS (hasta K = 3 paradas) usando
     * supervisorScope (patrón Bulkhead), antepone la caminata inicial a cada itinerario
     * (tanto en etapas para el timeline como en paradas para el trazado en MapLibre),
     * deduplica recorridos equivalentes y ordena por tiempo total puerta a puerta.
     */
    suspend fun buscarDesdeUbicacion(
        latUsuario: Double,
        lonUsuario: Double,
        candidatas: List<ParadaCandidataGps>,
        destino: Parada,
        maxCaminata: Float? = null
    ): List<Ruta> = supervisorScope {
        if (candidatas.isEmpty()) return@supervisorScope emptyList()

        val tareas = candidatas.take(3).map { cand ->
            async {
                runCatching {
                    val puntoGps = Parada(
                        id = "gps_origen",
                        nombre = "🟢 Tu ubicación (${cand.distanciaMetros} m a pie)",
                        latitud = latUsuario,
                        longitud = lonUsuario
                    )

                    if (cand.parada.id == destino.id) {
                        val etapaCaminata = Etapa(
                            linea = "CAMINANDO",
                            origen = puntoGps,
                            destino = destino,
                            paradasIntermedias = 0,
                            distancia = cand.distanciaMetros.toDouble(),
                            tiempo = cand.minutosCaminata.toDouble()
                        )
                        return@runCatching listOf(
                            Ruta(
                                esRutaExacta = false,
                                distanciaCaminataFinal = cand.distanciaMetros.toDouble(),
                                mensajeUsuario = "Estás a ${cand.distanciaMetros} m (${cand.minutosCaminata} min caminando) del destino.",
                                tiempoTotal = cand.minutosCaminata.toDouble(),
                                tiempoViaje = 0.0,
                                tiempoEspera = 0.0,
                                distancia = cand.distanciaMetros.toDouble(),
                                transbordos = 0,
                                lineas = emptyList(),
                                etapas = listOf(etapaCaminata),
                                transbordosDetalle = emptyList(),
                                paradas = listOf(puntoGps, destino)
                            )
                        )
                    }

                    BondiApi.obtenerRutas(cand.parada.id, destino.id, maxCaminata = maxCaminata).map { ruta ->
                        integrarCaminataInicial(
                            ruta = ruta,
                            puntoGps = puntoGps,
                            candidata = cand
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }

        val todas = tareas.awaitAll().flatten()

        todas.groupBy { firmaTopologica(it) }
            .values
            .mapNotNull { grupo -> grupo.minByOrNull { it.tiempoTotal } }
            .sortedWith(compareBy({ it.tiempoTotal }, { it.transbordos }))
    }

    private fun integrarCaminataInicial(
        ruta: Ruta,
        puntoGps: Parada,
        candidata: ParadaCandidataGps
    ): Ruta {
        // Si el usuario ya está parado en la parada (< 15 m), no agregar tramo peatonal redundante
        if (candidata.distanciaMetros <= UMBRAL_MINIMO_CAMINATA_METROS) {
            return ruta
        }

        val minCam = candidata.minutosCaminata.toDouble()
        val distCam = candidata.distanciaMetros.toDouble()

        val etapaInicial = Etapa(
            linea = "CAMINANDO",
            origen = puntoGps,
            destino = candidata.parada,
            paradasIntermedias = 0,
            distancia = distCam,
            tiempo = minCam
        )

        val instruccionCaminata =
            "🚶 Caminá ${candidata.distanciaMetros} m (${candidata.minutosCaminata} min) hasta la parada '${candidata.parada.nombre}' (${candidata.parada.id})."
        val mensajeCombinado = listOfNotNull(instruccionCaminata, ruta.mensajeUsuario)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        // Garantizar que puntoGps encabece ruta.paradas para que MapLibre dibuje el tramo inicial
        val paradasConOrigenGps = if (ruta.paradas.firstOrNull()?.id == puntoGps.id) {
            ruta.paradas
        } else {
            listOf(puntoGps) + ruta.paradas
        }

        return ruta.copy(
            tiempoTotal = round((ruta.tiempoTotal + minCam) * 10.0) / 10.0,
            distancia = ruta.distancia + distCam,
            mensajeUsuario = mensajeCombinado,
            etapas = listOf(etapaInicial) + ruta.etapas,
            paradas = paradasConOrigenGps
        )
    }

    private fun firmaTopologica(ruta: Ruta): String {
        val lineasClave = ruta.lineas.joinToString(">")
        val primerTramoBus = ruta.etapas.firstOrNull { it.linea != "CAMINANDO" }
        val nodoClave = primerTramoBus?.destino?.id ?: ruta.paradas.lastOrNull()?.id.orEmpty()
        return "$lineasClave|$nodoClave"
    }
}