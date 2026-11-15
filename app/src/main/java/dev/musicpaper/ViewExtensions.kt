package dev.musicpaper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.R.attr as MaterialAttr
import com.google.android.material.color.MaterialColors

internal fun Context.toast(@StringRes message: Int) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal fun View.setVisibleOrGone(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** A colour from the view's theme, as a tint list. */
internal fun View.themeTint(@AttrRes attr: Int): ColorStateList =
    ColorStateList.valueOf(MaterialColors.getColor(this, attr))

/** Shows [bitmap] cropped to fill, or a centred placeholder icon when there's nothing to show. */
internal fun ImageView.showPreview(bitmap: Bitmap?, @DrawableRes placeholder: Int) {
    if (bitmap != null) {
        scaleType = ImageView.ScaleType.CENTER_CROP
        imageTintList = null
        setImageBitmap(bitmap)
    } else {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(placeholder)
        imageTintList = themeTint(MaterialAttr.colorOutline)
    }
}
