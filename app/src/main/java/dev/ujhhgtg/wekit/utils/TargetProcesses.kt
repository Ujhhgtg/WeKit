package dev.ujhhgtg.wekit.utils

import android.app.ActivityManager
import android.os.Process
import dev.ujhhgtg.wekit.utils.android.getSystemService

enum class TargetProcess {
    MAIN,
    PUSH,
    APPBRAND,
    HOTPOT,
    OTHERS,
}

object TargetProcesses {
    val isInMain
        get() = currentType == TargetProcess.MAIN

    val currentName by lazy {
        var retry = 0
        do {
            runCatching {
                val ctx = HostInfo.application
                val am = ctx.getSystemService<ActivityManager>()
                val myPid = Process.myPid()

                val name = am.runningAppProcesses?.find { it?.pid == myPid }?.processName
                if (name != null) return@lazy name
            }
                .onFailure {
                    WeLogger.e("TargetProcesses", "failed to get current process name", it)
                }
            retry++
        } while (retry < 3)
        "unknown"
    }

    val currentType by lazy {
        val parts = currentName.split(":")

        when {
            parts.size == 1 -> TargetProcess.MAIN
            parts.last() == "push" -> TargetProcess.PUSH
            parts.last().startsWith("appbrand") -> TargetProcess.APPBRAND
            parts.last().startsWith("hotpot") -> TargetProcess.HOTPOT
            else -> TargetProcess.OTHERS
        }
    }
}
