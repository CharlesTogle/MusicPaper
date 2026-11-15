package dev.musicpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.withClip
import java.util.Date
import java.util.Locale

/**
 * Draws a simple lock screen over a layout preview (clock, date and a media notification) so the previews
 * show where the cover ends up next to what the phone puts on top of it.
 */
object LockScreenMock {
    // Positions as shares of the preview's height (portrait).
    private const val CLOCK_BASELINE = 0.17f
    private const val CLOCK_SIZE = 0.11f
    private const val DATE_BASELINE = 0.22f
    private const val DATE_SIZE = 0.03f
    private const val CARD_TOP = 0.29f
    private const val CARD_BOTTOM = 0.44f

    // Shares of the preview's width.
    private const val CARD_MARGIN = 0.06f
    private const val CARD_PADDING = 0.05f

    private const val CARD_ALPHA = 150
    private const val SHADOW_ALPHA = 90

    /** Draws onto [preview] in place. [art] is shown as the notification's thumbnail. */
    fun draw(context: Context, preview: Bitmap, art: Bitmap) {
        val canvas = Canvas(preview)
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        val now = Date()
        val shadow = Color.argb(SHADOW_ALPHA, 0, 0, 0)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            setShadowLayer(h * 0.006f, 0f, h * 0.002f, shadow)
        }
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = h * CLOCK_SIZE
        val clock = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", now).toString()
        canvas.drawText(clock, w / 2, h * CLOCK_BASELINE, text)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = h * DATE_SIZE
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd") // e.g. "Sat, Nov 15"
        canvas.drawText(DateFormat.format(datePattern, now).toString(), w / 2, h * DATE_BASELINE, text)

        drawMediaCard(canvas, art, w, h)
    }

    /** A dark glass card with the cover thumbnail, two text lines and a play button. */
    private fun drawMediaCard(canvas: Canvas, art: Bitmap, w: Float, h: Float) {
        val card = RectF(w * CARD_MARGIN, h * CARD_TOP, w * (1 - CARD_MARGIN), h * CARD_BOTTOM)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(CARD_ALPHA, 16, 16, 20) }
        val radius = card.height() * 0.18f
        canvas.drawRoundRect(card, radius, radius, paint)

        val pad = w * CARD_PADDING
        val thumbSize = card.height() * 0.42f
        val thumb = RectF(card.left + pad, card.top + pad, card.left + pad + thumbSize, card.top + pad + thumbSize)
        val thumbPath = Path().apply { addRoundRect(thumb, thumbSize * 0.2f, thumbSize * 0.2f, Path.Direction.CW) }
        canvas.withClip(thumbPath) {
            drawBitmap(art, squareCrop(art), thumb, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        val buttonRadius = thumbSize * 0.32f
        val cx = card.right - pad - buttonRadius
        val cy = thumb.centerY()

        val lineHeight = thumbSize * 0.22f
        val lineLeft = thumb.right + pad * 0.8f
        paint.color = Color.argb(230, 255, 255, 255)
        val titleTop = thumb.top + thumbSize * 0.12f
        val title = RectF(lineLeft, titleTop, cx - buttonRadius - pad * 0.8f, titleTop + lineHeight)
        canvas.drawRoundRect(title, lineHeight / 2, lineHeight / 2, paint)
        paint.color = Color.argb(140, 255, 255, 255)
        val artistTop = title.bottom + lineHeight * 0.9f
        val artist = RectF(lineLeft, artistTop, lineLeft + title.width() * 0.6f, artistTop + lineHeight)
        canvas.drawRoundRect(artist, lineHeight / 2, lineHeight / 2, paint)

        // Progress bar along the bottom.
        val barY = card.bottom - pad * 1.1f
        val barHeight = card.height() * 0.035f
        paint.color = Color.argb(90, 255, 255, 255)
        val bar = RectF(card.left + pad, barY, card.right - pad, barY + barHeight)
        canvas.drawRoundRect(bar, barHeight, barHeight, paint)
        paint.color = Color.argb(230, 255, 255, 255)
        bar.right = bar.left + bar.width() * 0.4f
        canvas.drawRoundRect(bar, barHeight, barHeight, paint)

        // Play button.
        canvas.drawCircle(cx, cy, buttonRadius, paint)
        paint.color = Color.argb(220, 16, 16, 20)
        val t = buttonRadius * 0.45f
        val play = Path().apply {
            moveTo(cx - t * 0.6f, cy - t)
            lineTo(cx + t, cy)
            lineTo(cx - t * 0.6f, cy + t)
            close()
        }
        canvas.drawPath(play, paint)
    }

    private fun squareCrop(bitmap: Bitmap): Rect {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Rect(x, y, x + side, y + side)
    }
}
