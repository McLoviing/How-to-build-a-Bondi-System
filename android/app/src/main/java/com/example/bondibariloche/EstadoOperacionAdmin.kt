package com.example.bondibariloche

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State Holder estable que encapsula la ejecución asíncrona de operaciones administrativas,
 * exclusión mutua en UI y notificaciones Snackbar no bloqueantes con soporte de Rollback.
 */
@Stable
class EstadoOperacionAdmin(
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState
) {
    var ocupado by mutableStateOf(false)
        private set

    var mensaje by mutableStateOf("")
        private set

    var error by mutableStateOf(false)
        private set

    private var snackbarJob: Job? = null
    private var generacion: Long = 0L

    fun reportarError(texto: String) {
        error = true
        mensaje = texto
    }

    fun limpiarMensaje() {
        mensaje = ""
        error = false
    }

    /**
     * Ejecuta [accion] dentro de [scope] bloqueando doble envío mientras dura la red.
     * Al finalizar, libera la UI de inmediato y muestra el aviso en un Job independiente.
     */
    fun ejecutar(
        ofrecerDeshacer: Boolean = false,
        textoExito: String? = null,
        alDeshacer: (suspend () -> Unit)? = null,
        accion: suspend () -> Unit
    ) {
        if (ocupado) return

        val genActual = ++generacion
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()

        ocupado = true
        mensaje = ""
        error = false

        scope.launch {
            var exito = false
            try {
                accion()
                exito = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                mensaje = e.message ?: "No se pudo completar la operación"
            } finally {
                ocupado = false
            }

            if (exito && textoExito != null && genActual == generacion) {
                mensaje = textoExito
                if (ofrecerDeshacer && alDeshacer != null) {
                    lanzarAvisoConDeshacer(textoExito, genActual, alDeshacer)
                }
            }
        }
    }

    private fun lanzarAvisoConDeshacer(
        texto: String,
        genOrigen: Long,
        alDeshacer: suspend () -> Unit
    ) {
        snackbarJob?.cancel()
        snackbarJob = scope.launch {
            val temporizador = launch {
                delay(4_500L)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            try {
                val resultado = snackbarHostState.showSnackbar(
                    message = texto,
                    actionLabel = "DESHACER",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite
                )
                if (resultado == SnackbarResult.ActionPerformed && genOrigen == generacion) {
                    ejecutar(
                        ofrecerDeshacer = false,
                        textoExito = "Último cambio revertido",
                        accion = alDeshacer
                    )
                }
            } finally {
                temporizador.cancel()
            }
        }
    }
}

@Composable
fun rememberEstadoOperacionAdmin(
    scope: CoroutineScope = rememberCoroutineScope(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
): EstadoOperacionAdmin {
    return remember(scope, snackbarHostState) {
        EstadoOperacionAdmin(scope = scope, snackbarHostState = snackbarHostState)
    }
}