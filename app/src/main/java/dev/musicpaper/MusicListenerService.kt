package dev.musicpaper

import android.app.Notification
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Runs once the user grants notification access. Watches the media sessions of the selected apps only,
 * and changes the lock screen when the playing song changes. Notifications from other apps are ignored.
 */
class MusicListenerService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val component by lazy { ComponentName(this, MusicListenerService::class.java) }
    private var sessionManager: MediaSessionManager? = null
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private var lastAppliedKey: String? = null

    /** Song key -> cover loaded from the player's URI (null if loading failed, so we don't wait again). */
    private var uriArt: Pair<String, Bitmap?>? = null
    private var shownOnLock: NowPlaying? = null
    private var orientation = Configuration.ORIENTATION_UNDEFINED
    private var fetchingFor: String? = null

    private val evaluateRunnable = Runnable { evaluate() }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { syncSessions(it.orEmpty()) }

    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (Prefs.isSelectionKey(key)) refreshSessions()
        if (Prefs.isStyleKey(key)) {
            lastAppliedKey = null
            scheduleEvaluate()
        }
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
        } catch (e: SecurityException) {
            return // access was revoked between connect and now
        }
        sessionManager = manager
        orientation = resources.configuration.orientation
        Log.i(TAG, "listener connected")
        Prefs.sharedPreferences(this).registerOnSharedPreferenceChangeListener(prefsChanged)
        refreshSessions()
    }

    /** Swap to the portrait or landscape version when the screen rotates. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == orientation) return
        orientation = newConfig.orientation
        val nowPlaying = shownOnLock ?: return
        // The live wallpaper redraws on rotation itself.
        if (!Prefs.isLockShowingCover(this) || WallpaperApplier.liveOnLock(this)) return
        val name = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.i(TAG, "rotated to $name, redrawing")
        WallpaperApplier.apply(this, nowPlaying)
    }

    override fun onListenerDisconnected() {
        teardown()
        requestRebind(component)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Filter first: content of unselected apps is never read.
        if (sbn.packageName !in Prefs.selectedPackages(this)) return
        if (sbn.isMedia()) scheduleEvaluate()
    }

    private fun teardown() {
        main.removeCallbacks(evaluateRunnable)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        sessionManager = null
        Prefs.sharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefsChanged)
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
        NowPlayingStore.publish(null)
    }

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        syncSessions(runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList()))
    }

    private fun syncSessions(all: List<MediaController>) {
        Prefs.addSeenPackages(this, all.map { it.packageName }.filter { it != packageName })

        val selected = Prefs.selectedPackages(this)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "sessions: ${all.map { it.packageName }} (watching ${all.count { it.packageName in selected }})")
        }
        val wanted = all.filter { it.packageName in selected }.associateBy { it.sessionToken }

        controllers.keys.filter { it !in wanted }.forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        wanted.forEach { (token, controller) ->
            if (token in controllers) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleEvaluate()
                override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleEvaluate()
                override fun onSessionDestroyed() = refreshSessions()
            }
            controller.registerCallback(callback, main)
            controllers[token] = controller to callback
        }
        scheduleEvaluate()
    }

    /** Players often send several updates per track change; wait for them to settle. */
    private fun scheduleEvaluate() {
        main.removeCallbacks(evaluateRunnable)
        main.postDelayed(evaluateRunnable, SETTLE_MS)
    }

    private fun evaluate() {
        val list = controllers.values.map { it.first }
        val playing = list.firstOrNull { it.state() == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.state() == PlaybackState.STATE_BUFFERING }

        if (playing == null) {
            val stillOpen = list.any { it.state() == PlaybackState.STATE_PAUSED }
            if (!stillOpen) onMusicStopped()
            return
        }

        val read = MediaReader.read(this, playing, mediaNotification(playing.packageName)) ?: return
        val nowPlaying = withSharperCover(read, playing)
        if (nowPlaying == null) {
            // Show the song now, but wait for the sharper cover rather than setting the wallpaper twice.
            NowPlayingStore.publish(read)
            return
        }
        NowPlayingStore.publish(nowPlaying)
        if (nowPlaying.art == null || nowPlaying.key == lastAppliedKey) return // no cover yet, or already shown

        lastAppliedKey = nowPlaying.key
        shownOnLock = nowPlaying
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "now playing: ${nowPlaying.title} — ${nowPlaying.artist} (${nowPlaying.packageName})")
        }
        Log.i(TAG, "cover ${nowPlaying.art.width}x${nowPlaying.art.height}, setting lock screen")
        WallpaperApplier.apply(this, nowPlaying) { ok ->
            Log.i(TAG, "lock screen ${if (ok) "updated" else "update FAILED"}")
            if (!ok && lastAppliedKey == nowPlaying.key) lastAppliedKey = null
        }
    }

    private fun onMusicStopped() {
        NowPlayingStore.publish(null)
        if (!Prefs.isLockShowingCover(this) || !Prefs.shouldRevert(this)) return
        Log.i(TAG, "music stopped, switching to default wallpaper")
        Prefs.setLockShowingCover(this, false) // set now so repeated evaluations don't queue it twice
        shownOnLock = null
        WallpaperApplier.fallback(this)
        lastAppliedKey = null
    }

    /**
     * The embedded bitmap is often small (players downscale it). If the player also gives a URI, use the cover
     * from there when it's bigger. Returns null while that cover is still loading; [evaluate] runs again after.
     */
    private fun withSharperCover(nowPlaying: NowPlaying, controller: MediaController): NowPlaying? {
        val embedded = nowPlaying.art
        if (embedded != null && maxOf(embedded.width, embedded.height) >= SHARP_PX) return nowPlaying

        val songKey = nowPlaying.songKey
        val cached = uriArt
        if (cached != null && cached.first == songKey) {
            val fromUri = cached.second ?: return nowPlaying
            val bigger = embedded == null || fromUri.width * fromUri.height > embedded.width * embedded.height
            return if (bigger) nowPlaying.copy(art = fromUri) else nowPlaying
        }

        val uri = MediaReader.artUri(controller) ?: return nowPlaying
        if (fetchingFor != songKey) {
            fetchingFor = songKey
            val app = applicationContext
            WallpaperApplier.worker.execute {
                val bitmap = MediaReader.loadUri(app, uri)
                main.post {
                    if (fetchingFor == songKey) fetchingFor = null
                    uriArt = songKey to bitmap
                    scheduleEvaluate()
                }
            }
        }
        return null
    }

    private fun mediaNotification(packageName: String): StatusBarNotification? =
        runCatching { activeNotifications }.getOrNull()
            ?.firstOrNull { it.packageName == packageName && it.isMedia() }

    private fun StatusBarNotification.isMedia(): Boolean =
        notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            notification.category == Notification.CATEGORY_TRANSPORT

    private fun MediaController.state(): Int = playbackState?.state ?: PlaybackState.STATE_NONE

    private companion object {
        const val SETTLE_MS = 700L
        const val SHARP_PX = 600 // below this, look for a higher resolution cover
    }
}
