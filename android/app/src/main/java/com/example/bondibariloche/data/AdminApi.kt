package com.example.bondibariloche.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Datos de una parada que puede consultar el administrador. */
data class ParadaAdmin(
    val id: String,
    val calle: String,
    val referencia: String,
    val latitud: Double,
    val longitud: Double,
    val vinculada: Boolean
)

data class TramoAdmin(val origen: String, val destino: String, val distancia: Double, val tiempo: Double)

object AdminApi {
    suspend fun lineas(token: String): List<String> = withContext(Dispatchers.IO) {
        val a = JSONArray(pedir("GET", "/admin/lineas", token))
        List(a.length()) { a.getString(it) }
    }

    suspend fun guardarLinea(token: String, anterior: String?, numero: String) = withContext(Dispatchers.IO) {
        pedir(if (anterior == null) "POST" else "PUT", if (anterior == null) "/admin/lineas" else "/admin/lineas/$anterior", token, JSONObject().put("numero", numero))
    }

    suspend fun eliminarLinea(token: String, numero: String) = withContext(Dispatchers.IO) {
        pedir("DELETE", "/admin/lineas/$numero", token)
    }

    suspend fun tramos(token: String, numero: String): List<TramoAdmin> = withContext(Dispatchers.IO) {
        val a = JSONArray(pedir("GET", "/admin/lineas/$numero/tramos", token))
        List(a.length()) { i ->
            val t = a.getJSONObject(i)
            TramoAdmin(t.getString("origen"), t.getString("destino"), t.getDouble("distancia"), t.getDouble("tiempo"))
        }
    }

    suspend fun guardarTramo(token: String, numero: String, t: TramoAdmin) = withContext(Dispatchers.IO) {
        pedir("PUT", "/admin/lineas/$numero/tramos", token, JSONObject().put("origen", t.origen).put("destino", t.destino).put("distancia", t.distancia).put("tiempo", t.tiempo))
    }

    suspend fun eliminarTramo(token: String, numero: String, t: TramoAdmin) = withContext(Dispatchers.IO) {
        pedir("DELETE", "/admin/lineas/$numero/tramos/${t.origen}/${t.destino}", token)
    }

    suspend fun listar(token: String): List<ParadaAdmin> = withContext(Dispatchers.IO) {
        val datos = JSONArray(pedir("GET", "/admin/paradas", token))
        List(datos.length()) { indice ->
            val p = datos.getJSONObject(indice)
            ParadaAdmin(p.getString("id"), p.getString("calle"),
                p.getString("referencia"), p.getDouble("latitud"),
                p.getDouble("longitud"), p.getBoolean("vinculada"))
        }
    }

    suspend fun guardar(token: String, id: String?, calle: String, referencia: String,
                        latitud: Double, longitud: Double) = withContext(Dispatchers.IO) {
        val datos = JSONObject().put("calle", calle).put("referencia", referencia)
            .put("latitud", latitud).put("longitud", longitud)
        val ruta = if (id == null) "/admin/paradas" else "/admin/paradas/$id"
        pedir(if (id == null) "POST" else "PUT", ruta, token, datos)
    }

    suspend fun eliminar(token: String, id: String) = withContext(Dispatchers.IO) {
        pedir("DELETE", "/admin/paradas/$id", token)
    }

    private fun pedir(metodo: String, ruta: String, token: String,
                      datos: JSONObject? = null): String {
        val conexion = URL(ApiConfig.baseUrl + ruta).openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = metodo
            conexion.connectTimeout = 8_000
            conexion.readTimeout = 30_000
            conexion.setRequestProperty("Accept", "application/json")
            conexion.setRequestProperty("X-Admin-Token", token)
            if (datos != null) {
                conexion.doOutput = true
                conexion.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conexion.outputStream.use { it.write(datos.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val cuerpo = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo !in 200..299) {
                val detalle = runCatching { JSONObject(cuerpo).optString("detail") }.getOrDefault("")
                throw ApiException(detalle.ifBlank { "Error HTTP $codigo" })
            }
            return cuerpo
        } finally {
            conexion.disconnect()
        }
    }
}
