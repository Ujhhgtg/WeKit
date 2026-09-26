package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcess
import org.json.JSONObject

object SpoofHostVersion : SwitchFeature(), IResolveDex {

    override val technicalId = "伪装宿主版本"
    override val nameRes = R.string.feature_spoof_host_version_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_spoof_host_version_description
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        ctorCgiLaunchWxaAppFunc1122.hookBefore {
            args[6] = 9999
        }

        methodPrivateOpenUrl.hookBefore {
            val data = args[1] as JSONObject
            val url = data.optString("url")
            if (UPGRADE_URLS.any {
                    url == it || url.startsWith("$it/") ||
                        url.startsWith("$it?") || url.startsWith("$it#")
                }) {
                // An empty URL takes the host's normal "fail" callback path. Skipping the
                // entire method would leave the Mini App's JS callback unresolved.
                data.put("url", "")
            }
        }
    }

    private val UPGRADE_URLS = listOf(
        "https://support.weixin.qq.com/update",
        "https://szsupport.weixin.qq.com/update",
    )

    private val methodPrivateOpenUrl by dexMethod {
        matcher {
            paramTypes(null, "org.json.JSONObject", "int")
            returnType = "void"
            usingEqStrings("private_openUrl", "rawUrl", "geta8key_open_webview_appid")
        }
    }

    private val ctorCgiLaunchWxaAppFunc1122 by dexConstructor {
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.CgiLaunchWxaApp|func:1122",
                "<init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] instanceId[%s] libVersion[%d], source:%s, launchMode:%d, migrate:%b, fallback:%b"
            )
        }
    }
}
