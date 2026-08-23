package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WeStartActivityApi : ApiFeature() {

    override val technicalId = "活动启动监听服务"
    override val nameRes = R.string.feature_we_start_activity_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_start_activity_api_description

    fun interface IStartActivityListener {
        fun onStartActivity(param: HookParam, intent: Intent)
    }

    private const val TAG = "WeStartActivityApi"

    private val listeners = CopyOnWriteArrayList<IStartActivityListener>()

    fun addListener(listener: IStartActivityListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: IStartActivityListener) {
        listeners.remove(listener)
    }

    override fun onEnable() {
        listOf(
            Activity::class.java,
            ContextWrapper::class.java
        ).forEach { clazz ->
            clazz.declaredMethods.forEach {
                if (it.name != "startActivity" && it.name != "startActivityForResult") {
                    return@forEach
                }
                it.hookBefore {
                    handleStartActivity(this)
                }
            }
        }
    }

    private fun handleStartActivity(param: HookParam) {
        val intent = param.args[0] as? Intent ?: param.args[1] as? Intent
        if (intent == null) {
            WeLogger.w(TAG, "startActivity called but no Intent found in arguments")
            return
        }

        if (intent.getBooleanExtra(BuildConfig.TAG, false)) {
            return
        }

        listeners.forEach { listener ->
            try {
                listener.onStartActivity(param, intent)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "listener threw an exception: ${e.message}")
            }
        }
    }
}
