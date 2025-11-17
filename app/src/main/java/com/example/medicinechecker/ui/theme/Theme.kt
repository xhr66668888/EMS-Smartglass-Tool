package com.example.medicinechecker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AquaTeal,
    onPrimary = Color(0xFF00211B),
    secondary = ElectricBlue,
    tertiary = SunsetPeach,
    background = MidnightIndigo,
    surface = Color(0xFF24244A),
    onBackground = Color(0xFFE8EAFF),
    onSurface = Color(0xFFE8EAFF),
    surfaceVariant = Color(0xFF3A3C69),
    outline = Color(0xFF6A6C9A)
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    secondary = AquaTeal,
    onSecondary = Color(0xFF00211B),
    tertiary = SunsetPeach,
    background = MistySurface,
    surface = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFE3E6F5),
    outline = TextSecondary.copy(alpha = 0.4f),
    inverseSurface = MidnightIndigo,
    inverseOnSurface = Color(0xFFE8EAFF)
)

@Composable
fun MedicineCheckerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
