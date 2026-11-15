package dev.musicpaper

import android.graphics.Bitmap

/**
 * Hand-off between the music listener and the live wallpaper (same process).
 * Main thread only, except [isActive], which any thread may read.
 * `art == null` means "nothing playing": the wallpaper shows the default image.
 */
object LiveCover {
    var art: Bitmap? = null
        private set
    var title: String? = null
        private set
    var artist: String? = null
        private set

    /** Live wallpaper engines currently set on the phone (previews in the picker don't count). */
    @Volatile
    private var activeEngines = 0

    val isActive: Boolean
        get() = activeEngines > 0

    private val listeners = mutableSetOf<() -> Unit>()

    fun engineStarted() {
        activeEngines++
    }

    fun engineStopped() {
        activeEngines--
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun show(newArt: Bitmap?, newTitle: String? = null, newArtist: String? = null) {
        art = newArt
        title = newTitle
        artist = newArtist
        listeners.toList().forEach { it() }
    }
}
