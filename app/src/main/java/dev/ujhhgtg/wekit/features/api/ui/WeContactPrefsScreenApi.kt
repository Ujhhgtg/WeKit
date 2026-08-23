package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.base.preference.MMPreference
import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

object WeContactPrefsScreenApi : ApiFeature() {

    override val technicalId = "联系人页面扩展"
    override val nameRes = R.string.feature_we_contact_prefs_screen_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    interface IContactInfoProvider {
        fun getContactInfoItem(activity: Activity): List<PreferenceItem>
        fun onItemClick(activity: Activity, key: String): Boolean
    }

    data class PreferenceItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1
    )

    private const val TAG = "WeContactPrefsScreenApi"

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
            it.reflekt().apply {
                firstMethod { name = "initView" }
                    .hookAfter {
                        val adapterInstance = adapterField.get(thisObject as Activity)
                        for (provider in providers) {
                            try {
                                val items = provider.getContactInfoItem(thisObject as Activity)
                                for (item in items) {
                                    val pref = prefConstructor.newInstance(thisObject as Context)
                                    setKeyMethod.invoke(pref, item.key)
                                    setTitleMethod.invoke(pref, item.title)
                                    item.summary?.let { summary -> setSummaryMethod.invoke(pref, summary) }
                                    addPreferenceMethod.invoke(adapterInstance, pref, item.position)
                                }
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
        prefConstructor = Preference::class.reflekt()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = Preference::class.reflekt()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.makeAccessible()

        adapterField = MMPreference::class.reflekt()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.makeAccessible()

        addPreferenceMethod = adapterField.type.reflekt()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(Preference::class, Int::class)
            }.self

        setKeyMethod = Preference::class.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = Preference::class.reflekt()
            .methods {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
