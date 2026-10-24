package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethodQuery
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.matcher.StringSpec
import dev.ujhhgtg.wekit.dexkit.matcher.TypeSpec
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlin.io.path.div

object PreventModuleDataDeletion : SwitchFeature(), IResolveDex {

    override val technicalId = "阻止微信清理模块数据"
    override val nameRes = R.string.feature_prevent_module_data_deletion_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_module_data_deletion_description

    private val methodNativeFileSystemEntryDelete by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass =
                    ClassSpec(
                        usingStrings =
                            listOf(
                                StringSpec("VFS.NativeFileSystem"),
                                StringSpec(
                                    "Base directory exists but is not a directory, delete and proceed.Base path: ",
                                ),
                            ),
                    ),
                paramTypes = listOf(TypeSpec("java.lang.String")),
                returnType = ClassSpec(className = NameSpec("boolean")),
                invokeMethods =
                    listOf(
                        MethodSpec(
                            declaredClass = ClassSpec(className = NameSpec("java.io.File")),
                            name = NameSpec("delete"),
                        ),
                    ),
            ),
        )
    }

    // WeChat's VFS canonicalizes base paths (/data/user/0 -> /data/data), so compare canonically
    private val protectedRoots by lazy {
        listOf(
                KnownPaths.moduleRoot,
                KnownPaths.downloads,
                KnownPaths.internalStorage /
                    "Android" /
                    "data" /
                    HostInfo.packageName /
                    "Layout Inspect",
            )
            .map { it.toFile().canonicalPath }
    }

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            val relPath = args[0] as String
            val basePath =
                thisObject!!
                    .reflekt()
                    .firstField {
                        type = String::class
                        modifiers(Modifiers.FINAL)
                    }
                    .get() as String

            val path = if (relPath.isEmpty()) basePath else "$basePath/$relPath"
            if (protectedRoots.any { path == it || path.startsWith("$it/") }) {
                result = true
            }
        }
    }
}
