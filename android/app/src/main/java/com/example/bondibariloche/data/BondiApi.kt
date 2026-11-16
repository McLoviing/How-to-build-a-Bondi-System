package com.example.bondibariloche.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiConfig {
    private const val PREFS_NAME = "bondi_api_prefs"
    private const val CLAVE_URL = "api_base_url"

    var baseUrl: String = "http://10.0.2.2:8000"
        private set

    fun inicializar(contexto: Context) {
        val prefs = contexto.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val guardada = prefs.getString(CLAVE_URL, null)
        baseUrl = when {
            !guardada.isNullOrBlank() -> guardada
            esEmulador() -> "http://10.0.2.2:8000"
            else -> "http://192.168.1.2:8000"
        }
    }

    fun guardarUrl(contexto: Context, nuevaUrl: String) {
        var limpia = nuevaUrl.trim()
        if (!limpia.startsWith("http://") && !limpia.startsWith("https://")) {
            limpia = "http://$limpia"
        }
        if (limpia.endsWith("/")) {
            limpia = limpia.dropLast(1)
        }
        baseUrl = limpia
        contexto.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CLAVE_URL, limpia)
            .apply()
    }

    private fun esEmulador(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }
}

class ApiException(mensaje: String) : Exception(mensaje)

object BondiApi {

    suspend fun obtenerParadas(): List<Parada> = withContext(Dispatchers.IO) {
        convertir {
            val arreglo = JSONArray(pedir("/paradas"))
            List(arreglo.length()) { i -> parsearParada(arreglo.getJSONObject(i)) }
        }
    }

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

    private fun pedir(rutaYConsulta: String): String {
        val urlCompleta = ApiConfig.baseUrl + rutaYConsulta
        val conexion = URL(urlCompleta).openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "GET"
            conexion.connectTimeout = 8_000
            conexion.readTimeout = 20_000
            conexion.setRequestProperty("Accept", "application/json")

            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val cuerpo = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (codigo !in 200..299) throw ApiException(mensajeDeError(codigo, cuerpo))
            return cuerpo
        } catch (e: IOException) {
            throw ApiException(
                "No se pudo conectar con el servidor (${ApiConfig.baseUrl}). " +
                        "Revisá que la API esté encendida y que la dirección IP sea correcta."
            )
        } finally {
            conexion.disconnect()
        }
    }

    private fun mensajeDeError(codigo: Int, cuerpo: String): String {
        val detalle = try {
            JSONObject(cuerpo).optString("detail", "")
        } catch (e: JSONException) {
            ""
        }
        return if (detalle.isNotBlank()) detalle else "El servidor respondió con error $codigo."
    }

    private inline fun <T> convertir(bloque: () -> T): T =
        try {
            bloque()
        } catch (e: JSONException) {
            throw ApiException("La respuesta del servidor no tiene el formato esperado (${e.message}).")
        }

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
        paradasIntermedias = o.optInt("paradas_intermedias", 1),
        distancia = o.optDouble("distancia", 0.0),
        tiempo = o.optDouble("tiempo", 0.0)
    )

    private fun parsearTransbordo(o: JSONObject): Transbordo {
        val objSubida = o.optJSONObject("parada_subida")
        return Transbordo(
            parada = parsearParada(o.getJSONObject("parada")),
            paradaSubida = objSubida?.let { parsearParada(it) },
            requiereCaminata = o.optBoolean("requiere_caminata", false),
            metrosCaminata = o.optDouble("metros_caminata", 0.0),
            bajarDe = o.getString("bajar_de"),
            subirA = o.getString("subir_a"),
            instruccion = o.getString("instruccion")
        )
    }

    private fun parsearRuta(o: JSONObject): Ruta {
        val lineas = o.getJSONArray("lineas")
        val etapas = o.getJSONArray("etapas")
        val transbordos = o.optJSONArray("transbordos_detalle") ?: JSONArray()
        val paradas = o.getJSONArray("paradas")
        val msgRaw = if (o.isNull("mensaje_usuario")) null else o.optString("mensaje_usuario", null)

        return Ruta(
            esRutaExacta = o.optBoolean("es_ruta_exacta", true),
            distanciaCaminataFinal = o.optDouble("distancia_caminata_final", 0.0),
            mensajeUsuario = msgRaw?.takeIf { it.isNotBlank() },
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