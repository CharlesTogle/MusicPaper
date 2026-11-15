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
import androidx.annotation.IdRes
import androidx.appcompat.R.attr as AppCompatAttr
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import com.google.android.material.R.attr as MaterialAttr
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The Classic / Square layout cards in "Style". Each has a phone-shaped preview of the lock screen made from
 * the playing cover (or a sample when nothing plays), with a mock clock and media notification on top.
 * "Fill the screen" only applies to Classic, so it hides while Square is picked.
 */
class LayoutPicker(private val activity: AppCompatActivity) {
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSeq = 0
    private var previewFor: NowPlaying? = null
    private val sampleCover by lazy { sampleCover() }

    /** Kept as one instance so [NowPlayingStore.remove] finds it again. */
    val onNowPlaying: (NowPlaying?) -> Unit = { renderPreviews(it) }

    private val cards: Map<WallpaperRenderer.Layout, MaterialCardView>
        get() = mapOf(
            WallpaperRenderer.Layout.CURRENT to activity.findViewById(R.id.layoutCurrent),
            WallpaperRenderer.Layout.SQUARE to activity.findViewById(R.id.layoutSquare),
        )

    fun bind() {
        select(Prefs.layout(activity))
        cards.forEach { (layout, card) ->
            card.setOnClickListener {
                Prefs.setLayout(activity, layout)
                select(layout)
            }
        }
        bindCaptionBox(R.id.cbShowTitle, Prefs.isTitleShown(activity)) { Prefs.setTitleShown(activity, it) }
        bindCaptionBox(R.id.cbShowArtist, Prefs.isArtistShown(activity)) { Prefs.setArtistShown(activity, it) }
        renderPreviews(null, force = true)
    }

    /** The Classic preview depends on "Fill the screen", so redraw when it changes. */
    fun onFillChanged() {
        renderPreviews(previewFor, force = true)
    }

    fun release() {
        previewWorker.shutdownNow()
    }

    private fun bindCaptionBox(@IdRes id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        activity.findViewById<CompoundButton>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked ->
                save(checked)
                renderPreviews(previewFor, force = true)
            }
        }
    }

    private fun select(selected: WallpaperRenderer.Layout) {
        cards.forEach { (layout, card) ->
            card.isChecked = layout == selected
            val (stroke, colour) = if (card.isChecked) {
                R.dimen.layout_card_stroke_checked to AppCompatAttr.colorPrimary
            } else {
                R.dimen.layout_card_stroke to MaterialAttr.colorOutlineVariant
            }
            card.strokeWidth = activity.resources.getDimensionPixelSize(stroke)
            card.strokeColor = MaterialColors.getColor(card, colour)
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
        val style = if (cover == null) {
            Prefs.renderStyle(
                activity,
                activity.getString(R.string.sample_title),
                activity.getString(R.string.sample_artist),
            )
        } else {
            Prefs.renderStyle(activity, nowPlaying.title, nowPlaying.artist)
        }
        val (screenWidth, screenHeight) = WallpaperRenderer.screenSize(activity)
        val aspect = screenWidth.toFloat() / screenHeight
        val views = mapOf<WallpaperRenderer.Layout, ImageView>(
            WallpaperRenderer.Layout.CURRENT to activity.findViewById(R.id.layoutCurrentPreview),
            WallpaperRenderer.Layout.SQUARE to activity.findViewById(R.id.layoutSquarePreview),
        )
        // The view height is fixed in the layout; match the width to the screen's shape.
        views.values.forEach { view ->
            view.layoutParams = view.layoutParams.apply { width = (height * aspect).toInt() }
        }
        val app = activity.applicationContext
        val height = PREVIEW_HEIGHT_PX
        val width = (height * aspect).toInt()
        try {
            previewWorker.execute {
                val art = cover ?: sampleCover
                val previews = runCatching {
                    views.mapValues { (layout, _) ->
                        val layoutStyle = style.copy(layout = layout)
                        WallpaperRenderer.render(app, art, layoutStyle, width, height, Upscaler.Mode.SIMPLE)
                            ?.also { LockScreenMock.draw(app, it, art) }
                    }
                }.getOrNull() ?: return@execute
                mainHandler.post {
                    if (seq != previewSeq || activity.isDestroyed) return@post
                    views.forEach { (layout, view) ->
                        view.showPreview(previews[layout], R.drawable.ic_music_note_outline)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            // Only after release(), when there's nothing left to show it on.
        }
    }

    /** A stand-in cover for the previews when nothing is playing: a soft brand-coloured gradient with a disc. */
    private fun sampleCover(): Bitmap {
        val size = SAMPLE_SIZE.toFloat()
        val bitmap = createBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
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
    }
}
