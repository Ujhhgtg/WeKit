package dev.ujhhgtg.wekit.bootstrap.identity

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.bootstrap.BootstrapLog
import org.luckypray.dexkit.DexKitBridge

data class CurrentIdentity(val wxId: String?, val alias: String?, val readState: String, val ready: Boolean)

/** Reads live host identity during startup and later account checks, without business features. */
class CurrentIdentityReader(private val hostClassLoader: ClassLoader) {
    private var core: CoreIdentityTargets? = null
    private var alias: StaticMethodTarget? = null
    private var resolved = false

    fun read(): CurrentIdentity {
        if (!resolved) {
            DexKitBridge.create(hostClassLoader, true).use { bridge ->
                core = IdentityTargets.resolveCore(bridge)
                try { alias = IdentityTargets.resolveAlias(bridge) }
                catch (error: Exception) { BootstrapLog.e("Identity", "Current alias lookup failed", error) }
            }
            resolved = true
        }
        val targets = core!!
        if (!(invoke(targets.ready) as Boolean)) return CurrentIdentity(null, null, "NOT_READY", false)
        val wxId = (invoke(targets.wxId) as String?).takeUnless { it.isNullOrEmpty() }
            ?: error("Current WeChat identity is unavailable despite live-ready state")
        var currentAlias: String? = null
        val aliasTarget = alias
        if (aliasTarget != null) {
            try { currentAlias = (invoke(aliasTarget) as String?).takeUnless { it.isNullOrEmpty() } }
            catch (error: Exception) { BootstrapLog.e("Identity", "Current alias read failed", error) }
        }
        return CurrentIdentity(wxId, currentAlias, if (currentAlias == null) "ALIAS_UNAVAILABLE" else "CURRENT", true)
    }

    private fun invoke(target: StaticMethodTarget): Any? =
        Class.forName(target.className, false, hostClassLoader).reflekt().firstMethod {
            name = target.methodName
            parameters()
        }.invoke()
}
