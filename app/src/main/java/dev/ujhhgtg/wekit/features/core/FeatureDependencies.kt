package dev.ujhhgtg.wekit.features.core

import java.util.concurrent.ConcurrentHashMap

/** Transitive closure over the build-time scanned [GeneratedFeatureDependencies.DIRECT] references. */
object FeatureDependencies {

    private val transitiveCache = ConcurrentHashMap<BaseFeature, List<BaseFeature>>()

    /** Every feature [feature] reaches through direct references, breadth-first, without itself. */
    fun transitive(feature: BaseFeature): List<BaseFeature> = transitiveCache.getOrPut(feature) {
        val visited = linkedSetOf(feature)
        val queue = ArrayDeque(listOf(feature))
        while (queue.isNotEmpty()) {
            GeneratedFeatureDependencies.DIRECT[queue.removeFirst()].orEmpty().forEach { dependency ->
                // Cycles exist (e.g. two APIs referencing each other); the visited set ends them.
                if (visited.add(dependency)) queue.addLast(dependency)
            }
        }
        visited.drop(1)
    }
}
