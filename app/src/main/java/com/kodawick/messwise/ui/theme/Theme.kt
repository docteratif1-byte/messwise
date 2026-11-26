package com.kodawick.messwise.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.text.TextStyle
import android.content.Context
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kodawick.messwise.R

/**
 * Messwise brand, "Haldi and Baingan": aubergine for structure and headings, turmeric for the main
 * buttons (always with dark ink on it), cream ground. Every text colour passes 4.5:1, most by far.
 */
object Brand {
    val Aubergine = Color(0xFF4A2545)
    val Turmeric = Color(0xFFF2A900)
    val Ink = Color(0xFF2A1D14)
    val Muted = Color(0xFF6B5B4E)
    val Cream = Color(0xFFFFF8EC)
    val Border = Color(0xFFE9DFD3)
    val Green = Color(0xFF1E7B47)
    val Red = Color(0xFFB42318)
    val Slate = Color(0xFF5B6780)
}

/** Colours that carry meaning: paid, pending, slip waiting, partly paid, on leave. Always shown with words too. */
@Immutable
data class StatusColors(
    val good: Color, val goodBg: Color,
    val owe: Color, val oweBg: Color,
    val part: Color, val partBg: Color,
    val info: Color, val infoBg: Color,
    val muted: Color, val mutedBg: Color,
)

private val LightStatus = StatusColors(
    good = Brand.Green, goodBg = Color(0xFFDDF1E4),
    owe = Brand.Red, oweBg = Color(0xFFFBE3E0),
    part = Color(0xFF8A5300), partBg = Color(0xFFFFEFC7),
    info = Brand.Slate, infoBg = Color(0xFFE6E9F0),
    muted = Brand.Muted, mutedBg = Color(0xFFF1E9DE),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF5FD08F), goodBg = Color(0xFF173A26),
    owe = Color(0xFFFF8A7A), oweBg = Color(0xFF4A1F1A),
    part = Color(0xFFF5B82E), partBg = Color(0xFF443210),
    info = Color(0xFFA3AEC4), infoBg = Color(0xFF2A3040),
    muted = Color(0xFFB9AEB8), mutedBg = Color(0xFF2E2530),
)

val LocalStatus = staticCompositionLocalOf { LightStatus }

private val Light = lightColorScheme(
    primary = Brand.Aubergine, onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DCEC), onPrimaryContainer = Color(0xFF2E1129),
    secondary = Brand.Turmeric, onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFFFFEBC2), onSecondaryContainer = Color(0xFF3D2A00),
    tertiary = Brand.Green, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7F0E0), onTertiaryContainer = Color(0xFF0A3A20),
    background = Brand.Cream, onBackground = Brand.Ink,
    surface = Color.White, onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF3EBE0), onSurfaceVariant = Brand.Muted,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFBF4),
    surfaceContainer = Color(0xFFFBF3E7), surfaceContainerHigh = Color(0xFFF6EDE1), surfaceContainerHighest = Color(0xFFF0E6D9),
    outline = Color(0xFFB7A797), outlineVariant = Brand.Border,
    error = Brand.Red, onError = Color.White, errorContainer = Color(0xFFFBE3E0), onErrorContainer = Color(0xFF5A100A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD9B8D4), onPrimary = Color(0xFF3A1735),
    primaryContainer = Color(0xFF5E3358), onPrimaryContainer = Color(0xFFF6DDF0),
    secondary = Color(0xFFF5B82E), onSecondary = Color(0xFF17121A),
    secondaryContainer = Color(0xFF5A4300), onSecondaryContainer = Color(0xFFFFE2A3),
    tertiary = Color(0xFF5FD08F), onTertiary = Color(0xFF00391D),
    tertiaryContainer = Color(0xFF16502E), onTertiaryContainer = Color(0xFFC9F2D8),
    background = Color(0xFF17121A), onBackground = Color(0xFFF3ECE4),
    surface = Color(0xFF241C26), onSurface = Color(0xFFF3ECE4),
    surfaceVariant = Color(0xFF332936), onSurfaceVariant = Color(0xFFB9AEB8),
    surfaceContainerLowest = Color(0xFF120E14), surfaceContainerLow = Color(0xFF1D171F),
    surfaceContainer = Color(0xFF241C26), surfaceContainerHigh = Color(0xFF2C232E), surfaceContainerHighest = Color(0xFF352B37),
    outline = Color(0xFF7D717C), outlineVariant = Color(0xFF41363F),
    error = Color(0xFFFF8A7A), onError = Color(0xFF4A0B05), errorContainer = Color(0xFF5C1D16), onErrorContainer = Color(0xFFFBE3E0),
)

