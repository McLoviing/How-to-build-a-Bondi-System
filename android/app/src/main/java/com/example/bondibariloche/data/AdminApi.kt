package com.example.bondibariloche.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ParadaAdmin(
    val id: String,
    val calle: String,
    val referencia: String,
    val latitud: Double,
    val longitud: Double,
    val vinculada: Boolean = false
)

data class TramoAdmin(
    val origen: String,
    val destino: String,
    val distancia: Double,
    val tiempo: Double
)

data class BackupAdmin(
    val archivo: String,
    val fechaUtc: String,
    val accion: String
)

object AdminApi {

    private fun codificarSegmento(valor: String): String =
        URLEncoder.encode(valor, "UTF-8").replace("+", "%20")

    /**
     * Cliente HTTP autenticado para endpoints /admin.
     * Solo activa doOutput cuando bodyJson != null para evitar que HttpURLConnection
     * mute peticiones DELETE en POST.
     */
    private fun pedirAdmin(
        metodo: String,
        ruta: String,
        token: String,
        bodyJson: String? = null
    ): String {
        val url = URL("${ApiConfig.baseUrl}$ruta")
        val conexion = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 20_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Admin-Token", token)
        }

        try {
            if (bodyJson != null) {
                val bytes = bodyJson.toByteArray(Charsets.UTF_8)
                conexion.doOutput = true
                conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conexion.setFixedLengthStreamingMode(bytes.size)
                conexion.outputStream.use { it.write(bytes) }
            }

            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val respuesta = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (codigo !in 200..299) {
                val detalle = try {
                    JSONObject(respuesta).optString("detail").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                throw ApiException(detalle ?: "Error HTTP $codigo al ejecutar $metodo$ruta")
            }

            return respuesta
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("No se pudo conectar con el servidor: ${e.message ?: "error de red"}")
        } finally {
            conexion.disconnect()
        }
    }

    // =========================================================================
    // GESTIÓN DE PARADAS
    // =========================================================================

    suspend fun listar(token: String): List<ParadaAdmin> = withContext(Dispatchers.IO) {
        val resp = pedirAdmin("GET", "/admin/paradas", token)
        val arr = JSONArray(resp)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ParadaAdmin(
                id = o.getString("id"),
                calle = o.getString("calle"),
                referencia = o.optString("referencia", ""),
                latitud = o.getDouble("latitud"),
                longitud = o.getDouble("longitud"),
                vinculada = o.optBoolean("vinculada", false)
            )
        }
    }

    suspend fun guardar(
        token: String,
        id: String?,
        calle: String,
        referencia: String,
        latitud: Double,
        longitud: Double
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("calle", calle)
            put("referencia", referencia)
            put("latitud", latitud)
            put("longitud", longitud)
        }.toString()

        if (id == null) {
            pedirAdmin("POST", "/admin/paradas", token, payload)
        } else {
            pedirAdmin("PUT", "/admin/paradas/${codificarSegmento(id)}", token, payload)
        }
    }

    suspend fun eliminar(token: String, id: String) = withContext(Dispatchers.IO) {
        pedirAdmin("DELETE", "/admin/paradas/${codificarSegmento(id)}", token)
    }

    // =========================================================================
    // GESTIÓN DE LÍNEAS Y TRAMOS
    // =========================================================================

    suspend fun lineas(token: String): List<String> = withContext(Dispatchers.IO) {
        val resp = pedirAdmin("GET", "/admin/lineas", token)
        val arr = JSONArray(resp)
        List(arr.length()) { i -> arr.getString(i) }
    }

    suspend fun guardarLinea(token: String, anterior: String?, numero: String) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("numero", numero)
            }.toString()

            if (anterior == null) {
                pedirAdmin("POST", "/admin/lineas", token, payload)
            } else {
                pedirAdmin("PUT", "/admin/lineas/${codificarSegmento(anterior)}", token, payload)
            }
        }

    suspend fun eliminarLinea(token: String, numero: String) = withContext(Dispatchers.IO) {
        pedirAdmin("DELETE", "/admin/lineas/${codificarSegmento(numero)}", token)
    }

    suspend fun tramos(token: String, linea: String): List<TramoAdmin> =
        withContext(Dispatchers.IO) {
            val resp = pedirAdmin("GET", "/admin/lineas/${codificarSegmento(linea)}/tramos", token)
            val arr = JSONArray(resp)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TramoAdmin(
                    origen = o.getString("origen"),
                    destino = o.getString("destino"),
                    distancia = o.getDouble("distancia"),
                    tiempo = o.getDouble("tiempo")
                )
            }
        }

    suspend fun guardarTramo(token: String, linea: String, tramo: TramoAdmin) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("origen", tramo.origen)
                put("destino", tramo.destino)
                put("distancia", tramo.distancia)
                put("tiempo", tramo.tiempo)
            }.toString()

            pedirAdmin("PUT", "/admin/lineas/${codificarSegmento(linea)}/tramos", token, payload)
        }

    suspend fun eliminarTramo(token: String, linea: String, tramo: TramoAdmin) =
        withContext(Dispatchers.IO) {
            val l = codificarSegmento(linea)
            val o = codificarSegmento(tramo.origen)
            val d = codificarSegmento(tramo.destino)
            pedirAdmin("DELETE", "/admin/lineas/$l/tramos/$o/$d", token)
        }

    // =========================================================================
    // RESPALDOS Y ROLLBACK ATÓMICO
    // =========================================================================

    suspend fun listarBackups(token: String): List<BackupAdmin> = withContext(Dispatchers.IO) {
        val resp = pedirAdmin("GET", "/admin/backups", token)
        val arr = JSONArray(resp)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            BackupAdmin(
                archivo = o.getString("archivo"),
                fechaUtc = o.optString("fecha_utc", ""),
                accion = o.optString("accion", "mutacion")
            )
        }
    }

    suspend fun rollback(token: String, archivo: String? = null): String =
        withContext(Dispatchers.IO) {
            val query = if (archivo != null) "?archivo=${URLEncoder.encode(archivo, "UTF-8")}" else ""
            val resp = pedirAdmin("POST", "/admin/rollback$query", token)
            JSONObject(resp).optString("mensaje", "Estado anterior restaurado")
        }

    suspend fun restaurarFabrica(token: String): String = withContext(Dispatchers.IO) {
        val resp = pedirAdmin("POST", "/admin/restaurar-fabrica", token)
        JSONObject(resp).optString("mensaje", "Base restaurada al estado original")
    }
}