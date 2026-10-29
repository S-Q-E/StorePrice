package com.storeprice.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Sand40,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Sand90,
    onSecondaryContainer = Sand10,
    background = NeutralLight,
    surface = NeutralLight,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green10,
    primaryContainer = Green40,
    onPrimaryContainer = Green90,
    secondary = Sand80,
    onSecondary = Sand10,
    secondaryContainer = Sand40,
    onSecondaryContainer = Sand90,
    background = NeutralDark,
    surface = NeutralDark,
)

@Composable
fun StorePriceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
