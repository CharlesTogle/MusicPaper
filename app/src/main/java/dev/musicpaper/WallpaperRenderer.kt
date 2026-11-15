package dev.musicpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withClip
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns a (usually square) cover into a portrait, screen-sized lock screen image. */
object WallpaperRenderer {
    /** How the cover is laid out on the screen. */
    enum class Layout {
        /** Solid colour above the cover, or the cover cropped to fill (see the fill setting). */
        CURRENT,

        /** The whole cover as a centred square over a blurred copy of itself. */
        SQUARE,
    }

    /** Song text drawn over the bottom-left of a Square cover; a null line is left out. */
    data class Caption(val title: String?, val artist: String?)

    /** The user's "Style" choices: [fill] applies to [Layout.CURRENT], [caption] to [Layout.SQUARE]. */
    data class Style(val layout: Layout, val fill: Boolean, val caption: Caption?)

    /** Share of the screen given to the solid colour; the lock screen's media player box covers the seam. */
    private const val SPLIT = 0.40f
    // Seam: the cover's own edge colours (column by column) bleed into the solid colour above and fade out over
    // the cover below, so there is no visible line or flat smear where they meet.
    private const val FADE_FRACTION = 0.12f // below the seam, share of the screen's short side
    private const val BLEED_FRACTION = 0.6f // above the seam, share of the colour area
    private const val EDGE_LINE_SAMPLES = 48
    private const val EDGE_LINE_DEPTH = 0.04f
    private const val EDGE_LINE_BLUR = 2
    private const val EASE_STOPS = 9
    private const val EDGE_STRIP_DIVISOR = 12
    private const val EDGE_SAMPLES = 64

    // Square layout: cover size as a share of the screen's short side, and where its centre sits in portrait.
    private const val SQUARE_PORTRAIT = 0.82f
    private const val SQUARE_LANDSCAPE = 0.72f
    private const val SQUARE_CENTER_Y = 0.70f
    private const val SQUARE_CORNER = 0.04f
    private const val SHADOW_RADIUS = 0.06f
    private const val SHADOW_OFFSET = 0.02f
    private const val SHADOW_ALPHA = 140
    private const val SCRIM_ALPHA = 70

    // Square caption, drawn over the cover's bottom-left; sizes as shares of the cover's side.
    private const val CAPTION_PADDING = 0.06f
    private const val CAPTION_FADE = 0.42f
    private const val CAPTION_SCRIM_ALPHA = 170
    private const val TITLE_SIZE = 0.075f
    private const val ARTIST_SIZE = 0.056f
    private const val LINE_GAP = 0.012f
    private const val ARTIST_ALPHA = 210

    // Blur: shrink to this width, box-blur a few times, then let bilinear stretching smooth the rest.
    private const val BLUR_WIDTH = 64
    private const val BLUR_RADIUS = 4
    private const val BLUR_PASSES = 3

    fun render(
        context: Context,
        art: Bitmap,
        style: Style,
        landscape: Boolean,
        mode: Upscaler.Mode,
        cachedOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): Bitmap? {
        val (width, height) = screenSize(context, landscape)
        return render(context, art, style, width, height, mode, cachedOnly, cancelled)
    }

    /**
     * Renders at an exact size (the live wallpaper passes its surface size). Landscape when width > height.
     * Null only when [cachedOnly] is set and the advanced upscale isn't ready (see [Upscaler.scale]).
     */
    fun render(
        context: Context,
        art: Bitmap,
        style: Style,
        width: Int,
        height: Int,
        mode: Upscaler.Mode,
        cachedOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): Bitmap? {
        val upscale = { src: Rect, w: Int, h: Int ->
            Upscaler.scale(context, art, src, w, h, mode, cachedOnly, cancelled)
        }
        return when {
            style.layout == Layout.SQUARE -> renderSquare(art, width, height, style.caption, upscale)
            style.fill -> renderFill(art, width, height, upscale)
            else -> renderSplit(art, width, height, upscale)
        }
    }

