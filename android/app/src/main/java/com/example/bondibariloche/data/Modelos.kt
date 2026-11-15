package com.example.bondibariloche.data

/**
 * Modelos de datos: son el espejo en Kotlin de los modelos Pydantic de api.py
 * (ParadaDetalle, EtapaDetalle, TransbordoDetalle y RutaRespuesta).
 */

data class Parada(
    val id: String,          // ej. "p_9"
    val nombre: String,      // ej. "SAN MARTIN - INDEPENDENCIA"
    val latitud: Double,
    val longitud: Double
)

/** Un tramo del viaje sobre UNA sola línea (entre dos transbordos). */
data class Etapa(
    val linea: String,
    val origen: Parada,
    val destino: Parada,
    val paradasIntermedias: Int,   // cantidad de paradas que se viajan en la etapa
    val distancia: Double,         // metros
    val tiempo: Double             // minutos
)

/** Dónde hay que bajar y a qué línea subir. */
data class Transbordo(
    val parada: Parada,
    val bajarDe: String,
    val subirA: String,
    val instruccion: String
)

/** Una alternativa de recorrido completa. */
data class Ruta(
    val tiempoTotal: Double,       // minutos (viaje + espera por transbordos)
    val tiempoViaje: Double,
    val tiempoEspera: Double,
    val distancia: Double,         // metros
    val transbordos: Int,
    val lineas: List<String>,
    val etapas: List<Etapa>,
    val transbordosDetalle: List<Transbordo>,
    val paradas: List<Parada>      // TODAS las paradas del recorrido, en orden
) {
    /**
     * Paradas que recorre una etapa, en orden (incluye origen y destino de la etapa).
     * Sirve para dibujar un tramo de color por línea sobre el mapa.
     *
     * Funciona porque la ruta nunca repite paradas, así que el origen y el destino
     * de cada etapa aparecen una sola vez dentro de [paradas].
     */
    fun paradasDeEtapa(etapa: Etapa): List<Parada> {
        val desde = paradas.indexOfFirst { it.id == etapa.origen.id }
        val hasta = paradas.indexOfFirst { it.id == etapa.destino.id }
        return if (desde in 0..hasta) paradas.subList(desde, hasta + 1)
        else listOf(etapa.origen, etapa.destino)   // plan B: línea recta
    }
}
