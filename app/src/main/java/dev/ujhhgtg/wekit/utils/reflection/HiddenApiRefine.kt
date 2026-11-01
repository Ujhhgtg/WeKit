package dev.ujhhgtg.wekit.utils.reflection

import android.content.res.AssetManager
import android.content.res.AssetManagerHidden
import android.content.res.Resources
import android.content.res.ResourcesHidden
import dev.rikka.tools.refine.Refine

@Suppress("NOTHING_TO_INLINE")
inline fun Resources.asHidden(): ResourcesHidden = Refine.unsafeCast(this)

@Suppress("NOTHING_TO_INLINE")
inline fun AssetManager.asHidden(): AssetManagerHidden = Refine.unsafeCast(this)