    /** Cover cropped to fill the whole screen. */
    private fun renderFill(art: Bitmap, width: Int, height: Int, upscale: (Rect, Int, Int) -> Bitmap?): Bitmap? {
        val scaled = upscale(centerCrop(art, width, height), width, height) ?: return null
        val out = createBitmap(width, height)
        Canvas(out).drawBitmap(scaled, null, Rect(0, 0, width, height), filterPaint())
        scaled.recycle()
        return out
    }

    /**
     * Solid colour taken from the cover's edge, then the cover (bottom in portrait, right in landscape), joined by
     * the cover's own edge colours melting into the solid above the seam and fading out over the cover below.
     */
    private fun renderSplit(art: Bitmap, width: Int, height: Int, upscale: (Rect, Int, Int) -> Bitmap?): Bitmap? {
        val landscape = width > height
        val dst = if (landscape) {
            RectF(width * SPLIT, 0f, width.toFloat(), height.toFloat())
        } else {
            RectF(0f, height * SPLIT, width.toFloat(), height.toFloat())
        }
        val src = centerCrop(art, dst.width().toInt(), dst.height().toInt())
        val scaled = upscale(src, dst.width().roundToInt(), dst.height().roundToInt()) ?: return null
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val edge = edgeColor(art, src, fromLeft = landscape)
        canvas.drawColor(edge)
        canvas.drawBitmap(scaled, null, dst, filterPaint())
        scaled.recycle()

        val line = edgeLine(art, src, fromLeft = landscape)
        val seam = if (landscape) dst.left else dst.top
        val fade = min(width, height) * FADE_FRACTION
        drawEdgeBand(canvas, line, width, height, landscape, seam - seam * BLEED_FRACTION, seam, opaqueAtEnd = true)
        drawEdgeBand(canvas, line, width, height, landscape, seam, seam + fade, opaqueAtEnd = false)
        line.recycle()
        return out
    }

