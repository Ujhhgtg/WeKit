@file:Suppress("LocalVariableName")

package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import com.android.dx.DexMaker
import com.android.dx.FieldId
import com.android.dx.MethodId
import com.android.dx.TypeId
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.MemberUnhookHandle
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.byte
import dev.ujhhgtg.wekit.utils.reflection.char
import dev.ujhhgtg.wekit.utils.reflection.double
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import dev.ujhhgtg.wekit.utils.reflection.short
import dev.ujhhgtg.wekit.utils.reflection.void
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * IHookBridge implementation for Zygisk mode.
 *
 * For each hooked method, DexMaker generates a pair of methods preserving the
 * target's static/instance calling convention. Reference parameters and
 * return values are represented as Object, matching FunBox's loader-safe DEX
 * bridge:
 *
 *   bridge(T0 p0, T1 p1, …) -> R (instance receiver is implicit)
 *     Boxes primitive params into Object[], calls WekitHookBridgeRuntime.dispatch,
 *     unboxes the Object result back to R.
 *
 *   backup(T0 p0, T1 p1, …) -> R
 *     Placeholder body (throws). The native hook overwrites its ArtMethod with a
 *     copy of the target's original ArtMethod so calling it via reflection invokes
 *     the original unhooked code.
 *
 * The native art_hook_method() swaps the target's entry_point to the bridge's;
 * preserving the static bit is therefore part of the ABI contract.
 */
@Keep
internal class ZygiskHookBridge : IHookBridge {

    // ── IHookBridge metadata ──────────────────────────────────────────────────

    override val hookBridgeName: String = "WeKit/Zygisk"
    override val apiLevel: Int = 100
    override val frameworkName: String = "Zygisk"
    override val frameworkVersion: String = "via Magisk/KernelSU"
    override val frameworkVersionCode: Long = 0L
    override val isDeoptimizationSupported: Boolean = false

    private val hookCounterInternal = AtomicLong(0L)
    override val hookCounter: Long get() = hookCounterInternal.get()
    override val hookedMethods: Set<Member?> get() = WekitHookBridgeRuntime.hookedMembers()

    // ── Hook ─────────────────────────────────────────────────────────────────

    override fun hookMethod(
        member: Member,
        callback: IMemberHookCallback,
        priority: Int,
    ): MemberUnhookHandle {
        require(member is Method || member is Constructor<*>) {
            "hookMethod: unsupported member type ${member::class.java}"
        }
        require(!Modifier.isAbstract(member.modifiers)) {
            "hookMethod: cannot hook abstract member $member"
        }
        require(!Modifier.isNative(member.modifiers)) {
            "hookMethod: cannot hook native member $member"
        }
        require(!Proxy.isProxyClass(member.declaringClass)) {
            "hookMethod: cannot hook proxy member $member"
        }
        require(!Modifier.isInterface(member.declaringClass.modifiers)) {
            "hookMethod: cannot hook interface member $member"
        }

        val hookId: Long = synchronized(WekitHookBridgeRuntime.hookLock) {
            val existingId = WekitHookBridgeRuntime.getHookId(member)
            val id = if (existingId != null) {
                existingId
            } else {
                val bridge = generateBridgePair(member)

                val targetArt = nativeGetArtMethod(member)
                val backupArt = nativeGetArtMethod(bridge.backupMethod)
                val bridgeArt = nativeGetArtMethod(bridge.bridgeMethod)
                check(targetArt != 0L && backupArt != 0L && bridgeArt != 0L) {
                    "art_get_art_method returned 0 for $member"
                }

                // Register first so dispatch() has an entry before any calls.
                val id = WekitHookBridgeRuntime.register(member, bridge.backupMethod)

                // Write hookId into the generated class's static field.
                // Must happen before nativeHookMethod installs the entry_point.
                bridge.setHookId(id)

                val rc = nativeHookMethod(targetArt, backupArt, bridgeArt, id)
                if (rc != 0) {
                    WekitHookBridgeRuntime.unregister(id)
                    error("art_hook_method failed (rc=$rc) for $member")
                }

                hookCounterInternal.incrementAndGet()
                id
            }
            // Pair callback registration with the native hook lifecycle. A
            // concurrent final unhook must never remove the entry in the gap
            // between installing/looking up a hook and adding this callback.
            WekitHookBridgeRuntime.addCallback(id, callback, priority)
            id
        }

        return ZygiskUnhookHandle(member, callback, hookId)
    }

    // ── Deoptimize (unsupported) ──────────────────────────────────────────────

    override fun deoptimize(executable: Executable): Boolean = false

    /** Called after NativeLoader has loaded every module-provided native library. */
    internal fun hideLoadedModuleLibraries(): Boolean = nativeHideLoadedModuleLibraries()

