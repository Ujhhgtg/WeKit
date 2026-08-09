package dev.ujhhgtg.wekit.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds

@Feature(
    id = "NetScene 服务",
    nameRes = "feature_we_net_scene_api_name",
    categoryIds = [FeatureCategoryIds.API],
    descriptionRes = "feature_we_net_scene_api_description",
)
object WeNetSceneApi : ApiFeature(), IResolveDex {

    fun sendNetScene(netScene: Any) {
        val queue = classMmKernel.clazz.reflekt()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invokeStatic()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    val classMmKernel by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        }
    }

    val methodAddNetSceneToQueue by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
        }
    }
}
