package dev.musicpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Enlarges the part of a cover that ends up on screen.
 *
 * - [Mode.SIMPLE]: Lanczos resampling on the CPU. Fast, no extra detail.
 * - [Mode.ADVANCED]: Real-ESRGAN (realesr-general-x4v3) on the phone, GPU when it can, CPU otherwise.
 *   Slower and uses more battery, so results are cached on disk by image content.
 */
object Upscaler {
    enum class Mode { SIMPLE, ADVANCED }

    private const val MODEL = "upscaler_x4.tflite"
    private const val SCALE = 4
    private const val TILE = 128 // model input size (fixed when the model was built)
    private const val PAD = 8 // overlap per tile side, hides seams
    private const val MIN_GAIN = 1.5f // below this much enlargement, Lanczos is as good
    private const val CACHE_FILES = 40
    private const val CACHE_QUALITY = 95
    private const val CPU_THREADS = 4
    private const val IDLE_CLOSE_MS = 60_000L

    /** All model work runs on this one thread (the GPU delegate must stay on the thread that made it). */
    private val thread = HandlerThread("upscaler").apply { start() }
    private val handler = Handler(thread.looper)
    private var interpreter: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private val closeIdle = Runnable { closeModel() }

    /**
     * Returns the [src] part of [art] scaled to [width]x[height].
     * ADVANCED with [cachedOnly] returns null when the sharp version isn't ready yet (so callers can show
     * the simple one first). [cancelled] is checked between tiles so a skipped song stops the work.
     */
    fun scale(
        context: Context,
        art: Bitmap,
        src: Rect,
        width: Int,
        height: Int,
        mode: Mode,
        cachedOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): Bitmap? {
        val gain = min(width.toFloat() / src.width(), height.toFloat() / src.height())
        if (mode == Mode.SIMPLE || gain < MIN_GAIN) return resampleLanczos(art, src, width, height)

        // Shrink the crop so the 4x output is about screen size: no wasted model work.
        val inWidth = max(1, min(src.width(), ceil(width / SCALE.toFloat()).toInt()))
        val inHeight = max(1, min(src.height(), ceil(height / SCALE.toFloat()).toInt()))
        val input = resampleLanczos(art, src, inWidth, inHeight)
        val file = cacheFile(context, input, width, height)
        if (file.exists()) {
            val cached = BitmapFactory.decodeFile(file.path)
            if (cached != null) {
                file.setLastModified(System.currentTimeMillis())
                return cached
            }
        }
        if (cachedOnly) return null

        val big = runOnModelThread { runModel(context, input, cancelled) } ?: return null
        val out = if (big.width == width && big.height == height) {
            big
        } else {
            resampleLanczos(big, Rect(0, 0, big.width, big.height), width, height)
        }
        runCatching { saveCache(file, out) }.onFailure { Log.w(TAG, "could not cache upscaled cover", it) }
        return out
    }

    // --- Model ------------------------------------------------------------------------------

    private fun <T> runOnModelThread(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        handler.removeCallbacks(closeIdle)
        handler.post {
            result = runCatching(block).onFailure { Log.w(TAG, "advanced upscaling failed", it) }.getOrNull()
            handler.postDelayed(closeIdle, IDLE_CLOSE_MS) // free GPU/RAM when idle
            done.countDown()
        }
        done.await()
        return result
    }

