package dev.ujhhgtg.wekit.features.items.entertain

import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.pet.PetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * User-facing desktop-pet entry. The toggle mounts/tears down the pet overlay
 * ([dev.ujhhgtg.wekit.pet.PetOverlayController]); long-press the pet to open its
 * info/treat panel. When WeAgent is also enabled, the pet's activity follows the
 * agent session state via [PetService.onAgentEvent].
 */
@Feature(
    name = "桌面宠物",
    categories = ["娱乐"],
    description = "Bongo Cat 桌宠: 摸头/拖拽/长按喂食, 随 WeAgent 会话状态变化。需要悬浮窗权限。",
)
object Pet : SwitchFeature() {

    override fun onEnable() {
        PetService.init()
        MainScope().launch(Dispatchers.Main) {
            PetService.setVisible(true)
        }
    }

    override fun onDisable() {
        MainScope().launch(Dispatchers.Main) {
            PetService.setVisible(false)
        }
    }
}
