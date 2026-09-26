package dev.ujhhgtg.wekit.features.automation

/**
 * Flattens a rule value into named scalar fields and ordered list fields, which map 1:1 onto the
 * `automation_scope_fields` / `automation_scope_list_values` tables. A stored rule is present iff it
 * has at least one scalar field, so every codec must write one (usually `enabled`).
 *
 * Lists of objects are stored as parallel lists that share positions (`step.kind`, `step.content`).
 */
class AutomationFieldWriter {
    val scalars = LinkedHashMap<String, String>()
    val lists = LinkedHashMap<String, List<String>>()

    fun put(field: String, value: String) {
        scalars[field] = value
    }

    fun put(field: String, value: Boolean) = put(field, value.toString())
    fun put(field: String, value: Int) = put(field, value.toString())
    fun put(field: String, value: Long) = put(field, value.toString())
    fun put(field: String, value: Enum<*>) = put(field, value.name)

    fun putList(field: String, values: Collection<String>) {
        lists[field] = values.toList()
    }
}

class AutomationFieldReader(
    private val scalars: Map<String, String>,
    private val lists: Map<String, List<String>>,
) {
    fun string(field: String, default: String? = null): String =
        scalars[field] ?: default ?: error("Stored rule field '$field' is missing")

    fun boolean(field: String, default: Boolean? = null): Boolean =
        scalars[field]?.toBooleanStrict() ?: default ?: error("Stored rule field '$field' is missing")

    fun int(field: String, default: Int? = null): Int =
        scalars[field]?.toInt() ?: default ?: error("Stored rule field '$field' is missing")

    fun long(field: String, default: Long? = null): Long =
        scalars[field]?.toLong() ?: default ?: error("Stored rule field '$field' is missing")

    inline fun <reified E : Enum<E>> enum(field: String, default: E? = null): E {
        val raw = scalarOrNull(field) ?: return default ?: error("Stored rule field '$field' is missing")
        return enumValueOf<E>(raw)
    }

    fun scalarOrNull(field: String): String? = scalars[field]

    fun list(field: String): List<String> = lists[field].orEmpty()
}

interface AutomationValueCodec<T : Any> {
    fun write(value: T, out: AutomationFieldWriter)
    fun read(input: AutomationFieldReader): T
}

fun <T : Any> AutomationValueCodec<T>.encode(value: T): AutomationFieldWriter =
    AutomationFieldWriter().also {
        write(value, it)
        require(it.scalars.isNotEmpty()) { "Rule codecs must write at least one scalar field" }
    }

object AutomationCodecs {
    val toggle = object : AutomationValueCodec<AutomationToggleRule> {
        override fun write(value: AutomationToggleRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
        }

        override fun read(input: AutomationFieldReader) = AutomationToggleRule(input.boolean("enabled"))
    }

    val timeRange = object : AutomationValueCodec<AutomationTimeRangeRule> {
        override fun write(value: AutomationTimeRangeRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("startSecond", value.startSecond)
            out.put("endSecond", value.endSecond)
        }

        override fun read(input: AutomationFieldReader) = AutomationTimeRangeRule(
            input.boolean("enabled"), input.int("startSecond"), input.int("endSecond"),
        )
    }

    val keyword = object : AutomationValueCodec<AutomationKeywordRule> {
        override fun write(value: AutomationKeywordRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("mode", value.mode)
            out.put("regex", value.regex)
            out.put("ignoreCase", value.ignoreCase)
            out.putList("strings", value.strings)
        }

        override fun read(input: AutomationFieldReader) = AutomationKeywordRule(
            enabled = input.boolean("enabled"),
            mode = input.enum("mode"),
            strings = input.list("strings"),
            regex = input.string("regex"),
            ignoreCase = input.boolean("ignoreCase"),
        )
    }

    val delay = object : AutomationValueCodec<AutomationDelayRule> {
        override fun write(value: AutomationDelayRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("baseMs", value.baseMs)
            out.put("randomRangeMs", value.randomRangeMs)
        }

        override fun read(input: AutomationFieldReader) = AutomationDelayRule(
            input.boolean("enabled"), input.string("baseMs"), input.string("randomRangeMs"),
        )
    }

    val limit = object : AutomationValueCodec<AutomationLimitRule> {
        override fun write(value: AutomationLimitRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("value", value.value)
        }

        override fun read(input: AutomationFieldReader) =
            AutomationLimitRule(input.boolean("enabled"), input.long("value"))
    }

    val text = object : AutomationValueCodec<AutomationTextRule> {
        override fun write(value: AutomationTextRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("text", value.text)
        }

        override fun read(input: AutomationFieldReader) =
            AutomationTextRule(input.boolean("enabled"), input.string("text"))
    }

    val target = object : AutomationValueCodec<AutomationTargetRule> {
        override fun write(value: AutomationTargetRule, out: AutomationFieldWriter) {
            out.put("mode", value.mode)
            out.putList("categories", value.categories.map { it.name })
            out.putList("include", value.include)
            out.putList("includeMembers", value.includeMembers)
            out.putList("exclude", value.exclude)
            out.putList("excludeMembers", value.excludeMembers)
        }

        override fun read(input: AutomationFieldReader) = AutomationTargetRule(
            mode = input.enum("mode"),
            categories = input.list("categories").map { enumValueOf<AutomationTalkerCategory>(it) }.toSet(),
            include = input.list("include").toSet(),
            includeMembers = input.list("includeMembers").toSet(),
            exclude = input.list("exclude").toSet(),
            excludeMembers = input.list("excludeMembers").toSet(),
        )
    }

    val reply = object : AutomationValueCodec<AutomationReplyRule> {
        override fun write(value: AutomationReplyRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("asQuote", value.asQuote)
            out.putList("step.kind", value.steps.map { it.kind.name })
            out.putList("step.content", value.steps.map { it.content })
            out.putList("step.delayMs", value.steps.map { it.delayMs.toString() })
            out.putList("step.randomDelay", value.steps.map { it.randomDelay.toString() })
        }

        override fun read(input: AutomationFieldReader): AutomationReplyRule {
            val kinds = input.list("step.kind")
            val contents = input.list("step.content")
            val delays = input.list("step.delayMs")
            val randoms = input.list("step.randomDelay")
            require(contents.size == kinds.size && delays.size == kinds.size && randoms.size == kinds.size) {
                "Stored reply steps are misaligned"
            }
            return AutomationReplyRule(
                enabled = input.boolean("enabled"),
                asQuote = input.boolean("asQuote", false),
                steps = kinds.indices.map { i ->
                    AutomationReplyStep(
                        kind = enumValueOf(kinds[i]),
                        content = contents[i],
                        delayMs = delays[i].toLong(),
                        randomDelay = randoms[i].toBooleanStrict(),
                    )
                },
            )
        }
    }

    val messageTypes = object : AutomationValueCodec<AutomationMessageTypeRule> {
        override fun write(value: AutomationMessageTypeRule, out: AutomationFieldWriter) {
            out.put("all", value.all)
            out.putList("kinds", value.kinds.sortedBy { it.ordinal }.map { it.name })
        }

        override fun read(input: AutomationFieldReader) = AutomationMessageTypeRule(
            input.boolean("all"), input.list("kinds").map { enumValueOf<AutomationMessageKind>(it) }.toSet(),
        )
    }

    inline fun <reified E : Enum<E>> enumValue(): AutomationValueCodec<E> = object : AutomationValueCodec<E> {
        override fun write(value: E, out: AutomationFieldWriter) {
            out.put("value", value)
        }

        override fun read(input: AutomationFieldReader): E = input.enum("value")
    }
}
