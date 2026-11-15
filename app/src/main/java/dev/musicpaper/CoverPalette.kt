package dev.musicpaper

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.Score

/** Colours for the "Now playing" card. Either the app's brand colours or a Material 3 scheme built from a cover. */
data class CoverPalette(
    val heroStart: Int,
    val heroEnd: Int,
    val onHero: Int,
    val accent: Int,
    val onAccent: Int,
    val pill: Int,
    val tonal: Int,
    val onTonal: Int,
) {
    /** Every colour [fraction] of the way from this palette to [target], for animating between the two. */
    fun blend(target: CoverPalette, fraction: Float): CoverPalette {
        fun mix(from: Int, to: Int) = argbEvaluator.evaluate(fraction, from, to) as Int
        return CoverPalette(
            heroStart = mix(heroStart, target.heroStart),
            heroEnd = mix(heroEnd, target.heroEnd),
            onHero = mix(onHero, target.onHero),
            accent = mix(accent, target.accent),
            onAccent = mix(onAccent, target.onAccent),
            pill = mix(pill, target.pill),
            tonal = mix(tonal, target.tonal),
            onTonal = mix(onTonal, target.onTonal),
        )
    }

    companion object {
        private const val BRAND_SEED = 0xFF4F4DBF.toInt()
        private const val SAMPLE_SIZE = 112 // covers are shrunk to this before picking a colour
        private const val MAX_COLORS = 128
        private val argbEvaluator = ArgbEvaluator()

        /** The card's default look, taken from the app theme. */
        fun brand(context: Context): CoverPalette = CoverPalette(
            heroStart = ContextCompat.getColor(context, R.color.hero_start),
            heroEnd = ContextCompat.getColor(context, R.color.hero_end),
            onHero = attr(context, com.google.android.material.R.attr.colorOnPrimaryContainer),
            accent = attr(context, androidx.appcompat.R.attr.colorPrimary),
            onAccent = attr(context, com.google.android.material.R.attr.colorOnPrimary),
            pill = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLowest),
            tonal = attr(context, com.google.android.material.R.attr.colorSecondaryContainer),
            onTonal = attr(context, com.google.android.material.R.attr.colorOnSecondaryContainer),
        )

        /**
         * Picks the cover's most suitable colour the way Android picks one from a wallpaper
         * (quantize, then score), and builds a content scheme from it, so text keeps M3 contrast.
         * Grey covers have no usable colour and fall back to the brand hue. Runs in a few ms; call off the main thread.
         */
        @SuppressLint("RestrictedApi")
        fun fromCover(cover: Bitmap, dark: Boolean): CoverPalette {
            val small = Bitmap.createScaledBitmap(cover, SAMPLE_SIZE, SAMPLE_SIZE, true)
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            if (small !== cover) small.recycle()

            val seed = Score.score(QuantizerCelebi.quantize(pixels, MAX_COLORS), 1, BRAND_SEED, true).first()
            // The palettes keep the cover's hue and chroma; the tones are fixed here so the card is always
            // pastel in light mode and deep in dark mode, whatever the cover's brightness. That keeps the
            // text contrast M3 asks for (tone 10 on 90, tone 90 on 30) on every cover.
            val scheme = SchemeContent(Hct.fromInt(seed), dark, 0.0)
            val primary = scheme.primaryPalette
            val tertiary = scheme.tertiaryPalette
            val secondary = scheme.secondaryPalette
            val neutral = scheme.neutralPalette
            return if (dark) {
                CoverPalette(
                    heroStart = primary.tone(30), heroEnd = tertiary.tone(30), onHero = primary.tone(90),
                    accent = primary.tone(80), onAccent = primary.tone(20),
                    pill = neutral.tone(6), tonal = secondary.tone(20), onTonal = secondary.tone(90),
                )
            } else {
                CoverPalette(
                    heroStart = primary.tone(90), heroEnd = tertiary.tone(90), onHero = primary.tone(10),
                    accent = primary.tone(40), onAccent = primary.tone(100),
                    pill = neutral.tone(100), tonal = neutral.tone(98), onTonal = primary.tone(30),
                )
            }
        }

        fun isDark(context: Context): Boolean {
            val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightMode == Configuration.UI_MODE_NIGHT_YES
        }

        private fun attr(context: Context, id: Int): Int = MaterialColors.getColor(context, id, 0)
    }
}
