package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div

/**
 * Skips mini program rewarded ads.
 *
 * These ads are user-initiated: the mini program / mini game calls RewardedVideoAd.show(), the ad
 * SDK (WAAppAd.js / WAGameAd.js, a remote commlib SDK subpackage) plays a video or hands off to the
 * MB runtime for a playable ad, and only after it finishes does it emit close({isEnded:true}),
 * which makes the mini program grant the reward.
 *
 * Injection chain (confirmed by decompilation, stable on the Java side): loadLibFiles -> f.j() ->
 * iCommLibReader.a()/H0() -> e3.h(script) -> V8 evaluation This implementation:
 * 1. Intercepts H0() (direct FD read, whose text can't be patched) so loadLibFiles falls back to
 *    the string path e3.h;
 * 2. In e3.h, patches the SDK text about to be evaluated at the start of show()'s implementation:
 *    once ad data is loaded (or after awaiting load()), the ad is never shown. show() resolves
 *    first, then isRewarded is set and the SDK's own full reset runs (the same one MB's resetStatus
 *    callback uses): emit close({isEnded:true}) -> reward granted, the SSV listener sends
 *    VerifyAdRewardEligibility, and the next ad is prefetched;
 * 3. Logs the whole chain: e3.h injection + webapi_getadvert requests + bridge events.
 *
 * Symbol extraction (important): the SDK is an obfuscated webpack bundle, so internal
 * function/variable names (hK/KX/uX, ...) change across WeChat / base library versions and
 * hard-coded anchors would silently break. The patch therefore never references a fixed obfuscated
 * name; it extracts them at runtime from the SDK text using non-obfuscated property names / strings
 * plus structural regexes:
 * - show(e={}){return X.call(this,e)} -> show implementation;
 * - resetStatus:()=>X.call(this,<enum>.MB_RESET) -> full reset function;
 * - load(){if(X.call(this))...var e=Y(this);if(e.isShowed) -> destroyed check + state getter;
 * - X=async function(e,t={}){var a=Y(this); -> injection site + state variable name;
 * - isSkitAd + "ad_video_has_finished_report" -> short drama completion report (optional). If any
 *   core structure is missing, it's reported via WeLogger.w and no partial patch is applied.
 */
object SkipRewardedAds : SwitchFeature(), IResolveDex {
    override val technicalId = "跳过激励广告"
    override val nameRes = R.string.feature_skip_rewarded_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_skip_rewarded_ads_description

    private const val TAG = "SkipRewardedAds"
    private const val DUMP_SDK_FILES = false

    /** Ad SDK files (commlib SDK subpackages); a match is a patch candidate. */
    private val SDK_CANDIDATES =
        listOf(
            "WAAppAd.js",
            "WAGameAd.js",
            "WASplashAdFloatWindow.js",
            "WASplashadWorker.js",
            "app-ad.js",
            "app-ad",
            "WAAd.js",
            "WASdkAd.js",
        )

    /** Obfuscated identifier (webpack local variable/function name, may contain $). */
    private const val ID = "[A-Za-z_$][A-Za-z0-9_$]*"

