package dev.ujhhgtg.wekit.dexkit.matcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * JSON codec for runtime script hosts (Python/Monet): parses snake_case spec objects into the
 * matcher data classes. No DexKit types are involved.
 *
 * See the Python wekit.dex builder helpers for the canonical JSON shapes; unknown keys are ignored
 * so future additive fields stay forward-compatible.
 */
object MatcherSpecJson {
    fun methodSpec(json: String): MethodSpec = parseMethod(parseObject(json))

    fun classSpec(json: String): ClassSpec = parseClass(parseObject(json))

    fun fieldSpec(json: String): FieldSpec = parseField(parseObject(json))

    private fun parseObject(json: String): JsonObject {
        val element =
            try {
                Json.parseToJsonElement(json)
            } catch (error: Exception) {
                throw IllegalArgumentException("matcher spec is not valid JSON", error)
            }
        return element as? JsonObject
            ?: throw IllegalArgumentException("matcher spec must be a JSON object")
    }

    // ------------------------------------------------------------------
    // Specs
    // ------------------------------------------------------------------

    private fun parseMethod(json: JsonObject): MethodSpec =
        MethodSpec(
            descriptor = json.stringOrNull("descriptor"),
            name =
                json.objectOrNull("name")?.let(::parseNameSpec)
                    ?: json.stringOrNull("name")?.let { NameSpec(it) },
            modifiers = json.intOrNull("modifiers"),
            declaredClass = json.objectOrNull("declared_class")?.let(::parseClass),
            returnType = json.objectOrNull("return_type")?.let(::parseClass),
            paramTypes =
                json.arrayOrNull("param_types")?.map { element ->
                    when (element) {
                        is kotlinx.serialization.json.JsonNull -> null
                        is JsonPrimitive -> TypeSpec(element.content)
                        is JsonObject -> parseTypeSpec(element)
                        else -> throw IllegalArgumentException("invalid param_types entry")
                    }
                },
            paramCount = json.rangeOrNull("param_count"),
            usingStrings = json.arrayOrNull("using_strings").orEmpty().map(::parseStringSpec),
            usingNumbers = json.arrayOrNull("using_numbers").orEmpty().map(::parseNumber),
            usingFields = json.arrayOrNull("using_fields").orEmpty().map(::parseUsingField),
            invokeMethods =
                json.arrayOrNull("invoke_methods").orEmpty().map { parseMethod(it.asObject()) },
            callerMethods =
                json.arrayOrNull("caller_methods").orEmpty().map { parseMethod(it.asObject()) },
            anyOf = json.arrayOrNull("any_of").orEmpty().map { parseMethod(it.asObject()) },
            allOf = json.arrayOrNull("all_of").orEmpty().map { parseMethod(it.asObject()) },
            opcodes = json.objectOrNull("opcodes")?.let(::parseOpcodes),
            annotations =
                json.arrayOrNull("annotations").orEmpty().map {
                    parseAnnotation(it.asObject())
                },
            searchPackages = json.stringList("search_packages"),
            excludePackages = json.stringList("exclude_packages"),
        )

    private fun parseClass(json: JsonObject): ClassSpec =
        ClassSpec(
            className =
                json.objectOrNull("class_name")?.let(::parseNameSpec)
                    ?: json.stringOrNull("class_name")?.let { NameSpec(it) },
            superClass = json.objectOrNull("super_class")?.let(::parseClass),
            interfaces = json.arrayOrNull("interfaces").orEmpty().map { parseClass(it.asObject()) },
            fields = json.arrayOrNull("fields").orEmpty().map { parseField(it.asObject()) },
            methods = json.arrayOrNull("methods").orEmpty().map { parseMethod(it.asObject()) },
            fieldCount = json.rangeOrNull("field_count"),
            methodCount = json.rangeOrNull("method_count"),
            interfaceCount = json.rangeOrNull("interface_count"),
            modifiers = json.intOrNull("modifiers"),
            usingStrings = json.arrayOrNull("using_strings").orEmpty().map(::parseStringSpec),
            annotations =
                json.arrayOrNull("annotations").orEmpty().map {
                    parseAnnotation(it.asObject())
                },
            usingNumbers = json.arrayOrNull("using_numbers").orEmpty().map(::parseNumber),
            searchPackages = json.stringList("search_packages"),
            excludePackages = json.stringList("exclude_packages"),
        )

