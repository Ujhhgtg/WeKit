package dev.ujhhgtg.wekit.hooks.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.isSubclassOf
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.base.preference.MMPreference
import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/用户联系页面扩展")
object WeContactPrefsScreenApi : ApiHookItem() {

    interface IContactInfoProvider {
        fun getContactInfoItem(activity: Activity): ContactInfoItem
        fun onItemClick(activity: Activity, key: String): Boolean
    }

    data class ContactInfoItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1
    )

    private val TAG = nameOf(WeContactPrefsScreenApi)

    private val providers = CopyOnWriteArrayList<IContactInfoProvider>()

    fun addProvider(provider: IContactInfoProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IContactInfoProvider) {
        providers.remove(provider)
    }

    private lateinit var prefConstructor: Constructor<*>
    private lateinit var prefKeyField: Field
    private lateinit var adapterField: Field
    private lateinit var addPreferenceMethod: Method
    private lateinit var setKeyMethod: Method
    private lateinit var setSummaryMethod: Method
    private lateinit var setTitleMethod: Method

    override fun onEnable() {
        initReflection()

        listOf(
            ContactInfoUI::class,
            ChatroomInfoUI::class
        ).forEach {
            it.asResolver().apply {
                firstMethod { name = "initView" }
                    .hookAfter { param ->
                        val adapterInstance = adapterField.get(param.thisObject as Activity)
                        for (provider in providers) {
                            try {
                                val item = provider.getContactInfoItem(param.thisObject as Activity)
                                val pref = prefConstructor.newInstance(param.thisObject as Context)
                                setKeyMethod.invoke(pref, item.key)
                                setTitleMethod.invoke(pref, item.title)
                                item.summary?.let { summary -> setSummaryMethod.invoke(pref, summary) }
                                addPreferenceMethod.invoke(adapterInstance, pref, item.position)
                            } catch (ex: Exception) {
                                WeLogger.e(
                                    TAG,
                                    "provider ${provider.javaClass.name} threw while providing contact info item",
                                    ex
                                )
                            }
                        }
                    }

                firstMethod {
                    name = "onPreferenceTreeClick"
                }.hookBefore { param ->
                    val preference = param.args[1] ?: return@hookBefore
                    val key = prefKeyField.get(preference) as? String ?: return@hookBefore
                    for (provider in providers) {
                        try {
                            if (provider.onItemClick(param.thisObject as Activity, key)) {
                                param.result = true
                                return@hookBefore
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initReflection() {
        val prefClass = Preference::class

        prefConstructor = prefClass.asResolver()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = prefClass.asResolver()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.also { it.isAccessible = true }

        adapterField = MMPreference::class.asResolver()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.also { it.isAccessible = true }

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

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
