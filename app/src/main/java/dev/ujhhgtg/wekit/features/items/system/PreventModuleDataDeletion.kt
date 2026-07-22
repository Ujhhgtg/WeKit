package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Field

@Feature(name = "阻止微信清理模块数据", categories = ["系统与隐私"], description = "阻止微信「设置 → 存储空间 → 清理」删除模块数据")
object PreventModuleDataDeletion : SwitchFeature(), IResolveDex {

    private val methodNativeFileSystemEntryDelete by dexMethod {
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
    private lateinit var basePathField: Field

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            val relPath = args[0] as String
            if (!::basePathField.isInitialized) {
                basePathField = thisObject!!.reflekt()
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
}
