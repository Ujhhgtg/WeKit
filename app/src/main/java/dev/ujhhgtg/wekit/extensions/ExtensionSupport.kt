package dev.ujhhgtg.wekit.extensions

import android.os.Build
import android.os.Process

private const val ARM64_ABI = "arm64-v8a"

/** True only when this process itself can load the arm64-only local-LLM payloads. */
fun supportsArm64ExtensionProcess(): Boolean =
    Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains(ARM64_ABI)