    // ── Invoke original ───────────────────────────────────────────────────────

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? =
        WekitHookBridgeRuntime.invokeOriginal(method, thisObject, args)

    override fun <T> invokeOriginalConstructor(ctor: Constructor<T?>, thisObject: T, args: Array<Any?>) {
        WekitHookBridgeRuntime.invokeOriginal(ctor, thisObject, args)
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        if (WekitHookBridgeRuntime.getHookId(constructor) == null) {
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(*args) as T
        }

        // Constructor.newInstance() would re-enter the hooked ArtMethod. Match
        // LSPlant's origin path: allocate without initialization, then invoke
        // the copied original constructor through the backup ArtMethod.
        @Suppress("UNCHECKED_CAST")
        val instance = nativeAllocateInstance(constructor.declaringClass) as T
        val originArgs = arrayOfNulls<Any>(args.size)
        args.copyInto(originArgs)
        WekitHookBridgeRuntime.invokeOriginal(constructor, instance, originArgs)
        return instance
    }

    // ── BridgePair ────────────────────────────────────────────────────────────

    private data class BridgePair(
        val bridgeMethod: Method,
        val backupMethod: Method,
        /** Call this after registration to write the hookId into the generated class. */
        val setHookId: (Long) -> Unit,
    )

    // ── DexMaker bridge generation ────────────────────────────────────────────

