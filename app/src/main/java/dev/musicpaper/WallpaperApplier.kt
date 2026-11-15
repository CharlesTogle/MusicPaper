package dev.musicpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Sets or clears the lock screen wallpaper on a background thread. */
object WallpaperApplier {
    private const val IMPORT_QUALITY = 92

    /** Wallpaper changes, image imports and file deletes, one at a time. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor()

    /** Slow advanced upscaling, kept apart so it never holds up wallpaper changes. */
    val sharpener: ExecutorService = Executors.newSingleThreadExecutor()

    private val applySeq = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    fun apply(context: Context, nowPlaying: NowPlaying, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val art = nowPlaying.art
        if (art == null) {
            done?.invoke(false)
            return
        }
        // Always keep it current, so a newly started live wallpaper has it.
        LiveCover.show(art, nowPlaying.title, nowPlaying.artist)
        if (liveOnLock(app)) {
            // The live wallpaper draws it and crossfades; no static image needed.
            Prefs.setLockShowingCover(app, true)
            done?.invoke(true)
            return
        }
        val style = Prefs.renderStyle(app, nowPlaying.title, nowPlaying.artist)
        val mode = Prefs.upscaleMode(app)
        val landscape = isLandscape(app)
        val seq = applySeq.incrementAndGet()
        worker.execute {
            var sharpLater = false
            val ok = runCatching {
                // Advanced: use the sharp version if it's cached, otherwise show the simple one now.
                val sharp = if (mode == Upscaler.Mode.ADVANCED) {
                    WallpaperRenderer.render(app, art, style, landscape, mode, cachedOnly = true)
                } else {
                    null
                }
                sharpLater = mode == Upscaler.Mode.ADVANCED && sharp == null
                val frame = sharp
                    ?: WallpaperRenderer.render(app, art, style, landscape, Upscaler.Mode.SIMPLE)
                    ?: error("simple render returned no image")
                setLock(app, frame)
            }.onFailure { Log.w(TAG, "Could not set lock screen wallpaper", it) }.isSuccess
            if (ok) Prefs.setLockShowingCover(app, true)
            main.post { done?.invoke(ok) }
            if (ok && sharpLater) sharpen(app, art, style, landscape, seq)
        }
    }

    /** Runs the advanced upscale off the main worker, then swaps it in if the song hasn't changed meanwhile. */
    private fun sharpen(app: Context, art: Bitmap, style: WallpaperRenderer.Style, landscape: Boolean, seq: Int) {
        val stale = { applySeq.get() != seq }
        sharpener.execute {
            if (stale()) return@execute
            val sharp = runCatching {
                WallpaperRenderer.render(app, art, style, landscape, Upscaler.Mode.ADVANCED, cancelled = stale)
            }.getOrNull() ?: return@execute
            worker.execute {
                if (stale()) {
                    sharp.recycle()
                    return@execute
                }
                runCatching { setLock(app, sharp) }.onFailure { Log.w(TAG, "Could not set sharpened cover", it) }
            }
        }
    }

    /**
     * What the lock screen shows when no music is playing: the user's default wallpaper if they picked one,
     * otherwise the same image as the home screen.
     */
    fun fallback(context: Context, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        applySeq.incrementAndGet() // drop any sharpening still running for the last song
        LiveCover.show(null) // live wallpaper fades to the default image (or black if none is set)
        if (liveOnLock(app)) {
            Prefs.setLockShowingCover(app, false)
            done?.invoke(true)
            return
        }
        worker.execute {
            val ok = runCatching {
                val file = Prefs.defaultWallpaperFile(app)
                val picture = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                if (picture != null) {
                    setLock(app, WallpaperRenderer.renderPlain(app, picture, isLandscape(app)))
                    picture.recycle()
                } else {
                    WallpaperManager.getInstance(app).clear(WallpaperManager.FLAG_LOCK)
                }
            }.onFailure { Log.w(TAG, "Could not restore lock screen wallpaper", it) }.isSuccess
            if (ok) Prefs.setLockShowingCover(app, false)
            main.post { done?.invoke(ok) }
        }
    }

    /**
     * Whether MusicPaper's live wallpaper is on the given screen. Null if the phone won't say
     * (the per-screen check needs Android 14+).
     */
    private fun liveOn(app: Context, which: Int): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            WallpaperManager.getInstance(app).getWallpaperInfo(which)?.packageName == app.packageName
        }.onFailure { Log.w(TAG, "Could not check wallpaper for screen $which", it) }.getOrNull()
    }

    /** True when the lock screen is MusicPaper's live wallpaper (so covers go to it, not a static image). */
    fun liveOnLock(app: Context): Boolean = LiveCover.isActive && (liveOn(app, WallpaperManager.FLAG_LOCK) ?: true)

    /**
     * If the live wallpaper is on the home screen and the user picked a home picture, put the picture back on
     * the home screen. When the live wallpaper was on both screens, Android then keeps it on the lock screen only.
     */
    fun splitHome(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val file = Prefs.homeWallpaperFile(app)
                if (!file.exists()) return@runCatching
                val liveOnHome = liveOn(app, WallpaperManager.FLAG_SYSTEM) ?: LiveCover.isActive
                if (!liveOnHome) return@runCatching
                val picture = BitmapFactory.decodeFile(file.path) ?: return@runCatching
                val wallpaperManager = WallpaperManager.getInstance(app)
                val frame = WallpaperRenderer.renderPlain(app, picture)
                wallpaperManager.setBitmap(frame, null, true, WallpaperManager.FLAG_SYSTEM)
                picture.recycle()
                Log.i(TAG, "home screen set to the home picture; live wallpaper stays on the lock screen")
            }.onFailure { Log.w(TAG, "Could not set home screen picture", it) }
        }
    }

    fun importDefault(context: Context, uri: Uri, done: (Boolean) -> Unit) {
        importImage(context, uri, Prefs.defaultWallpaperFile(context), done)
    }

    fun importHome(context: Context, uri: Uri, done: (Boolean) -> Unit) {
        importImage(context, uri, Prefs.homeWallpaperFile(context), done)
    }

    /** Copies a picked image into private storage, upright and at most screen-ish size. */
    private fun importImage(context: Context, uri: Uri, file: File, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val ok = runCatching {
                val (screenWidth, screenHeight) = WallpaperRenderer.screenSize(app)
                val target = max(screenWidth, screenHeight)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openImage(app, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2

                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = openImage(app, uri).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: error("not an image")
                val upright = rotateUpright(decoded, exifRotation(app, uri))

                val tmp = File(file.path + ".tmp")
                tmp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, IMPORT_QUALITY, it) }
                check(tmp.renameTo(file)) { "could not save" }
                upright.recycle()
            }.onFailure { Log.w(TAG, "Could not import image", it) }.isSuccess
            main.post { done(ok) }
        }
    }

    private fun openImage(app: Context, uri: Uri): InputStream =
        app.contentResolver.openInputStream(uri) ?: error("cannot open $uri")

    private fun exifRotation(app: Context, uri: Uri): Float = runCatching {
        openImage(app, uri).use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrDefault(0f)

    private fun rotateUpright(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun deleteImage(file: File) {
        worker.execute { file.delete() }
    }

    private fun isLandscape(app: Context): Boolean =
        app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun setLock(app: Context, bitmap: Bitmap) {
        val wallpaperManager = WallpaperManager.getInstance(app)
        check(wallpaperManager.isWallpaperSupported && wallpaperManager.isSetWallpaperAllowed) {
            "wallpaper changes are blocked on this device"
        }
        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
        bitmap.recycle()
    }
}
