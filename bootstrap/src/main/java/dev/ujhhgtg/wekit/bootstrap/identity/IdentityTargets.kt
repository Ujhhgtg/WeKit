package dev.ujhhgtg.wekit.bootstrap.identity

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

data class StaticMethodTarget(val className: String, val methodName: String)
data class CoreIdentityTargets(val ready: StaticMethodTarget, val wxId: StaticMethodTarget)

/** Only DexKit metadata is used here; returned strings remain valid after the bridge is closed. */
object IdentityTargets {
    fun resolveCore(bridge: DexKitBridge): CoreIdentityTargets {
        val kernel = bridge.findClass {
            matcher { usingEqStrings("Kernel not initialized by MMApplication!") }
        }.single()
        val accountGetter = bridge.findMethod {
            matcher {
                declaredClass = kernel.name
                modifiers(Modifier.STATIC)
                paramCount = 0
                usingEqStrings("mCoreAccount not initialized!")
            }
        }.single()
        val coreAccount = accountGetter.returnTypeName
        // Enum constant field names are obfuscated; jadx reconstructs display names from these strings.
        val accountState = bridge.findClass {
            matcher {
                superClass = "java.lang.Enum"
                usingEqStrings("NotReady", "AccountHasReady")
            }
        }.single()
        val accountReady = bridge.findMethod {
            matcher {
                declaredClass = coreAccount
                paramCount = 0
                returnType = "boolean"
                usingFields {
                    add {
                        declaredClass = accountState.name
                        type = accountState.name
                        usingType = UsingType.Read
                        modifiers(Modifier.STATIC or Modifier.FINAL)
                    }
                    add {
                        declaredClass = coreAccount
                        type = accountState.name
                        usingType = UsingType.Read
                    }
                }
            }
        }.filterNot { Modifier.isStatic(it.modifiers) }.single()
        val ready = bridge.findMethod {
            matcher {
                declaredClass = kernel.name
                modifiers(Modifier.STATIC)
                paramCount = 0
                returnType = "boolean"
                invokeMethods { add { descriptor = accountReady.descriptor } }
                // The kernel wrapper also checks the CoreAccount extension-ready boolean.
                usingFields {
                    add {
                        declaredClass = coreAccount
                        type = "boolean"
                        usingType = UsingType.Read
                    }
                }
            }
        }.single()
        return CoreIdentityTargets(ready.target(), resolveConfigValue(bridge, 2))
    }

    fun resolveAlias(bridge: DexKitBridge): StaticMethodTarget = resolveConfigValue(bridge, 42)

    private fun resolveConfigValue(bridge: DexKitBridge, key: Int): StaticMethodTarget {
        val config = bridge.findClass {
            matcher { usingEqStrings("MicroMsg.ConfigStorageLogic", "get userinfo fail") }
        }.single()
        return bridge.findMethod {
            matcher {
                declaredClass = config.name
                modifiers(Modifier.STATIC)
                paramCount = 0
                returnType = "java.lang.String"
                usingNumbers(key)
            }
        }.single().target()
    }

    private fun MethodData.target(): StaticMethodTarget = StaticMethodTarget(declaredClassName, name)
}
