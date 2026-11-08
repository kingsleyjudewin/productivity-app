package com.dayzero.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Dz {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF111111)
    val Surface = Color(0xFFF2F2F7)
    val Divider = Color(0xFFD9D9DE)
    val Muted = Color(0xFF8E8E93)
    /** Darker edge under primary buttons to give the restrained 3D depth. */
    val Edge = Color(0xFF3A3A3C)
    val Alert = Color(0xFF111111)
}

private val colors = lightColorScheme(
    primary = Dz.Black, onPrimary = Dz.White,
    background = Dz.White, onBackground = Dz.Black,
    surface = Dz.White, onSurface = Dz.Black,
    surfaceVariant = Dz.Surface, onSurfaceVariant = Dz.Muted,
    outline = Dz.Divider,
)

private val sans = FontFamily.SansSerif

private val type = Typography(
    displayLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 72.sp, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 15.sp),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp),
)

/** DAYZERO is deliberately light-only: pure white, deep black. */
@Composable
fun DayZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
