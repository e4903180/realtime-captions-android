package tw.captions.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.captions.app.ui.DarkPalette
import tw.captions.app.ui.LightPalette

class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    @Test
    fun captionColoursAreReadable() {
        for (p in listOf(DarkPalette, LightPalette)) {
            // Background speech (TV) is meant to be quieter, but still at least 3:1.
            assertTrue("backgroundSpeech dark=${p.dark}", contrast(p.backgroundSpeech, p.background) >= 3.0)
            assertTrue("tentative dark=${p.dark}", contrast(p.tentative, p.background) >= 3.0)
            assertTrue("text dark=${p.dark}", contrast(p.text, p.background) >= 7.0)
            assertTrue("stable dark=${p.dark}", contrast(p.stable, p.background) >= 4.5)
        }
    }
}