    // The real SDK injection site: the script text read by loadLibFiles is compiled/evaluated here.
    // e3.h(f9, jsruntime.t, path, name, version, ctxId, script, i3, b3)
    private val methodInjectLibScript by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.utils")
        usingEqStrings("MicroMsg.JsValidationInjector", "hy: injecting file %s")
    }

    // H0 of the remote commlib reader: returns an AssetFileDescriptor for V8 to read directly,
    // which bypasses text patching. Returning null makes loadLibFiles fall back to
    // iCommLibReader.a() -> the e3.h string path.
    private val methodPkgReaderH0 by
        dexMethod(allowFailure = true) {
            searchPackages("com.tencent.mm.plugin.appbrand.appcache")
            returnType = "android.content.res.AssetFileDescriptor"
            paramTypes("java.lang.String")
            declaredClass {
                usingEqStrings("PkgReader[%d] [%s]")
            }
        }

    // Same for the local assets reader (older base libraries).
    private val methodAssetReaderH0 by
        dexMethod(allowFailure = true) {
            searchPackages("com.tencent.mm.plugin.appbrand.appcache")
            returnType = "android.content.res.AssetFileDescriptor"
            paramTypes("java.lang.String")
            declaredClass {
                usingEqStrings("AssetReader[%d][%s]")
            }
        }

    /** SDK file names already dumped (once per process). */
    private val dumpedNames = ConcurrentHashMap.newKeySet<String>()

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        // 1) Force the string injection path: ad SDK files must not be read via FD.
        listOf(methodPkgReaderH0, methodAssetReaderH0)
            .filter { it.isFound }
            .forEach { method ->
                method.hookBefore {
                    val path = args.getOrNull(0) as? String ?: return@hookBefore
                    if (isSdkCandidate(path)) {
                        WeLogger.i(TAG, "H0 blocked path=$path (force text inject)")
                        result = null
                    }
                }
            }

        // 2) The actual patch point: the script passed to e3.h is the SDK text about to be
        //    compiled and evaluated.
        methodInjectLibScript.hookBefore {
            val path = args.getOrNull(2) as? String ?: return@hookBefore
            if (!isSdkCandidate(path)) return@hookBefore
            val script = args.getOrNull(6) as? String ?: return@hookBefore
            dumpSdkFile(path, script)
            val patched = patchAdSdk(path, script)
            if (patched != script) {
                args[6] = patched
                WeLogger.i(
                    TAG,
                    "inject SDK path=$path size=${script.length} " +
                        "delta=${patched.length - script.length}",
                )
            }
        }
    }

    private fun dumpSdkFile(path: String, content: String) {
        if (!DUMP_SDK_FILES) return
        if (!dumpedNames.add(path)) return
        runCatching {
            val dir = (KnownPaths.moduleCache / "skip-rewarded-ads").toFile().apply { mkdirs() }
            val safeName = path.substringAfterLast('/').ifBlank { path.hashCode().toString() }
            val target = File(dir, safeName)
            target.writeText(content)
            WeLogger.i(
                TAG,
                "dumped SDK path=$path size=${content.length} -> ${target.absolutePath}",
            )
        }
            .onFailure { WeLogger.e(TAG, "dump SDK path=$path failed", it) }
    }

    private fun isSdkCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in SDK_CANDIDATES || name.contains("ad", ignoreCase = true)
    }

    /**
     * Dynamic patch: located only via stable property names / structure, never via hard-coded
     * obfuscated names.
     *
     * Injected at the start of show()'s implementation (right after `var <state>=<getter>(this);`).
     * When ad data is ready: return, so show() resolves first (the mini program's show().then runs
     * before close); after setTimeout 0, set isRewarded (and isFallbackShareSuccess for the
     * share-page fallback), send the short drama completion report if available, then
     * <reset>.call(this) -> emit close({isEnded:true}) + reward verification + next ad fetch. Data
     * not ready / already showing / instance destroyed -> fall through to the original logic (which
     * rejects or proceeds normally); a concurrent show() during a skip is rejected. If any core
     * structure can't be extracted -> WeLogger.w and the original text is returned.
     */
    private fun patchAdSdk(path: String, content: String): String {
        val show =
            Regex("""show\(e=\{\}\)\{return ($ID)\.call\(this,e\)\}""")
                .find(content)
                ?.groupValues
                ?.get(1)
                ?: return unsupported(
                    path,
                    content,
                    "show implementation (show(e={}){return X.call(this,e)})",
                )

        val reset =
            Regex("""resetStatus:\(\)=>($ID)\.call\(this,$ID\.MB_RESET\)""")
                .find(content)
                ?.groupValues
                ?.get(1)
                ?: return unsupported(
                    path,
                    content,
                    "full reset function (resetStatus:()=>X.call(this,<enum>.MB_RESET))",
                )

        val load =
            Regex(
                    """load\(\)\{if\(($ID)\.call\(this\)\)return Promise\.reject\(\{errMsg:$ID\}\);""" +
                        """var e=($ID)\(this\);if\(e\.isShowed\)""",
                )
                .find(content)
                ?: return unsupported(path, content, "destroyed check + state getter in load()")
        val destroyed = load.groupValues[1]
        val getter = load.groupValues[2]

        val siteRegex =
            Regex(
                Regex.escape(show) +
                    """=async function\($ID,$ID=\{\}\)\{var ($ID)=""" +
                    Regex.escape(getter) +
                    """\(this\);""",
            )
        val sites = siteRegex.findAll(content).toList()
        if (sites.size != 1) {
            return unsupported(
                path,
                content,
                "show implementation site $show=async function(...) (${sites.size} matches)",
            )
        }
        val site = sites.single()
        val state = site.groupValues[1]
        val skitReport = findSkitRewardReporter(content, destroyed, getter)

        WeLogger.i(
            TAG,
            "sdk patch symbols path=$path show=$show reset=$reset destroyed=$destroyed " +
                "getter=$getter state=$state skitReport=${skitReport ?: "N/A"}",
        )
        return content.replaceRange(
            site.range.last + 1,
            site.range.last + 1,
            buildSkip(state, destroyed, reset, skitReport),
        )
    }

    /**
     * Short drama ad completion report (ad_video_has_finished_report, unlocks episodes); may be
     * absent from SDKs without short drama support.
     */
    private fun findSkitRewardReporter(
        content: String,
        destroyed: String,
        getter: String,
    ): String? {
        val re =
            Regex(
                """($ID)=function\(e=!0\)\{if\(!""" +
                    Regex.escape(destroyed) +
                    """\.call\(this\)\)\{var t=""" +
                    Regex.escape(getter) +
                    """\(this\);if\(t\.isSkitAd&&!t\.isHostingSkitAd\)""",
            )
        return re.findAll(content)
            .firstOrNull { m ->
                val frag =
                    content.substring(m.range.last, minOf(m.range.last + 800, content.length))
                frag.contains("ad_video_has_finished_report")
            }
            ?.groupValues
            ?.get(1)
    }

    private fun unsupported(path: String, content: String, missing: String): String {
        WeLogger.w(
            TAG,
            "skip patch NOT applied path=$path missing=$missing (SDK structure may have changed)",
        )
        return content
    }

    private fun buildSkip(
        s: String,
        destroyed: String,
        reset: String,
        skitReport: String?,
    ): String {
        val ready = "!$destroyed.call(this)&&!$s.isShowed"
        // A show() arriving while a skip is in flight must not reach the original logic (it would
        // display the ad); reject it the way the SDK rejects show() while another ad is showing.
        val busy =
            "if($s.__wekitSkip)return Promise.reject({errMsg:\"can't invoke show() while other video-ad is showed\"});"
        val report = if (skitReport != null) "$skitReport.call(this,!0);" else ""
        return "if($ready){${busy}if(!$s.isData&&$s.adFetchResult)try{await $s.adFetchResult}catch{}" +
            "${busy}if($ready&&$s.isData){$s.__wekitSkip=!0;setTimeout(()=>{" +
            "$s.isRewarded=!0;$s.isFallbackShareSuccess=!0;" +
            "Promise.resolve().then(()=>{${report}return $reset.call(this)}).catch(()=>{})" +
            ".then(()=>{$s.__wekitSkip=!1})},0);return}}"
    }
}
