package dev.musicpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import androidx.appcompat.R.attr as AppCompatAttr
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R.attr as MaterialAttr
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/**
 * The Classic / Square layout cards in "Style", each with a phone-shaped preview of the lock screen made from
 * the playing cover (or a sample when nothing plays), with a mock clock and media notification on top. "Fill the screen" only applies to Classic, so it hides.
 */
class LayoutPicker(private val activity: AppCompatActivity) {
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSeq = 0
    private var previewFor: NowPlaying? = null
    private val sampleCover by lazy { sampleCover() }

    /** Kept as one instance so [NowPlayingStore.remove] finds it again. */
    val onNowPlaying: (NowPlaying?) -> Unit = { renderPreviews(it) }

    private val cards
        get() = mapOf(
            WallpaperRenderer.Layout.CURRENT to activity.findViewById<MaterialCardView>(R.id.layoutCurrent),
            WallpaperRenderer.Layout.SQUARE to activity.findViewById<MaterialCardView>(R.id.layoutSquare),
        )

    fun bind() {
        select(Prefs.layout(activity))
        cards.forEach { (layout, card) ->
            card.setOnClickListener {
                Prefs.setLayout(activity, layout)
                select(layout)
            }
        }
        bindCaptionBox(R.id.cbShowTitle, Prefs.showTitle(activity)) { Prefs.setShowTitle(activity, it) }
        bindCaptionBox(R.id.cbShowArtist, Prefs.showArtist(activity)) { Prefs.setShowArtist(activity, it) }
        renderPreviews(null, force = true)
    }

    private fun bindCaptionBox(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        activity.findViewById<CompoundButton>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked ->
                save(checked)
                renderPreviews(previewFor, force = true)
            }
        }
    }

    /** The Classic preview depends on "Fill the screen", so redraw when it changes. */
    fun onFillChanged() = renderPreviews(previewFor, force = true)

    fun release() {
        previewWorker.shutdownNow()
    }

    private fun select(selected: WallpaperRenderer.Layout) {
        cards.forEach { (layout, card) ->
            card.isChecked = layout == selected
            card.strokeWidth = activity.resources.getDimensionPixelSize(
                if (card.isChecked) R.dimen.layout_card_stroke_checked else R.dimen.layout_card_stroke,
            )
            card.strokeColor = MaterialColors.getColor(
                card,
                if (card.isChecked) AppCompatAttr.colorPrimary else MaterialAttr.colorOutlineVariant,
            )
        }
        val fillApplies = selected == WallpaperRenderer.Layout.CURRENT
        activity.findViewById<View>(R.id.rowFill).setVisibleOrGone(fillApplies)
        activity.findViewById<View>(R.id.dividerFill).setVisibleOrGone(fillApplies)
        activity.findViewById<View>(R.id.squareOptions).setVisibleOrGone(!fillApplies)
    }

    /** Renders both previews off the main thread. Same cover: no work. */
    private fun renderPreviews(nowPlaying: NowPlaying?, force: Boolean = false) {
        if (!force && nowPlaying?.key == previewFor?.key) return
        previewFor = nowPlaying
        val cover = nowPlaying?.art
        val seq = ++previewSeq
        val fill = Prefs.isFillEnabled(activity)
        val caption = if (cover == null) {
            Prefs.caption(activity, SAMPLE_TITLE, SAMPLE_ARTIST)
        } else {
            Prefs.caption(activity, nowPlaying.title, nowPlaying.artist)
        }
        val (screenWidth, screenHeight) = WallpaperRenderer.screenSize(activity)
        val aspect = screenWidth.toFloat() / screenHeight
        val views = mapOf(
            WallpaperRenderer.Layout.CURRENT to activity.findViewById<ImageView>(R.id.layoutCurrentPreview),
            WallpaperRenderer.Layout.SQUARE to activity.findViewById<ImageView>(R.id.layoutSquarePreview),
        )
        // The view height is fixed in the layout; match the width to the screen's shape.
        views.values.forEach { view ->
            view.layoutParams = view.layoutParams.apply { width = (height * aspect).toInt() }
        }
        val app = activity.applicationContext
        val height = PREVIEW_HEIGHT_PX
        val width = (height * aspect).toInt()
        runCatching {
            previewWorker.execute {
                val art = cover ?: sampleCover
                val previews = runCatching {
                    views.mapValues { (layout, _) ->
                        WallpaperRenderer.render(app, art, layout, fill, width, height, Upscaler.Mode.SIMPLE, caption = caption)
                            ?.also { LockScreenMock.draw(app, it, art) }
                    }
                }.getOrNull() ?: return@execute
                mainHandler.post {
                    if (seq != previewSeq || activity.isDestroyed) return@post
                    views.forEach { (layout, view) -> view.showPreview(previews[layout], R.drawable.ic_music_note_outline) }
                }
            }
        } // rejected after release(): nothing to show anyway
    }

    /** A stand-in cover for the previews when nothing is playing: a soft brand-coloured gradient with a disc. */
    private fun sampleCover(): Bitmap {
        val size = SAMPLE_SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val brand = CoverPalette.brand(activity)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size, size, brand.accent, brand.tonal, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(size / 2, size / 2, size * 0.28f, paint)
        paint.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(size / 2, size / 2, size * 0.06f, paint)
        return bitmap
    }

    private companion object {
        const val PREVIEW_HEIGHT_PX = 400
        const val SAMPLE_SIZE = 256
        const val SAMPLE_TITLE = "Song title"
        const val SAMPLE_ARTIST = "Artist"
    }
}
