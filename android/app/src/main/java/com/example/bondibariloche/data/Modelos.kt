package com.example.bondibariloche.data


/**
 * Modelos de datos: espejo en Kotlin de los modelos Pydantic de api_2.py.
 */
data class Parada(
    val id: String,
    val nombre: String,
    val latitud: Double,
    val longitud: Double
)

/** Un tramo del viaje (colectivo o caminata) entre dos puntos. */
data class Etapa(
    val linea: String,
    val origen: Parada,
    val destino: Parada,
    val paradasIntermedias: Int,
    val distancia: Double,
    val tiempo: Double
) {
    val esCaminata: Boolean
        get() = linea.equals("CAMINANDO", ignoreCase = true)
}

/** Detalle de combinación entre colectivos (directa o con caminata intermedia). */
data class Transbordo(
    val parada: Parada,
    val paradaSubida: Parada? = null,
    val requiereCaminata: Boolean = false,
    val metrosCaminata: Double = 0.0,
    val bajarDe: String,
    val subirA: String,
    val instruccion: String
)

/** Una alternativa de recorrido completa. */
data class Ruta(
    val esRutaExacta: Boolean = true,
    val distanciaCaminataFinal: Double = 0.0,
    val mensajeUsuario: String? = null,
    val tiempoTotal: Double,
    val tiempoViaje: Double,
    val tiempoEspera: Double,
    val distancia: Double,
    val transbordos: Int,
    val lineas: List<String>,
    val etapas: List<Etapa>,
    val transbordosDetalle: List<Transbordo>,
    val paradas: List<Parada>
) {
    val lineasColectivo: List<String>
        get() = lineas.filter { !it.equals("CAMINANDO", ignoreCase = true) }

    val tieneCaminata: Boolean
        get() = etapas.any { it.esCaminata }

    /**
     * Extrae la sublista ordenada de paradas que componen una etapa.
     * Maneja correctamente etapas consecutivas buscando desde el índice previo.
     */
    fun paradasDeEtapa(etapa: Etapa, indiceInicioBusqueda: Int = 0): List<Parada> {
        val desde = paradas.drop(indiceInicioBusqueda)
            .indexOfFirst { it.id == etapa.origen.id }
            .let { if (it >= 0) it + indiceInicioBusqueda else -1 }

        val hasta = if (desde >= 0) {
            paradas.drop(desde)
                .indexOfFirst { it.id == etapa.destino.id }
                .let { if (it >= 0) it + desde else -1 }
        } else -1

        return if (desde in 0..hasta) {
            paradas.subList(desde, hasta + 1)
        } else {
            listOf(etapa.origen, etapa.destino)
        }
    }
}