/**
 * Urdu fonts, the same as Dr Jason Partner and MohallaCircle: Jameel Noori Nastaleeq for text and
 * Jameel Noori Nastaleeq Kasheeda for headings ("free of charge for Urdu lovers"). Built by
 * tools/urdu-fonts/build.py: enlarged 1.35x inside the font, with Latin letters, digits and spaces left
 * out so Android draws those with the phone's normal font.
 *
 * Nastaleeq has one weight. Each requested weight gets its own Typeface from the same file that declares
 * that weight: the Urdu shapes are never fake-bolded (which smears Nastaleeq), while the English words and
 * digits, which come from the phone's font, still show in bold where the design asks for bold.
 */
private class Nastaleeq(val path: String, override val weight: FontWeight) :
    AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): android.graphics.Typeface? {
            val f = font as Nastaleeq
            return android.graphics.Typeface.Builder(context.assets, f.path).setWeight(f.weight.weight).setItalic(false).build()
        }
        override suspend fun awaitLoad(context: Context, font: AndroidFont): android.graphics.Typeface? = loadBlocking(context, font)
    }

    override fun toString() = "Nastaleeq($path, ${weight.weight})"
}

private fun nastaleeq(path: String) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { Nastaleeq(path, it) },
)
private val JameelNoori = nastaleeq("fonts/mw_jameel_noori.ttf")
private val JameelKasheeda = nastaleeq("fonts/mw_jameel_kasheeda.ttf")

/** Nastaleeq needs taller lines than Latin text; the line box is centred so nothing is cut off. */
private fun TextStyle.urdu(family: FontFamily) = copy(
    fontFamily = family,
    lineHeight = 2.0.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private val Base = Typography()

private val UrduTypography = Typography(
    // Headings: Kasheeda.
    displayLarge = Base.displayLarge.urdu(JameelKasheeda), displayMedium = Base.displayMedium.urdu(JameelKasheeda),
    displaySmall = Base.displaySmall.urdu(JameelKasheeda),
    headlineLarge = Base.headlineLarge.urdu(JameelKasheeda), headlineMedium = Base.headlineMedium.urdu(JameelKasheeda),
    headlineSmall = Base.headlineSmall.urdu(JameelKasheeda),
    titleLarge = Base.titleLarge.urdu(JameelKasheeda), titleMedium = Base.titleMedium.urdu(JameelKasheeda),
    // Text: Noori.
    titleSmall = Base.titleSmall.urdu(JameelNoori),
    bodyLarge = Base.bodyLarge.urdu(JameelNoori), bodyMedium = Base.bodyMedium.urdu(JameelNoori), bodySmall = Base.bodySmall.urdu(JameelNoori),
    labelLarge = Base.labelLarge.urdu(JameelNoori), labelMedium = Base.labelMedium.urdu(JameelNoori), labelSmall = Base.labelSmall.urdu(JameelNoori),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun MesswiseTheme(themePref: String = "system", content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    // True exactly when the Urdu texts are on screen (values-ur), whether Urdu was chosen in the app or on the phone.
    val urdu = booleanResource(R.bool.urdu_ui)
    CompositionLocalProvider(LocalStatus provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = if (urdu) UrduTypography else Base,
            shapes = AppShapes,
            content = content,
        )
    }
}
