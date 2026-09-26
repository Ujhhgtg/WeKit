package dev.ujhhgtg.wekit.loader.entry.zygisk

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.HostLocalizedStrings
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.MemberUnhookHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * IHookBridge implementation for Zygisk mode. LSPlant owns ART manipulation,
 * generated bridges and backup methods; Kotlin owns callback dispatch.
 *
 * A member retains its LSPlant hook for the lifetime of the process. Removing
 * its last callback makes the bridge call the backup directly, so in-flight
 * invocations can never observe a backup invalidated by native unhooking.
 */
@Keep
class ArtHookBridge : IHookBridge {

    override val hookBridgeName: String get() = HostLocalizedStrings.get(R.string.loader_art_hook_name)
    override val frameworkName: String = "Zygisk"
    override val frameworkVersion: String = "v1"
    override val frameworkVersionCode: Long = 1
    override val isDeoptimizationSupported: Boolean = true

    override val hookCounter: Long get() = ArtHookBridgeRuntime.hookedMembers().size.toLong()
    override val hookedMethods: Set<Member?> get() = ArtHookBridgeRuntime.hookedMembers()

    override fun hookMethod(
        member: Member,
        callback: IMemberHookCallback,
        priority: Int,
    ): MemberUnhookHandle {
        require(member is Method || member is Constructor<*>) {
            "hookMethod: unsupported member type ${member::class.java}"
        }
        // LSPlant handles native methods, generated proxy methods and concrete
        // interface methods. Abstract declarations have no implementation to hook.
        require(!Modifier.isAbstract(member.modifiers)) {
            "hookMethod: cannot hook abstract member $member"
        }
        // LSPlant d8b5d1d uses Method.getReturnType() for every member declared
        // by a generated proxy class; passing a Constructor is invalid JNI usage.
        require(member !is Constructor<*> || !Proxy.isProxyClass(member.declaringClass)) {
            "hookMethod: this LSPlant version cannot hook generated proxy constructor $member"
        }

        val candidate = ArtHookBridgeRuntime.HookEntry(member)
        val registration = ArtHookBridgeRuntime.PrioritizedCallback(callback, priority)
        candidate.addCallback(registration)
        val existing = ArtHookBridgeRuntime.register(candidate)
        val entry = existing ?: candidate
        if (existing == null) {
            try {
                // No Java monitor is held across LSPlant's ART suspension.
                // The hooker and its first callback are published beforehand;
                // calls arriving before Hook returns wait for the backup.
                val backup = checkNotNull(nativeHookMethod(member, entry, callbackMethod)) {
                    "LSPlant failed to hook $member"
                }
                entry.completeInstallation(backup)
            } catch (t: Throwable) {
                ArtHookBridgeRuntime.unregisterFailed(entry)
                entry.failInstallation(t)
                throw t
            }
        } else {
            entry.awaitBackup()
            entry.addCallback(registration)
        }
        return ArtUnhookHandle(member, callback, entry, registration)
    }

    override fun deoptimize(executable: Executable): Boolean {
        ArtHookBridgeRuntime.getEntry(executable)?.awaitBackup()
        return nativeDeoptimize(executable)
    }

    /** Called after NativeLoader has loaded every module-provided native library. */
    fun hideLoadedModuleLibraries(): Boolean = nativeHideLoadedModuleLibraries()

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? =
        ArtHookBridgeRuntime.invokeOriginal(method, thisObject, args)

    override fun <T> invokeOriginalConstructor(ctor: Constructor<T?>, thisObject: T, args: Array<Any?>) {
        ArtHookBridgeRuntime.invokeOriginal(ctor, thisObject, args)
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        @Suppress("UNCHECKED_CAST")
        val entry = ArtHookBridgeRuntime.getEntry(constructor) ?:
            return constructor.newInstance(*args) as T

        // Constructor.newInstance() would re-enter even a logically unhooked
        // constructor. Allocate without initialization, then invoke its backup.
        entry.awaitBackup()
        @Suppress("UNCHECKED_CAST")
        val instance = nativeAllocateInstance(constructor.declaringClass) as T
        val originArgs = arrayOfNulls<Any>(args.size)
        args.copyInto(originArgs)
        entry.invokeOriginal(instance, originArgs)
        return instance
    }

    companion object {
        private val callbackMethod = ArtHookBridgeRuntime.HookEntry::class.java.getDeclaredMethod(
            "callback", Array<Any?>::class.java,
        )

        @JvmStatic private external fun nativeHookMethod(
            target: Executable, hooker: Any, callback: Method,
        ): Method?
        @JvmStatic private external fun nativeDeoptimize(executable: Executable): Boolean
        @JvmStatic private external fun nativeAllocateInstance(clazz: Class<*>): Any
        @JvmStatic private external fun nativeHideLoadedModuleLibraries(): Boolean
    }

    private class ArtUnhookHandle(
        override val member: Member,
        override val callback: IMemberHookCallback,
        private val entry: ArtHookBridgeRuntime.HookEntry,
        private val registration: ArtHookBridgeRuntime.PrioritizedCallback,
    ) : MemberUnhookHandle {

        override val isHookActive: Boolean get() = entry.callbacks.contains(registration)

        override fun unhook() {
            // Registration identity makes repeated/concurrent unhook idempotent
            // without removing another handle for the same callback object.
            entry.removeCallback(registration)
        }
    }
}
