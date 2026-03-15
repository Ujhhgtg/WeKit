package moe.ouom.wekit.utils.logging

import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.loader.core.NativeLoader
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.formatEpoch
import moe.ouom.wekit.utils.PathUtils
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories

object LogUtils {
    private val logRootDirectory: Path?
        get() {
            return PathUtils.moduleDataPath?.resolve("logs")?.apply {
                createDirectories()
            }
        }

    private val runLogDirectory: Path?
        get() {
            return logRootDirectory?.resolve("run")?.apply {
                createDirectories()
            }
        }

    private val errorLogDirectory: Path?
        get() {
            return logRootDirectory?.resolve("error")?.apply {
                createDirectories()
            }
        }

    /**
     * 获取堆栈跟踪
     * 
     * @param throwable new Throwable || Exception
     * @return 堆栈跟踪
     */
    fun getStackTrace(throwable: Throwable): String {
        val result = StringBuilder()
        result.append(throwable).append("\n")
        val stackTraceElements = throwable.stackTrace
        for (stackTraceElement in stackTraceElements) {
            //不把当前类加入结果中
            if (stackTraceElement!!.className == LogUtils::class.java.name) continue
            result.append(stackTraceElement).append("\n")
        }
        return result.toString()
    }

    /**
     * 记录运行日志 确保能走到那一行代码
     * 
     * @param tag(文件名) 内容
     */
    fun addRunLog(tag: String, content: Any?) {
        addLog(tag, content.toString(), content, false)
    }

    /**
     * 记录异常
     */
    fun addError(tag: String, e: Throwable) {
        addLog(tag, e.toString(), e, true)
    }

    private fun addLog(fileName: String, desc: String?, content: Any?, isError: Boolean) {
        try {
            if (NativeLoader.isInitialized() && !WePrefs.getBoolOrFalse(PreferenceKeys.ENABLE_LOG)
            ) {
                return
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }

        val directory = if (isError) errorLogDirectory else runLogDirectory
        if (directory == null) return

        val path = directory.resolve("$fileName.log")
        val stringBuffer = StringBuilder(time)
        stringBuffer.append("\n").append(desc)
        if (content is Exception) {
            stringBuffer.append("\n").append(getStackTrace(content))
        }
        stringBuffer.append("\n\n")
        path.appendText(stringBuffer)
    }

    val time: String
        get() = formatEpoch(System.currentTimeMillis(), true)

    fun addError(tag: String, msg: String?) {
        addLog(tag, msg, null, true)
    }
}
