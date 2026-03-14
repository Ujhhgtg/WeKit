package moe.ouom.wekit.hooks.items.system

import android.annotation.SuppressLint
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.host.HostInfo
import java.nio.file.FileSystemException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

@HookItem(path = "系统与隐私/禁用应用热更新", desc = "禁止应用热更新, 避免被强制更新到不兼容版本")
object DisableHostHotUpdates : SwitchHookItem() {

    @SuppressLint("SdCardPath")
    @OptIn(ExperimentalPathApi::class)
    override fun onLoad() {
        try {
            val file = Path("/data/data/${HostInfo.packageName}/tinker")
            if (file.exists()) {
                file.deleteRecursively()
            }
        } catch (_: FileSystemException) {
        }

        val tinkerCls =
            "com.tencent.tinker.loader.shareutil.ShareTinkerInternals".toClass()

        tinkerCls.asResolver()
            .method {
                name {
                    it.startsWith("isTinkerEnabled")
                }
            }
            .forEach {
                it.hookBefore { param ->
                    param.result = false
                }
            }
    }
}