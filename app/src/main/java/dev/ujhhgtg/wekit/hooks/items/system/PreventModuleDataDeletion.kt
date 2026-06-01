package dev.ujhhgtg.wekit.hooks.items.system

import com.highcapable.kavaref.condition.type.Modifiers
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field

@HookItem(path = "系统与隐私/阻止微信清理模块数据", description = "阻止微信「设置 → 存储空间 → 清理」删除模块数据")
object PreventModuleDataDeletion : SwitchHookItem(), IResolvesDex {

    private val methodNativeFileSystemEntryDelete by dexMethod()
    private lateinit var basePathField: Field

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            val relPath = args[0] as String
            if (!::basePathField.isInitialized) {
                basePathField = thisObject.asResolver()
                    .firstField {
                        type = String::class
                        modifiers(Modifiers.FINAL)
                    }.self
            }
            val basePath = basePathField.get(thisObject) as String

            val path = "$basePath/$relPath"
            if (path.contains(BuildConfig.TAG) || path.contains("Layout Inspect")) {
                result = true
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodNativeFileSystemEntryDelete.find(dexKit) {
            matcher {
                declaredClass {
                    usingEqStrings("VFS.NativeFileSystem", "Base directory exists but is not a directory, delete and proceed.Base path: ")
                }

                paramTypes(String::class.java)
                returnType = "boolean"

                invokeMethods {
                    add {
                        declaredClass = "java.io.File"
                        name = "delete"
                    }
                }
            }
        }
    }
}
