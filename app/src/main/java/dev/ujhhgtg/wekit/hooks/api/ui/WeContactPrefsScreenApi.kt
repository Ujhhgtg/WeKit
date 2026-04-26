package dev.ujhhgtg.wekit.hooks.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
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
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import dev.ujhhgtg.wekit.utils.reflection.resolve
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/联系人页面扩展")
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
            it.resolve().apply {
                firstMethod { name = "initView" }
                    .hookAfter {
                        val adapterInstance = adapterField.get(thisObject as Activity)
                        for (provider in providers) {
                            try {
                                val item = provider.getContactInfoItem(thisObject as Activity)
                                val pref = prefConstructor.newInstance(thisObject as Context)
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
                }.hookBefore {
                    val preference = args[1] ?: return@hookBefore
                    val key = prefKeyField.get(preference) as? String ?: return@hookBefore
                    for (provider in providers) {
                        try {
                            if (provider.onItemClick(thisObject as Activity, key)) {
                                result = true
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
        prefConstructor = Preference::class.resolve()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = Preference::class.resolve()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.makeAccessible()

        adapterField = MMPreference::class.resolve()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.makeAccessible()

        addPreferenceMethod = adapterField.type.resolve()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(Preference::class, Int::class)
            }.self

        setKeyMethod = Preference::class.resolve()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = Preference::class.resolve()
            .method {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
