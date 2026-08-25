package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 跳过小程序激励广告。
 *
 * 这类广告是用户主动点击的: 短暂加载后触发一次长震, 然后一个内嵌的精简版小程序/网页
 * (微信广告 SDK 里的 MB 奖励页) 从底部滑入覆盖原小程序, 看完倒计时后小程序才发奖励。
 * 8.0.76 群收集实际遇到的是「试玩 (playable)」奖励广告: 试玩包下载/注入/首帧由 MB
 * 运行时管理, WAService 侧的广告 SDK (WAAppAd.js / WAGameAd.js, 远程 commlib SDK 子包)
 * 能收到的唯一「广告已展示」信号是 onPlayableStatusChangeNew action=1
 * (PLAYABLE_FIRST_FRAME_READY)。
 *
 * 注入链路 (反编译确认, Java 侧稳定):
 *   loadLibFiles -> f.j() -> iCommLibReader.a()/H0() -> e3.h(script) -> V8 执行
 * 本实现:
 * 1. 拦截 H0() (FD 直读, 无法改文本), 强制 loadLibFiles 走字符串路径 e3.h;
 * 2. 在 e3.h 拿到即将执行的 SDK 文本, 打补丁: 首帧就绪信号后 1 秒执行 SDK 自己的
 *    完整关闭链路 (置 isRewarded -> JX/mZ 全量关闭: 移除试玩视图 + 容器收起 +
 *    emit close({isEnded:true}) -> 小程序发奖 -> q.destroy() 通知 MB 运行时关广告页);
 * 3. 完整链路日志: e3.h 注入点 + webapi_getadvert 请求 + bridge 事件。
 *
 * 符号提取策略 (重要): SDK 是混淆 webpack bundle, 内部函数/变量名 (v$/JX/GY/mZ 等)
 * 在不同微信版本/基础库版本会变, 硬编码锚点会静默失效。因此补丁不引用任何写死的
 * 混淆名, 而是在拿到 SDK 文本后, 用「不混淆的字符串字面量 + 结构正则」运行时提取:
 *   - "PLAYABLE_FIRST_FRAME_READY" -> 首帧事件变量名;
 *   - 函数签名 function(e,t=!1) + 体内 isRewarded=!0/countDown -> 置奖励函数;
 *   - async function(e=0) + 体内 lastAdIsEnded -> 关闭链路函数;
 *   - VerifyAdRewardEligibility 请求上下文 -> 发请求助手 (marker 用, 可缺失);
 *   - .mb=new / .mbId -> MB 通道实例字段存在性。
 * 任一核心结构缺失时明确打 WeLogger.w 报告, 不注入半成品补丁。
 */
object SkipRewardedAds : SwitchFeature(), IResolveDex {

    override val technicalId = "跳过激励广告"
    override val nameRes = R.string.feature_skip_rewarded_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_skip_rewarded_ads_description

    private const val TAG = "SkipRewardedAds"
    private const val SESSION_WINDOW_MS = 30_000L
    private const val SKIP_DELAY_MS = 200
    private const val DUMP_SDK_FILES = false

    private val AD_EVENT_MARKERS = listOf("ad", "video", "reward", "close", "ended", "endpage")

    /** 广告 SDK 文件 (commlib SDK 子包), 命中即尝试打补丁。 */
    private val SDK_CANDIDATES = listOf(
        "WAAppAd.js",
        "WAGameAd.js",
        "WASplashAdFloatWindow.js",
        "WASplashadWorker.js",
        "app-ad.js",
        "app-ad",
        "WAAd.js",
        "WASdkAd.js",
    )

    /** 混淆标识符 (webpack 局部变量/函数名, 可能含 $)。 */
    private const val ID = "[A-Za-z_$][A-Za-z0-9_$]*"

