package dev.musicpaper

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the latest published release and reports its version if it is newer than this build.
 * Sends nothing about the user or device; a failed or offline check is silently ignored.
 */
object UpdateChecker {
    const val RELEASES_PAGE = "https://github.com/CharlesTogle/MusicPaper/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/CharlesTogle/MusicPaper/releases/latest"
    private const val TIMEOUT_MS = 8_000

    /** Calls [onNewer] on the main thread with the latest version name, only when it is newer than [current]. */
    fun check(current: String, onNewer: (String) -> Unit) {
        Thread {
            val latest = runCatching(::fetchLatestTag)
                .onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "Update check failed", it) }
                .getOrNull() ?: return@Thread
            if (isNewer(latest, current)) {
                Handler(Looper.getMainLooper()).post { onNewer(latest.removePrefix("v")) }
            }
        }.start()
    }

    private fun fetchLatestTag(): String? {
        val connection = URL(LATEST_API).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").takeIf(String::isNotBlank)
        } finally {
            connection.disconnect()
        }
    }

    /** Compares dotted versions numerically ("v1.10.0" > "1.9.2"); anything after a '-' is ignored. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.trim().removePrefix("v").substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: return null }
}
