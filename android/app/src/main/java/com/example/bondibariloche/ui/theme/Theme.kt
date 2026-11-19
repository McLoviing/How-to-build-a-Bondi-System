package com.example.bondibariloche.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Paleta oscura calibrada para evitar OLED smearing (#0F1419 en lugar de #000000)
// y maximizar el contraste de polilíneas e indicadores de tiempo en MapLibre.
val BondiDarkColorScheme = darkColorScheme(
    primary = BondiCelestePrimario,
    onPrimary = BondiCelesteOscuro,
    primaryContainer = BondiCelesteContenedor,
    onPrimaryContainer = BondiCelesteContenedorTexto,
    secondary = BondiVerdeTransbordo,
    onSecondary = BondiVerdeOscuro,
    secondaryContainer = BondiVerdeContenedor,
    onSecondaryContainer = BondiVerdeContenedorTexto,
    tertiary = BondiAmbarAlerta,
    onTertiary = BondiAmbarOscuro,
    background = BondiFondoOled,
    onBackground = BondiTextoClaro,
    surface = BondiSuperficie,
    onSurface = BondiTextoClaro,
    surfaceVariant = BondiSuperficieVariante,
    onSurfaceVariant = BondiTextoSecundario,
    surfaceContainerLow = BondiContenedorBajo,
    surfaceContainer = BondiContenedorMedio,
    surfaceContainerHigh = BondiContenedorAlto,
    outline = BondiBorde,
    outlineVariant = BondiBordeVariante,
    error = BondiError,
    onError = BondiErrorTexto
)

@Composable
fun BondiBarilocheTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BondiDarkColorScheme,
        typography = Typography,
        content = content
    )
}
