package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "禁用微信进程状态检测器", categories = ["系统与隐私"], description = "微信会在后台每隔一段时间检测微信主进程状态, 并在特定条件下故意抛出异常结束主进程\n虽然我不知道这玩意有啥用和是否应该关掉, 但「崩溃拦截」会把这玩意算进去, 有点烦, 所以如果你想关的话这里可以关")
object NerfBackgroundProcessChecker : SwitchFeature(), IResolveDex {

    private val methodPerformProcessCheck by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AbstractProcessChecker", "pass this check,because request is null! ????")
        }
    }

    override fun onEnable() {
        methodPerformProcessCheck.hookBefore {
            result = null
        }
    }
}