    private fun parseField(json: JsonObject): FieldSpec =
        FieldSpec(
            name =
                json.objectOrNull("name")?.let(::parseNameSpec)
                    ?: json.stringOrNull("name")?.let { NameSpec(it) },
            modifiers = json.intOrNull("modifiers"),
            type = json.objectOrNull("type")?.let(::parseClass),
            declaredClass = json.objectOrNull("declared_class")?.let(::parseClass),
            readMethods =
                json.arrayOrNull("read_methods").orEmpty().map { parseMethod(it.asObject()) },
            writeMethods =
                json.arrayOrNull("write_methods").orEmpty().map { parseMethod(it.asObject()) },
            searchPackages = json.stringList("search_packages"),
            excludePackages = json.stringList("exclude_packages"),
        )

    // ------------------------------------------------------------------
    // Leaf specs
    // ------------------------------------------------------------------

    private fun parseNameSpec(json: JsonObject): NameSpec =
        NameSpec(
            value =
                json.stringOrNull("value")
                    ?: throw IllegalArgumentException("name spec requires a string 'value'"),
            match = parseNameMatch(json.stringOrNull("match")),
        )

    private fun parseTypeSpec(json: JsonObject): TypeSpec =
        TypeSpec(
            value =
                json.stringOrNull("value")
                    ?: throw IllegalArgumentException("type spec requires a string 'value'"),
            match = parseNameMatch(json.stringOrNull("match")),
        )

    private fun parseStringSpec(element: JsonElement): StringSpec =
        when (element) {
            is JsonPrimitive -> {
                StringSpec(element.content)
            }

            is JsonObject -> {
                StringSpec(
                    value =
                        element.stringOrNull("value")
                            ?: throw IllegalArgumentException(
                                "string spec requires a string 'value'",
                            ),
                    match =
                        parseNameMatch(element.stringOrNull("match"), default = NameMatch.Contains),
                )
            }

            else -> {
                throw IllegalArgumentException("invalid using_strings entry")
            }
        }

    private fun parseUsingField(element: JsonElement): UsingFieldSpec {
        val json = element.asObject()
        return UsingFieldSpec(
            field = json.objectOrNull("field")?.let(::parseField) ?: FieldSpec(),
            usingType =
                when (json.stringOrNull("using_type")?.lowercase()) {
                    null -> null
                    "read" -> dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType.Read
                    "write" -> dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType.Write
                    else -> throw IllegalArgumentException("using_type must be read or write")
                },
        )
    }

    private fun parseOpcodes(json: JsonObject): OpcodeSpec =
        OpcodeSpec(
            names = json.arrayOrNull("names").orEmpty().map { (it as JsonPrimitive).content },
            matchType =
                when (json.stringOrNull("match_type")?.lowercase()) {
                    null,
                    "contains" -> {
                        OpcodeMatchType.Contains
                    }

                    "equals" -> {
                        OpcodeMatchType.Equals
                    }

                    else -> {
                        throw IllegalArgumentException(
                            "opcode match_type must be contains or equals",
                        )
                    }
                },
            count = json.rangeOrNull("count"),
        )

