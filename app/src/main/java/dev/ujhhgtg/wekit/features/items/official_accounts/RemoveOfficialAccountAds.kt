package dev.ujhhgtg.wekit.features.items.official_accounts

import dev.ujhhgtg.wekit.features.api.net.WePacketManager
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.features.api.net.abc.IWePacketInterceptor
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.AD_CARD_PREFIX
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.AD_INFO_FIELD
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.CAPTURE_MODE
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.DIAG_ALL_URIS
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.URI_BATCH_GET_MSG_LIST
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.URI_BIZ_MSG_RESORT
import dev.ujhhgtg.wekit.features.items.official_accounts.RemoveOfficialAccountAds.URI_RECOMMEND_FEEDS
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 公众号去广告。
 *
 * 新版微信公众号 UI 已迁移到 Flutter，但 Flutter 并不自行联网：所有 CGI 请求都通过 Pigeon
 * 通道 `dev.flutter.pigeon.mm_auto_gen.FlutterCgiHost.send` 进入 Java 层，最终经由
 * `com.tencent.mm.modelbase.g extends m1 (MicroMsg.NetSceneBase)` 的 `dispatch()` 发出。
 * WeKit 现有的 [dev.ujhhgtg.wekit.features.api.net.listener.WePacketDispatcher] 已经 hook
 * 了 `NetSceneBase.dispatch`，并把明文 protobuf 字节交给 [WePacketManager]。因此这里只需注册
 * 一个按 URI 匹配的 [IWePacketInterceptor] 即可，无需任何额外 native hook 代码。
 *
 * ## 真机抓包结论（覆盖了早期静态分析的猜测）
 * 订阅号信息流的文章列表来自**本地数据库**，[URI_BIZ_MSG_RESORT] 只是一次「重排」请求，并不
 * 携带文章 item。但它（以及推荐流 [URI_RECOMMEND_FEEDS]）的响应里，广告投放由一个**内嵌的
 * JSON 字符串字段**控制（实测在 `bizmsgresortv2` 的字段 12），形如：
 * ```
 * {"ret":1010,"msg":"no ad","check_ad_resp":{"aid":"...","online_aid":[...]},
 *  "no_ad_indicator_info":[{"pos_id":"...","no_ad_indicator":{...}}], ...}
 * ```
 * 这与现有 `RemoveArticleAds`（文章正文 getappmsg）处理的广告 JSON 是**同一族**字段
 * （`ad_slot_data` / `advertisement_info` / `advertisement_num` / `no_ad_indicator_info` /
 * `check_ad_resp`）。因此本特性对这些 endpoint 的做法是：递归遍历响应 protobuf，凡某个字符串
 * 字段本身是携带上述广告键的 JSON，就地清空这些键，使客户端拿到「无广告」结果。
 *
 * 另一类投放（Box / 聚合页）走 [URI_BATCH_GET_MSG_LIST]，其 schema 含 `SingleAppMsgItem`，
 * 静态分析（见下）确认 **item 内字段号 [AD_INFO_FIELD]=6 是广告子消息**：携带字段 6 的 item
 * 即广告，整条删除即可。本机订阅号信息流不走此路径，故需多用户协助抓包验证。
 *
 * ## 静态分析依据（libapp.so / Dart AOT，仅对 batchgetmsglist 这类 SingleAppMsgItem schema 有效）
 * 解析 protobuf `BuilderInfo` 字段注册序列（`MOV X16,#tag` 与字段名指针在同一条 `STP` 内成对
 * 出现，tag 可靠），在 `SingleAppMsgItem`（stub_1c50fa0）与 `MpSingleAppMsgItem`（stub_1c129ec）
 * 两处布局一致：tag2=`importToFinder`、tag4=标量、tag6=`*AppMsgItem.AdInfo` 子消息。
 *
 * ## [CAPTURE_MODE] / [DIAG_ALL_URIS]（面向众测）
 * - [DIAG_ALL_URIS]：打印每个经过管线的 uri+cgiId，用于确认管线生效、定位真实 endpoint。
 * - [CAPTURE_MODE]：额外打印抓到的广告（在过滤之前）。首行打印一条 `SUMMARY`，用户一眼即可判断
 *   本次是否抓到广告（内嵌广告 JSON 命中键 / item 携带字段 6），再附完整响应 JSON 供核对。
 *   注意：**过滤始终执行**，[CAPTURE_MODE] 只控制是否额外 dump，不影响是否修改；收集完样本后
 *   把它置 false 关掉 dump 即可，过滤照常生效。
 */
