package app.instaaddict.runner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OkColor = Color(0xFF2E7D32)
val WarnColor = Color(0xFFE65100)
val BadColor = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3E001D),
    secondaryContainer = Color(0xFFFFE4EB),
    onSecondaryContainer = Color(0xFF3E001D),
    background = Color(0xFFFFFBFC),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF6E3E8),
    onSurfaceVariant = Color(0xFF514347),
    error = BadColor,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF650033),
    primaryContainer = Color(0xFF8D0F4A),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondaryContainer = Color(0xFF5B3F47),
    onSecondaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF181214),
    onBackground = Color(0xFFECDFE1),
    surface = Color(0xFF181214),
    onSurface = Color(0xFFECDFE1),
    surfaceVariant = Color(0xFF3A2D31),
    onSurfaceVariant = Color(0xFFD6C2C6),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
