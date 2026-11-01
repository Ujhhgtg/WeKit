package dev.ujhhgtg.wekit.utils.reflection

import android.content.res.AssetManager
import android.content.res.AssetManagerHidden
import android.content.res.Resources
import android.content.res.ResourcesHidden
import android.view.SurfaceControl
import android.view.SurfaceControlHidden
import android.view.View
import android.view.ViewHidden
import dev.rikka.tools.refine.Refine

@Suppress("NOTHING_TO_INLINE")
inline fun Resources.asHidden(): ResourcesHidden = Refine.unsafeCast(this)

@Suppress("NOTHING_TO_INLINE")
inline fun AssetManager.asHidden(): AssetManagerHidden = Refine.unsafeCast(this)

@Suppress("NOTHING_TO_INLINE")
inline fun SurfaceControl.asHidden(): SurfaceControlHidden = Refine.unsafeCast(this)

@Suppress("NOTHING_TO_INLINE")
inline fun SurfaceControl.Transaction.asHidden(): SurfaceControlHidden.TransactionHidden =
    Refine.unsafeCast(this)

@Suppress("NOTHING_TO_INLINE") inline fun View.asHidden(): ViewHidden = Refine.unsafeCast(this)