    /**
     * Generates a class with two methods matching [target]'s calling convention.
     *
     * Bridge body (FunBox approach):
     *   1. Read static field `hookId`.
     *   2. Allocate `Object[] args` of length == target param count.
     *   3. For each param: box primitive → Object, or use reference directly.
     *   4. Call `WekitHookBridgeRuntime.dispatch(hookId, self, args)`.
     *   5. Unbox the returned Object to target return type (or return void).
     *
     * Backup body: throws UnsupportedOperationException — ArtMethod is overwritten
     * by the native hook with a copy of target's original ArtMethod, so the body
     * is never actually executed.
     */
    private fun generateBridgePair(target: Executable): BridgePair {
        val targetParams: Array<Class<*>> = target.parameterTypes
        val targetReturn: Class<*> = when (target) {
            is Method      -> target.returnType
            is Constructor<*> -> void   // constructors are void at the ART level
            else            -> void
        }

        val suffix       = java.lang.Long.toHexString(bridgeCounter.incrementAndGet())
        val fqn          = $$"dev.ujhhgtg.wekit.loader.entry.zygisk.WkBr$$$suffix"
        val descriptor   = "L${fqn.replace('.', '/')};"

        val dm = DexMaker()

        // ── TypeId helpers ────────────────────────────────────────────────────

        @Suppress("UNCHECKED_CAST")
        fun <T> tid(c: Class<T>): TypeId<T> = when (c) {
            int -> TypeId.INT       as TypeId<T>
            long -> TypeId.LONG      as TypeId<T>
            bool -> TypeId.BOOLEAN   as TypeId<T>
            byte -> TypeId.BYTE      as TypeId<T>
            char -> TypeId.CHAR      as TypeId<T>
            short -> TypeId.SHORT     as TypeId<T>
            float -> TypeId.FLOAT     as TypeId<T>
            double -> TypeId.DOUBLE    as TypeId<T>
            void -> TypeId.VOID      as TypeId<T>
            else -> TypeId.get(c)
        }

        val classId   = TypeId.get<Any>(descriptor)
        val OBJ       = TypeId.OBJECT as TypeId<Any>
        val OBJ_ARR   = TypeId.get<Array<Any?>>("[Ljava/lang/Object;")
        val PLONG     = TypeId.LONG        // TypeId<Long> — no cast needed (java.lang.Long = Kotlin Long)
        val INT_TID   = TypeId.INT         // TypeId<Int>  — no cast needed

        dm.declare(classId, fqn, Modifier.PUBLIC, OBJ)

        // static long hookId
        val hookIdFld = classId.getField(PLONG, "hookId") as FieldId<Any, Long>
        dm.declare(hookIdFld, Modifier.PUBLIC or Modifier.STATIC, 0L)

        // WekitHookBridgeRuntime.dispatch(long, Object, Object[]) -> Object
        val rtFqn = WekitHookBridgeRuntime::class.java.name.replace('.', '/')
        val rtType = TypeId.get<Any>("L$rtFqn;")
        val dispatchMid = rtType.getMethod(OBJ, "dispatch", PLONG, OBJ, OBJ_ARR) as MethodId<Any, Any>

        val isStatic = target is Method && Modifier.isStatic(target.modifiers)

        // FunBox deliberately avoids referring to host-private classes from the
        // generated DEX. Primitive types stay exact; every reference is Object.
        val dexParamClasses = Array(targetParams.size) { i ->
            if (targetParams[i].isPrimitive) targetParams[i] else Any::class.java
        }
        val dexReturnClass = when {
            targetReturn == Void.TYPE || targetReturn.isPrimitive -> targetReturn
            else -> Any::class.java
        }

        val allParamTids: Array<TypeId<*>> = Array(targetParams.size) { i ->
            tid(dexParamClasses[i])
        }
        val retTid = tid(dexReturnClass)

        // Wrapper types for boxing/unboxing
        data class BoxInfo(
            val wrapperTid: TypeId<*>,
            val valueOfMid: MethodId<*, *>,
            val unboxMid: MethodId<*, *>,
        )

        fun boxInfo(prim: Class<*>): BoxInfo? {
            if (!prim.isPrimitive || prim == Void.TYPE) return null
            @Suppress("UNCHECKED_CAST")
            val wrapperClass: Class<Any> = when (prim) {
                int     -> Int::class.java
                long    -> Long::class.java
                bool -> Boolean::class.java
                byte    -> Byte::class.java
                char    -> Char::class.java
                short   -> Short::class.java
                float   -> Float::class.java
                double  -> Double::class.java
                else -> return null
            } as Class<Any>
            val unboxName = when (prim) {
                int     -> "intValue"
                long    -> "longValue"
                bool -> "booleanValue"
                byte    -> "byteValue"
                char    -> "charValue"
                short   -> "shortValue"
                float   -> "floatValue"
                double  -> "doubleValue"
                else -> return null
            }
            val wTid    = tid(wrapperClass)
            val primTid = tid(prim)
            @Suppress("UNCHECKED_CAST")
            return BoxInfo(
                wrapperTid = wTid,
                valueOfMid = wTid.getMethod(wTid, "valueOf", primTid),
                unboxMid   = wTid.getMethod(primTid, unboxName),
            )
        }

        // ── Generate bridge / backup ──────────────────────────────────────────

        @Suppress("UNCHECKED_CAST")
        fun declareBridgeOrBackup(name: String, isBackup: Boolean) {
            val mid = classId.getMethod(retTid, name, *allParamTids) as MethodId<Any, Any>
            val modifiers = Modifier.PUBLIC or if (isStatic) Modifier.STATIC else 0
            val code = dm.declare(mid, modifiers)

            if (isBackup) {
                val usoeTid = TypeId.get<UnsupportedOperationException>(
                    "Ljava/lang/UnsupportedOperationException;")
                val strTid  = TypeId.STRING
                val exLocal  = code.newLocal(usoeTid)
                val msgLocal = code.newLocal(strTid)
                code.loadConstant(msgLocal, "backup not initialized")
                @Suppress("UNCHECKED_CAST")
                val usoeCtorMid = usoeTid.getConstructor(strTid) as MethodId<UnsupportedOperationException, Void>
                code.newInstance(exLocal, usoeCtorMid, msgLocal)
                code.throwValue(exLocal)
                return
            }

            // 1. Read hookId
            val hookIdLocal = code.newLocal(PLONG)
            code.sget(hookIdFld, hookIdLocal)

            // 2. Get self for dispatch. The receiver is implicit for an
            // instance method and absent for a static method.
            @Suppress("UNCHECKED_CAST")
            val selfLocal: com.android.dx.Local<Any> = if (isStatic) {
                val nl = code.newLocal(OBJ)
                code.loadConstant(nl as com.android.dx.Local<Nothing?>, null)
                nl
            } else {
                val receiver = code.getThis(classId)
                val nl = code.newLocal(OBJ)
                code.cast(nl, receiver)
                nl
            }

            // 3. Allocate Object[] args
            val sizeLocal = code.newLocal(INT_TID)
            code.loadConstant(sizeLocal, targetParams.size)
            val argsLocal = code.newLocal(OBJ_ARR)
            code.newArray(argsLocal, sizeLocal)

            // 4. Box each param into args[i]
            for (i in targetParams.indices) {
                val paramTid   = allParamTids[i]
                val paramLocal = code.getParameter(i, paramTid)
                val idxLocal   = code.newLocal(INT_TID)
                code.loadConstant(idxLocal, i)

                val bi = boxInfo(targetParams[i])
                if (bi != null) {
                    @Suppress("UNCHECKED_CAST")
                    val boxedLocal = code.newLocal(bi.wrapperTid as TypeId<Any>)
                    @Suppress("UNCHECKED_CAST")
                    code.invokeStatic(
                        bi.valueOfMid as MethodId<Any, Any>,
                        boxedLocal,
                        paramLocal as com.android.dx.Local<Any>,
                    )
                    code.aput(argsLocal, idxLocal, boxedLocal)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val objLocal = code.newLocal(OBJ)
                    code.cast(objLocal, paramLocal)
                    code.aput(argsLocal, idxLocal, objLocal)
                }
            }

            // 5. Call dispatch(hookId, self, args) -> Object
            val rawResultLocal = code.newLocal(OBJ)
            code.invokeStatic(dispatchMid, rawResultLocal, hookIdLocal, selfLocal, argsLocal)

            // 6. Return (unboxing as needed)
            when {
                targetReturn == Void.TYPE -> code.returnVoid()

                targetReturn.isPrimitive -> {
                    val bi = boxInfo(targetReturn)!!
                    @Suppress("UNCHECKED_CAST")
                    val wLocal = code.newLocal(bi.wrapperTid as TypeId<Any>)
                    code.cast(wLocal, rawResultLocal)
                    @Suppress("UNCHECKED_CAST")
                    val primLocal = code.newLocal(tid(targetReturn) as TypeId<Any>)
                    @Suppress("UNCHECKED_CAST")
                    code.invokeVirtual(
                        bi.unboxMid as MethodId<Any, Any>,
                        primLocal,
                        wLocal,
                    )
                    code.returnValue(primLocal)
                }

                else -> code.returnValue(rawResultLocal)
            }
        }

        declareBridgeOrBackup("bridge", isBackup = false)
        declareBridgeOrBackup("backup", isBackup = true)

        // ── Load generated DEX ────────────────────────────────────────────────

        val dexBytes = dm.generate()
        val cl = dalvik.system.InMemoryDexClassLoader(
            java.nio.ByteBuffer.wrap(dexBytes),
            ZygiskHookBridge::class.java.classLoader,
        )
        check(nativeTrustClassLoader(cl)) { "failed to trust generated hook dex" }
        val genClass = cl.loadClass(fqn)

        // Reference parameters are Object in the generated DEX, so reflection
        // must look up the same erased signature.
        val reflectParams: Array<Class<*>> = dexParamClasses
        val bridgeMethod = genClass.getDeclaredMethod("bridge", *reflectParams)
        val backupMethod = genClass.getDeclaredMethod("backup", *reflectParams)
        bridgeMethod.isAccessible = true
        backupMethod.isAccessible = true

        val hookIdStaticFld: Field = genClass.getDeclaredField("hookId").also { it.isAccessible = true }

        return BridgePair(
            bridgeMethod = bridgeMethod,
            backupMethod = backupMethod,
            setHookId    = { id -> hookIdStaticFld.setLong(null, id) },
        )
    }

