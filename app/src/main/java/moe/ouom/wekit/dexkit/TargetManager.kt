package moe.ouom.wekit.dexkit

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.text.TextUtils
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.util.Initiator.loadClass
import moe.ouom.wekit.util.common.Utils.findMethodByName
import moe.ouom.wekit.util.log.Logger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

object TargetManager {
    // 更新此版本可在宿主无版本更新的时候强制要求用户重新搜索被混淆的方法
    const val VERSION = 3

    ////////// Cache Keys //////////
    const val KEY_METHOD_SET_TITLE = "method_pref_setTitle"
    const val KEY_METHOD_SET_KEY = "method_pref_setKey"
    const val KEY_METHOD_GET_KEY = "method_pref_getKey"
    const val KEY_METHOD_ADD_PREF = "method_adapter_addPreference"
    const val KEY_CLASS_LUCKY_MONEY_RECEIVE = "cls_lucky_money_receive"
    const val KEY_CLASS_LUCKY_MONEY_OPEN = "cls_lucky_money_open"
    const val KEY_METHOD_GET_SEND_MGR = "method_get_send_mgr"

    ////////////////////////////////////////

    ////////// PKG //////////
    private const val PKG_PREFERENCE = "com.tencent.mm.ui.base.preference"
    private const val CLS_PREFERENCE = "$PKG_PREFERENCE.Preference"

    ////////////////////////////////////////

    /* =========================================================
     * Config Properties
     * ========================================================= */
    var isNeedFindTarget: Boolean
        get() = ConfigManager.cGetBoolean("isNeedFindTarget", true)
        set(value) = ConfigManager.cPutBoolean("isNeedFindTarget", value)

    var targetManagerVersion: Int
        get() = ConfigManager.cGetInt("TargetManager_VERSION", 0)
        set(value) = ConfigManager.cPutInt("TargetManager_VERSION", value)

    var lastWeChatVersion: String
        get() = ConfigManager.cGetString("LastWeChatVersion", "")
        set(value) = ConfigManager.cPutString("LastWeChatVersion", value)

    /* =========================================================
     * Core Logic
     * ========================================================= */
    fun runMethodFinder(
        ai: ApplicationInfo,
        cl: ClassLoader,
        activity: Activity,
        callback: ((String) -> Unit)?
    ) {
        thread {
            try {
                val result = getResult(ai, cl)
                activity.runOnUiThread { callback?.invoke(result) }
            } catch (e: Exception) {
                Logger.e("TargetManager: DexKit Fatal Error", e)
                activity.runOnUiThread { callback?.invoke("搜索失败: ${e.message}") }
            }
        }
    }

    private fun getResult(ai: ApplicationInfo, cl: ClassLoader): String {
        val out = StringBuilder()
        // 使用 use 自动关闭 bridge，确保资源释放
        DexKitBridge.create(ai.sourceDir)?.use { bridge ->
            out.append(">>> 开始分析微信代码...\n")
            searchPreferenceMethods(bridge, cl, out)
            searchAdapterMethods(bridge, cl, out)

            out.append("\n\n>>> 分析红包模块...\n")
            searchLuckyMoneyTargets(bridge, cl, out)
        } ?: run {
            return "DexKitBridge 初始化失败 (APK路径错误?)"
        }

        out.append("\n\n[SUCCESS] 搜索与配置更新完成")
        return out.toString()
    }

    @SuppressLint("NonUniqueDexKitData")
    private fun searchLuckyMoneyTargets(bridge: DexKitBridge, cl: ClassLoader, out: StringBuilder) {
        try {
            // 寻找 NetSceneQueue
            val senderOwnerClass = bridge.findClass {
                matcher {
                    methods {
                        add {
                            paramCount = 4
                            usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                        }
                    }
                }
            }.firstOrNull()

            if (senderOwnerClass != null) {
                val queueClassName = senderOwnerClass.name
                out.append("\n[OK] 找到发送管理类: $queueClassName")

                // 寻找静态单例方法
                val getMgrMethod = bridge.findMethod {
                    matcher {
                        modifiers = Modifier.STATIC
                        paramCount = 0
                        returnType = queueClassName
                    }
                }.firstOrNull()

                if (getMgrMethod != null) {
                    cacheMethod(getMgrMethod, cl, KEY_METHOD_GET_SEND_MGR, out)
                } else {
                    out.append("\n[FAIL] 未找到静态单例方法 (ReturnType: $queueClassName)")
                }

            } else {
                out.append("\n[FAIL] 未找到 NetSceneObserverOwner 特征类")
            }

            // 拆红包请求类
            val receiveClass = bridge.findClass {
                matcher {
                    methods {
                        add { usingStrings("MicroMsg.NetSceneReceiveLuckyMoney") }
                    }
                }
            }.firstOrNull()

            if (receiveClass != null) {
                ConfigManager.cPutString(KEY_CLASS_LUCKY_MONEY_RECEIVE, receiveClass.name)
                out.append("\n[OK] ReceiveLuckyMoney Class -> ${receiveClass.name}")
            } else {
                out.append("\n[FAIL] ReceiveLuckyMoney Class 未找到")
            }

            // 开红包请求类
            val openClass = bridge.findClass {
                matcher {
                    methods {
                        add { usingStrings("MicroMsg.NetSceneOpenLuckyMoney") }
                    }
                }
            }.firstOrNull()

            if (openClass != null) {
                ConfigManager.cPutString(KEY_CLASS_LUCKY_MONEY_OPEN, openClass.name)
                out.append("\n[OK] OpenLuckyMoney Class -> ${openClass.name}")
            } else {
                out.append("\n[FAIL] OpenLuckyMoney Class 未找到")
            }

        } catch (t: Throwable) {
            Logger.e("Search LuckyMoney Error", t)
            out.append("\n[ERROR] 搜索红包相关类时发生异常: ${t.message}")
        }
    }