    // 广告数据请求日志: 打印 webapi_getadvert 的请求内容 (主进程)。
    // 能看到奖励广告数据放行、发奖关键请求 VerifyAdRewardEligibility。
    // 真正的 SDK 注入点: loadLibFiles 读到的脚本文本在这里被编译/求值。
    // e3.h(f9, jsruntime.t, path, name, version, ctxId, script, i3, b3)
    private val methodInjectLibScript by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.utils")
        matcher {
            usingEqStrings("MicroMsg.JsValidationInjector", "hy: injecting file %s")
        }
    }

    // 远程 commlib reader 的 H0: 返回 AssetFileDescriptor 给 V8 直读, 文本补丁无效。
    // 置 null 后 loadLibFiles 回退到 iCommLibReader.a() -> e3.h 字符串路径。
    private val methodPkgReaderH0 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            paramTypes("java.lang.String")
            returnType("android.content.res.AssetFileDescriptor")
            declaredClass {
                usingEqStrings("PkgReader[%d] [%s]")
            }
        }
    }

    // 本地 assets reader 同样处理 (老版本基础库)。
    private val methodAssetReaderH0 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            paramTypes("java.lang.String")
            returnType("android.content.res.AssetFileDescriptor")
            declaredClass {
                usingEqStrings("AssetReader[%d][%s]")
            }
        }
    }

    /** 已 dump 过的 SDK 文件名 (每个进程一次)。 */
    private val dumpedNames = ConcurrentHashMap.newKeySet<String>()

    /** 命中广告相关事件后, 接下来 30 秒内的所有 bridge 事件都会被打日志。 */
    private val adEventSessionUntil = AtomicLong(0)

    override val shouldLoadInCurrentProcess
        get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        // 1) webapi_getadvert 请求日志 (主进程): 奖励请求放行 + 发奖校验。
        RemoveEmbeddedAds.ctorNetSceneJSOperateWxData.hookBefore {
            val dataIndex = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    JSONObject(arg).optString("api_name") == "webapi_getadvert"
                }.getOrDefault(false)
            }
            if (dataIndex < 0) return@hookBefore
            val data = runCatching {
                JSONObject(args[dataIndex] as String).optJSONObject("data")?.toString()
            }.getOrNull()
            WeLogger.i(TAG, "webapi_getadvert data=${data?.take(800)}")
        }

        // 2) 强制字符串注入路径: 广告 SDK 文件不允许 FD 直读。
        listOf(methodPkgReaderH0, methodAssetReaderH0)
            .filter { !it.isPlaceholder }
            .forEach { method ->
                method.hookBefore {
                    val path = args.getOrNull(0) as? String ?: return@hookBefore
                    if (isSdkCandidate(path)) {
                        WeLogger.i(TAG, "H0 blocked path=$path (force text inject)")
                        result = null
                    }
                }
            }

        // 3) 真正的补丁点: e3.h 里的 script 就是即将被编译执行的 SDK 文本。
        methodInjectLibScript.hookBefore {
            val path = args.getOrNull(2) as? String ?: return@hookBefore
            if (!isSdkCandidate(path)) return@hookBefore
            val script = args.getOrNull(6) as? String ?: return@hookBefore
            dumpSdkFile(path, script)
            val patched = patchMbSdk(path, script)
            if (patched != script) {
                args[6] = patched
                WeLogger.i(
                    TAG,
                    "inject SDK path=$path size=${script.length} " +
                        "delta=${patched.length - script.length}"
                )
            }
        }

        // 4) bridge 事件日志: mbAd_* 全家 + 广告会话窗口, 观察完整 MB 流程。
        val bindingClass = "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass()
        bindingClass.reflekt().firstMethod { name = "subscribeHandler" }.hookBefore {
            val type = args.getOrNull(0) as? String ?: return@hookBefore
            val data = (args.getOrNull(1) as? String).orEmpty()
            val now = System.currentTimeMillis()
            val looksAd = type.startsWith("mbAd_") ||
                AD_EVENT_MARKERS.any { type.contains(it, ignoreCase = true) }
            val inSession = now <= adEventSessionUntil.get()
            if (looksAd) {
                adEventSessionUntil.set(now + SESSION_WINDOW_MS)
            }
            if (looksAd || inSession) {
                WeLogger.i(TAG, "bridge event type=$type data=${data.take(500)}")
            }
        }
    }

    private fun dumpSdkFile(path: String, content: String) {
        if (!DUMP_SDK_FILES) return
        if (!dumpedNames.add(path)) return
        runCatching {
            val dir = File(HostInfo.application.filesDir, "wekit_skip_rewarded_js").apply { mkdirs() }
            val safeName = path.substringAfterLast('/').ifBlank { path.hashCode().toString() }
            val target = File(dir, safeName)
            target.writeText(content)
            WeLogger.i(TAG, "dumped SDK path=$path size=${content.length} -> ${target.absolutePath}")
        }.onFailure { WeLogger.e(TAG, "dump SDK path=$path failed", it) }
    }

    private fun isSdkCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in SDK_CANDIDATES || name.contains("ad", ignoreCase = true)
    }

    /**
     * 动态补丁: 只用稳定字符串/结构定位, 不写死混淆符号名。
     *
     * 注入点在 SDK 的「试玩首帧就绪」处理分支
     * (t.emitter.emit(<FIRST_FRAME_READY 变量>);break;), 1 秒后执行:
     *   <置奖励函数>.call(this,0,!0)          -> isRewarded=true, KX/uZ 发 close 才带 isEnded;
     *   Promise.resolve(<关闭链路函数>.call(this)) -> 移除试玩视图 + 容器收起 +
     *                                          emit close({isEnded:true}) -> 小程序发奖;
     *   q.destroy()                            -> 通知 MB 运行时关闭广告页。
     * 任一核心结构提取失败 -> WeLogger.w 明确报告, 返回原文不注入。
     */
    private fun patchMbSdk(path: String, content: String): String {
        val ox = Regex("""($ID)="PLAYABLE_FIRST_FRAME_READY"""")
            .find(content)?.groupValues?.get(1)
        if (ox == null) {
            return unsupported(path, content, "PLAYABLE_FIRST_FRAME_READY 常量")
        }

        val reward = findRewardSetter(content) ?: return unsupported(path, content, "置奖励函数 (function(e,t=!1)+isRewarded=!0+countDown)")

        val close = findCloseFlow(content) ?: return unsupported(path, content, "关闭链路函数 (async function(e=0)+lastAdIsEnded)")

        if (!hasMbChannel(content)) {
            return unsupported(path, content, "MB 通道实例字段 (.mb=new / .mbId)")
        }

        val req = findRequestHelper(content)
        val skip = buildSkip(reward, close, req)
        val siteRegex = Regex("""emitter\.emit\(""" + Regex.escape(ox) + """\);break;""")
        val sites = siteRegex.findAll(content).count()
        if (sites == 0) {
            return unsupported(path, content, "首帧注入点 emitter.emit($ox);break;")
        }

        WeLogger.i(
            TAG,
            "sdk patch symbols path=$path ox=$ox reward=$reward close=$close " +
                "req=${req ?: "N/A"} sites=$sites"
        )
        return siteRegex.replace(content) { it.value.replace(";break;", ",$skip;break;") }
    }

    private fun unsupported(path: String, content: String, missing: String): String {
        WeLogger.w(TAG, "skip patch NOT applied path=$path missing=$missing (SDK 结构可能已改版)")
        return content
    }

    /** 置奖励函数: 倒计时结束显示函数, 特征签名 + 体内置 isRewarded + 操作 countDown UI。 */
    private fun findRewardSetter(content: String): String? {
        val re = Regex("""($ID)=function\(e,t=!1\)\{var a=$ID\(this\)""")
        return re.findAll(content).firstOrNull { m ->
            val frag = content.substring(m.range.last, minOf(m.range.last + 400, content.length))
            frag.contains("isRewarded=!0") && frag.contains("countDown")
        }?.groupValues?.get(1)
    }

    /** 关闭链路函数: RewardedVideoAd 全量关闭, 特征 async 签名 + 体内 lastAdIsEnded。 */
    private fun findCloseFlow(content: String): String? {
        val re = Regex("""($ID)=async function\(e=0\)\{if\($ID\.call\(this\)\)throw""")
        return re.findAll(content).firstOrNull { m ->
            val frag = content.substring(m.range.last, minOf(m.range.last + 6000, content.length))
            frag.contains("lastAdIsEnded")
        }?.groupValues?.get(1)
    }

    /** 发请求助手 (marker 用): VerifyAdRewardEligibility 请求上下文里的调用名。 */
    private fun findRequestHelper(content: String): String? {
        val re = Regex(
            """await ($ID)\(\{apiName:"webapi_getadvert",reqData:\{action:"weapp_comm",""" +
                """request_data:JSON\.stringify\(\{rpc_method:"VerifyAdRewardEligibility""""
        )
        return re.find(content)?.groupValues?.get(1)
    }

    /** MB 通道实例字段存在性校验 (t.mb / q.mbId), 属性名若改版会在这里暴露。 */
    private fun hasMbChannel(content: String): Boolean {
        return Regex("""\.mb=new $ID\(""").containsMatchIn(content) &&
            Regex("""\.mbId""").containsMatchIn(content)
    }

    private fun buildSkip(reward: String, close: String, req: String?): String {
        val marker = if (req != null) {
            "$req({apiName:\"webapi_getadvert\",reqData:{action:\"weapp_comm\"," +
                "request_data:JSON.stringify({rpc_method:\"WEKIT_PLAYABLE_CLOSE\",traceid:id})}});"
        } else {
            ""
        }
        return "setTimeout((()=>{try{var w=globalThis;w.__wekitPlayable=w.__wekitPlayable||{};" +
            "var id=t.adProxy?.data?.traceid;" +
            "if(!w.__wekitPlayable[id]){w.__wekitPlayable[id]=1;$marker" +
            "var q=t.mb;if(q&&q.mbId){t.isEnded=!0," +
            "$reward.call(this,0,!0),Promise.resolve($close.call(this)).catch(function(){})," +
            "q.destroy().catch(function(){})}}}catch(_){}}),$SKIP_DELAY_MS)"
    }
}