@Feature(
    name = "公众号去广告",
    categories = ["公众号"],
    description = "清除公众号信息流中的广告内容\n（订阅号信息流 / 推荐流 / 聚合页的广告投放位）"
)
object RemoveOfficialAccountAds : SwitchFeature(), IWePacketInterceptor {

    private const val TAG = "RemoveOfficialAccountAds"

    /** 抓包模式：额外 dump 抓到的广告（过滤之前）。不影响过滤本身，过滤始终执行。 */
    private const val CAPTURE_MODE = false

    /** 诊断模式：打印每个经过管线的 uri+cgiId（不含内容）。 */
    private const val DIAG_ALL_URIS = false

    /** item 内承载广告子消息的字段号（静态分析确认，仅适用于 SingleAppMsgItem 类 schema）。 */
    private const val AD_INFO_FIELD = 6

    /** 订阅号信息流的消息列表重排（cgiId 4687）。广告投放位的 JSON 内嵌在其响应里。 */
    private const val URI_BIZ_MSG_RESORT = "/cgi-bin/mmbiz-bin/timeline/bizmsgresortv2"

    /** 推荐信息流（cgiId 4326）——主要广告投放位之一。 */
    private const val URI_RECOMMEND_FEEDS = "/cgi-bin/mmbiz-bin/timeline/getrecommendfeedsv2"

    /** Box / 聚合页的消息批量拉取。其 schema 含 SingleAppMsgItem，广告靠 item 字段 6 标记。 */
    private const val URI_BATCH_GET_MSG_LIST = "/cgi-bin/mmbiz-bin/batchgetmsglist"

    /** 视频号 / 视频前贴片相关的异步加载信息。 */
    private const val URI_FINDER_ASYNC_LOAD =
        "/cgi-bin/micromsg-bin/finderbatchgetobjectasyncloadinfo"

    private val targetUris = setOf(
        URI_BIZ_MSG_RESORT,
        URI_RECOMMEND_FEEDS,
        URI_BATCH_GET_MSG_LIST,
        URI_FINDER_ASYNC_LOAD,
    )

    /** 走 SingleAppMsgItem 类 schema（用字段 6 检测广告）的 URI。 */
    private val itemSchemaUris = setOf(URI_BATCH_GET_MSG_LIST)

    /**
     * 广告控制 JSON 里需要清空的键。与 `RemoveArticleAds` 保持一致：
     *  - 数组型清空为 `[]`；
     *  - `advertisement_num` 置 0；
     *  - `check_ad_resp` 重置为空壳。
     * 命中其中任一键即认为该 JSON 是广告控制块。
     */
    private val AD_ARRAY_KEYS = setOf("ad_slot_data", "advertisement_info", "no_ad_indicator_info")
    private const val AD_NUM_KEY = "advertisement_num"
    private const val AD_CHECK_KEY = "check_ad_resp"

    /**
     * 「真的有广告」的强信号键。实测 no-ad 响应里 `check_ad_resp` / `no_ad_indicator_info` 也会被填充
     * （`"msg":"no ad"` 仍带 pos_id），故它们**不能**用来判定是否抓到广告，只用于清理。
     * 只有这些键非空才算真正投放了广告——众测 SUMMARY 的 `caughtAd` 据此判定，避免人人误报。
     */
    private val AD_STRONG_ARRAY_KEYS = setOf("ad_slot_data", "advertisement_info")

    /** 广告创意子消息的 frame_set_name 前缀（field 1）。实测形如 `ad_card87782834896_...`。 */
    private const val AD_CARD_PREFIX = "ad_card"

