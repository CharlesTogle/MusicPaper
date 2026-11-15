package dev.musicpaper

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.service.notification.StatusBarNotification
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Pulls title, artist and cover art out of a media session, falling back to the player's notification. */
object MediaReader {
    private const val TIMEOUT_MS = 5_000
    private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024

    fun read(context: Context, controller: MediaController, notification: StatusBarNotification?): NowPlaying? {
        val metadata = controller.metadata
        val extras = notification?.notification?.extras

        val title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null
        val artist = metadata?.text(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_AUTHOR,
        ) ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val art = metadata?.art() ?: notification?.largeIconBitmap(context)

        return NowPlaying(controller.packageName, title, artist, art)
    }

    /** The cover's URI, if the player gives one. Often higher resolution than the embedded bitmap. */
    fun artUri(controller: MediaController): String? {
        val metadata = controller.metadata ?: return null
        return listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { metadata.getString(it)?.takeIf(String::isNotBlank) }
    }

    /** Loads a cover URI. Can touch the network, so call it off the main thread. */
    fun loadUri(context: Context, uri: String): Bitmap? = runCatching {
        val parsed = uri.toUri()
        when (parsed.scheme) {
            "http", "https" -> download(uri)
            "content", "android.resource", "file" ->
                context.contentResolver.openInputStream(parsed)?.use(BitmapFactory::decodeStream)
            else -> null // e.g. Spotify's own "spotify:image:" scheme
        }
    }.getOrNull()

    private fun download(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return try {
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null
            val bytes = connection.inputStream.use { it.readAtMost(MAX_DOWNLOAD_BYTES) } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            connection.disconnect()
        }
    }

    /** The whole stream, or null if it's longer than [limit]. Content-Length can be missing or wrong. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private fun MediaMetadata.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { getText(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    private fun MediaMetadata.art(): Bitmap? = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    ).firstNotNullOfOrNull { getBitmap(it) }?.softwareCopy()

    private fun StatusBarNotification.largeIconBitmap(context: Context): Bitmap? {
        val icon = notification.getLargeIcon() ?: return null
        val drawable = runCatching { icon.loadDrawable(context) }.getOrNull() as? BitmapDrawable ?: return null
        return drawable.bitmap?.softwareCopy()
    }

    /** Copy so the player can't recycle it under us, and so it can be drawn on a software canvas. */
    private fun Bitmap.softwareCopy(): Bitmap? =
        if (isRecycled) null else runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
}
