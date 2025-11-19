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

private val IndustrialColorScheme = darkColorScheme(
    primary = IndustrialHighVisYellow,
    onPrimary = Color.Black,
    secondary = IndustrialOrange,
    onSecondary = Color.Black,
    tertiary = IndustrialCyan,
    onTertiary = Color.Black,
    background = IndustrialBlack,
    onBackground = IndustrialTextPrimary,
    surface = IndustrialSurface,
    onSurface = IndustrialTextPrimary,
    surfaceVariant = IndustrialDarkGray,
    onSurfaceVariant = IndustrialTextSecondary,
    error = IndustrialDanger,
    onError = Color.Black,
    outline = IndustrialTextSecondary
)

@Composable
fun MedicineCheckerTheme(
    darkTheme: Boolean = true, // Force Dark Theme
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disable dynamic color for consistency
    content: @Composable () -> Unit
) {
    // Always use Industrial Color Scheme
    val colorScheme = IndustrialColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
