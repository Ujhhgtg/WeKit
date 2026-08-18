package dev.ujhhgtg.wekit.pet.core

/**
 * Pet remark (reaction copy) library — pure. Ported 1:1 from dsh-pet remarks.ts.
 * Reaction bubbles come from two layers: the built-in default library below,
 * and per-pet custom lines declared in a manifest's 'remarks' block. A custom
 * slot replaces the built-in pool for that slot only.
 */

/** Interaction events a reaction line can accompany. */
enum class RemarkKind(val id: String) {
    PET("pet"),
    PET_COOLDOWN("petCooldown"),
    FEED("feed"),
    FEED_COOLDOWN("feedCooldown"),
    NO_TREATS("noTreats");

    companion object {
        val ALL: List<RemarkKind> = entries
        fun fromId(id: String): RemarkKind? = entries.firstOrNull { it.id == id }
    }
}

/** Per-pet remark overrides (normalized shape; each slot is a line pool). */
typealias PetRemarks = Map<RemarkKind, List<String>>

/** Longest accepted reaction line (characters, trimmed before slicing). */
const val REMARK_LINE_MAX = 120

/** Longest accepted pool per slot. */
const val REMARK_LINES_MAX = 64

/** Built-in default remark library (whale-girl voice). No emoji, `～` is the signature. */
val BUILTIN_REMARKS: Map<RemarkKind, List<String>> = mapOf(
    RemarkKind.PET to listOf(
        "咕噜咕噜～被摸摸好舒服！",
        "再摸摸这里，痒痒的～",
        "头顶温度刚刚好，安心～",
        "被摸到耳朵啦，扑通扑通！",
        "你的手掌好温暖，舍不得你走～",
        "呼噜呼噜～就靠在这里不走了！",
        "今天的摸头也收货成功！",
        "蹭蹭你的手心，这是回礼～",
        "多摸摸我，亲密度会涨哦！",
        "闭眼享受中，请勿打扰～",
    ),
    RemarkKind.PET_COOLDOWN to listOf(
        "摸过头啦，让鲸鱼娘歇口气～",
        "羽毛都快被摸秃啦，缓一缓～",
        "呼……先让我喘口气嘛！",
        "再摸就要睡着了哦～",
        "稍微休息一下，待会儿再摸～",
        "头顶要冒烟啦，停一停！",
        "我知道你喜欢我，但也要节制呀～",
        "歇一歇，摸摸的手感会更好哦～",
        "咕……等我回个蓝～",
        "让我先消化一下刚才的爱！",
    ),
    RemarkKind.FEED to listOf(
        "呜哇！小鱼干好好吃！",
        "咔嚓咔嚓，美味到尾巴打结～",
        "这条小鱼干是刚晒好的，好香！",
        "谢谢你，胃里暖暖的～",
        "囤粮 +1，今天也有好好被爱！",
        "好吃到想转圈圈～",
        "小鱼干最好吃了，再来亿条！",
        "饱餐一顿，马上满血复活～",
        "这个味道，是幸福的味道！",
        "吃完了还不忘舔舔爪子～",
    ),
    RemarkKind.FEED_COOLDOWN to listOf(
        "吃饱啦，晚点再喂～",
        "肚子圆滚滚的，装不下啦～",
        "再喂就要变成球啦！",
        "让我慢慢消化这份心意～",
        "小鱼干的香气还没散呢～",
        "呼……满足得动不了了～",
        "先散步一圈再吃下一顿！",
        "肚皮已经鼓鼓的啦～",
        "好吃是好吃，可也得节制呀～",
        "等我饿了会告诉你哦～",
    ),
    RemarkKind.NO_TREATS to listOf(
        "没有小鱼干了，多陪我工作一会儿吧～",
        "粮仓空空，陪我完成几轮任务就会有小鱼干啦～",
        "小鱼干在路上啦，先一起加油工作！",
        "嘴巴寂寞了……快去完成一轮任务！",
        "陪我多工作一会儿，鱼干自动到账～",
        "现在喂我也只会饿着肚子说谢谢哦～",
        "粮仓见底啦，用几轮任务换一条鱼干吧～",
        "饿着肚子等你完成下一轮任务～",
        "小鱼干藏在你的工作里，去找找看！",
        "先工作后干饭，我们的约定哦～",
    ),
)

/** The legacy first line of one kind. */
fun builtinRemark(kind: RemarkKind): String = BUILTIN_REMARKS[kind]!!.first()

/**
 * Normalize a manifest 'remarks' block into per-kind line pools. Unknown slots
 * and non-string entries are skipped; empty pools are dropped so the built-in
 * library takes the slot. Returns null when no usable slot remains.
 */
fun normalizePetRemarks(
    raw: Map<String, Any?>?,
    onWarning: (String) -> Unit = {},
): PetRemarks? {
    if (raw == null) return null
    val remarks = mutableMapOf<RemarkKind, List<String>>()
    for ((key, value) in raw) {
        val kind = RemarkKind.fromId(key)
        if (kind == null) {
            onWarning("unknown remarks slot $key")
            continue
        }
        val values = if (value is List<*>) value else listOf(value)
        val lines = values
            .filterIsInstance<String>()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(REMARK_LINES_MAX)
            .map { it.take(REMARK_LINE_MAX) }
        if (lines.isEmpty()) {
            onWarning("remarks slot $key carries no usable lines")
            continue
        }
        remarks[kind] = lines
    }
    return if (remarks.isEmpty()) null else remarks
}

/**
 * Round-robin reaction picker over the effective pools (per-pet custom lines
 * override the built-in pool per slot). Counters are per slot, so each slot
 * cycles its own list independently and picks stay deterministic.
 */
class RemarkPicker(overrides: PetRemarks? = null) {
    private val counters = mutableMapOf<RemarkKind, Long>()
    private val pools: Map<RemarkKind, List<String>>

    init {
        val map = mutableMapOf<RemarkKind, List<String>>()
        for (kind in RemarkKind.ALL) {
            val custom = overrides?.get(kind)
            map[kind] = if (custom != null && custom.isNotEmpty()) custom else BUILTIN_REMARKS[kind]!!
        }
        pools = map
    }

    /** The effective pool for one slot (custom override or built-in). */
    fun pool(kind: RemarkKind): List<String> = pools[kind]!!

    /** The next line for one slot (round-robin within its pool). */
    fun pick(kind: RemarkKind): String {
        val pool = pools[kind]!!
        val index = ((counters[kind] ?: 0L) % pool.size).toInt()
        counters[kind] = (counters[kind] ?: 0L) + 1
        return pool[index]
    }

    /** Select a line from a stable external counter without changing local picker state. */
    fun pickAt(kind: RemarkKind, count: Long): String {
        val pool = pools[kind]!!
        return pool[(0L.coerceAtLeast(count) % pool.size).toInt()]
    }
}
