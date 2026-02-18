package moe.ouom.wekit.hooks.item.example

import android.util.Log
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.dsl.resultNull
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge

/**
 * HookItem 的写法示例
 */

// 下面这一行在写功能的时候必须保留，否则 ksp 将无法标记此类，这里为了防止被扫描所以注释掉了
//@HookItem(path = "example/示例写法", desc = "展示新架构的简化写法")
class SimplifiedExample :
    BaseSwitchFunctionHookItem() /* 这里也可以继承 BaseClickableFunctionHookItem */, IDexFind {

    // DSL: Dex 方法委托（自动生成 key）
    private val MethodTarget by dexMethod()

    // DSL: Dex 类委托（自动生成 key）
    private val ExampleClass by dexClass()

    // ========== Dex 查找与缓存 ==========

    /**
     * Dex 查找逻辑
     *
     * 重要说明：
     * 1. 必须返回 Map<属性名, descriptor字符串>
     * 2. 系统会自动检测方法逻辑变化，当查找逻辑改变时会自动要求重新扫描
     * 3. 所有使用 dexMethod/dexClass 声明的属性都应该在这里搜索并返回
     * 4. 使用 find() 方法查找，allowMultiple=false 时找不到或找到多个会抛出异常
     * 5. 使用 allowMultiple=true 可以允许多个结果
     * 6. 使用 delegate.key 作为 Map 的键（自动生成）
     */
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找目标方法
        MethodTarget.find(dexKit, descriptors = descriptors) {
            matcher {
                name = "targetMethod"
                paramCount = 2
                usingStrings("some_string_constant")
            }
        }

        // 查找目标类
        ExampleClass.find(dexKit, descriptors = descriptors) {
            matcher {
                usingStrings("ExampleClassName")
            }
        }

        // 如果有多个方法需要查找，继续添加：
        // AnotherMethod.find(dexKit, descriptors = descriptors) { ... }

        return descriptors
    }

    // Hook 入口
    override fun entry(classLoader: ClassLoader) {
        // 日志输出请务必使用 `WeLogger`，他会自动添加 TAG，并且适配多种输出需求，如：
        WeLogger.i("SimplifiedExample: 日志输出请务必使用 `WeLogger`，他会自动添加 TAG，并且适配多种输出需求，如：")
        WeLogger.i("SimplifiedExample: 错误", Throwable())
        WeLogger.e("SimplifiedExample", "xxxxx")
        WeLogger.w("SimplifiedExample", "xxxxx")
        WeLogger.v("SimplifiedExample", "xxxxx")
        WeLogger.e("SimplifiedExample", 1230000000000L)
        WeLogger.w("SimplifiedExample", WeLogger.getStackTraceString())
        WeLogger.printStackTrace() // DEBUG 级别
        WeLogger.printStackTrace(Log.ERROR, "SimplifiedExample", "异常堆栈：")
        WeLogger.printStackTraceErr("SimplifiedExample", Throwable())


        // 方式 1: 使用全局优先级（推荐）
        MethodTarget.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    // ...
                    param.resultNull()
                }
            }
        }

        // 方式 2: 使用自定义优先级
        MethodTarget.toDexMethod(priority = 100) {
            hook {
                afterIfEnabled { param ->
                    // ...
                }
            }
        }

        // 方式 3: 使用 dexClass 委托直接访问 Class（推荐）
        // 直接使用 .clazz 访问器，自动反射获取 Class
        XposedHelpers.newInstance(
            ExampleClass.clazz,
            "param1", "param2"
        )

        // 方式 4: 这里拿 Hook A 作为例子 （使用全局 HOOK 优先级）
        val clsReceiveLuckyMoney: Class<*> =
            XposedHelpers.findClass("com.example.LuckyMoneyReceive", classLoader)
        val mOnGYNetEnd = XposedHelpers.findMethodExact(
            clsReceiveLuckyMoney,
            "A",
            Int::class.javaPrimitiveType,
            String::class.java,
            JSONObject::class.java
        )

        val h1 = hookAfter(mOnGYNetEnd) { param ->
            // ...
        }

        // 可选：如需取消Hook，调用 h1.unhook()
        h1.unhook()


        // 方式 5: 这里拿 Hook B 作为例子 （使用自定义 HOOK 优先级）
        val clsReceiveLuckyMoney2: Class<*> =
            XposedHelpers.findClass("com.example.LuckyMoneyReceive", classLoader)
        val mOnGYNetEnd2 = XposedHelpers.findMethodExact(
            clsReceiveLuckyMoney2,
            "B",
            Int::class.javaPrimitiveType,
            String::class.java,
            JSONObject::class.java
        )

        hookAfter(mOnGYNetEnd2, priority = 50) { param ->
            // ...
        }

        // 方式 6: 带执行优先级的 hook 构造方法执行后
        val targetClass = XposedHelpers.findClass("com.example.TestClass", classLoader)
        val h2 = hookAfter(
            clazz = targetClass,
            priority = 50,
            action = {

            }
            // 无参构造方法，无需传parameterTypesAndCallback参数
        )

        // 可选：如需取消Hook，调用 h2.unhook()
        h2.unhook()

        // 方式 7: 自动 Hook 该类下所有同名的方法
        hookBefore(targetClass, "targetMethod") { param ->
            // ....
        }

        // 此处不再举例....
    }

    override fun unload(classLoader: ClassLoader) {
        // 在这里清理资源
    }

    // 若继承 BaseClickableFunctionHookItem，可以重写此方法来定义点击事件
//    override fun onClick(context: Context?) {
//        WeLogger.i("onClick")
//        super.onClick(context)
//    }
}
