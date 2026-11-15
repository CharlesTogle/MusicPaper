package dev.musicpaper

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** User settings. The selected-apps set is the "read notifications from selected apps only" filter. */
object Prefs {
    private const val FILE = "musicpaper"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_SEEN = "seen_packages"
    private const val KEY_FILL = "fill_screen"
    private const val KEY_REVERT = "revert_on_close"
    private const val KEY_LOCK_IS_COVER = "lock_is_cover"
    private const val KEY_UPSCALE = "upscale"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_SHOW_TITLE = "show_title"
    private const val KEY_SHOW_ARTIST = "show_artist"

    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE = "com.google.android.youtube"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    val PRESETS: List<String> = listOf(SPOTIFY, YOUTUBE, YT_MUSIC)

    fun sharedPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selectedPackages(context: Context): Set<String> =
        sharedPreferences(context).getStringSet(KEY_SELECTED, null)?.toSet() ?: PRESETS.toSet()

    fun setPackageSelected(context: Context, packageName: String, selected: Boolean) {
        val next = selectedPackages(context).toMutableSet()
        if (selected) next += packageName else next -= packageName
        sharedPreferences(context).edit().putStringSet(KEY_SELECTED, next).apply()
    }

    /** Package names that have published a media session (names only, used to list local players). */
    fun seenPackages(context: Context): Set<String> =
        sharedPreferences(context).getStringSet(KEY_SEEN, null).orEmpty().toSet()

    fun addSeenPackages(context: Context, packageNames: Collection<String>) {
        val current = seenPackages(context)
        if (current.containsAll(packageNames)) return
        sharedPreferences(context).edit().putStringSet(KEY_SEEN, current + packageNames).apply()
    }

    fun isFillEnabled(context: Context): Boolean = sharedPreferences(context).getBoolean(KEY_FILL, false)

    fun setFillEnabled(context: Context, enabled: Boolean) {
        sharedPreferences(context).edit().putBoolean(KEY_FILL, enabled).apply()
    }

    /** Stored lowercase ("current", "square"). */
    fun layout(context: Context): WallpaperRenderer.Layout {
        val stored = sharedPreferences(context).getString(KEY_LAYOUT, null)
        return WallpaperRenderer.Layout.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            ?: WallpaperRenderer.Layout.CURRENT
    }

    fun setLayout(context: Context, layout: WallpaperRenderer.Layout) {
        sharedPreferences(context).edit().putString(KEY_LAYOUT, layout.name.lowercase()).apply()
    }

    /** Square layout only: show the song title on the cover. */
    fun showTitle(context: Context): Boolean = sharedPreferences(context).getBoolean(KEY_SHOW_TITLE, false)

    fun setShowTitle(context: Context, show: Boolean) {
        sharedPreferences(context).edit().putBoolean(KEY_SHOW_TITLE, show).apply()
    }

    /** Square layout only: show the artist on the cover. */
    fun showArtist(context: Context): Boolean = sharedPreferences(context).getBoolean(KEY_SHOW_ARTIST, false)

    fun setShowArtist(context: Context, show: Boolean) {
        sharedPreferences(context).edit().putBoolean(KEY_SHOW_ARTIST, show).apply()
    }

    /** The text to show on a Square cover, keeping only what the user ticked. Null when there's none. */
    fun caption(context: Context, title: String?, artist: String?): WallpaperRenderer.Caption? {
        val caption = WallpaperRenderer.Caption(
            title = title?.takeIf { showTitle(context) && it.isNotBlank() },
            artist = artist?.takeIf { showArtist(context) && it.isNotBlank() },
        )
        return caption.takeIf { it.title != null || it.artist != null }
    }

    /** Stored lowercase ("simple", "advanced"), which is what earlier versions wrote. */
    fun upscaleMode(context: Context): Upscaler.Mode {
        val stored = sharedPreferences(context).getString(KEY_UPSCALE, null)
        return Upscaler.Mode.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: Upscaler.Mode.SIMPLE
    }

    fun setUpscaleMode(context: Context, mode: Upscaler.Mode) {
        sharedPreferences(context).edit().putString(KEY_UPSCALE, mode.name.lowercase()).apply()
    }

    /** Whether the lock screen goes back to the default wallpaper when the music stops. */
    fun shouldRevert(context: Context): Boolean = sharedPreferences(context).getBoolean(KEY_REVERT, true)

    fun setShouldRevert(context: Context, revert: Boolean) {
        sharedPreferences(context).edit().putBoolean(KEY_REVERT, revert).apply()
    }

    /** True while the lock screen shows a cover we set, so we know to fall back when music stops. */
    fun isLockShowingCover(context: Context): Boolean =
        sharedPreferences(context).getBoolean(KEY_LOCK_IS_COVER, false)

    fun setLockShowingCover(context: Context, showing: Boolean) {
        sharedPreferences(context).edit().putBoolean(KEY_LOCK_IS_COVER, showing).apply()
    }

    /** The user's default wallpaper, copied into private storage so it survives the original being deleted. */
    fun defaultWallpaperFile(context: Context): File =
        File(context.applicationContext.filesDir, "default_wallpaper.jpg")

    /** Home screen picture used when the live wallpaper is set, so the cover only shows on the lock screen. */
    fun homeWallpaperFile(context: Context): File = File(context.applicationContext.filesDir, "home_wallpaper.jpg")

    fun isSelectionKey(key: String?): Boolean = key == KEY_SELECTED

    fun isStyleKey(key: String?): Boolean =
        key == KEY_FILL || key == KEY_UPSCALE || key == KEY_LAYOUT || key == KEY_SHOW_TITLE || key == KEY_SHOW_ARTIST
}
