package moe.ouom.wekit.hooks.sdk.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.Initiator.loadClass
import moe.ouom.wekit.util.log.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/用户联系页面扩展")
class WeChatContactInfoAdapterItemHook : ApiHookItem() {

    companion object {
        private const val TAG = "ContactInfoAdapterItemHook"
        private val initCallbacks = CopyOnWriteArrayList<InitContactInfoViewCallback>()
        private val clickListeners = CopyOnWriteArrayList<OnContactInfoItemClickListener>()

        @Volatile
        private var isRefInitialized = false
        private lateinit var prefConstructor: Constructor<*>
        private lateinit var prefKeyField: Field
        private lateinit var adapterField: Field
        private lateinit var onPreferenceTreeClickMethod: Method
        private lateinit var addPreferenceMethod: Method
        private lateinit var setKeyMethod: Method
        private lateinit var setSummaryMethod: Method
        private lateinit var setTitleMethod: Method


        fun addInitCallback(callback: InitContactInfoViewCallback) {
            initCallbacks.add(callback)
        }

        fun removeInitCallback(callback: InitContactInfoViewCallback) {
            initCallbacks.remove(callback)
        }

        fun addClickListener(listener: OnContactInfoItemClickListener) {
            clickListeners.add(listener)
        }

        fun removeClickListener(listener: OnContactInfoItemClickListener) {
            clickListeners.remove(listener)
        }

    }

    fun interface InitContactInfoViewCallback {
        fun onInitContactInfoView(context: Activity): ContactInfoItem?
    }

    fun interface OnContactInfoItemClickListener {
        fun onItemClick(activity: Activity, key: String): Boolean
    }


    data class ContactInfoItem(val key: String, val title: String, val summary: String? = null, val position: Int = -1)

    override fun entry(classLoader: ClassLoader) {
        initReflection()
        hook(classLoader)
        hookItemClick(classLoader)
    }

    private fun initReflection() {
        if (isRefInitialized) return

        synchronized(this) {
            if (isRefInitialized) return

            val prefClass = loadClass("com.tencent.mm.ui.base.preference.Preference")
            prefConstructor = prefClass.getConstructor(Context::class.java)
            prefKeyField = prefClass.declaredFields.first { field ->
                field.type == String::class.java && !Modifier.isFinal(field.modifiers)
            }

            val contactInfoUIClass = loadClass("com.tencent.mm.plugin.profile.ui.ContactInfoUI")
            adapterField = contactInfoUIClass.superclass.declaredFields.first {
                BaseAdapter::class.java.isAssignableFrom(it.type)
            }.apply { isAccessible = true }
            onPreferenceTreeClickMethod = contactInfoUIClass.declaredMethods.first {
                it.name == "onPreferenceTreeClick"
            }

            val adapterClass = adapterField.type
            addPreferenceMethod = adapterClass.declaredMethods.first {
                !Modifier.isFinal(it.modifiers)
                        && it.parameterCount == 2 &&
                        it.parameterTypes[0] == prefClass
                        && it.parameterTypes[1] == Int::class.java
            }

            setKeyMethod = prefClass.declaredMethods.first {
                it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }

            val charSeqMethods = prefClass.declaredMethods.filter {
                it.parameterCount == 1 && it.parameterTypes[0] == CharSequence::class.java
            }

            // 可能需要之后维护 不稳定的方法
            setSummaryMethod = charSeqMethods.getOrElse(0) {
                throw RuntimeException("setTitle method not found")
            }
            setTitleMethod = charSeqMethods.getOrElse(1) {
                throw RuntimeException("setSummary method not found")
            }

            isRefInitialized = true
            WeLogger.i(
                TAG, """
                prefConstructor: $prefConstructor
                prefKeyField: $prefKeyField
                adapterField: $adapterField
                onPreferenceTreeClickMethod: $onPreferenceTreeClickMethod
                addPreferenceMethod: $addPreferenceMethod
                setKeyMethod: $setKeyMethod
                setSummaryMethod: $setSummaryMethod
                setTitleMethod: $setTitleMethod
            """.trimIndent()
            )
            WeLogger.i(TAG, "反射初始化完成")
        }
    }

    fun hook(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
                classLoader,
                "initView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapterInstance = adapterField.get(param.thisObject as Activity)
                        for (listener in initCallbacks) {
                            val item = listener.onInitContactInfoView(param.thisObject as Activity)
                            try {
                                val preference = prefConstructor.newInstance(param.thisObject as Context)
                                item?.let {
                                    setKeyMethod.invoke(preference, it.key)
                                    setTitleMethod.invoke(preference, it.title)
                                    it.summary?.let { summary -> setSummaryMethod.invoke(preference, summary) }
                                    addPreferenceMethod.invoke(adapterInstance, preference, it.position)
                                }
                            } catch (e: Exception) {
                                WeLogger.e(TAG, "添加条目失败: ${e.message}")
                            }
                        }
                    }
                }
            )

            WeLogger.i(TAG, "Hook 注册成功")
        } catch (e: Exception) {
            WeLogger.e(TAG, "Hook 失败 - ${e.message}", e)
        }
    }

    private fun hookItemClick(classLoader: ClassLoader) {
        XposedBridge.hookMethod(onPreferenceTreeClickMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val preference = param.args[1] ?: return
                val key = prefKeyField.get(preference) as? String
                if (key != null) {
                    for (listener in clickListeners) {
                        if (listener.onItemClick(param.thisObject as Activity, key)) {
                            param.result = true
                            break
                        }
                    }
                }
            }
        })
    }
}