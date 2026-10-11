package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.KvStore.prefOption
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.livephoto.WeKitLivePhotoBackend
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

/** Supplies the missing local media adapter while retaining WeChat's validation and remux. */
object UnlockLivePhotos : ClickableFeature(), IResolveDex {
    override val technicalId = "强开实况照片"
    override val nameRes = R.string.feature_unlock_live_photos_name
    override val descriptionRes = R.string.feature_unlock_live_photos_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.MOMENTS)

    private var forceWeKitBackend by prefOption("live_photo_force_wekit_backend", false)
    private var oplusCompatibility by prefOption("live_photo_oplus_compatibility", false)
    private var vivoCompatibility by prefOption("live_photo_vivo_compatibility", 0)
    // Changing the test preference never switches an in-flight operation or cached host object.
    private var processForceWeKit: Boolean? = null
    @SuppressLint("StaticFieldLeak")
    private var backend: WeKitLivePhotoBackend? = null
    private var currentBackendRes = R.string.live_photo_backend_inactive

    override val shouldEnableOnStartup: Boolean
        get() {
            // Latch even when disabled: editing the option before enabling still requires restart.
            if (processForceWeKit == null) processForceWeKit = forceWeKitBackend
            return super.shouldEnableOnStartup
        }

    private val classCore by dexClass { matcher { className = CORE } }
    private val classWrapper by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMLivePhotoCore", "initCore >> init result: ",
                "getLivePhotoMediaData >> parse data json error")
        }
    }
    private val fieldCore by dexField()
    private val fieldCoreVersion by dexField()
    private val fieldSysVersion by dexField()
    private val fieldSupport by dexField()
    private val fieldSnsSave by dexField()

    private val methodExpt by dexMethod {
        matcher {
            usingEqStrings("V2GetExpt result[%s] exptId[%d] key[%s] defStr[%s] cost[%d]")
            paramTypes("java.lang.String", "java.lang.String", "boolean")
            returnType = "java.lang.String"
        }
    }
    private val methodMultiExpt by dexMethod {
        matcher {
            usingEqStrings("V2GetMulitExpt result[%s] key[%s] def[%s] cost[%d] hadReport[%b %b] exptId[%d]")
            paramTypes("java.lang.String", "java.lang.String", "boolean", "boolean")
            returnType = "java.lang.String"
        }
    }
    private val methodRepairerGet by dexMethod {
        matcher {
            declaredClass { usingEqStrings("RepairerConfigThread", "ValueStrategy_") }
            usingEqStrings("String", "Int", "Long", "Float", "key", "defaultValue")
            paramTypes("java.lang.String", "java.lang.Object")
            returnType = "java.lang.Object"
        }
    }
    private val methodRepairerStrategy by dexMethod {
        matcher {
            declaredClass { usingEqStrings("RepairerConfigThread") }
            usingEqStrings("ValueStrategy_", "keyObj")
            paramCount = 1
            returnType = "int"
        }
    }
    private val methodSnsPublish by dexMethod {
        matcher {
            usingEqStrings("enablePublishLivePhoto", "SnsLivePhoto.ServerPublishConfig")
            paramCount = 0
            returnType = "boolean"
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val fields = classWrapper.data.fields.filter { Modifier.isStatic(it.modifiers) }
        fieldCore.setDescriptor(fields.single { it.typeName == CORE })
        fieldCoreVersion.setDescriptor(fields.single { it.typeName == "int" })
        fieldSysVersion.setDescriptor(fields.single { it.typeName == "java.lang.String" })
        fieldSupport.setDescriptor(fields.single { it.typeName == "boolean" })
        val saveReads = dexKit.findMethod {
            matcher { usingEqStrings("enableSaveLivePhoto") }
        }.asSequence().flatMap { it.usingFields }
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter { it.declaredClassName == methodSnsPublish.data.declaredClassName && it.typeName == "boolean" }
            .distinctBy { it.descriptor }.toList()
        fieldSnsSave.setDescriptor(saveReads.single())
        // The stable adapter must provide all six signatures on every supported host.
        val expected = mapOf(
            "initCore" to Pair(listOf("android.content.Context"), "int"),
            "isSupport" to Pair(emptyList(), "boolean"),
            "getCoreMetaData" to Pair(emptyList(), "java.lang.String"),
            "isLivePhoto" to Pair(listOf("java.util.List"), "java.util.HashMap"),
            "getVideoMetaData" to Pair(listOf("long", "java.lang.String"), "java.lang.String"),
            "exportLivePhoto" to Pair(listOf("java.lang.String"), "int"),
        )
        expected.forEach { (name, signature) ->
            classCore.data.methods.single {
                it.name == name && it.paramTypeNames == signature.first && it.returnTypeName == signature.second
            }
        }
    }

    override fun onEnable() {
        val forced = processForceWeKit ?: forceWeKitBackend.also { processForceWeKit = it }
        val core = classCore.clazz
        val system = if (forced) false else systemProvidesCore(core)
        val useWeKit = forced || system == false
        if (useWeKit) {
            val local = WeKitLivePhotoBackend
            check(local.initCore(HostInfo.application) == 0)
            backend = local
            installCoreHooks(core, local)
            currentBackendRes = R.string.live_photo_backend_wekit
        } else {
            currentBackendRes = if (system == true) R.string.live_photo_backend_system else R.string.live_photo_backend_unknown
        }
        installConfigHooks()
        // Loading the wrapper now lets its original initializer run under the selected adapter.
        val wrapper = classWrapper.clazz
        Class.forName(wrapper.name, true, wrapper.classLoader)
        if (useWeKit) initializeWrapper(core)
        methodSnsPublish.hookAfter {
            val supported = fieldSupport.field.getBoolean(null)
            fieldSnsSave.field.setBoolean(null, supported)
            result = supported
        }
        WeLogger.i(TAG, "enabled: forced=$forced system=$system builtin=$useWeKit")
    }

    private fun installConfigHooks() {
        listOf(methodExpt, methodMultiExpt).forEach { target ->
            target.hookBefore(priority = 100) {
                if (args[0] as String in EXPT_KEYS) result = "1"
            }
        }
        methodRepairerGet.hookBefore(priority = 100) {
            if (args[0] as String in REPAIRER_KEYS) result = 1
        }
        // A Repairer default can otherwise be replaced by a cloud value at the typed getter.
        methodRepairerStrategy.hookBefore(priority = 100) {
            if (args[0]!!.javaClass.name in REPAIRER_CLASSES) result = 1
        }
    }

    private fun installCoreHooks(core: Class<*>, local: WeKitLivePhotoBackend) {
        core.reflekt().firstMethod { name = "initCore"; parameters(Context::class) }.hookBefore {
            result = local.initCore(args[0] as Context)
        }
        core.reflekt().firstMethod { name = "isSupport"; parameters() }.hookBefore { result = local.isSupport() }
        core.reflekt().firstMethod { name = "getCoreMetaData"; parameters() }.hookBefore { result = local.getCoreMetaData() }
        core.reflekt().firstMethod { name = "isLivePhoto"; parameters(List::class) }.hookBefore {
            @Suppress("UNCHECKED_CAST")
            val ids = args[0] as List<Long>
            result = local.isLivePhoto(ids)
        }
        core.reflekt().firstMethod { name = "getVideoMetaData"; parameters(Long::class, String::class) }.hookBefore {
            result = local.getVideoMetaData(args[0] as Long, args[1] as String)
        }
        core.reflekt().firstMethod { name = "exportLivePhoto"; parameters(String::class) }.hookBefore {
            result = local.exportLivePhoto(args[0] as String, oplusCompatibility, vivoCompatibility)
        }
    }

    private fun initializeWrapper(core: Class<*>) {
        val instance = fieldCore.field.get(null) ?: core.reflekt().firstConstructor { parameterCount = 0 }
            .newInstance().also { fieldCore.field.set(null, it) }
        val init = instance.reflekt().firstMethod { name = "initCore"; parameters(Context::class) }
        if (init.invoke(HostInfo.application) as Int != 0) return
        val supported = instance.reflekt().firstMethod { name = "isSupport"; parameters() }.invoke() as Boolean
        if (!supported) return
        val metadata = JSONObject(instance.reflekt().firstMethod { name = "getCoreMetaData"; parameters() }.invoke() as String)
        if (metadata.optInt("coreVersion") == 0 || metadata.optString("wechatKey") != WECHAT_KEY) return
        fieldCoreVersion.field.setInt(null, metadata.getInt("coreVersion"))
        fieldSysVersion.field.set(null, metadata.optString("sysVersion"))
        fieldSupport.field.setBoolean(null, true)
        WeLogger.i(TAG, "initialized wrapper with WeKit metadata")
    }

    /** Only a known APK stub source allows automatic replacement; unknown loaders pass through. */
    private fun systemProvidesCore(core: Class<*>): Boolean? {
        val loader = core.classLoader
        if (loader == null || loader === Context::class.java.classLoader) return true
        if (loader !is BaseDexClassLoader) return null
        return try {
            val pathList = loader.reflekt().firstField { name = "pathList"; superclass() }.get()!!
            val elements = pathList.reflekt().firstField { name = "dexElements" }.get() as Array<*>
            val paths = elements.mapNotNull { element ->
                @Suppress("DEPRECATION")
                (element!!.reflekt().firstField { name = "dexFile" }.get() as DexFile?)?.name
            }
            val hostPaths = listOf(HostInfo.appInfo.sourceDir) + HostInfo.appInfo.splitSourceDirs.orEmpty()
            when {
                paths.isEmpty() -> null
                paths.all { path -> SYSTEM_PREFIXES.any(path::startsWith) } -> true
                paths.all { it in hostPaths } -> false
                else -> null
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "cannot establish LivePhotoCore origin: ${e.message}")
            null
        }
    }

    override fun onDisable() {
        if (backend != null) fieldSupport.field.setBoolean(null, false)
        backend = null
        currentBackendRes = R.string.live_photo_backend_inactive
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var forced by remember { mutableStateOf(forceWeKitBackend) }
            var oplusCompatible by remember { mutableStateOf(oplusCompatibility) }
            var vivoMode by remember { mutableStateOf(vivoCompatibility) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_unlock_live_photos_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.live_photo_force_wekit),
                                description = stringResource(R.string.live_photo_force_wekit_description),
                                checked = forced,
                                onCheckedChange = { forced = it; forceWeKitBackend = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.live_photo_oplus_compatibility),
                                description = stringResource(R.string.live_photo_oplus_compatibility_description),
                                checked = oplusCompatible,
                                onCheckedChange = { oplusCompatible = it; oplusCompatibility = it },
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.live_photo_vivo_compatibility),
                                description = stringResource(R.string.live_photo_vivo_compatibility_description),
                                value = vivoMode,
                                options = listOf(
                                    DropdownOption(0, stringResource(R.string.live_photo_vivo_compatibility_disabled)),
                                    DropdownOption(1, stringResource(R.string.live_photo_vivo_compatibility_dual_file)),
                                    DropdownOption(2, stringResource(R.string.live_photo_vivo_compatibility_single_file)),
                                    DropdownOption(3, stringResource(R.string.live_photo_vivo_compatibility_standard)),
                                ),
                                onValueChange = { vivoMode = it; vivoCompatibility = it },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.live_photo_current_backend),
                                description = stringResource(currentBackendRes),
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private const val TAG = "UnlockLivePhotos"
    private const val CORE = "com.motion.core.LivePhotoCore"
    private const val WECHAT_KEY = "303e1e50a7e5a84dc261223a0dd6a083"
    private val SYSTEM_PREFIXES = listOf("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/", "/my_product/")
    private val EXPT_KEYS = setOf(
        "clicfg_live_photo_switch", "clicfg_live_photo_extra_manufacturer",
        "clicfg_chatting_c2c_live_send_v4", "clicfg_chatting_c2c_live_preview_v2",
        "clicfg_sns_publish_live_photo_v2", "clicfg_sns_check_sys_live_photo", "clicfg_sns_save_live_photo",
    )
    private val REPAIRER_KEYS = setOf(
        "RepairerConfig_Chatting_C2C_Live_Send_V4_Int", "RepairerConfig_Chatting_C2C_Live_Preview_V2_Int",
        "RepairerConfig_SnsPublishLivePhoto_Int", "RepairerConfig_SnsSaveLivePhoto_Int", "RepairerConfig_SnsCheckSysLivePhoto_Int",
    )
    private val REPAIRER_CLASSES = setOf(
        "com.tencent.mm.repairer.config.chatting.RepairerConfigC2CLiveImageSend",
        "com.tencent.mm.repairer.config.chatting.RepairerConfigC2CLiveImagePreview",
        "com.tencent.mm.repairer.config.livephoto.RCSnsPublishLivePhoto",
        "com.tencent.mm.repairer.config.livephoto.RCSnsSupportSaveLivePhoto",
        "com.tencent.mm.repairer.config.livephoto.RCSnsSupportCheckSysLivePhoto",
    )
}
