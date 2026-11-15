package dev.musicpaper

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * The "Now playing" card: the song, its cover, and colours that follow the cover.
 * [hasNotificationAccess] picks the message shown when nothing is playing.
 */
class NowPlayingCard(
    private val activity: AppCompatActivity,
    private val hasNotificationAccess: () -> Boolean,
) {
    private val paletteWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroGradient = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TL_BR }
    private var heroPalette: CoverPalette? = null
    private var heroAnimator: ValueAnimator? = null
    private var paletteFor: Bitmap? = null

    /** Kept as one instance so [NowPlayingStore.remove] finds it again. */
    val onNowPlaying: (NowPlaying?) -> Unit = { show(it) }

    fun bind() {
        activity.findViewById<View>(R.id.heroBg).background = heroGradient
        setHeroPalette(CoverPalette.brand(activity), animate = false)

        activity.findViewById<Button>(R.id.btnApply).setOnClickListener {
            val nowPlaying = NowPlayingStore.current
            if (nowPlaying?.art == null) {
                activity.toast(R.string.toast_nothing_with_cover)
                return@setOnClickListener
            }
            WallpaperApplier.apply(activity, nowPlaying) { ok ->
                activity.toast(if (ok) R.string.toast_lock_updated else R.string.toast_lock_update_failed)
            }
        }
        activity.findViewById<Button>(R.id.btnReset).setOnClickListener {
            WallpaperApplier.fallback(activity) { ok ->
                activity.toast(if (ok) R.string.toast_lock_reset else R.string.toast_lock_reset_failed)
            }
        }
    }

    fun release() {
        heroAnimator?.cancel()
        paletteWorker.shutdownNow()
    }

    private fun show(nowPlaying: NowPlaying?) {
        val art = activity.findViewById<ImageView>(R.id.art)
        val title = activity.findViewById<TextView>(R.id.title)
        val artist = activity.findViewById<TextView>(R.id.artist)
        val source = activity.findViewById<TextView>(R.id.source)
        if (nowPlaying == null) {
            showCover(art, null)
            title.setText(if (hasNotificationAccess()) R.string.now_playing_none else R.string.now_playing_needs_access)
            artist.text = ""
            source.text = ""
            followCover(null)
            return
        }
        showCover(art, nowPlaying.art)
        followCover(nowPlaying.art)
        title.text = nowPlaying.title
        artist.text = nowPlaying.artist
        source.text = activity.getString(R.string.now_playing_via, appLabel(nowPlaying.packageName))
    }

    private fun appLabel(packageName: String): CharSequence {
        val packageManager = activity.packageManager
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        }.getOrDefault(packageName)
    }

    /** Recolours the card from [cover], or back to the brand colours when there is none. Same cover: no work. */
    private fun followCover(cover: Bitmap?) {
        if (cover === paletteFor && heroPalette != null) return
        paletteFor = cover
        if (cover == null) {
            setHeroPalette(CoverPalette.brand(activity), animate = true)
            return
        }
        val dark = CoverPalette.isDark(activity)
        paletteWorker.execute {
            val palette = runCatching { CoverPalette.fromCover(cover, dark) }.getOrNull() ?: return@execute
            mainHandler.post {
                // A newer song may have arrived while this one was being worked out.
                if (paletteFor === cover && !activity.isDestroyed) setHeroPalette(palette, animate = true)
            }
        }
    }

    private fun setHeroPalette(target: CoverPalette, animate: Boolean) {
        heroAnimator?.cancel()
        val from = heroPalette
        if (!animate || from == null) {
            paintHero(target)
            heroPalette = target
            return
        }
        heroAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COVER_FADE_MS.toLong()
            addUpdateListener {
                val palette = from.blend(target, it.animatedValue as Float)
                paintHero(palette)
                heroPalette = palette
            }
            start()
        }
    }

    private fun paintHero(palette: CoverPalette) {
        heroGradient.colors = intArrayOf(palette.heroStart, palette.heroEnd)
        activity.findViewById<ImageView>(R.id.heroNote).imageTintList = ColorStateList.valueOf(palette.onHero)
        for (id in intArrayOf(R.id.title, R.id.artist, R.id.source)) {
            activity.findViewById<TextView>(id).setTextColor(palette.onHero)
        }
        activity.findViewById<TextView>(R.id.nowLabel).apply {
            backgroundTintList = ColorStateList.valueOf(palette.pill)
            setTextColor(palette.accent)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(palette.accent))
        }
        activity.findViewById<ImageView>(R.id.art).backgroundTintList = ColorStateList.valueOf(palette.pill)
        activity.findViewById<MaterialButton>(R.id.btnApply).apply {
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            setTextColor(palette.onAccent)
            iconTint = ColorStateList.valueOf(palette.onAccent)
        }
        activity.findViewById<MaterialButton>(R.id.btnReset).apply {
            backgroundTintList = ColorStateList.valueOf(palette.tonal)
            setTextColor(palette.onTonal)
        }
    }

    /**
     * Shows the cover, fading from the previous one in step with the card colours.
     * Cover to cover is a crossfade; to or from the placeholder icon, the new image fades in.
     */
    private fun showCover(view: ImageView, cover: Bitmap?) {
        val old = (view.drawable as? TransitionDrawable)?.getDrawable(1) ?: view.drawable
        val oldBitmap = (old as? BitmapDrawable)?.bitmap
        if (cover != null && cover === oldBitmap) return
        if (cover != null && old is BitmapDrawable) {
            val fade = TransitionDrawable(arrayOf(old, cover.toDrawable(activity.resources)))
            fade.isCrossFadeEnabled = true
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.imageTintList = null
            view.setImageDrawable(fade)
            fade.startTransition(COVER_FADE_MS)
            return
        }
        if (cover == null && old != null && oldBitmap == null) return // already the placeholder
        view.showPreview(cover, R.drawable.ic_music_note_outline)
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(COVER_FADE_MS.toLong()).start()
    }

    private companion object {
        const val COVER_FADE_MS = 450
    }
}
