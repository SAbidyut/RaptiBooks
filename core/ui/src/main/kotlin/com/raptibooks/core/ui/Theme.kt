package com.raptibooks.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.raptibooks.core.model.ReaderFont
import com.raptibooks.core.model.ReaderTheme

/** Bundled fonts. Both cover Bangla and Latin, so mixed text uses one consistent face. */
object RaptiFonts {
    val serif = FontFamily(
        Font(R.font.tiro_bangla_regular, FontWeight.Normal),
        Font(R.font.tiro_bangla_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val sans = FontFamily(
        Font(R.font.hind_siliguri_regular, FontWeight.Normal),
        Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
        Font(R.font.hind_siliguri_bold, FontWeight.Bold),
    )

    fun forReader(font: ReaderFont): FontFamily = when (font) {
        ReaderFont.SERIF -> serif
        ReaderFont.SANS -> sans
        ReaderFont.SYSTEM_SERIF -> FontFamily.Serif
        ReaderFont.SYSTEM_SANS -> FontFamily.SansSerif
    }
}

private val Ink = Color(0xFF1F4E5F)
private val InkLight = Color(0xFF8FC6D8)
private val Ember = Color(0xFFE8833A)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8F1),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF9A4F16),
    secondaryContainer = Color(0xFFFFDCC6),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = Ember,
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4D463B),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEEE7DC),
    surfaceContainerLow = Color(0xFFF7F2EA),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD1C5B4),
)

private val DarkColors = darkColorScheme(
    primary = InkLight,
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF0F4D61),
    onPrimaryContainer = Color(0xFFCDE8F1),
    secondary = Color(0xFFFFB68A),
    secondaryContainer = Color(0xFF6E3A0C),
    onSecondaryContainer = Color(0xFFFFDCC6),
    tertiary = Ember,
    background = Color(0xFF15130F),
    onBackground = Color(0xFFE9E1D6),
    surface = Color(0xFF15130F),
    onSurface = Color(0xFFE9E1D6),
    surfaceVariant = Color(0xFF4D463B),
    onSurfaceVariant = Color(0xFFD1C5B4),
    surfaceContainer = Color(0xFF221F1A),
    surfaceContainerHigh = Color(0xFF2C2923),
    surfaceContainerLow = Color(0xFF1C1A15),
    outline = Color(0xFF9A8F80),
    outlineVariant = Color(0xFF4D463B),
)

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.sans() = copy(fontFamily = RaptiFonts.sans)
    return Typography(
        displayLarge = base.displayLarge.sans(),
        displayMedium = base.displayMedium.sans(),
        displaySmall = base.displaySmall.sans(),
        headlineLarge = base.headlineLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.sans(),
        bodyMedium = base.bodyMedium.sans(),
        bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.sans(),
        labelSmall = base.labelSmall.sans().copy(fontSize = 11.sp),
    )
}

@Composable
fun RaptiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = appTypography(),
        content = content,
    )
}

/** Colours of the reading surface for each [ReaderTheme]. */
@Immutable
data class ReaderColors(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val surface: Color,
    val highlight: Color,
    val isDark: Boolean,
) {
    companion object {
        fun of(theme: ReaderTheme): ReaderColors = when (theme) {
            ReaderTheme.LIGHT -> ReaderColors(
                background = Color(0xFFFFFFFF), text = Color(0xFF1C1B19), secondary = Color(0xFF6A665E),
                accent = Ink, surface = Color(0xFFF4F1EC), highlight = Color(0x66FFD54F), isDark = false,
            )
            ReaderTheme.SEPIA -> ReaderColors(
                background = Color(0xFFF4ECD8), text = Color(0xFF4B3A26), secondary = Color(0xFF7D6A52),
                accent = Color(0xFF8B4A1C), surface = Color(0xFFEADFC6), highlight = Color(0x66E8A33A), isDark = false,
            )
            ReaderTheme.DARK -> ReaderColors(
                background = Color(0xFF1E1D1B), text = Color(0xFFE2DDD3), secondary = Color(0xFFA19B90),
                accent = InkLight, surface = Color(0xFF2A2825), highlight = Color(0x668FC6D8), isDark = true,
            )
            ReaderTheme.BLACK -> ReaderColors(
                background = Color(0xFF000000), text = Color(0xFFC9C6C0), secondary = Color(0xFF8C8882),
                accent = InkLight, surface = Color(0xFF151515), highlight = Color(0x668FC6D8), isDark = true,
            )
        }
    }
}
