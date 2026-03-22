package com.vakildoot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Colour palette ───────────────────────────────────────────────────────────
object VakilColors {
    // Dark surface hierarchy
    val Ink          = Color(0xFF0D0E14)
    val Ink2         = Color(0xFF13151F)
    val Ink3         = Color(0xFF1C1E2C)
    val Ink4         = Color(0xFF252838)

    // Gold accent (primary brand)
    val Gold         = Color(0xFFD4A843)
    val Gold2        = Color(0xFFF0C96A)
    val GoldBg       = Color(0x14D4A843)  // 8% opacity

    // Orange accent (secondary - for highlights)
    val Orange       = Color(0xFFFF6B35)
    val OrangeBg     = Color(0x14FF6B35)  // 8% opacity

    // Text hierarchy
    val TextPrimary  = Color(0xFFE8E6F0)
    val TextSecondary= Color(0xFF9896AA)
    val TextTertiary = Color(0xFF5A5870)

    // Semantic
    val RiskHigh     = Color(0xFFE05555)
    val RiskMed      = Color(0xFFD4822A)
    val RiskLow      = Color(0xFF3DB87A)
    val RiskHighBg   = Color(0x1AE05555)
    val RiskMedBg    = Color(0x1AD4822A)
    val RiskLowBg    = Color(0x1A3DB87A)

    // Border
    val Border       = Color(0x12FFFFFF)
    val Border2      = Color(0x1FFFFFFF)

    // Gradient
    val GradientStart= Color(0xFF13151F)
    val GradientEnd  = Color(0xFF1C1E2C)
}

private val DarkColorScheme = darkColorScheme(
    primary          = VakilColors.Gold,
    onPrimary        = VakilColors.Ink,
    primaryContainer = VakilColors.GoldBg,
    background       = VakilColors.Ink,
    surface          = VakilColors.Ink2,
    surfaceVariant   = VakilColors.Ink3,
    onBackground     = VakilColors.TextPrimary,
    onSurface        = VakilColors.TextPrimary,
    onSurfaceVariant = VakilColors.TextSecondary,
    outline          = VakilColors.Border,
    outlineVariant   = VakilColors.Border2,
    error            = VakilColors.RiskHigh,
)

// VakilDoot uses Syne (display) + Instrument Sans (body) from Google Fonts
// In production: add font files to res/font/ and reference here
// For simplicity we fall back to default sans-serif at runtime
val SyneFontFamily = FontFamily.Default
val InstrumentFontFamily = FontFamily.Default

val VakilTypography = Typography(
    displayLarge  = TextStyle(fontFamily = SyneFontFamily, fontWeight = FontWeight.ExtraBold,  fontSize = 32.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = SyneFontFamily, fontWeight = FontWeight.Bold,        fontSize = 26.sp),
    headlineLarge = TextStyle(fontFamily = SyneFontFamily, fontWeight = FontWeight.Bold,        fontSize = 22.sp),
    headlineMedium= TextStyle(fontFamily = SyneFontFamily, fontWeight = FontWeight.SemiBold,    fontSize = 18.sp),
    titleLarge    = TextStyle(fontFamily = SyneFontFamily, fontWeight = FontWeight.SemiBold,    fontSize = 16.sp),
    titleMedium   = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge     = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium    = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall     = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    labelLarge    = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium   = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelSmall    = TextStyle(fontFamily = InstrumentFontFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp),
)

@Composable
fun VakilDootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = VakilTypography,
        content     = content,
    )
}
