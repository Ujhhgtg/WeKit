//@file:SuppressLint("ReplaceWithKavaRefExtension")
//
//package dev.ujhhgtg.wekit.utils.reflection
//
//import android.annotation.SuppressLint
//import de.robv.android.xposed.XC_MethodHook
//import de.robv.android.xposed.XposedBridge
//import dev.ujhhgtg.reflekt.utils.makeAccessible
//import dev.ujhhgtg.wekit.utils.WeLogger
//import java.lang.ref.WeakReference
//import java.util.Collections
//import java.util.concurrent.ConcurrentHashMap
//
//object ClassLoaderRegistry {
//
//    // Identity-keyed so two CLs with the same toString() don't collapse
//    private val _seen: MutableSet<ClassLoader> = Collections.newSetFromMap(ConcurrentHashMap())
//
//    /**
//     * Walks the parent chain, recording every node.
//     * Returns early if a node was already recorded (cycle/merge guard).
//     */
//    fun walkChain(cl: ClassLoader?) {
//        var node = cl
//        while (node != null && _seen.add(node)) {
//            node = runCatching { node.parent }.getOrNull()
//        }
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Strategy 1: well-known static roots
//    // ────────────────────────────────────────────────────────────────
//    @SuppressLint("BlockedPrivateApi")
//    fun collectStaticRoots() {
//        // BootClassLoader singleton (parent of everything)
//        runCatching {
//            Class.forName("java.lang.BootClassLoader")
//                .getDeclaredMethod("getInstance")
//                .makeAccessible()
//                .invoke(null) as ClassLoader
//        }.getOrElse {
//            // pre-8 fallback: parent of system CL is boot CL
//            runCatching { ClassLoader.getSystemClassLoader().parent }.getOrNull()
//        }?.let { walkChain(it) }
//
//        walkChain(ClassLoader.getSystemClassLoader())
//
//        // Thread context CL (often the app's PathClassLoader)
//        walkChain(Thread.currentThread().contextClassLoader)
//
//        // LSPosed's own injector ClassLoader
//        walkChain(XposedBridge::class.java.classLoader)
//
//        // This module's ClassLoader
//        walkChain(ClassLoaderRegistry::class.java.classLoader)
//
//        // Object/String: reveals BootClassLoader on API 26+
//        walkChain(Any::class.java.classLoader)
//        walkChain(String::class.java.classLoader)
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Strategy 2: ActivityThread — every LoadedApk in the process
//    // Catches: app CL, other modules' LoadedApk CLs, shared libraries
//    // ────────────────────────────────────────────────────────────────
//    @SuppressLint("DiscouragedPrivateApi")
//    @Suppress("UNCHECKED_CAST")
//    fun collectFromActivityThread() = runCatching {
//        val atCls = Class.forName("android.app.ActivityThread")
//        val at = atCls.getDeclaredMethod("currentActivityThread").invoke(null)
//
//        for (fieldName in listOf("mPackages", "mResourcePackages")) {
//            runCatching {
//                val map = atCls.getDeclaredField(fieldName)
//                    .apply { isAccessible = true }
//                    .get(at) as? Map<*, *> ?: return@runCatching
//
//                map.values.forEach { ref ->
//                    val loadedApk = (ref as? WeakReference<*>)?.get() ?: return@forEach
//                    runCatching {
//                        loadedApk.javaClass
//                            .getDeclaredField("mClassLoader")
//                            .apply { isAccessible = true }
//                            .get(loadedApk) as? ClassLoader
//                    }.getOrNull()?.let { walkChain(it) }
//                }
//            }.onFailure { XposedBridge.log("ClassLoaderRegistry: $fieldName: $it") }
//        }
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Strategy 3: all live threads' context ClassLoaders
//    // Catches: bg threads that inherited a different CL
//    // ────────────────────────────────────────────────────────────────
//    fun collectFromThreads() = runCatching {
//        var root = Thread.currentThread().threadGroup!!
//        while (root.parent != null) root = root.parent!!
//
//        val buf = arrayOfNulls<Thread>(root.activeCount() + 64)
//        val n = root.enumerate(buf, /* recurse = */ true)
//        repeat(n) { i -> buf[i]?.contextClassLoader?.let { walkChain(it) } }
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Strategy 4: VMDebug heap scan (API ≥ 29)
//    // Most complete: finds every live ClassLoader instance on the heap,
//    // including ones not reachable from any parent chain.
//    // Works in LSPosed without --debug flag on most ROMs.
//    // ────────────────────────────────────────────────────────────────
//    fun collectFromHeap() {
//        runCatching {
//            val vmDebug = Class.forName("dalvik.system.VMDebug")
//            // signature: getInstancesOfClasses(Class<?>[] classes, boolean countOnly)
//            val method = vmDebug.getDeclaredMethod(
//                "getInstancesOfClasses",
//                arrayOf<Class<*>>().javaClass,
//                Boolean::class.javaPrimitiveType!!
//            ).makeAccessible()
//
//            @Suppress("UNCHECKED_CAST")
//            val matrix = method.invoke(
//                null,
//                arrayOf(ClassLoader::class.java),
//                false // countOnly=false → return actual instances
//            ) as? Array<Array<*>> ?: return
//
//            // matrix[0] = all instances of ClassLoader (and subclasses via ART heap walk)
//            matrix.firstOrNull()
//                ?.filterIsInstance<ClassLoader>()
//                ?.forEach { walkChain(it) }
//
//        }.onFailure { XposedBridge.log("ClassLoaderRegistry: heap scan failed: $it") }
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Strategy 5: hook ClassLoader constructors
//    // Catches: all ClassLoaders created AFTER this hook is installed,
//    // including those from modules injected later by LSPosed.
//    // Install this FIRST before any other strategy.
//    // ────────────────────────────────────────────────────────────────
//    fun hookConstructors() {
//        // Hooking ClassLoader itself is sufficient: every concrete CL
//        // (PathClassLoader, DexClassLoader, InMemoryDexClassLoader,
//        //  LSPosed's ModuleClassLoader, etc.) calls super().
//        XposedBridge.hookAllConstructors(
//            ClassLoader::class.java,
//            object : XC_MethodHook() {
//                override fun afterHookedMethod(param: MethodHookParam) {
//                    (param.thisObject as? ClassLoader)?.let { walkChain(it) }
//                }
//            }
//        )
//    }
//
//    // ────────────────────────────────────────────────────────────────
//    // Accessors
//    // ────────────────────────────────────────────────────────────────
//    fun snapshot(): Set<ClassLoader> = _seen.toSet()
//
//    fun dump(tag: String = "ClassLoaderRegistry") {
//        val all = snapshot()
//        WeLogger.i(tag, "${all.size} ClassLoaders in this process")
//        all.sortedBy { it.javaClass.name }.forEachIndexed { i, cl ->
//            val id  = "@%08x".format(System.identityHashCode(cl))
//            val par = cl.parent?.let { "${it.javaClass.simpleName}@%08x".format(System.identityHashCode(it)) } ?: "null"
//            val detail = when (cl) {
//                is dalvik.system.BaseDexClassLoader ->
//                    runCatching {
//                        cl.javaClass.getDeclaredMethod("toString").invoke(cl)
//                    }.getOrDefault("")
//                else -> ""
//            }
//            WeLogger.i(tag, "  [$i] ${cl.javaClass.name}$id  parent=$par  $detail")
//        }
//    }
//}
