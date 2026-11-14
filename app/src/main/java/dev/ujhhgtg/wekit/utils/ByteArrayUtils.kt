@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils

import java.nio.ByteBuffer

inline fun ByteArray.toByteBuffer() = ByteBuffer.wrap(this)
