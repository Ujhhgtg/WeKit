package dev.ujhhgtg.wekit.features.items.entertain

import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
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
    id = "桌面宠物",
    nameRes = "feature_pet_name",
    categoryIds = [FeatureCategoryIds.ENTERTAIN],
    descriptionRes = "feature_pet_description",
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
