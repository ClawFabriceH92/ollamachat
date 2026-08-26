package com.trucdecomptable.ollamachat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * A complete Material 3 palette instead of four overridden colours.
 *
 * The chat leans hard on surface containers — bubbles, tool traces, code
 * blocks — and leaving those at the baseline defaults left them grey against
 * a violet app.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6541D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF210F63),
    secondary = Color(0xFF615B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF0F6),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF79767F),
    outlineVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F1FA),
    surfaceContainer = Color(0xFFF2ECF4),
    surfaceContainerHigh = Color(0xFFECE6EF),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    inverseSurface = Color(0xFF313035),
    inverseOnSurface = Color(0xFFF4EFF7),
    inversePrimary = Color(0xFFCBBEFF),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF35109B),
    primaryContainer = Color(0xFF4C29B9),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE7DEF8),
    tertiary = Color(0xFF80D4DA),
    onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF0F6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141318),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454E),
    surfaceContainerLowest = Color(0xFF0E0D13),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A),
    inverseSurface = Color(0xFFE6E0E9),
    inverseOnSurface = Color(0xFF313035),
    inversePrimary = Color(0xFF6541D2),
    scrim = Color(0xFF000000),
)

@Composable
fun OllamaChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Material You: follow the wallpaper when the user asked for it.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** True when the device can derive colours from the wallpaper. */
fun dynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