    @SuppressLint("NonUniqueDexKitData")
    private fun searchPreferenceMethods(bridge: DexKitBridge, cl: ClassLoader, out: StringBuilder) {
        try {
            // 定位 Preference 类
            val prefClass = bridge.findClass {
                matcher { className = CLS_PREFERENCE }
            }.firstOrNull()

            if (prefClass == null) {
                out.append("\n[FAIL] 未找到 Preference 类: $CLS_PREFERENCE")
                return
            }

            // setKey
            val setKeyCandidates = prefClass.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("java.lang.String")
                    usingStrings("Preference")
                }
            }
            if (setKeyCandidates.isNotEmpty()) {
                cacheMethod(setKeyCandidates[0], cl, KEY_METHOD_SET_KEY, out)
            } else {
                out.append("\n[FAIL] setKey 未找到")
            }

            // setTitle
            val charSeqMethods = prefClass.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("java.lang.CharSequence")
                }
            }

            if (charSeqMethods.isEmpty()) {
                out.append("\n[FAIL] setTitle (CharSequence) 未找到")
            } else {
                val target = charSeqMethods.last()
                cacheMethod(target, cl, KEY_METHOD_SET_TITLE, out)
                if (charSeqMethods.size > 1) {
                    out.append("\n[INFO] setTitle 取最后一个 (共${charSeqMethods.size}个)")
                }
            }

            // getKey
            val getKeyCandidates = prefClass.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "java.lang.String"
                }
            }

            val targetGetKey = getKeyCandidates.firstOrNull { it.name != "toString" }

            if (targetGetKey != null) {
                // 手动拼装签名，避免不必要的反射
                val sig = "$CLS_PREFERENCE#${targetGetKey.name}"
                ConfigManager.cPutString(KEY_METHOD_GET_KEY, sig)
                out.append("\n[OK] $KEY_METHOD_GET_KEY -> ${targetGetKey.name}")
            } else {
                out.append("\n[FAIL] getKey 未找到")
            }

        } catch (t: Throwable) {
            Logger.e("Preference class error", t)
            out.append("\n[FAIL] Preference 类分析严重错误")
        }
    }

    @SuppressLint("NonUniqueDexKitData")
    private fun searchAdapterMethods(bridge: DexKitBridge, cl: ClassLoader, out: StringBuilder) {
        try {
            val adapterClass = bridge.findClass {
                searchPackages(PKG_PREFERENCE)
                matcher {
                    superClass = "android.widget.BaseAdapter"
                    methods {
                        add {
                            modifiers = Modifier.PUBLIC
                            name = "getView"
                            paramCount = 3
                        }
                        add {
                            name = "<init>"
                            paramCount = 3
                        }
                    }
                }
            }.firstOrNull()

            if (adapterClass == null) {
                out.append("\n[FAIL] Adapter 类未找到")
                return
            }

            // addPreference
            val candidates = adapterClass.findMethod {
                matcher {
                    paramTypes(CLS_PREFERENCE, "int")
                    returnType = "void"
                }
            }

            if (candidates.isEmpty()) {
                out.append("\n[FAIL] addPreference 方法未找到")
            } else {
                val target = candidates.first()
                cacheMethod(target, cl, KEY_METHOD_ADD_PREF, out)
                if (candidates.size > 1) {
                    out.append("\n[INFO] addPreference 发现多个，取第1个: ${target.name}")
                }
            }

        } catch (t: Throwable) {
            Logger.e("Adapter search error", t)
            out.append("\n[FAIL] Adapter 类分析出错")
        }
    }

    private fun cacheMethod(md: MethodData, cl: ClassLoader, key: String, out: StringBuilder) {
        try {
            val m = md.getMethodInstance(cl)
            val sig = "${m.declaringClass.name}#${m.name}"
            ConfigManager.cPutString(key, sig)
            out.append("\n[OK] $key -> ${m.name}")
        } catch (e: Exception) {
            out.append("\n[ERROR] 缓存失败 $key: ${e.message}")
            Logger.e("Cache error for $key", e)
        }
    }

    // Helper Functions
    fun removeAllMethodSignature() {
        val cfg = ConfigManager.getCache()
        val e = cfg.edit()
        cfg.all.keys.filter { it.startsWith("method_") }.forEach { e.remove(it) }
        e.apply()
    }

    fun requireMethod(key: String): Method? {
        return try {
            val sig = ConfigManager.cGetString(key, null)
            if (TextUtils.isEmpty(sig)) return null
            val p = sig!!.split("#")
            findMethodByName(loadClass(p[0]), p[1])
        } catch (t: Throwable) { null }
    }

    fun requireClassName(key: String): String = ConfigManager.cGetString(key, "")

    fun requireConstructor(key: String): Constructor<*>? {
        try {
            val sig = ConfigManager.cGetString(key, null)
            if (TextUtils.isEmpty(sig)) return null
            val p = sig!!.split("#")
            if (p.size < 2) return null
            val clazz = loadClass(p[0])
            val count = p[1].toInt()
            return clazz.declaredConstructors.firstOrNull { it.parameterCount == count }?.apply { isAccessible = true }
        } catch (t: Throwable) {
            return null
        }
    }
}