package dev.musicpaper

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
) {
    /** Identifies the song, whatever cover came with it. */
    val songKey: String
        get() = "$packageName|$title|$artist"

    /** Changes when the song changes, or when cover art shows up after the title (Spotify does this). */
    val key: String
        get() = "$songKey|${art?.width}x${art?.height}"
}

/** Shares the current track between the listener service and the screen. Main thread only. */
object NowPlayingStore {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(NowPlaying?) -> Unit>()

    var current: NowPlaying? = null
        private set

    fun publish(nowPlaying: NowPlaying?) {
        main.post {
            current = nowPlaying
            listeners.forEach { it(nowPlaying) }
        }
    }

    fun observe(listener: (NowPlaying?) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (NowPlaying?) -> Unit) {
        listeners -= listener
    }
}
