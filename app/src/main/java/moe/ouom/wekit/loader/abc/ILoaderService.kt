package moe.ouom.wekit.loader.abc

import androidx.annotation.Keep

@Keep
interface ILoaderService {
    val entryPointName: String

    val loaderVersionName: String

    val loaderVersionCode: Int

    val mainModulePath: String

    fun log(msg: String)

    fun log(tr: Throwable)

    fun queryExtension(key: String, vararg args: Any?): Any?

    var classLoaderHelper: IClassLoaderHelper?
}
