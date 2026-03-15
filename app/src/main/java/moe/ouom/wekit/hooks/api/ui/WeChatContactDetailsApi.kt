package moe.ouom.wekit.hooks.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/用户联系页面扩展")
object WeChatContactDetailsApi : ApiHookItem() {

    private val initCallbacks = CopyOnWriteArrayList<InitContactInfoViewCallback>()
    private val clickListeners = CopyOnWriteArrayList<OnContactInfoItemClickListener>()

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

    fun interface InitContactInfoViewCallback {
        fun onInitContactInfoView(context: Activity): ContactInfoItem?
    }

    fun interface OnContactInfoItemClickListener {
        fun onItemClick(activity: Activity, key: String): Boolean
    }


    data class ContactInfoItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1
    )

    override fun onEnable() {
        initReflection()
        hook()
        hookItemClick()
    }

    private fun initReflection() {
        WeLogger.d("WeChatContactDetailsApi", "initReflection")

        val prefClass = "com.tencent.mm.ui.base.preference.Preference".toClass()

        prefConstructor = prefClass.asResolver()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = prefClass.asResolver()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self

        val contactInfoUIClass = "com.tencent.mm.plugin.profile.ui.ContactInfoUI".toClass()

        adapterField = contactInfoUIClass.asResolver()
            .firstField {
                superclass()
                modifiers { !it.contains(Modifiers.STATIC) }
                type { BaseAdapter::class.java.isAssignableFrom(it) }
            }.self

        onPreferenceTreeClickMethod = contactInfoUIClass.asResolver()
            .firstMethod {
                name = "onPreferenceTreeClick"
            }.self

        val adapterClass = adapterField.type

        addPreferenceMethod = adapterClass.asResolver()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(prefClass, Int::class)
            }.self

        setKeyMethod = prefClass.asResolver()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = prefClass.asResolver()
            .method {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) {
            throw RuntimeException("setSummary method not found")
        }
        setTitleMethod = charSeqMethods.getOrElse(1) {
            throw RuntimeException("setTitle method not found")
        }
    }

    fun hook() {
        "com.tencent.mm.plugin.profile.ui.ContactInfoUI".toClass().asResolver()
            .firstMethod { name = "initView" }
            .hookAfter { param ->
                val adapterInstance = adapterField.get(param.thisObject as Activity)
                for (listener in initCallbacks) {
                    val item = listener.onInitContactInfoView(param.thisObject as Activity)
                    val pref =
                        prefConstructor.newInstance(param.thisObject as Context)
                    item?.let {
                        setKeyMethod.invoke(pref, it.key)
                        setTitleMethod.invoke(pref, it.title)
                        it.summary?.let { summary ->
                            setSummaryMethod.invoke(
                                pref,
                                summary
                            )
                        }
                        addPreferenceMethod.invoke(
                            adapterInstance,
                            pref,
                            it.position
                        )
                    }
                }
            }
    }

    private fun hookItemClick() {
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