    // ── Native JNI declarations (registered by the resident Zygisk SO) ───────

    companion object {
        private const val TAG = "ZygiskHookBridge"
        private val bridgeCounter = AtomicLong(0L)

        @JvmStatic private external fun nativeGetArtMethod(executable: Executable): Long
        @JvmStatic private external fun nativeHookMethod(
            targetArt: Long, backupArt: Long, bridgeArt: Long, hookId: Long): Int
        @JvmStatic private external fun nativeUnhookMethod(targetArt: Long, backupArt: Long): Int
        @JvmStatic private external fun nativeTrustClassLoader(classLoader: ClassLoader): Boolean
        @JvmStatic private external fun nativeAllocateInstance(clazz: Class<*>): Any
        @JvmStatic private external fun nativeHideLoadedModuleLibraries(): Boolean
    }

    // ── Unhook handle ─────────────────────────────────────────────────────────

    private inner class ZygiskUnhookHandle(
        override val member: Member,
        override val callback: IMemberHookCallback,
        private val hookId: Long,
    ) : MemberUnhookHandle {

        @Volatile private var active = true
        override val isHookActive: Boolean get() = active

        override fun unhook() {
            synchronized(WekitHookBridgeRuntime.hookLock) {
                if (!active) return
                active = false
                WekitHookBridgeRuntime.removeCallback(hookId, callback)
                val entry = WekitHookBridgeRuntime.getEntry(hookId)
                if (entry == null || entry.callbacks.isNotEmpty()) return

                val targetArt = nativeGetArtMethod(member as Executable)
                val backupArt = nativeGetArtMethod(entry.backupMethod)
                if (targetArt == 0L || backupArt == 0L ||
                    nativeUnhookMethod(targetArt, backupArt) != 0
                ) {
                    Log.e(TAG, "failed to unhook $member; keeping native dispatch entry")
                    return
                }
                WekitHookBridgeRuntime.retire(hookId)
                hookCounterInternal.decrementAndGet()
            }
        }
    }
}
