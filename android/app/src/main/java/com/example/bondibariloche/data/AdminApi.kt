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

object AdminApi {
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
