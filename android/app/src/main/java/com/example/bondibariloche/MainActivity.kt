
package com.example.bondibariloche

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bondibariloche.ui.theme.BondiBarilocheTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BondiBarilocheTheme {
                AplicacionBondi()
            }
        }
    }
}

@Composable
fun AplicacionBondi() {
    var pantalla by remember { mutableStateOf("inicio") }

    when (pantalla) {
        "inicio" -> PantallaInicio(
            usuario = { pantalla = "usuario" },
            administrador = { pantalla = "administrador" }
        )

        "usuario" -> PantallaUsuario(
            volver = { pantalla = "inicio" }
        )

        "administrador" -> PantallaAdministrador(
            volver = { pantalla = "inicio" }
        )
    }
}

@Composable
fun PantallaInicio(
    usuario: () -> Unit,
    administrador: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Bondi Bariloche",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))
        Text("Sistema de recomendación de colectivos")
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = usuario,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ingresar como Usuario")
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = administrador,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Ingresar como Administrador")
        }
    }
}

@Composable
fun PantallaUsuario(volver: () -> Unit) {
    var origen by remember { mutableStateOf("") }
    var destino by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TextButton(onClick = volver) {
            Text("← Volver")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "¿A dónde vamos?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text("Encontrá el colectivo para llegar a tu destino.")

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = origen,
            onValueChange = { origen = it },
            label = { Text("Parada de origen") },
            placeholder = { Text("Ej: Terminal") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                val temporal = origen
                origen = destino
                destino = temporal
                mensaje = ""
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("⇅ Intercambiar")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = destino,
            onValueChange = { destino = it },
            label = { Text("Parada de destino") },
            placeholder = { Text("Ej: Centro") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                mensaje = when {
                    origen.isBlank() || destino.isBlank() ->
                        "Completá el origen y el destino."

                    origen.trim().equals(destino.trim(), true) ->
                        "El origen y el destino son iguales."

                    else ->
                        "Búsqueda preparada: $origen → $destino. " +
                        "Falta conectar la API."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Buscar recorrido")
        }

        if (mensaje.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(mensaje)
        }

        Spacer(Modifier.height(28.dp))

        MapaBariloche()
    }
}

@Composable
fun PantallaAdministrador(volver: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Modo Administrador",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(16.dp))

        Text("Próximamente: gestión de líneas y paradas.")

        Spacer(Modifier.height(24.dp))

        Button(onClick = volver) {
            Text("Volver al inicio")
        }
    }
}

@Composable
fun MapaBariloche() {
    val contexto = LocalContext.current

    val mapa = remember(contexto) {
        Configuration.getInstance().setUserAgentValue(
            "BondiBariloche/1.0 (${contexto.packageName})"
        )

        MapView(contexto).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)

            // Centro de San Carlos de Bariloche
            controller.setZoom(13.0)
            controller.setCenter(
                GeoPoint(-41.1335, -71.3103)
            )
        }
    }

    DisposableEffect(mapa) {
        mapa.onResume()

        onDispose {
            mapa.onPause()
            mapa.onDetach()
        }
    }

    Column {
        AndroidView(
            factory = { mapa },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )

        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
