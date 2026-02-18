package moe.ouom.wekit.utils.log

import android.annotation.SuppressLint
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.loader.core.NativeCoreBridge
import moe.ouom.wekit.utils.io.FileUtils
import moe.ouom.wekit.utils.io.PathUtils
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Calendar
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

    fun getCallStack(): String {
        val throwable = Throwable()
        return getStackTrace(throwable)
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

    fun addError(e: Throwable) {
        addError("Error Log", e.toString(), e)
    }

    fun addRunLog(content: Any?) {
        addRunLog("Run Log", content)
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
    @JvmStatic
    fun addError(tag: String, e: Throwable) {
        addLog(tag, e.toString(), e, true)
    }

    /**
     * 记录异常
     * 
     * @param tag         标签 文件名
     * @param desc        错误的相关描述
     * @param e           Exception
     */
    fun addError(tag: String, desc: String?, e: Throwable?) {
        addLog(tag, desc, e, true)
    }


    private fun addLog(fileName: String, desc: String?, content: Any?, isError: Boolean) {
        try {
            if (NativeCoreBridge.isNativeCoreInitialized() && !WeConfig.getDefaultConfig()
                .getBooleanOrFalse(Constants.PrekEnableLog)
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
        FileUtils.writeTextToFile(path.toString(), stringBuffer.toString(), true)
    }

    val time: String
        get() {
            @SuppressLint("SimpleDateFormat") val df =
                SimpleDateFormat("[yyyy/MM/dd HH:mm:ss]")
            val calendar = Calendar.getInstance()
            return df.format(calendar.getTime())
        }

    fun addError(tag: String, msg: String?) {
        addLog(tag, msg, null, true)
    }
}
