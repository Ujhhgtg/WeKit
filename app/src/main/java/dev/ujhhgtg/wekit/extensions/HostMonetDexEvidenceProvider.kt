package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.evidence.DexKitMonetEvidenceCollector
import dev.ujhhgtg.wekit.utils.reflection.withDexKit

internal object HostMonetDexEvidenceProvider : MonetDexEvidenceProvider {
    override fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> =
        withDexKit { bridge -> DexKitMonetEvidenceCollector.collect(bridge, candidates) }
}
