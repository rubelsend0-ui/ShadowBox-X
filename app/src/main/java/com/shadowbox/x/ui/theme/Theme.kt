package com.shadowbox.x.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Midnight = Color(0xFF05070D)
val Navy = Color(0xFF0A1226)
val NavyLight = Color(0xFF111C3A)
val Electric = Color(0xFF2F80FF)
val Cyan = Color(0xFF22D3EE)
val Danger = Color(0xFFFF5C6C)
val Ok = Color(0xFF34D399)

enum class Accent(val label: String, val color: Color) {
    ELECTRIC("Electric Blue", Color(0xFF2F80FF)),
    CYAN("Cyan", Color(0xFF22D3EE)),
    VIOLET("Violet", Color(0xFF8B7CFF))
}

private val Sans = FontFamily.SansSerif
private val ShadowTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 1.5.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun ShadowTheme(accent: Accent, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent.color, onPrimary = Color.White,
            secondary = Cyan, onSecondary = Midnight,
            background = Midnight, onBackground = Color(0xFFE8EEFF),
            surface = Navy, onSurface = Color(0xFFE8EEFF),
            surfaceVariant = NavyLight, onSurfaceVariant = Color(0xFF9FB0D6),
            error = Danger, outline = Color(0xFF3A4A75)
        ),
        typography = ShadowTypography,
        content = content
    )
}
