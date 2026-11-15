package dev.musicpaper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val setup = SetupSection(this)
    private val appList = AppListSection(this)
    private val nowPlayingCard = NowPlayingCard(this, setup::hasNotificationAccess)
    private val layoutPicker = LayoutPicker(this)

    private val thumbnailWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The system picker hands over just the chosen image, so no storage permission is needed.
    private val pickDefault = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::onDefaultPicked)
    }
    private val pickHome = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::onHomePicked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+ draws edge to edge; keep content clear of the status and nav bars.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        setup.bind()
        nowPlayingCard.bind()
        layoutPicker.bind()

        findViewById<Button>(R.id.btnPickDefault).setOnClickListener { pickImage(pickDefault) }
        findViewById<Button>(R.id.btnRemoveDefault).setOnClickListener {
            WallpaperApplier.deleteImage(Prefs.defaultWallpaperFile(this))
            showDefaultWallpaper(hasImage = false)
        }
        findViewById<Button>(R.id.btnPickHome).setOnClickListener { pickImage(pickHome) }
        findViewById<Button>(R.id.btnRemoveHome).setOnClickListener {
            WallpaperApplier.deleteImage(Prefs.homeWallpaperFile(this))
            showHomeWallpaper(hasImage = false)
        }

        bindSwitch(R.id.rowFill, R.id.swFill, Prefs.isFillEnabled(this)) {
            Prefs.setFillEnabled(this, it)
            layoutPicker.onFillChanged()
        }
        bindSwitch(R.id.rowRevert, R.id.swRevert, Prefs.shouldRevert(this)) { Prefs.setShouldRevert(this, it) }
        bindUpscale()
        showDefaultWallpaper()
        showHomeWallpaper()

        // Once per launch, not again on rotation.
        if (savedInstanceState == null) UpdateChecker.check(BuildConfig.VERSION_NAME, ::showUpdateDialog)
    }

    override fun onResume() {
        super.onResume()
        setup.refresh()
        appList.refresh()
        NowPlayingStore.observe(nowPlayingCard.onNowPlaying)
        NowPlayingStore.observe(layoutPicker.onNowPlaying)
    }

    override fun onPause() {
        NowPlayingStore.remove(nowPlayingCard.onNowPlaying)
        NowPlayingStore.remove(layoutPicker.onNowPlaying)
        super.onPause()
    }

    override fun onDestroy() {
        nowPlayingCard.release()
        layoutPicker.release()
        thumbnailWorker.shutdownNow()
        super.onDestroy()
    }

    // --- Updates -----------------------------------------------------------------------------

    private fun showUpdateDialog(latest: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, latest, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_action) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE))) }
                    .onFailure { toast(R.string.toast_no_browser) }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    // --- Default and home wallpapers ---------------------------------------------------------

    private fun pickImage(launcher: ActivityResultLauncher<Array<String>>) {
        runCatching { launcher.launch(arrayOf("image/*")) }.onFailure { toast(R.string.toast_no_image_picker) }
    }

    private fun onDefaultPicked(uri: Uri) {
        WallpaperApplier.importDefault(this, uri) { ok ->
            if (!ok) {
                toast(R.string.toast_image_unreadable)
                return@importDefault
            }
            showDefaultWallpaper()
            if (!Prefs.shouldRevert(this)) {
                Prefs.setShouldRevert(this, true)
                findViewById<MaterialSwitch>(R.id.swRevert).isChecked = true
            }
            // Nothing playing right now: show the new default straight away.
            if (NowPlayingStore.current == null) WallpaperApplier.fallback(this)
            toast(R.string.toast_default_saved)
        }
    }

    private fun onHomePicked(uri: Uri) {
        WallpaperApplier.importHome(this, uri) { ok ->
            if (!ok) {
                toast(R.string.toast_image_unreadable)
                return@importHome
            }
            showHomeWallpaper()
            WallpaperApplier.splitHome(this) // if the live wallpaper is on the home screen, swap the picture in now
            toast(R.string.toast_home_saved)
        }
    }

    /** [hasImage] is false right after a delete, which is still queued, so the old file isn't shown again. */
    private fun showDefaultWallpaper(hasImage: Boolean = true) {
        loadThumbnail(Prefs.defaultWallpaperFile(this), hasImage) { thumb ->
            findViewById<ImageView>(R.id.defaultPreview).showPreview(thumb, R.drawable.ic_cellphone_lock)
            findViewById<TextView>(R.id.defaultHint)
                .setText(if (thumb != null) R.string.default_hint_set else R.string.default_hint_none)
            findViewById<Button>(R.id.btnRemoveDefault).setVisibleOrGone(thumb != null)
        }
    }

    private fun showHomeWallpaper(hasImage: Boolean = true) {
        loadThumbnail(Prefs.homeWallpaperFile(this), hasImage) { thumb ->
            findViewById<ImageView>(R.id.homePreview).showPreview(thumb, R.drawable.ic_cellphone)
            findViewById<TextView>(R.id.homeHint)
                .setText(if (thumb != null) R.string.home_hint_set else R.string.home_hint_none)
            findViewById<Button>(R.id.btnRemoveHome).setVisibleOrGone(thumb != null)
        }
    }

    /** Decodes a small preview of [file] off the main thread, then hands it (or null) to [onLoaded]. */
    private fun loadThumbnail(file: File, hasImage: Boolean, onLoaded: (Bitmap?) -> Unit) {
        thumbnailWorker.execute {
            val options = BitmapFactory.Options().apply { inSampleSize = THUMBNAIL_SAMPLE }
            val thumb = if (hasImage && file.exists()) BitmapFactory.decodeFile(file.path, options) else null
            mainHandler.post { if (!isDestroyed) onLoaded(thumb) }
        }
    }

    // --- Style -------------------------------------------------------------------------------

    /** Simple / Advanced upscaling choice, with a line explaining the selected one. */
    private fun bindUpscale() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.upscaleGroup)
        val hint = findViewById<TextView>(R.id.upscaleHint)
        fun describe(mode: Upscaler.Mode) {
            hint.setText(
                when (mode) {
                    Upscaler.Mode.ADVANCED -> R.string.upscale_hint_advanced
                    Upscaler.Mode.SIMPLE -> R.string.upscale_hint_simple
                },
            )
        }
        val mode = Prefs.upscaleMode(this)
        group.check(if (mode == Upscaler.Mode.ADVANCED) R.id.btnUpscaleAdvanced else R.id.btnUpscaleSimple)
        describe(mode)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val next = if (id == R.id.btnUpscaleAdvanced) Upscaler.Mode.ADVANCED else Upscaler.Mode.SIMPLE
            Prefs.setUpscaleMode(this, next)
            describe(next)
        }
    }

    /** Binds a switch; tapping anywhere on its row flips it too. */
    private fun bindSwitch(@IdRes rowId: Int, @IdRes switchId: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val switch = findViewById<MaterialSwitch>(switchId).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        findViewById<View>(rowId).setOnClickListener { switch.toggle() }
    }

    private companion object {
        const val THUMBNAIL_SAMPLE = 8
    }
}
