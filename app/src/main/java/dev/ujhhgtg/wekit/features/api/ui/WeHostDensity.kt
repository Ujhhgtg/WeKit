package dev.ujhhgtg.wekit.features.api.ui

import android.util.DisplayMetrics
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature

/**
 * 提供微信 MMDensityManager 适配后的显示指标 —— 与微信自身 MMActivity 构造适配 Resources
 * 时调用的静态 `d()` 是同一个方法。模块设置页等借宿主的 Activity 用的是框架原生 Resources，
 * 弹出的 Compose 对话框因此比微信内的对话框大一圈；这里按模块标准 Dex 解析 + 缓存流程解析
 * 该方法，供 [dev.ujhhgtg.wekit.ui.utils.showComposeDialog] 对齐密度。
 */
@Feature(name = "宿主显示密度", categories = ["API"], description = "向模块 UI 提供微信适配后的显示指标")
object WeHostDensity : ApiFeature(), IResolveDex {

    private val methodGetTargetDisplayMetrics by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.MMDensityManager", "screenResolution_target_field")
            }
            modifiers = Modifiers.PUBLIC or Modifiers.STATIC
            returnType = DisplayMetrics::class.java.name
            paramCount = 0
        }
    }

    /** WeChat 当前适配后的显示指标副本；解析失败或尚未解析完成时为 null。 */
    fun targetDisplayMetrics(): DisplayMetrics? = runCatching {
        val delegate = methodGetTargetDisplayMetrics
        if (delegate.isPlaceholder) return null
        val metrics = delegate.method.invoke(null) as? DisplayMetrics ?: return null
        DisplayMetrics().apply { setTo(metrics) }
    }.getOrNull()
}
