package dev.ujhhgtg.wekit.utils

import android.app.ActivityManager
import android.os.Process
import dev.ujhhgtg.wekit.utils.android.getSystemService

enum class TargetProcess {
    MAIN,
    PUSH,
    APPBRAND,
    TOOLS,
    SANDBOX,
    HOTPOT,
    EXDEVICE,
    SUPPORT,
    CUPLOADER,
    PATCH,
    FALLBACK,
    DEXOPT,
    RECOVERY,
    NOSPACE,
    JECTL,
    OPENGL_DETECTOR,
    RUBBISHBIN,
    ISOLATED,
    RES_CAN_WORKER,
    EXTMIG,
    BACKTRACE,
    TMASSISTANT,
    SWITCH,
    HLD,
    PLAYCORE,
    HLDFL,
    MAGIC_EMOJI,
    OTHERS,
}

object TargetProcesses {

    val isInMain get() = currentType == TargetProcess.MAIN

    val currentName by lazy {
        var retry = 0
        do {
            runCatching {
                val ctx = HostInfo.application
                val am = ctx.getSystemService<ActivityManager>()
                val myPid = Process.myPid()

                val name = am.runningAppProcesses?.find { it?.pid == myPid }?.processName
                if (name != null) return@lazy name
            }.onFailure { WeLogger.e("TargetProcesses", "failed to get current process name", it) }
            retry++
        } while (retry < 3)
        "unknown"
    }

    val currentType by lazy {
        val name = currentName
        val parts = name.split(":")

        if (parts.size == 1) {
            TargetProcess.MAIN
        } else {
            when (val tail = parts.last()) {
                "push" -> TargetProcess.PUSH
                "sandbox" -> TargetProcess.SANDBOX
                "exdevice" -> TargetProcess.EXDEVICE
                "support" -> TargetProcess.SUPPORT
                "cuploader" -> TargetProcess.CUPLOADER
                "patch" -> TargetProcess.PATCH
                "fallback" -> TargetProcess.FALLBACK
                "dexopt" -> TargetProcess.DEXOPT
                "recovery" -> TargetProcess.RECOVERY
                "nospace" -> TargetProcess.NOSPACE
                "jectl" -> TargetProcess.JECTL
                "opengl_detector" -> TargetProcess.OPENGL_DETECTOR
                "rubbishbin" -> TargetProcess.RUBBISHBIN
                "res_can_worker" -> TargetProcess.RES_CAN_WORKER
                "extmig" -> TargetProcess.EXTMIG
                "TMAssistantDownloadSDKService" -> TargetProcess.TMASSISTANT
                "switch" -> TargetProcess.SWITCH
                "hld" -> TargetProcess.HLD
                "playcore_missing_splits_activity" -> TargetProcess.PLAYCORE
                "hldfl" -> TargetProcess.HLDFL
                "magic_emoji" -> TargetProcess.MAGIC_EMOJI
                else -> when {
                    tail.startsWith("appbrand") -> TargetProcess.APPBRAND
                    tail.startsWith("tools") -> TargetProcess.TOOLS
                    tail.startsWith("hotpot") -> TargetProcess.HOTPOT
                    tail.startsWith("isolated_process") -> TargetProcess.ISOLATED
                    tail.startsWith("backtrace") -> TargetProcess.BACKTRACE
                    else -> TargetProcess.OTHERS
                }
            }
        }
    }
}
