package dev.ujhhgtg.wekit.dexkit.meta

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Loads the frozen DexKit Phase 0 snapshot fixture (a small representative subset of
 * artifacts/dexkit-snapshot/wechat_8079_3180.json.gz).
 */
internal object SnapshotFixture {
    const val SAMPLE_CLASS = "Lcom/tencent/mm/pluginsdk/model/app/c0;"
    const val SAMPLE_METHOD = "Lcom/tencent/mm/ui/chatting/x0;->G(Z)V"
    const val SAMPLE_METHOD_READ_WRITE =
        "Lcom/tencent/mm/plugin/ipcall/IPCallManager\$3;->callback(Lcom/tencent/mm/sdk/event/IEvent;)Z"
    const val SAMPLE_FIELD = "Lwp/b;->c:I"
    const val SAMPLE_FIELD_OBJECT =
        "Lcom/tencent/mm/ui/conversation/ConversationListView;->C:Landroid/view/View;"
    const val SAMPLE_CLASS_NESTED_INTERFACE = "Lmg3/e;"
    const val SAMPLE_INTERFACE = "Lcom/tencent/mm/network/l0;"
    const val SAMPLE_CLASS_WITH_SUPER = "Lcom/tencent/mm/plugin/luckymoney/model/i6;"
    const val SAMPLE_SUPER_CLASS = "Lcom/tencent/mm/plugin/luckymoney/model/h6;"
    const val SAMPLE_CLASS_OBJECT_RETURN = "Lcom/tencent/mm/modelbase/f;"
    const val SAMPLE_METHOD_OBJECT_RETURN =
        "Lcom/tencent/mm/modelbase/f;->a(IILjava/lang/String;Lcom/tencent/mm/protobuf/f;Lcom/tencent/mm/modelbase/n1;Lcom/tencent/mm/modelbase/i;)Lcom/tencent/mm/modelbase/f;"

    val root: JsonObject by lazy {
        val stream =
            SnapshotFixture::class.java.getResourceAsStream("/dexkit-meta/sample.json")
                ?: error("Missing snapshot fixture /dexkit-meta/sample.json")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    fun entry(kind: DexMetadataKind, descriptor: String): JsonObject =
        root.getValue(kind.key).jsonObject.getValue(descriptor).jsonObject
}

/** Fake [DexMetadataProvider] over the fixture that counts one query per kind|descriptor. */
internal class CountingMetadataProvider(private val root: JsonObject = SnapshotFixture.root) :
    DexMetadataProvider {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    val totalQueries: Int
        get() = counts.values.sumOf { it.get() }

    fun count(kind: DexMetadataKind, descriptor: String): Int =
        counts[kind.key + "|" + descriptor]?.get() ?: 0

    override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? {
        counts.computeIfAbsent(kind.key + "|" + descriptor) { AtomicInteger() }.incrementAndGet()
        val bucket = root[kind.key]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        return bucket[descriptor]?.takeIf { it !is JsonNull }?.jsonObject
    }
}
