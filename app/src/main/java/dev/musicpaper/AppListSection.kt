package dev.musicpaper

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.checkbox.MaterialCheckBox

/** The "Selected apps" list: one checkbox per music app; only ticked apps are ever read. */
class AppListSection(private val activity: AppCompatActivity) {
    private val packageManager get() = activity.packageManager

    fun refresh() {
        val container = activity.findViewById<LinearLayout>(R.id.appList)
        container.removeAllViews()
        val selected = Prefs.selectedPackages(activity)

        val apps = musicApps().mapNotNull { packageName ->
            val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return@mapNotNull null
            packageName to packageManager.getApplicationLabel(info).toString()
        }.sortedWith(compareBy({ it.first !in Prefs.PRESETS }, { it.second.lowercase() }))

        if (apps.isEmpty()) {
            container.addView(emptyRow(container))
            return
        }
        for ((packageName, label) in apps) {
            val row = activity.layoutInflater.inflate(R.layout.item_app, container, false)
            val check = row.findViewById<MaterialCheckBox>(R.id.appCheck)
            row.findViewById<TextView>(R.id.appLabel).text = label
            row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull(),
            )
            check.contentDescription = label
            check.isChecked = packageName in selected
            check.setOnCheckedChangeListener { _, checked ->
                Prefs.setPackageSelected(activity, packageName, checked)
            }
            row.setOnClickListener { check.toggle() }
            container.addView(row)
        }
    }

    private fun emptyRow(container: LinearLayout): View {
        val row = activity.layoutInflater.inflate(R.layout.item_app, container, false)
        row.findViewById<View>(R.id.appCheck).visibility = View.GONE
        row.findViewById<ImageView>(R.id.appIcon).visibility = View.GONE
        row.findViewById<TextView>(R.id.appLabel).apply {
            setText(R.string.apps_none_found)
            maxLines = EMPTY_MAX_LINES
        }
        return row
    }

    /** Spotify, YouTube, YouTube Music, plus anything that looks like a local music player. */
    private fun musicApps(): Set<String> {
        val found = mutableSetOf<String>()
        found += Prefs.PRESETS
        found += Prefs.seenPackages(activity)
        found += Prefs.selectedPackages(activity)
        runCatching {
            packageManager.queryIntentServices(Intent(MEDIA_BROWSER_SERVICE), 0)
                .forEach { found += it.serviceInfo.packageName }
        }
        runCatching {
            packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0)
                .forEach { found += it.activityInfo.packageName }
        }
        runCatching {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(SAMPLE_AUDIO_URI.toUri(), "audio/*")
            packageManager.queryIntentActivities(view, 0).forEach { found += it.activityInfo.packageName }
        }
        found -= activity.packageName
        return found
    }

    private companion object {
        const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
        const val SAMPLE_AUDIO_URI = "content://media/external/audio/media/1"
        const val EMPTY_MAX_LINES = 3
    }
}
