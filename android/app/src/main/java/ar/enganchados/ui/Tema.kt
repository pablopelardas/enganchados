package ar.enganchados.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mismos colores que la version de escritorio, para que se sientan la
// misma herramienta aunque sean dos apps distintas.
val Acento = Color(0xFFFFB340)
val AcentoFuerte = Color(0xFFFF9500)
val Fondo = Color(0xFF14161A)
val Panel = Color(0xFF1C1F26)
val PanelAlto = Color(0xFF232732)
val Borde = Color(0xFF2E3341)
val Tenue = Color(0xFF8A92A6)
val Ok = Color(0xFF4ADE80)
val Mal = Color(0xFFF87171)

private val oscuro = darkColorScheme(
    primary = Acento,
    onPrimary = Color(0xFF1A1206),
    secondary = AcentoFuerte,
    background = Fondo,
    onBackground = Color(0xFFE4E7EE),
    surface = Panel,
    onSurface = Color(0xFFE4E7EE),
    surfaceVariant = PanelAlto,
    onSurfaceVariant = Tenue,
    outline = Borde,
    error = Mal,
)

private val claro = lightColorScheme(
    primary = AcentoFuerte,
    background = Color(0xFFF7F7F9),
    surface = Color.White,
)

@Composable
fun TemaEnganchados(content: @Composable () -> Unit) {
    // La app es para usar en fiestas y de noche: oscuro por defecto.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) oscuro else oscuro,
        content = content,
    )
}
