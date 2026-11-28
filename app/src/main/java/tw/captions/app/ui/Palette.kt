package tw.captions.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for dark and light appearance (ADR-031, same as iPhone). Dark is the original black
 * screen; light is white with black text, the settled current sentence in dark amber (pale
 * yellow is unreadable on white) and darker speaker colours.
 */
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    val border: Color,
    val stable: Color,
    val tentative: Color,
    val error: Color,
    val warn: Color,
    val accent: Color,
    val backgroundSpeech: Color,
    val meterTrack: Color,
    val speakers: List<Color>,
)

val DarkPalette = Palette(
    dark = true,
    background = Color.Black,
    surface = Color(0xFF1C1C1C),
    text = Color.White,
    sub = Color(0xFFB0B0B0),
    border = Color(0xFF3A3A3A),
    stable = Color(0xFFFFF59D),
    tentative = Color(0xFF9E9E9E),
    error = Color(0xFFFF8A80),
    warn = Color(0xFFFFB74D),
    accent = Color(0xFF66BB6A),
    backgroundSpeech = Color(0xFF757575),
    meterTrack = Color(0xFF303030),
    speakers = listOf(Color(0xFF80D8FF), Color(0xFFFFAB91), Color(0xFFB9F6CA), Color(0xFFEA80FC), Color(0xFFFFE57F)),
)

val LightPalette = Palette(
    dark = false,
    background = Color.White,
    surface = Color(0xFFF4F4F4),
    text = Color.Black,
    sub = Color(0xFF555555),
    border = Color(0xFFCCCCCC),
    stable = Color(0xFF8D5A00),
    tentative = Color(0xFF808080),
    error = Color(0xFFC62828),
    warn = Color(0xFFB45F06),
    accent = Color(0xFF2E7D32),
    // At least 3:1 on white (0x9E9E9E was 2.7:1); 0x767676 is 4.5:1.
    backgroundSpeech = Color(0xFF767676),
    meterTrack = Color(0xFFE0E0E0),
    speakers = listOf(Color(0xFF01579B), Color(0xFFBF360C), Color(0xFF1B5E20), Color(0xFF6A1B9A), Color(0xFF795548)),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Appearance setting: follow the system (default), or always dark / light. */
enum class Appearance(val label: String) { SYSTEM("跟隨系統"), DARK("深色"), LIGHT("淺色") }

@Composable
fun AppTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.DARK -> true
        Appearance.LIGHT -> false
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(background = p.background, surface = p.surface, primary = p.accent, onPrimary = Color.Black)
    } else {
        lightColorScheme(background = p.background, surface = p.surface, primary = p.accent, onPrimary = Color.White)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Shorthands for the current palette. */
val Ink: Color @Composable get() = LocalPalette.current.text

/**
 * Inside a ModalBottomSheet: the sheet has its own window, which picks status-bar icon colours
 * from the system setting. With a forced dark / light appearance that gives white icons on white
 * (or black on black); set them from the app's palette instead.
 */
@Composable
fun SheetStatusBar() {
    val view = androidx.compose.ui.platform.LocalView.current
    val light = !LocalPalette.current.dark
    androidx.compose.runtime.SideEffect {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null && parent !is androidx.compose.ui.window.DialogWindowProvider) parent = parent.parent
        val window = (parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
