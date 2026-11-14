package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.asPath
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

object WeVfsApi : ApiFeature() {
    override val technicalId = "微信虚拟文件系统服务"
    override val nameRes = R.string.feature_we_vfs_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_vfs_api_description

    private const val TAG = "WeVfsApi"

    private val classFileOp by dexClass {
        usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI")
    }

    private val methodInputStream by dexMethod {
        declaredClass(classFileOp.data.name)
        modifiers = 8
        paramTypes("java.lang.String")
        returnType = "java.io.InputStream"
    }

    private val methodOutputStream by dexMethod {
        declaredClass(classFileOp.data.name)
        modifiers = 8
        paramTypes("java.lang.String", "boolean")
        returnType = "java.io.OutputStream"
    }

    private val methodExists by dexMethod {
        declaredClass(classFileOp.data.name)
        modifiers = 8
        paramTypes("java.lang.String")
        returnType = "boolean"
        callerMethod {
            usingEqStrings(
                "getSnsVideoThumbImagePath",
                "com.tencent.mm.plugin.sns.model.SnsVideoLogic",
            )
        }
    }

    private val methodFileSize by dexMethod {
        declaredClass(classFileOp.data.name)
        modifiers = 8
        paramTypes("java.lang.String")
        returnType = "long"
        callerMethod {
            searchPackages("com.tencent.mm.plugin.sns.model")
            usingEqStrings("sightupload %d videopath %s sightFileSize %d md5 %s")
        }
    }

    private val methodResolveLocalPath by dexMethod {
        declaredClass(classFileOp.data.name)
        modifiers = 8
        paramTypes("java.lang.String", "boolean")
        returnType = "java.lang.String"
    }

    val methodResolveDownloadDirectory by dexMethod {
        searchPackages("com.tencent.mm.vfs")
        returnType = "java.lang.String"
        paramTypes("java.lang.String")
        declaredClass {
            usingStrings("VFS.VFSStrategy")
            usingStrings("Found wrong moving file: ")
            usingStrings("accountSalt")
        }
    }

    val methodNativeEntryDelete by dexMethod {
        declaredClass {
            usingString("VFS.NativeFileSystem")
            usingString(
                "Base directory exists but is not a directory, delete and proceed.Base path: ",
            )
        }
        paramTypes("java.lang.String")
        returnType = "boolean"
        invokeMethod {
            declaredClass = "java.io.File"
            name = "delete"
        }
    }

    val fieldNativeEntryBasePath by dexField {
        declaredClass(methodNativeEntryDelete.data.declaredClassName)
        type = "java.lang.String"
        modifiers = 16
    }

    fun inputStream(path: String): InputStream =
        methodInputStream.method.invoke(null, path) as InputStream

    fun outputStream(path: String, append: Boolean = false): OutputStream =
        methodOutputStream.method.invoke(null, path, append) as OutputStream

    fun exists(path: String): Boolean = methodExists.method.invoke(null, path) as Boolean

    fun fileSize(path: String): Long = methodFileSize.method.invoke(null, path) as Long

    fun resolveLocalPath(path: String, forWrite: Boolean = false): Path? =
        (methodResolveLocalPath.method.invoke(null, path, forWrite) as String?)?.asPath

    fun readBytes(path: String): ByteArray = inputStream(path).use { it.readBytes() }

    fun copy(source: String, target: String): Boolean =
        try {
            inputStream(source).use { input ->
                outputStream(target).use { output -> input.copyTo(output) }
            }
            exists(target)
        } catch (error: Exception) {
            WeLogger.e(TAG, "failed to copy VFS file: $source -> $target", error)
            false
        }
}
