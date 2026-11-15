package dev.musicpaper

import android.Manifest
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.R.attr as AppCompatAttr
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.R.attr as MaterialAttr
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** The "Setup" and "Background" rows: what's granted, plus buttons that open the matching system screens. */
class SetupSection(private val activity: AppCompatActivity) {
    // Lazy: the section is created before the activity has a context.
    private val listenerComponent by lazy { ComponentName(activity, MusicListenerService::class.java) }

    fun bind() {
        activity.findViewById<Button>(R.id.btnGrant).setOnClickListener { openNotificationAccess() }
        activity.findViewById<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }
        activity.findViewById<Button>(R.id.btnAutostart).setOnClickListener { openAutostart() }
        activity.findViewById<Button>(R.id.btnLive).setOnClickListener { openLiveWallpaperPicker() }
        activity.findViewById<View>(R.id.rowAutostart).setVisibleOrGone(isXiaomi())
    }

    /** Re-checks every row. If the phone killed the listener (e.g. after clearing recents), asks to reconnect it. */
    fun refresh() {
        if (hasNotificationAccess()) NotificationListenerService.requestRebind(listenerComponent)

        val wallpaperOk = activity.checkSelfPermission(Manifest.permission.SET_WALLPAPER) ==
            PackageManager.PERMISSION_GRANTED
        val wallpaperText = if (wallpaperOk) R.string.perm_wallpaper_granted else R.string.perm_wallpaper_blocked
        setText(R.id.permWallpaper, wallpaperText)
        showStatus(R.id.permWallpaperIcon, wallpaperOk, hasButton = false)

        val notifOk = hasNotificationAccess()
        setText(R.id.permNotif, if (notifOk) R.string.perm_notif_on else R.string.perm_notif_off)
        val grantLabel = if (notifOk) R.string.action_manage else R.string.action_grant
        styleAction(R.id.btnGrant, primary = !notifOk, label = grantLabel)

        val liveOn = LiveCover.isActive
        setText(R.id.liveStatus, if (liveOn) R.string.live_on else R.string.live_off)
        val liveLabel = if (liveOn) R.string.action_change else R.string.action_set
        styleAction(R.id.btnLive, primary = !liveOn, label = liveLabel)

        val batteryOk = ignoresBatteryOptimisation()
        setText(R.id.bgBattery, if (batteryOk) R.string.battery_unrestricted else R.string.battery_optimised)
        showStatus(R.id.bgBatteryIcon, batteryOk, hasButton = true)
        activity.findViewById<Button>(R.id.btnBattery).setVisibleOrGone(!batteryOk)

        showSetupSummary(missing = listOf(wallpaperOk, notifOk, batteryOk).count { !it })
    }

    fun hasNotificationAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return activity.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listenerComponent)
        }
        val flat = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == listenerComponent }
    }

    private fun showSetupSummary(missing: Int) {
        activity.findViewById<TextView>(R.id.setupStatus).apply {
            val done = missing == 0
            text = if (done) {
                activity.getString(R.string.setup_all_set)
            } else {
                resources.getQuantityString(R.plurals.setup_steps_left, missing, missing)
            }
            val (background, foreground) = if (done) {
                MaterialAttr.colorPrimaryContainer to MaterialAttr.colorOnPrimaryContainer
            } else {
                MaterialAttr.colorErrorContainer to MaterialAttr.colorOnErrorContainer
            }
            backgroundTintList = themeTint(background)
            setTextColor(MaterialColors.getColor(this, foreground))
        }
    }

    private fun setText(@IdRes id: Int, @StringRes text: Int) {
        activity.findViewById<TextView>(id).setText(text)
    }

    /** Tick or warning at the end of a setup row. Rows with a button skip the warning: the button says it. */
    private fun showStatus(@IdRes id: Int, ok: Boolean, hasButton: Boolean) {
        activity.findViewById<ImageView>(id).apply {
            setVisibleOrGone(ok || !hasButton)
            setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_alert_circle_outline)
            imageTintList = themeTint(if (ok) AppCompatAttr.colorPrimary else AppCompatAttr.colorError)
        }
    }

    /** A needed action gets a filled button; once it's done, a quieter tonal one. */
    private fun styleAction(@IdRes id: Int, primary: Boolean, @StringRes label: Int) {
        val button = activity.findViewById<MaterialButton>(id)
        button.setText(label)
        val (background, foreground) = if (primary) {
            AppCompatAttr.colorPrimary to MaterialAttr.colorOnPrimary
        } else {
            MaterialAttr.colorSecondaryContainer to MaterialAttr.colorOnSecondaryContainer
        }
        button.backgroundTintList = button.themeTint(background)
        button.setTextColor(MaterialColors.getColor(button, foreground))
    }

    private fun ignoresBatteryOptimisation(): Boolean =
        activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)

    private fun askBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${activity.packageName}".toUri(),
        )
        runCatching { activity.startActivity(direct) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** Opens the system's live wallpaper preview for MusicPaper. Android requires the user to confirm it there. */
    private fun openLiveWallpaperPicker() {
        val component = ComponentName(activity, CoverWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        runCatching { activity.startActivity(direct) }
            .recoverCatching { activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            .onFailure { activity.toast(R.string.toast_no_live_picker) }
    }

    private fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Xiaomi's Autostart screen has no public API; fall back to the app's info page. */
    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        runCatching { activity.startActivity(miui) }
            .onFailure { activity.startActivity(appDetailsIntent()) }
    }

    private fun openNotificationAccess() {
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent.flattenToString())
        } else {
            fallback
        }
        runCatching { activity.startActivity(direct) }
            .recoverCatching { activity.startActivity(fallback) }
            .onFailure { activity.startActivity(appDetailsIntent()) }
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
}
