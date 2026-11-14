package dev.ujhhgtg.wekit.bootstrap.ui.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val bitmap = createBitmap(width, height)

    val canvas = Canvas(bitmap)

    setBounds(0, 0, width, height)

    draw(canvas)

    return bitmap
}