    override fun onEnable() {
        WePacketManager.addInterceptor(this)
    }

    override fun onDisable() {
        WePacketManager.removeInterceptor(this)
    }

    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (DIAG_ALL_URIS) WeLogger.i("$TAG.Diag", "REQ  uri=$uri cgiId=$cgiId len=${reqBytes.size}")
        return null
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (DIAG_ALL_URIS) WeLogger.i("$TAG.Diag", "RESP uri=$uri cgiId=$cgiId len=${respBytes.size}")

        if (uri !in targetUris) return null

        return try {
            val data = WeProtoData.fromBytes(respBytes)
            val root = data.toJsonObject()
            val usesItemSchema = uri in itemSchemaUris

            if (CAPTURE_MODE) {
                captureDump(uri, cgiId, respBytes.size, root, usesItemSchema)
            }

            var cleared = neutralizeEmbeddedAdJson(root)   // 1. 广告控制块（field 12 的 advertisement_num 等）
            cleared += stripAdCreatives(root)                  // 2. 广告创意子消息（field 91 的 ad_card 30KB 负载）
            if (usesItemSchema) cleared += removeAdItems(root) // 3. SingleAppMsgItem schema 的 field6 item
            if (cleared == 0) return null

            // deleteMissing=true：被移除的字段（如 ad_card 创意）才会真正从 protobuf 中删掉，
            // 与现有 RemoveArticleAds 的写回方式一致。root 本身是完整 toJsonObject()，只增删改，故安全。
            data.applyViewJson(root, true)
            WeLogger.i(TAG, "neutralized/removed $cleared ad block(s) in $uri")
            data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, "onResponse failed for $uri", e)
            null
        }
    }

    // ---------------------------------------------------------------------------
    // 抓包 (CAPTURE_MODE)
    // ---------------------------------------------------------------------------

    /**
     * 众测友好的抓包输出：首行 `SUMMARY` 让用户一眼看出本次是否抓到广告，便于回报。
     */
    private fun captureDump(
        uri: String,
        cgiId: Int,
        len: Int,
        root: JSONObject,
        usesItemSchema: Boolean,
    ) {
        val adJsonHits = describeAdJson(root)
        val creativeHits = describeAdCreatives(root)
        val itemAdHits = if (usesItemSchema) describeAdItems(root) else ""
        val caughtAd = adJsonHits.isNotBlank() || creativeHits.isNotBlank() || itemAdHits.isNotBlank()

        WeLogger.logChunkedI(
            "$TAG.Capture",
            buildString {
                append("SUMMARY caughtAd=$caughtAd uri=$uri cgiId=$cgiId len=$len\n")
                if (adJsonHits.isNotBlank()) append("adJsonHits:\n$adJsonHits")
                if (creativeHits.isNotBlank()) append("adCreatives:\n$creativeHits")
                if (itemAdHits.isNotBlank()) append("itemAdHits(field$AD_INFO_FIELD):\n$itemAdHits")
                append("full:\n${root.toString(2)}")
            }
        )
    }

    // ---------------------------------------------------------------------------
    // 内嵌广告 JSON（bizmsgresortv2 / getrecommendfeedsv2 等）
    // ---------------------------------------------------------------------------

    /**
     * 递归遍历 [node]：凡是「字符串值本身是携带广告键的 JSON」就地清空广告键并写回。
     * 返回清理掉的广告控制块数量。
     */
    private fun neutralizeEmbeddedAdJson(node: Any?): Int {
        var count = 0
        when (node) {
            is JSONObject -> {
                for (key in node.keys().asSequence().toList()) {
                    when (val v = node.opt(key)) {
                        is String -> {
                            val inner = v.asAdControlJsonOrNull()
                            if (inner != null && neutralizeAdKeys(inner)) {
                                node.put(key, inner.toString())
                                count++
                            }
                        }

                        else -> count += neutralizeEmbeddedAdJson(v)
                    }
                }
            }

            is JSONArray -> for (i in 0 until node.length()) count += neutralizeEmbeddedAdJson(node.opt(i))
        }
        return count
    }

    /** 若字符串是 JSON 对象且含任一广告键则解析返回，否则 null。 */
    private fun String.asAdControlJsonOrNull(): JSONObject? {
        val s = trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        if (AD_ARRAY_KEYS.none { it in s } && AD_NUM_KEY !in s && AD_CHECK_KEY !in s) return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    /** 就地清空广告键。返回是否发生改动。 */
    private fun neutralizeAdKeys(json: JSONObject): Boolean {
        var modified = false
        for (k in AD_ARRAY_KEYS) {
            if (json.has(k)) {
                json.put(k, JSONArray()); modified = true
            }
        }
        if (json.has(AD_NUM_KEY)) {
            json.put(AD_NUM_KEY, 0); modified = true
        }
        if (json.has(AD_CHECK_KEY)) {
            json.put(AD_CHECK_KEY, JSONObject().apply {
                put("aid", "0")
                put("del_aid", JSONArray())
                put("offline_aid", JSONArray())
                put("online_aid", JSONArray())
            })
            modified = true
        }
        return modified
    }

    /** 抓包用：列出每个内嵌广告 JSON 的位置与其携带的广告键。 */
    private fun describeAdJson(node: Any?, prefix: String = "root"): String {
        val sb = StringBuilder()
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                val v = node.opt(key)
                if (v is String) {
                    val inner = v.asAdControlJsonOrNull()
                    if (inner != null) {
                        // 强信号才算「抓到广告」：ad_slot_data/advertisement_info 非空，或 advertisement_num>0。
                        // check_ad_resp / no_ad_indicator_info 在 no-ad 时也非空，不能作为判定依据。
                        val strongHits = AD_STRONG_ARRAY_KEYS.filter { inner.opt(it).isNonEmptyAdValue() } +
                                if (inner.opt(AD_NUM_KEY).isNonEmptyAdValue()) listOf(AD_NUM_KEY) else emptyList()
                        if (strongHits.isNotEmpty()) {
                            val allKeys = (AD_ARRAY_KEYS + AD_NUM_KEY + AD_CHECK_KEY).filter { inner.has(it) }
                            sb.append("$prefix.$key  strong=$strongHits all=$allKeys\n")
                        }
                    }
                } else {
                    sb.append(describeAdJson(v, "$prefix.$key"))
                }
            }

            is JSONArray -> for (i in 0 until node.length()) sb.append(describeAdJson(node.opt(i), "$prefix[$i]"))
        }
        return sb.toString()
    }

    private fun Any?.isNonEmptyAdValue(): Boolean = when (this) {
        is JSONArray -> length() > 0
        is JSONObject -> length() > 0
        is Number -> toInt() != 0
        is String -> isNotEmpty() && this != "0"
        else -> false
    }

    // ---------------------------------------------------------------------------
    // SingleAppMsgItem 类 schema（batchgetmsglist）：item 字段 6 = 广告
    // ---------------------------------------------------------------------------

    /**
     * 在 SingleAppMsgItem 类 schema 中删除广告 item：递归查找含「数组元素带字段 [AD_INFO_FIELD]」的
     * repeated 字段，剔除这些元素。返回删除的 item 数。
     */
    private fun removeAdItems(node: Any?): Int {
        var removed = 0
        when (node) {
            is JSONObject -> for (key in node.keys().asSequence().toList()) {
                when (val v = node.opt(key)) {
                    is JSONArray -> {
                        if (v.length() > 0 && (0 until v.length()).any { isAdItem(v.opt(it)) }) {
                            val kept = JSONArray()
                            for (i in 0 until v.length()) {
                                if (isAdItem(v.opt(i))) removed++ else kept.put(v.opt(i))
                            }
                            node.put(key, kept)
                        }
                        for (i in 0 until v.length()) removed += removeAdItems(v.opt(i))
                    }

                    else -> removed += removeAdItems(v)
                }
            }

            is JSONArray -> for (i in 0 until node.length()) removed += removeAdItems(node.opt(i))
        }
        return removed
    }

    /** item 是否为广告：携带字段 [AD_INFO_FIELD] 的子消息即视为广告。 */
    private fun isAdItem(item: Any?): Boolean {
        val obj = item as? JSONObject ?: return false
        return obj.opt(AD_INFO_FIELD.toString()) is JSONObject
    }

    /** 抓包用：列出携带字段 [AD_INFO_FIELD] 的 item 位置。 */
    private fun describeAdItems(node: Any?, prefix: String = "root"): String {
        val sb = StringBuilder()
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                if (node.opt(key) is JSONObject && (node.opt(key) as JSONObject).opt(AD_INFO_FIELD.toString()) is JSONObject) {
                    sb.append("$prefix.$key\n")
                }
                sb.append(describeAdItems(node.opt(key), "$prefix.$key"))
            }

            is JSONArray -> for (i in 0 until node.length()) {
                if (isAdItem(node.opt(i))) sb.append("$prefix[$i]\n")
                sb.append(describeAdItems(node.opt(i), "$prefix[$i]"))
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------
    // 广告创意子消息（bizmsgresortv2 的 resort buffer，field 91 = ad_card 创意）
    // ---------------------------------------------------------------------------

    /**
     * 删除广告创意子消息。实测 `bizmsgresortv2` 的广告创意挂在 `root.13.2.91`：
     * `91.1 = "ad_card..."`（创意 frame_set_name），`91.2 =` 约 30KB 的广告负载 JSON
     * （`weixinadinfo` / `ad_posid` / 模板等），是真正渲染广告卡片的数据。
     *
     * 判定极稳健：某个字段的值是「field 1 为以 [AD_CARD_PREFIX] 开头的字符串」的子消息，即广告创意，
     * 整字段删除。普通 resort buffer 条目不带此结构。返回删除的创意数。
     */
    private fun stripAdCreatives(node: Any?): Int {
        var removed = 0
        when (node) {
            is JSONObject -> for (key in node.keys().asSequence().toList()) {
                val v = node.opt(key)
                // 整条删除承载广告创意的 entry（如 root.13.2），而非只删 field 91 留下空壳。
                if (isAdEntry(v)) {
                    node.remove(key); removed++
                } else {
                    removed += stripAdCreatives(v)
                }
            }

            is JSONArray -> {
                // 先递归更深层，再移除本层广告 entry（如 root.4[3]）。
                for (i in 0 until node.length()) removed += stripAdCreatives(node.opt(i))
                var i = 0
                while (i < node.length()) {
                    if (isAdEntry(node.opt(i))) {
                        node.remove(i); removed++
                    } else i++
                }
            }
        }
        return removed
    }

    /** 是否为广告创意子消息：field 1 是以 [AD_CARD_PREFIX] 开头的字符串（实测 `ad_card<aid>_...`）。 */
    private fun isAdCreative(node: Any?): Boolean {
        val obj = node as? JSONObject ?: return false
        return (obj.opt("1") as? String)?.startsWith(AD_CARD_PREFIX) == true
    }

    /**
     * 是否为广告 entry：某个 resort buffer 条目，其任一字段值是广告创意子消息（field 91 = ad_card）。
     * 实测两种容器形态都命中：`root.13.2`（单对象槽，type3 重排）与 `root.4[n]`（数组元素，type1 全量）。
     */
    private fun isAdEntry(node: Any?): Boolean {
        val obj = node as? JSONObject ?: return false
        for (key in obj.keys()) if (isAdCreative(obj.opt(key))) return true
        return false
    }

    /** 抓包用：列出广告创意子消息的位置。 */
    private fun describeAdCreatives(node: Any?, prefix: String = "root"): String {
        val sb = StringBuilder()
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                val v = node.opt(key)
                if (isAdCreative(v)) {
                    val name = (v as JSONObject).opt("1")
                    sb.append("$prefix.$key  $name\n")
                } else {
                    sb.append(describeAdCreatives(v, "$prefix.$key"))
                }
            }

            is JSONArray -> for (i in 0 until node.length()) sb.append(describeAdCreatives(node.opt(i), "$prefix[$i]"))
        }
        return sb.toString()
    }
}
