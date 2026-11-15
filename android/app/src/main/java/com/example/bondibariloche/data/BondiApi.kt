package com.example.bondibariloche.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Dirección del servidor FastAPI.
 *
 *  - Emulador de Android Studio: "http://10.0.2.2:8000" (10.0.2.2 es tu PC vista desde el emulador).
 *  - Celular real: la IP de tu PC en la red WiFi, ej. "http://192.168.0.15:8000".
 *    Además hay que iniciar el servidor con:  uvicorn api:app --host 0.0.0.0 --port 8000
 */
object ApiConfig {
    var baseUrl: String = "http://10.0.2.2:8000"
}

/** Error "presentable": su mensaje se le puede mostrar tal cual al usuario. */
class ApiException(mensaje: String) : Exception(mensaje)

object BondiApi {

    /** GET /paradas  ->  las 526 paradas (con id, nombre y coordenadas). */
    suspend fun obtenerParadas(): List<Parada> = withContext(Dispatchers.IO) {
        convertir {
            val arreglo = JSONArray(pedir("/paradas"))
            List(arreglo.length()) { i -> parsearParada(arreglo.getJSONObject(i)) }
        }
    }

    /** GET /rutas?origen=p_9&destino=p_20&alternativas=5  ->  hasta 5 alternativas de recorrido. */
    suspend fun obtenerRutas(
        origenId: String,
        destinoId: String,
        alternativas: Int = 5
    ): List<Ruta> = withContext(Dispatchers.IO) {
        val o = URLEncoder.encode(origenId, "UTF-8")
        val d = URLEncoder.encode(destinoId, "UTF-8")
        convertir {
            val arreglo = JSONArray(pedir("/rutas?origen=$o&destino=$d&alternativas=$alternativas"))
            List(arreglo.length()) { i -> parsearRuta(arreglo.getJSONObject(i)) }
        }
    }

    // ------------------------------------------------------------------
    // Capa HTTP
    // ------------------------------------------------------------------

    /** Hace el GET y devuelve el cuerpo como texto. Debe llamarse fuera del hilo principal. */
    private fun pedir(rutaYConsulta: String): String {
        val conexion = URL(ApiConfig.baseUrl + rutaYConsulta).openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "GET"
            conexion.connectTimeout = 8_000    // tiempo máximo para conectarse
            conexion.readTimeout = 20_000      // tiempo máximo esperando la respuesta (Prolog puede tardar)
            conexion.setRequestProperty("Accept", "application/json")

            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val cuerpo = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (codigo !in 200..299) throw ApiException(mensajeDeError(codigo, cuerpo))
            return cuerpo
        } catch (e: IOException) {
            throw ApiException(
                "No se pudo conectar con el servidor (${ApiConfig.baseUrl}). " +
                    "Revisá que la API esté encendida y que la dirección sea correcta."
            )
        } finally {
            conexion.disconnect()
        }
    }

    /** FastAPI responde los errores como {"detail": "..."}; lo usamos como mensaje. */
    private fun mensajeDeError(codigo: Int, cuerpo: String): String {
        val detalle = try {
            JSONObject(cuerpo).optString("detail", "")
        } catch (e: JSONException) {
            ""
        }
        return if (detalle.isNotBlank()) detalle else "El servidor respondió con error $codigo."
    }

    /** Convierte cualquier fallo de lectura del JSON en un ApiException entendible. */
    private inline fun <T> convertir(bloque: () -> T): T =
        try {
            bloque()
        } catch (e: JSONException) {
            throw ApiException("La respuesta del servidor no tiene el formato esperado (${e.message}).")
        }

    // ------------------------------------------------------------------
    // Conversión JSON -> objetos Kotlin (las claves son las de api.py)
    // ------------------------------------------------------------------

    private fun parsearParada(o: JSONObject) = Parada(
        id = o.getString("id"),
        nombre = o.getString("nombre"),
        latitud = o.getDouble("latitud"),
        longitud = o.getDouble("longitud")
    )

    private fun parsearEtapa(o: JSONObject) = Etapa(
        linea = o.getString("linea"),
        origen = parsearParada(o.getJSONObject("origen")),
        destino = parsearParada(o.getJSONObject("destino")),
        paradasIntermedias = o.getInt("paradas_intermedias"),
        distancia = o.getDouble("distancia"),
        tiempo = o.getDouble("tiempo")
    )

    private fun parsearTransbordo(o: JSONObject) = Transbordo(
        parada = parsearParada(o.getJSONObject("parada")),
        bajarDe = o.getString("bajar_de"),
        subirA = o.getString("subir_a"),
        instruccion = o.getString("instruccion")
    )

    private fun parsearRuta(o: JSONObject): Ruta {
        val lineas = o.getJSONArray("lineas")
        val etapas = o.getJSONArray("etapas")
        val transbordos = o.getJSONArray("transbordos_detalle")
        val paradas = o.getJSONArray("paradas")
        return Ruta(
            tiempoTotal = o.getDouble("tiempo_total"),
            tiempoViaje = o.getDouble("tiempo_viaje"),
            tiempoEspera = o.getDouble("tiempo_espera"),
            distancia = o.getDouble("distancia"),
            transbordos = o.getInt("transbordos"),
            lineas = List(lineas.length()) { lineas.getString(it) },
            etapas = List(etapas.length()) { parsearEtapa(etapas.getJSONObject(it)) },
            transbordosDetalle = List(transbordos.length()) { parsearTransbordo(transbordos.getJSONObject(it)) },
            paradas = List(paradas.length()) { parsearParada(paradas.getJSONObject(it)) }
        )
    }
}