    /** Runs the x4 model over [input] in overlapping tiles. Null if cancelled. */
    private fun runModel(context: Context, input: Bitmap, cancelled: () -> Boolean): Bitmap? {
        val tflite = interpreter ?: openModel(context)
        val w = input.width
        val h = input.height
        val px = IntArray(w * h).also { input.getPixels(it, 0, w, 0, 0, w, h) }
        val outW = w * SCALE
        val outH = h * SCALE
        val out = IntArray(outW * outH)

        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val outTile = TILE * SCALE
        val outBuf = ByteBuffer.allocateDirect(outTile * outTile * 3 * 4).order(ByteOrder.nativeOrder())
        val step = TILE - 2 * PAD
        val started = System.nanoTime()

        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                if (cancelled()) return null
                // Fill the tile, starting PAD before the area we keep; edges repeat the border pixel.
                inBuf.rewind()
                val fb = inBuf.asFloatBuffer()
                for (y in 0 until TILE) {
                    val sy = (ty - PAD + y).coerceIn(0, h - 1)
                    for (x in 0 until TILE) {
                        val c = px[sy * w + (tx - PAD + x).coerceIn(0, w - 1)]
                        fb.put(((c shr 16) and 0xFF) / 255f)
                        fb.put(((c shr 8) and 0xFF) / 255f)
                        fb.put((c and 0xFF) / 255f)
                    }
                }
                outBuf.rewind()
                tflite.run(inBuf, outBuf)

                // Copy back only the middle part (without the overlap).
                val ob = outBuf.also { it.rewind() }.asFloatBuffer()
                val keepW = min(step, w - tx) * SCALE
                val keepH = min(step, h - ty) * SCALE
                for (y in 0 until keepH) {
                    val row = (PAD * SCALE + y) * outTile + PAD * SCALE
                    val dst = (ty * SCALE + y) * outW + tx * SCALE
                    for (x in 0 until keepW) {
                        val i = (row + x) * 3
                        out[dst + x] = argb(to8(ob.get(i)), to8(ob.get(i + 1)), to8(ob.get(i + 2)))
                    }
                }
                tx += step
            }
            ty += step
        }
        Log.i(TAG, "advanced upscale ${w}x$h -> ${outW}x$outH in ${(System.nanoTime() - started) / 1_000_000} ms")
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun to8(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun openModel(context: Context): Interpreter {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        val gpuInterpreter = runCatching {
            val compat = CompatibilityList()
            check(compat.isDelegateSupportedOnThisDevice) { "GPU not supported" }
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            Interpreter(model, Interpreter.Options().addDelegate(delegate)).also { gpu = delegate }
        }.onFailure { Log.i(TAG, "upscaler using CPU: ${it.message}") }.getOrNull()

        val tflite = gpuInterpreter
            ?: Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS).setUseXNNPACK(true))
        Log.i(TAG, "upscaler model loaded (${if (gpu != null) "GPU" else "CPU"})")
        interpreter = tflite
        return tflite
    }

    private fun closeModel() {
        interpreter?.close()
        interpreter = null
        gpu?.close()
        gpu = null
    }

    // --- Cache ------------------------------------------------------------------------------

    /** Named by the shrunk input's pixels plus the output size, so the same cover is never upscaled twice. */
    private fun cacheFile(context: Context, input: Bitmap, width: Int, height: Int): File {
        val px = IntArray(input.width * input.height)
        input.getPixels(px, 0, input.width, 0, 0, input.width, input.height)
        val bytes = ByteBuffer.allocate(px.size * 4).apply { asIntBuffer().put(px) }.array()
        val digest = MessageDigest.getInstance("SHA-1").apply {
            update(bytes)
            update("${width}x$height".toByteArray())
        }
        val name = digest.digest().joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "upscaled").apply { mkdirs() }, "$name.jpg")
    }

    private fun saveCache(file: File, bitmap: Bitmap) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, CACHE_QUALITY, it) }
        tmp.renameTo(file)
        // Keep the newest few.
        file.parentFile?.listFiles { f -> f.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(CACHE_FILES)
            ?.forEach { it.delete() }
    }

    // --- Lanczos ----------------------------------------------------------------------------

    /** Lanczos-3 resample of [src] inside [bitmap] to [dw]x[dh]. Works for enlarging and shrinking. */
    fun resampleLanczos(bitmap: Bitmap, src: Rect, dw: Int, dh: Int): Bitmap {
        val sw = src.width()
        val sh = src.height()
        val px = IntArray(sw * sh).also { bitmap.getPixels(it, 0, sw, src.left, src.top, sw, sh) }

        // Horizontal pass: sw x sh -> dw x sh, 3 float channels.
        val hx = weights(sw, dw)
        val mid = FloatArray(dw * sh * 3)
        for (y in 0 until sh) {
            val rowIn = y * sw
            val rowOut = y * dw * 3
            for (x in 0 until dw) {
                var r = 0f
                var g = 0f
                var b = 0f
                val first = hx.first[x]
                val base = x * hx.taps
                for (k in 0 until hx.taps) {
                    val wt = hx.w[base + k]
                    if (wt == 0f) continue
                    val c = px[rowIn + (first + k).coerceIn(0, sw - 1)]
                    r += wt * ((c shr 16) and 0xFF)
                    g += wt * ((c shr 8) and 0xFF)
                    b += wt * (c and 0xFF)
                }
                val o = rowOut + x * 3
                mid[o] = r
                mid[o + 1] = g
                mid[o + 2] = b
            }
        }

        // Vertical pass: dw x sh -> dw x dh.
        val vy = weights(sh, dh)
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val first = vy.first[y]
            val base = y * vy.taps
            for (x in 0 until dw) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0 until vy.taps) {
                    val wt = vy.w[base + k]
                    if (wt == 0f) continue
                    val o = ((first + k).coerceIn(0, sh - 1) * dw + x) * 3
                    r += wt * mid[o]
                    g += wt * mid[o + 1]
                    b += wt * mid[o + 2]
                }
                out[y * dw + x] = argb(clamp(r), clamp(g), clamp(b))
            }
        }
        return Bitmap.createBitmap(out, dw, dh, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    /** Opaque ARGB pixel from 0..255 channels. */
    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private class Weights(val taps: Int, val first: IntArray, val w: FloatArray)

    /** Per output pixel: index of the first source pixel and the normalised Lanczos weights. */
    private fun weights(srcLen: Int, dstLen: Int): Weights {
        val scale = dstLen.toDouble() / srcLen
        val stretch = if (scale < 1) 1 / scale else 1.0 // widen the filter when shrinking
        val support = 3.0 * stretch
        val taps = ceil(support * 2).toInt() + 1
        val first = IntArray(dstLen)
        val w = FloatArray(dstLen * taps)
        for (i in 0 until dstLen) {
            val center = (i + 0.5) / scale - 0.5
            val start = ceil(center - support).toInt()
            first[i] = start
            var sum = 0.0
            for (k in 0 until taps) {
                val v = lanczos3((start + k - center) / stretch)
                w[i * taps + k] = v.toFloat()
                sum += v
            }
            if (sum != 0.0) {
                for (k in 0 until taps) w[i * taps + k] = (w[i * taps + k] / sum).toFloat()
            }
        }
        return Weights(taps, first, w)
    }

    private fun lanczos3(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -3 || x >= 3) return 0.0
        val px = PI * x
        return 3 * sin(px) * sin(px / 3) / (px * px)
    }
}
