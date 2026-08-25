package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListenerV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequestV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResultV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApiV2

class MonetGeneratorEntrypointV2 : MonetGeneratorApiV2 {
    override fun generate(
        request: MonetGenerationRequestV2,
        listener: MonetGenerationListenerV2,
    ): MonetGenerationResultV2 = MonetGenerationPipeline().generate(request, listener)
}
