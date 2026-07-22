package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.lang.reflect.Modifier
import java.math.BigInteger

@Feature(name = "朋友圈菜单增强扩展", categories = ["API"], description = "为朋友圈消息长按菜单提供添加菜单项功能")
object WeMomentsContextMenuApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeMomentsContextMenuApi"

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val shouldShow: (context: MomentsContext, itemId: Int) -> Boolean,
        val onClick: (context: MomentsContext) -> Unit
    )

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: Any?,
        val timelineObject: Any?,
        val source: Any? = null
    )

    private val methodOnCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings(
                "MicroMsg.TimelineOnCreateContextMenuListener",
                "onMMCreateContextMenu error"
            )
        }
    }
    private val methodOnItemSelected by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings(
                "delete comment fail!!! snsInfo is null",
                "send photo fail, mediaObj is null",
                "mediaObj is null, send failed!"
            )
        }
    }
    private val methodImproveOnItemSelectedRegister2 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.sns.ui.improve.item.click")
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick${'$'}register${'$'}2"
            )
        }
    }
    private val methodImproveOnItemSelectedRegister3 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.sns.ui.improve.item.click")
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick${'$'}register${'$'}3"
            )
        }
    }
    private val methodImproveMultiPhotoOnItemSelected by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.sns.ui.improve.item.click")
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.ImproveMultiPhotoClick${'$'}register${'$'}1${'$'}1${'$'}1"
            )
        }
    }
    private val methodSnsInfoStorage by dexMethod {
        matcher {
            paramCount(1)
            paramTypes("java.lang.String")
            usingStrings(
                "getByLocalId",
                "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"
            )
            returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
        }
    }
    private val methodGetSnsInfoStorage by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            modifiers = Modifier.STATIC
            returnType(methodSnsInfoStorage.method.declaringClass)
            paramCount(0)
            usingStrings(
                "com.tencent.mm.plugin.sns.model.SnsCore",
                "getSnsInfoStorage"
            )
        }
    }

    override fun onEnable() {
        methodOnCreateMenu.method.hookAfter {
            handleCreateMenu(this)
        }

        methodOnItemSelected.method.hookAfter {
            handleSelectMenu(this)
        }

        hookOptionalImproveSelect(methodImproveOnItemSelectedRegister2, "register2")
        hookOptionalImproveSelect(methodImproveOnItemSelectedRegister3, "register3")
        hookOptionalImproveSelect(methodImproveMultiPhotoOnItemSelected, "multiPhoto")
    }

    private fun hookOptionalImproveSelect(target: DexMethodDelegate, label: String) {
        runCatching {
            if (target.isPlaceholder) return
            target.method.hookAfter {
                handleSelectMenu(this)
            }
        }.onFailure {
            WeLogger.w(TAG, "Improve Moments menu item selected hook unavailable ($label): ${it.message}")
        }
    }

    private fun handleCreateMenu(param: HookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu? ?: return
        val context = resolveContext(param.thisObject!!)

        for (item in menuItems.values.flatten()) {
            try {
                if (context != null && !item.shouldShow(context, item.id)) continue
                menu.reflekt()
                    .firstMethod {
                        parameters(Int::class, CharSequence::class, Drawable::class)
                    }
                    .invoke(item.id, item.text, item.drawable)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "shouldShow/add callback failed", e)
            }
        }
    }

    private fun handleSelectMenu(param: HookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return
        val clickedId = menuItem.itemId
        if (menuItems.values.flatten().none { it.id == clickedId }) return

        val context = resolveContext(param.thisObject!!)
        if (context == null) {
            WeLogger.w(TAG, "failed to resolve Moments context, listener=${param.thisObject!!.javaClass.name}, item=$clickedId")
            return
        }

        for (item in menuItems.values.flatten()) {
            try {
                if (item.id == clickedId) {
                    item.onClick(context)
                    param.result = null
                    return
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onSelect callback failed", e)
            }
        }
    }

    private fun resolveContext(listener: Any): MomentsContext? {
        resolveImproveContext(listener)?.let { return it }

        val reflected = listener.reflekt()
        val activity = reflected.firstFieldOrNull {
            type = Activity::class
        }?.get() as? Activity ?: return null

        val timeLineObject = reflected.firstFieldOrNull {
            type = "com.tencent.mm.protocal.protobuf.TimeLineObject"
        }?.get()

        val snsInfo = resolveSnsInfo(listener, timeLineObject)
        if (snsInfo == null) {
            WeLogger.w(
                TAG,
                "snsInfo is null, activity=${activity.javaClass.name}, listener=${listener.javaClass.name}, " +
                        "timeline=${describeTimeline(timeLineObject)}, stringFields=${readStringFields(listener)}"
            )
        }
        return MomentsContext(activity, snsInfo, timeLineObject, listener)
    }

    private fun resolveImproveContext(listener: Any): MomentsContext? {
        val click = findObjectInFields(listener) { obj -> hasImproveInfoField(obj) } ?: return null
        val activity = click.reflekt().firstFieldOrNull {
            type { Context::class.java.isAssignableFrom(it) }
        }?.get() as? Activity ?: return null

        val info = findObjectInFields(click) { obj ->
            hasNoArgMethodReturning(obj, "com.tencent.mm.plugin.sns.storage.SnsInfo") &&
                    hasNoArgMethodReturning(obj, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        } ?: return null

        val snsInfo = invokeNoArgReturning(info, "com.tencent.mm.plugin.sns.storage.SnsInfo")
        val timeLineObject = invokeNoArgReturning(info, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        if (snsInfo == null || timeLineObject == null) {
            WeLogger.w(
                TAG,
                "failed to resolve Improve Moments context values, listener=${listener.javaClass.name}, " +
                        "click=${click.javaClass.name}, info=${info.javaClass.name}, snsInfo=${snsInfo?.javaClass?.name}, " +
                        "timeline=${timeLineObject?.javaClass?.name}"
            )
        }
        return MomentsContext(activity, snsInfo, timeLineObject, listener)
    }

    private fun resolveSnsInfo(listener: Any, timeLineObject: Any?): Any? {
        for (localId in readStringFields(listener)) {
            getSnsInfoByLocalId(localId)?.let { return it }
        }

        readTimelineSnsId(timeLineObject)?.let { snsId ->
            WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { return it }
        }

        for (snsId in readLongFields(listener)) {
            WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { return it }
        }

        return null
    }

    private fun getSnsInfoByLocalId(localId: String): Any? {
        if (localId.isBlank()) return null
        return runCatching {
            val storage = methodGetSnsInfoStorage.method.invoke(null)
            methodSnsInfoStorage.method.invoke(storage, localId)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to get Moments snsInfo by localId=$localId", error)
            null
        }
    }

    private fun readStringFields(target: Any): List<String> {
        return runCatching {
            target.reflekt().fields {
                type = BString
                modifiers { !it.contains(Modifiers.FINAL) && !it.contains(Modifiers.STATIC) }
            }.mapNotNull { it.get() as? String }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrElse {
            WeLogger.e(TAG, "failed to read Moments context string fields", it)
            emptyList()
        }
    }

    private fun readLongFields(target: Any): List<Long> {
        return runCatching {
            target.javaClass.declaredFields.mapNotNull { field ->
                val modifiers = field.modifiers
                if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) return@mapNotNull null
                if (field.type != Long::class.javaPrimitiveType && field.type != Long::class.javaObjectType) return@mapNotNull null
                field.isAccessible = true
                (field.get(target) as? Number)?.toLong()?.takeIf { it != 0L }
            }.distinct()
        }.getOrElse {
            WeLogger.e(TAG, "failed to read Moments context long fields", it)
            emptyList()
        }
    }

    private fun readTimelineSnsId(timeLineObject: Any?): Long? {
        if (timeLineObject == null) return null
        val id = runCatching {
            timeLineObject.reflekt().firstFieldOrNull { name = "Id" }?.get() as? String
        }.getOrNull() ?: return null
        return runCatching { BigInteger(id).toLong() }.getOrNull()
    }

    private fun findObjectInFields(target: Any, predicate: (Any) -> Boolean): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                val modifiers = field.modifiers
                if (Modifier.isStatic(modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue
                if (predicate(value)) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun hasImproveInfoField(target: Any): Boolean {
        return findObjectInFields(target) { obj ->
            hasNoArgMethodReturning(obj, "com.tencent.mm.plugin.sns.storage.SnsInfo") &&
                    hasNoArgMethodReturning(obj, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        } != null
    }

    private fun hasNoArgMethodReturning(target: Any, returnTypeName: String): Boolean =
        target.javaClass.methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType.name == returnTypeName
        }

    private fun invokeNoArgReturning(target: Any, returnTypeName: String): Any? =
        target.javaClass.methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && method.returnType.name == returnTypeName
        }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrElse {
                WeLogger.e(TAG, "failed to invoke Moments context method returning $returnTypeName", it)
                null
            }
        }

    private fun describeTimeline(timeLineObject: Any?): String {
        if (timeLineObject == null) return "null"
        return runCatching {
            val id = timeLineObject.reflekt().firstFieldOrNull { name = "Id" }?.get() as? String
            val desc = timeLineObject.reflekt().firstFieldOrNull { name = "ContentDesc" }?.get() as? String
            "id=$id, descLen=${desc?.length ?: 0}"
        }.getOrDefault(timeLineObject.javaClass.name)
    }
}
