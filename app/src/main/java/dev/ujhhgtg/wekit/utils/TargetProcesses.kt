package dev.ujhhgtg.wekit.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import dev.ujhhgtg.wekit.utils.logging.WeLogger

object TargetProcesses {

    const val PROC_MAIN = 1
    const val PROC_PUSH = 1 shl 1
    const val PROC_APPBRAND = 1 shl 2
    const val PROC_TOOLS = 1 shl 3
    const val PROC_SANDBOX = 1 shl 4
    const val PROC_HOTPOT = 1 shl 5
    const val PROC_EXDEVICE = 1 shl 6
    const val PROC_SUPPORT = 1 shl 7
    const val PROC_CUPLOADER = 1 shl 8
    const val PROC_PATCH = 1 shl 9
    const val PROC_FALLBACK = 1 shl 10
    const val PROC_DEXOPT = 1 shl 11
    const val PROC_RECOVERY = 1 shl 12
    const val PROC_NOSPACE = 1 shl 13
    const val PROC_JECTL = 1 shl 14
    const val PROC_OPENGL_DETECTOR = 1 shl 15
    const val PROC_RUBBISHBIN = 1 shl 16
    const val PROC_ISOLATED = 1 shl 17
    const val PROC_RES_CAN_WORKER = 1 shl 18
    const val PROC_EXTMIG = 1 shl 19
    const val PROC_BACKTRACE = 1 shl 20
    const val PROC_TMASSISTANT = 1 shl 21
    const val PROC_SWITCH = 1 shl 22
    const val PROC_HLD = 1 shl 23
    const val PROC_PLAYCORE = 1 shl 24
    const val PROC_HLDFL = 1 shl 25
    const val PROC_MAGIC_EMOJI = 1 shl 26
    const val PROC_OTHERS = 1 shl 30

    private var procType = 0
    private var procName: String? = null

    fun getCurrentProcessType(): Int {
        if (procType != 0) return procType

        val procName = getCurrentProcessName()
        val parts = procName.split(":")

        procType = if (parts.size == 1) {
            PROC_MAIN
        } else {
            when (val tail = parts.last()) {
                "push" -> PROC_PUSH
                "sandbox" -> PROC_SANDBOX
                "exdevice" -> PROC_EXDEVICE
                "support" -> PROC_SUPPORT
                "cuploader" -> PROC_CUPLOADER
                "patch" -> PROC_PATCH
                "fallback" -> PROC_FALLBACK
                "dexopt" -> PROC_DEXOPT
                "recovery" -> PROC_RECOVERY
                "nospace" -> PROC_NOSPACE
                "jectl" -> PROC_JECTL
                "opengl_detector" -> PROC_OPENGL_DETECTOR
                "rubbishbin" -> PROC_RUBBISHBIN
                "res_can_worker" -> PROC_RES_CAN_WORKER
                "extmig" -> PROC_EXTMIG
                "TMAssistantDownloadSDKService" -> PROC_TMASSISTANT
                "switch" -> PROC_SWITCH
                "hld" -> PROC_HLD
                "playcore_missing_splits_activity" -> PROC_PLAYCORE
                "hldfl" -> PROC_HLDFL
                "magic_emoji" -> PROC_MAGIC_EMOJI
                else -> when {
                    tail.startsWith("appbrand") -> PROC_APPBRAND
                    tail.startsWith("tools") -> PROC_TOOLS
                    tail.startsWith("hotpot") -> PROC_HOTPOT
                    tail.startsWith("isolated_process") -> PROC_ISOLATED
                    tail.startsWith("backtrace") -> PROC_BACKTRACE
                    else -> PROC_OTHERS
                }
            }
        }
        return procType
    }

    @JvmStatic
    fun isInMainProcess(): Boolean = getCurrentProcessType() == PROC_MAIN

    fun getCurrentProcessName(): String {
        procName?.let { return it }

        var retry = 0
        do {
            try {
                val ctx = HostInfo.application
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val myPid = Process.myPid()
                am.runningAppProcesses?.forEach { info ->
                    if (info?.pid == myPid) {
                        procName = info.processName
                        return info.processName
                    }
                }
            } catch (e: Throwable) {
                WeLogger.e("getProcessName error $e")
            }
            retry++
        } while (retry < 3)

        return "unknown"
    }
}