    /**
     * The whole cover as a rounded square with a soft shadow, over a blurred, darkened copy filling the screen.
     * In portrait it sits in the lower part of the screen, below the clock and the media notification.
     */
    private fun renderSquare(
        art: Bitmap,
        width: Int,
        height: Int,
        caption: Caption?,
        upscale: (Rect, Int, Int) -> Bitmap?,
    ): Bitmap? {
        val landscape = width > height
        val size = (min(width, height) * if (landscape) SQUARE_LANDSCAPE else SQUARE_PORTRAIT).roundToInt()
        val cover = upscale(centerCrop(art, size, size), size, size) ?: return null

        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val blurred = blurredBackdrop(art, width, height)
        canvas.drawBitmap(blurred, null, Rect(0, 0, width, height), filterPaint())
        blurred.recycle()
        canvas.drawColor(Color.argb(SCRIM_ALPHA, 0, 0, 0)) // keeps the clock readable on bright covers

        val left = (width - size) / 2f
        val top = if (landscape) (height - size) / 2f else height * SQUARE_CENTER_Y - size / 2f
        val dst = RectF(left, top, left + size, top + size)
        val corner = size * SQUARE_CORNER
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(size * SHADOW_RADIUS, 0f, size * SHADOW_OFFSET, Color.argb(SHADOW_ALPHA, 0, 0, 0))
        }
        canvas.drawRoundRect(dst, corner, corner, shadow)
        val coverPaint = filterPaint().apply {
            shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left, top) })
            }
        }
        canvas.drawRoundRect(dst, corner, corner, coverPaint)
        cover.recycle()

        if (caption != null) drawCaption(canvas, dst, corner, caption)
        return out
    }

    /** [art] cropped to a width:height box and strongly blurred, returned small; the caller stretches it. */
    private fun blurredBackdrop(art: Bitmap, width: Int, height: Int): Bitmap {
        val src = centerCrop(art, width, height)
        val w = BLUR_WIDTH
        val h = max(1, (w * height.toFloat() / width).roundToInt())
        val small = createBitmap(w, h)
        Canvas(small).drawBitmap(art, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(w * h)
        val scratch = IntArray(pixels.size)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(BLUR_PASSES) {
            boxBlur(pixels, scratch, w, h, horizontal = true)
            boxBlur(scratch, pixels, w, h, horizontal = false)
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return small
    }

    /**
     * Paints [line] (the cover's edge colours) stretched across the screen between [from] and [to] along the
     * split axis, with a smoothstep alpha ramp: no hard start or end, so no Mach bands.
     */
    private fun drawEdgeBand(
        canvas: Canvas,
        line: Bitmap,
        width: Int,
        height: Int,
        landscape: Boolean,
        from: Float,
        to: Float,
        opaqueAtEnd: Boolean,
    ) {
        if (to - from < 1f) return
        val colours = BitmapShader(line, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    if (landscape) {
                        setScale(1f, height.toFloat() / line.height)
                    } else {
                        setScale(width.toFloat() / line.width, 1f)
                    }
                },
            )
        }
        val positions = FloatArray(EASE_STOPS) { it / (EASE_STOPS - 1f) }
        val alphas = IntArray(EASE_STOPS) { i ->
            val t = positions[i]
            val eased = t * t * (3 - 2 * t)
            Color.argb(((if (opaqueAtEnd) eased else 1 - eased) * 255).roundToInt(), 0, 0, 0)
        }
        val mask = if (landscape) {
            LinearGradient(from, 0f, to, 0f, alphas, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, from, 0f, to, alphas, positions, Shader.TileMode.CLAMP)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            shader = ComposeShader(colours, mask, PorterDuff.Mode.DST_IN)
        }
        if (landscape) {
            canvas.drawRect(from, 0f, to, height.toFloat(), paint)
        } else {
            canvas.drawRect(0f, from, width.toFloat(), to, paint)
        }
    }

    /**
     * The cover's top rows (left columns in landscape) averaged into [EDGE_LINE_SAMPLES] softened colours,
     * as an N×1 (or 1×N) bitmap ready to be stretched along the seam.
     */
    private fun edgeLine(art: Bitmap, src: Rect, fromLeft: Boolean): Bitmap {
        val along = if (fromLeft) src.height() else src.width()
        val depth = max(1, ((if (fromLeft) src.width() else src.height()) * EDGE_LINE_DEPTH).roundToInt())
        val strip = if (fromLeft) {
            Rect(src.left, src.top, src.left + depth, src.bottom)
        } else {
            Rect(src.left, src.top, src.right, src.top + depth)
        }
        val pixels = IntArray(strip.width() * strip.height())
        art.getPixels(pixels, 0, strip.width(), strip.left, strip.top, strip.width(), strip.height())
        val n = min(EDGE_LINE_SAMPLES, along)
        val averaged = IntArray(n) { cell ->
            val start = cell * along / n
            val end = max(start + 1, (cell + 1) * along / n)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            for (a in start until end) {
                for (d in 0 until depth) {
                    val c = if (fromLeft) pixels[a * strip.width() + d] else pixels[d * strip.width() + a]
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                    count++
                }
            }
            Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        }
        val (w, h) = if (fromLeft) 1 to n else n to 1
        val softened = IntArray(n)
        boxBlur(averaged, softened, w, h, horizontal = !fromLeft, radius = EDGE_LINE_BLUR)
        return Bitmap.createBitmap(softened, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One box-blur pass along rows or columns, clamping at the edges. */
    private fun boxBlur(
        from: IntArray,
        to: IntArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
        radius: Int = BLUR_RADIUS,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val taps = radius * 2 + 1
        fun index(line: Int, i: Int) = if (horizontal) line * width + i else i * width + line
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val c = from[index(line, (i + k).coerceIn(0, length - 1))]
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                }
                to[index(line, i)] = Color.rgb(r / taps, g / taps, b / taps)
            }
        }
    }

    /**
     * Title and/or artist over the bottom-left of the cover, on a dark fade so they read on any artwork.
     * Long lines are cut with an ellipsis.
     */
    private fun drawCaption(canvas: Canvas, cover: RectF, corner: Float, caption: Caption) {
        val size = cover.width()
        val titlePaint = captionPaint(size * TITLE_SIZE, Typeface.BOLD, Color.WHITE)
        val artistPaint = captionPaint(size * ARTIST_SIZE, Typeface.NORMAL, Color.argb(ARTIST_ALPHA, 255, 255, 255))
        val lines = listOfNotNull(caption.title?.let { it to titlePaint }, caption.artist?.let { it to artistPaint })
        if (lines.isEmpty()) return

        val fadeTop = cover.bottom - size * CAPTION_FADE
        val scrimColours = intArrayOf(
            Color.TRANSPARENT,
            Color.argb(CAPTION_SCRIM_ALPHA / 2, 0, 0, 0),
            Color.argb(CAPTION_SCRIM_ALPHA, 0, 0, 0),
        )
        val fade = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                0f,
                fadeTop,
                0f,
                cover.bottom,
                scrimColours,
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.withClip(Path().apply { addRoundRect(cover, corner, corner, Path.Direction.CW) }) {
            drawRect(cover.left, fadeTop, cover.right, cover.bottom, fade)
        }

        // Lay the lines out upwards from the bottom padding, so the last line sits on the bottom edge.
        val pad = size * CAPTION_PADDING
        val maxWidth = cover.width() - pad * 2
        var baseline = cover.bottom - pad
        lines.asReversed().forEachIndexed { i, (text, paint) ->
            if (i > 0) baseline -= size * LINE_GAP + paint.descent()
            val fitted = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, cover.left + pad, baseline - paint.descent(), paint)
            baseline -= paint.textSize
        }
    }

    private fun captionPaint(size: Float, style: Int, colour: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
        color = colour
        setShadowLayer(size * 0.15f, 0f, size * 0.04f, Color.argb(SHADOW_ALPHA, 0, 0, 0))
    }

    private fun filterPaint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Average colour of the cover's top strip (or left strip in landscape), used to fill the rest of the screen. */
    private fun edgeColor(art: Bitmap, src: Rect, fromLeft: Boolean): Int {
        val strip = if (fromLeft) {
            Rect(src.left, src.top, src.left + max(1, src.width() / EDGE_STRIP_DIVISOR), src.bottom)
        } else {
            Rect(src.left, src.top, src.right, src.top + max(1, src.height() / EDGE_STRIP_DIVISOR))
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val step = max(1, max(strip.width(), strip.height()) / EDGE_SAMPLES)
        for (y in strip.top until strip.bottom step step) {
            for (x in strip.left until strip.right step step) {
                val c = art[x, y]
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
        }
        if (n == 0L) return Color.BLACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** A plain picture cropped to fill the screen (used for the default wallpaper). */
    fun renderPlain(context: Context, src: Bitmap, landscape: Boolean = false): Bitmap {
        val (width, height) = screenSize(context, landscape)
        return renderPlain(src, width, height)
    }

    fun renderPlain(src: Bitmap, width: Int, height: Int): Bitmap {
        val out = createBitmap(width, height)
        val dst = Rect(0, 0, width, height)
        Canvas(out).drawBitmap(src, centerCrop(src, width, height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Screen size in pixels for the given orientation. */
    fun screenSize(context: Context, landscape: Boolean = false): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val (a, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        return if (landscape) max(a, b) to min(a, b) else min(a, b) to max(a, b)
    }

    /** Source rect of [bitmap] that covers a width:height box. */
    private fun centerCrop(bitmap: Bitmap, width: Int, height: Int): Rect {
        val target = width.toFloat() / height
        val ratio = bitmap.width.toFloat() / bitmap.height
        return if (ratio > target) {
            val cropWidth = (bitmap.height * target).toInt()
            val x = (bitmap.width - cropWidth) / 2
            Rect(x, 0, x + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / target).toInt()
            val y = (bitmap.height - cropHeight) / 2
            Rect(0, y, bitmap.width, y + cropHeight)
        }
    }
}
