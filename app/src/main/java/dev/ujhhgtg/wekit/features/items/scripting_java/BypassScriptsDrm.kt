package dev.ujhhgtg.wekit.features.items.scripting_java

import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "绕过部分脚本验证", categories = ["脚本 (Java)"], description = "尝试绕过轩心云脚本的抓包检测 & 授权验证 & 云黑检测\n没关系的, 你们继续圈你们的钱, 我继续写我的代码, 不喜欢就受着")
object BypassScriptsDrm : SwitchFeature() // actual implementation in JavaEngine
