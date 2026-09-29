package app.zenelo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.zenelo.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette from the Figma file "Final · Mustard + Celadon". */
object ZeneloColors {
    val Background = Color(0xFF0D0E0B)
    val Bar = Color(0xFF181A15)          // bottom nav, mini player
    val Card = Color(0xFF20231C)         // icon tiles, settings cards
    val Placeholder = Color(0xFF2A2D25)  // empty cover art
    val Inset = Color(0xFF121410)        // cells inside a card (sort menu, segmented control)
    val TextPrimary = Color(0xFFF4F4EE)
    val TextSecondary = Color(0xFFAEB0A0)
    val TextMuted = Color(0xFF7F8274)
    val Mustard = Color(0xFFD4A24C)
    val OnMustard = Color(0xFF170F03)
    val Celadon = Color(0xFF9FD18B)
    val Danger = Color(0xFFE0685F)
    val Info = Color(0xFF8FB7FF)

    val MustardTint = Mustard.copy(alpha = 0.14f)
    val CeladonTint = Celadon.copy(alpha = 0.12f)
}

// IBM Plex (OFL), built from the design bundle's woff2 subsets (latin, latin-ext, cyrillic,
// cyrillic-ext, greek, vietnamese) merged into one TTF per weight.
val PlexSans: FontFamily = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_bold, FontWeight.Bold),
)
val PlexMono: FontFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

private val ZeneloTypography = Typography(
    // Screen titles: "Ambient", "Favorites".
    titleLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.22).sp),
    // Now Playing track title.
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Bold, fontSize = 19.sp),
    // Sub-screen titles, "Low Tide Signals" in the mini player.
    titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    // List row titles.
    bodyLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, lineHeight = 20.25.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 16.sp),
    // Mono: section headers ("FOLDERS · 3") and metadata ("4:31 · 24/96 · 78 MB").
    labelMedium = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = PlexMono, fontSize = 10.5.sp, letterSpacing = 1.26.sp),
).let { t ->
    // The styles not set above (buttons, dialogs, text fields) in Plex Sans too, not Roboto.
    val d = Typography()
    t.copy(
        displayLarge = d.displayLarge.copy(fontFamily = PlexSans),
        displayMedium = d.displayMedium.copy(fontFamily = PlexSans),
        displaySmall = d.displaySmall.copy(fontFamily = PlexSans),
        headlineLarge = d.headlineLarge.copy(fontFamily = PlexSans),
        headlineMedium = d.headlineMedium.copy(fontFamily = PlexSans),
        headlineSmall = d.headlineSmall.copy(fontFamily = PlexSans),
        labelLarge = d.labelLarge.copy(fontFamily = PlexSans),
    )
}

private val ZeneloColorScheme = darkColorScheme(
    primary = ZeneloColors.Mustard,
    onPrimary = ZeneloColors.OnMustard,
    secondary = ZeneloColors.Celadon,
    onSecondary = ZeneloColors.Background,
    background = ZeneloColors.Background,
    onBackground = ZeneloColors.TextPrimary,
    surface = ZeneloColors.Background,
    onSurface = ZeneloColors.TextPrimary,
    surfaceVariant = ZeneloColors.Card,
    onSurfaceVariant = ZeneloColors.TextSecondary,
    surfaceContainer = ZeneloColors.Card,
    surfaceContainerHigh = ZeneloColors.Card,
    outline = ZeneloColors.TextMuted,
    error = ZeneloColors.Danger,
)

@Composable
fun ZeneloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZeneloColorScheme, typography = ZeneloTypography, content = content)
}
