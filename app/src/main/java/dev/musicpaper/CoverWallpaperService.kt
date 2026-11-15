package dev.musicpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

/**
 * Live wallpaper version: draws the cover itself, so it can crossfade from one song to the next.
 * The music listener feeds it through [LiveCover]; it only animates during a change.
 */
class CoverWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CoverEngine()

    private inner class CoverEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var width = 0
        private var height = 0
        private var visible = false
        private var counted = false

        private var current: Bitmap? = null // fully drawn frame for the current song
        private var previous: Bitmap? = null // frame we're fading away from
        private var fadeStart = 0L

        @Volatile
        private var renderSeq = 0

        private val frame = Runnable { drawFrame() }
        private val onCover: () -> Unit = { rerender(fade = true) }
        private val onPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (Prefs.isStyleKey(key)) rerender(fade = true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            if (!isPreview) {
                LiveCover.engineStarted()
                counted = true
            }
            LiveCover.addListener(onCover)
            Prefs.sharedPreferences(this@CoverWallpaperService).registerOnSharedPreferenceChangeListener(onPrefs)
            Log.i(TAG, "live wallpaper started (preview=$isPreview)")
            // Give Android a moment to finish applying, then move the home screen back to the user's picture.
            if (!isPreview) {
                main.postDelayed({ WallpaperApplier.splitHome(this@CoverWallpaperService) }, SPLIT_HOME_DELAY_MS)
            }
        }

        override fun onDestroy() {
            if (counted) LiveCover.engineStopped()
            renderSeq++ // stop any sharpening still running for this engine
            LiveCover.removeListener(onCover)
            Prefs.sharedPreferences(this@CoverWallpaperService).unregisterOnSharedPreferenceChangeListener(onPrefs)
            main.removeCallbacks(frame)
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            rerender(fade = false) // size or rotation changed: redraw straight away
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) drawFrame() else main.removeCallbacks(frame)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            main.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        /** Builds the new frame off the main thread, then fades to it. */
        private fun rerender(fade: Boolean) {
            if (width == 0 || height == 0) return
            val w = width
            val h = height
            val art = LiveCover.art
            val context = this@CoverWallpaperService
            val style = Prefs.renderStyle(context, LiveCover.title, LiveCover.artist)
            val mode = Prefs.upscaleMode(context)
            val seq = ++renderSeq
            WallpaperApplier.worker.execute {
                var sharpLater = false
                val next = runCatching {
                    if (art == null) return@runCatching defaultFrame(w, h)
                    // Advanced: use the cached sharp frame if there is one, else show the simple one first.
                    val sharp = if (mode == Upscaler.Mode.ADVANCED) {
                        WallpaperRenderer.render(context, art, style, w, h, mode, cachedOnly = true)
                    } else {
                        null
                    }
                    sharpLater = mode == Upscaler.Mode.ADVANCED && sharp == null
                    sharp ?: WallpaperRenderer.render(context, art, style, w, h, Upscaler.Mode.SIMPLE)
                }.onFailure { Log.w(TAG, "live render failed", it) }.getOrNull()
                show(seq, next, fade)
                if (art != null && sharpLater) {
                    WallpaperApplier.sharpener.execute {
                        val stale = { seq != renderSeq }
                        if (stale()) return@execute
                        val advanced = Upscaler.Mode.ADVANCED
                        val sharp = runCatching {
                            WallpaperRenderer.render(context, art, style, w, h, advanced, cancelled = stale)
                        }.getOrNull()
                        show(seq, sharp, fade = true) // soft-to-sharp crossfade
                    }
                }
            }
        }

        /** Swaps in a finished frame on the main thread unless a newer change came in. */
        private fun show(seq: Int, next: Bitmap?, fade: Boolean) {
            main.post {
                if (seq != renderSeq || next == null) return@post
                previous = if (fade) current else null
                current = next
                fadeStart = SystemClock.uptimeMillis()
                drawFrame()
            }
        }

        private fun defaultFrame(width: Int, height: Int): Bitmap? {
            val file = Prefs.defaultWallpaperFile(this@CoverWallpaperService)
            if (!file.exists()) return null
            val picture = BitmapFactory.decodeFile(file.path) ?: return null
            return try {
                WallpaperRenderer.renderPlain(picture, width, height)
            } finally {
                picture.recycle()
            }
        }

        private fun drawFrame() {
            main.removeCallbacks(frame)
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            var t = 1f
            try {
                val cur = current
                val prev = previous
                canvas.drawColor(Color.BLACK)
                if (prev != null) {
                    t = ((SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                    paint.alpha = 255
                    canvas.drawBitmap(prev, 0f, 0f, paint)
                }
                if (cur != null) {
                    paint.alpha = (255 * easeInOut(t)).toInt()
                    canvas.drawBitmap(cur, 0f, 0f, paint)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (previous != null) {
                if (t < 1f && visible) main.postDelayed(frame, FRAME_MS) else previous = null
            }
        }

        private fun easeInOut(t: Float): Float = t * t * (3 - 2 * t)
    }

    private companion object {
        const val FADE_MS = 700L
        const val FRAME_MS = 16L // about 60 fps
        const val SPLIT_HOME_DELAY_MS = 1_500L
    }
}
