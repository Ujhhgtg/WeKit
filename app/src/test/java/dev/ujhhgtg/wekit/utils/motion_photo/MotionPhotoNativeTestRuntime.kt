package dev.ujhhgtg.wekit.utils.motion_photo

import java.io.File

/** Prepare with `cargo build -p wekit-native --lib` before running parser integration tests. */
object MotionPhotoNativeTestRuntime {
    init {
        System.load(File("../target/debug/libwekit_native.so").canonicalPath)
    }

    fun load() = Unit
}