    private fun parseAnnotation(json: JsonObject): AnnotationSpec =
        AnnotationSpec(
            type =
                json.objectOrNull("type")?.let(::parseNameSpec)
                    ?: json.stringOrNull("type")?.let { NameSpec(it) },
            visibility =
                when (json.stringOrNull("visibility")?.lowercase()) {
                    null -> null
                    "build" -> AnnotationVisibility.Build
                    "runtime" -> AnnotationVisibility.Runtime
                    "system" -> AnnotationVisibility.System
                    "unknown" -> AnnotationVisibility.Unknown
                    else -> throw IllegalArgumentException("unknown annotation visibility")
                },
            elements =
                json.arrayOrNull("elements").orEmpty().map { element ->
                    val elementJson = element.asObject()
                    AnnotationElementSpec(
                        name =
                            elementJson.objectOrNull("name")?.let(::parseNameSpec)
                                ?: elementJson.stringOrNull("name")?.let { NameSpec(it) }
                                ?: throw IllegalArgumentException(
                                    "annotation element requires a name",
                                ),
                        value = parseAnnotationValue(elementJson["value"]),
                    )
                },
        )

    private fun parseAnnotationValue(element: JsonElement?): AnnotationValue {
        if (element == null) throw IllegalArgumentException("annotation element requires a value")
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.booleanOrNull != null -> {
                        AnnotationValue.BooleanValue(element.booleanOrNull!!)
                    }

                    element.isString -> {
                        AnnotationValue.Text(element.content)
                    }

                    else -> {
                        AnnotationValue.NumberValue(element.content.toDouble())
                    }
                }
            }

            is JsonArray -> {
                AnnotationValue.ListValue(element.map(::parseAnnotationValue))
            }

            is JsonObject -> {
                when {
                    element.containsKey("text") -> {
                        AnnotationValue.Text(element.stringOrNull("text")!!)
                    }

                    element.containsKey("descriptor") -> {
                        AnnotationValue.DescriptorValue(element.stringOrNull("descriptor")!!)
                    }

                    element.containsKey("number") -> {
                        AnnotationValue.NumberValue(parseNumber(element["number"]!!))
                    }

                    element.containsKey("boolean") -> {
                        AnnotationValue.BooleanValue(element.booleanOrNull("boolean") ?: false)
                    }

                    element.containsKey("list") -> {
                        AnnotationValue.ListValue(
                            (element["list"] as JsonArray).map(::parseAnnotationValue),
                        )
                    }

                    element.containsKey("nested") -> {
                        AnnotationValue.Nested(parseAnnotation(element["nested"] as JsonObject))
                    }

                    else -> {
                        throw IllegalArgumentException("invalid annotation value object")
                    }
                }
            }
        }
    }

    private fun parseNumber(element: JsonElement): Number {
        val text =
            (element as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("using_numbers entry must be a number")
        return text.toLongOrNull()
            ?: text.toDoubleOrNull()
            ?: throw IllegalArgumentException("using_numbers entry must be a number")
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private fun parseNameMatch(value: String?, default: NameMatch = NameMatch.Equals): NameMatch =
        when (value?.trim()?.lowercase()?.replace('-', '_')) {
            null,
            "" -> default

            "equals",
            "eq" -> NameMatch.Equals

            "starts_with",
            "startswith",
            "prefix" -> NameMatch.StartsWith

            "contains" -> NameMatch.Contains

            "ends_with",
            "endswith",
            "suffix" -> NameMatch.EndsWith

            else -> throw IllegalArgumentException("unknown name/type match mode: $value")
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.stringList(key: String): List<String> =
        arrayOrNull(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.rangeOrNull(key: String): IntRange? {
        val element = this[key] ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return when (element) {
            is JsonPrimitive -> {
                val value =
                    element.intOrNull
                        ?: throw IllegalArgumentException("$key must be an int or range")
                value..value
            }

            is JsonArray -> {
                val min =
                    (element.getOrNull(0) as? JsonPrimitive)?.intOrNull
                        ?: throw IllegalArgumentException("$key range requires [min, max]")
                val max = (element.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: min
                min..max
            }

            is JsonObject -> {
                val min = (element["min"] as? JsonPrimitive)?.intOrNull ?: 0
                val max = (element["max"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
                min..max
            }
        }
    }

    private fun JsonElement.asObject(): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")
}
