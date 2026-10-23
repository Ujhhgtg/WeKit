package dev.ujhhgtg.wekit.dextest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Temporary Phase 0 tool for the DexKit-removal migration: freezes the DexKit *Data fields
 * consumed by WeKit (and the fields the replacement metadata API must reproduce) for every
 * SUCCESS delegate in existing dex-test reports, so the DexSQL metadata API and the Kotlin
 * replacement can be validated field-by-field before DexKit is removed.
 *
 * Properties:
 *  - wekit.dexSnapshot.manifest: JSON array of {apk, report, output}
 *  - wekit.dexSnapshot.nativeLibrary: desktop libdexkit.so
 */
private fun JsonObjectBuilder.put(key: String, value: String) = put(key, JsonPrimitive(value))

private fun JsonObjectBuilder.put(key: String, value: Int) = put(key, JsonPrimitive(value))

private fun JsonObjectBuilder.put(key: String, value: Boolean) = put(key, JsonPrimitive(value))

private fun JsonArrayBuilder.add(value: String) = add(JsonPrimitive(value))

class DexKitDataSnapshotTest {

    @Test
    fun captureSnapshots() {
        val manifestPath = requireProperty("wekit.dexSnapshot.manifest")
        val nativeLibrary = requireProperty("wekit.dexSnapshot.nativeLibrary")
        System.load(nativeLibrary)
        val entries = Json.parseToJsonElement(File(manifestPath).readText()).jsonArray
        for (entryElement in entries) {
            val entry = entryElement.jsonObject
            captureOne(
                apk = entry.getValue("apk").jsonPrimitive.content,
                reportPath = entry.getValue("report").jsonPrimitive.content,
                outputPath = entry.getValue("output").jsonPrimitive.content,
            )
        }
    }

    private fun captureOne(apk: String, reportPath: String, outputPath: String) {
        val report = Json.parseToJsonElement(File(reportPath).readText()).jsonObject
        val delegates = mutableListOf<JsonObject>()
        val keys = linkedSetOf<String>()
        for (featureElement in report.getValue("features").jsonArray) {
            val feature = featureElement.jsonObject
            for (delegateElement in feature.getValue("delegates").jsonArray) {
                val delegate = delegateElement.jsonObject
                val descriptorElement = delegate["descriptor"]
                val descriptor =
                    if (descriptorElement == null || descriptorElement is JsonNull) null
                    else descriptorElement.jsonPrimitive.content
                if (delegate.getValue("status").jsonPrimitive.content != "SUCCESS" || descriptor == null) continue
                val kind = classify(descriptor)
                delegates.add(
                    buildJsonObject {
                        put("feature", feature.getValue("technicalId").jsonPrimitive.content)
                        put("key", delegate.getValue("key").jsonPrimitive.content)
                        put("kind", kind)
                        put("descriptor", descriptor)
                    }
                )
                keys.add(kind + "|" + descriptor)
            }
        }
        val data: JsonObject = buildJsonObject {
            DexKitBridge.create(apk).use { bridge ->
                for (key in keys) {
                    val separator = key.indexOf('|')
                    val kind = key.substring(0, separator)
                    val descriptor = key.substring(separator + 1)
                    val value: JsonElement? = when (kind) {
                        "class" -> bridge.getClassData(descriptor)?.let(::classJson)
                        "method" -> bridge.getMethodData(descriptor)?.let(::methodJson)
                        else -> bridge.getFieldData(descriptor)?.let(::fieldJson)
                    }
                    put(key, value ?: JsonNull)
                }
            }
        }
        val root = buildJsonObject {
            put("apk", apk)
            put("report", reportPath)
            put("delegates", JsonArray(delegates))
            put("data", data)
        }
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        GZIPOutputStream(outputFile.outputStream().buffered()).use { stream ->
            stream.write(root.toString().toByteArray())
        }
        println("snapshot " + outputPath + ": delegates=" + delegates.size + " data=" + data.size)
    }

    private fun classify(descriptor: String): String = when {
        !descriptor.contains("->") -> "class"
        descriptor.contains('(') -> "method"
        else -> "field"
    }

    private fun requireProperty(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("missing -D" + name)

    private fun classJson(data: ClassData): JsonObject = buildJsonObject {
        put("descriptor", data.descriptor)
        put("name", data.name)
        put("simple_name", data.simpleName)
        put("source_file", data.sourceFile)
        put("modifiers", data.modifiers)
        put("access_flags", data.accessFlags)
        put("is_array", data.isArray)
        val superClass = data.superClass
        put(
            "super_class",
            if (superClass == null) JsonNull else buildJsonObject {
                put("descriptor", superClass.descriptor)
                put("name", superClass.name)
            }
        )
        put(
            "interfaces",
            buildJsonArray {
                for (item in data.interfaces) {
                    add(buildJsonObject {
                        put("descriptor", item.descriptor)
                        put("name", item.name)
                    })
                }
            }
        )
        put("methods", buildJsonArray { for (item in data.methods) add(methodRefJson(item)) })
        put("fields", buildJsonArray { for (item in data.fields) add(fieldRefJson(item)) })
    }

    private fun methodRefJson(data: MethodData): JsonObject = buildJsonObject {
        put("descriptor", data.descriptor)
        put("name", data.name)
        put("declared_class_name", data.declaredClassName)
        put("method_sign", data.methodSign)
        put("modifiers", data.modifiers)
        put("access_flags", data.accessFlags)
        put("return_type_name", data.returnTypeName)
        put("param_type_names", buildJsonArray { for (item in data.paramTypeNames) add(item) })
        put("param_count", data.paramCount)
        put("is_constructor", data.isConstructor)
        put("is_static_initializer", data.isStaticInitializer)
        put("is_method", data.isMethod)
    }

    private fun methodJson(data: MethodData): JsonObject = buildJsonObject {
        put("descriptor", data.descriptor)
        put("name", data.name)
        put("declared_class_name", data.declaredClassName)
        put("method_sign", data.methodSign)
        put("modifiers", data.modifiers)
        put("access_flags", data.accessFlags)
        put("return_type_name", data.returnTypeName)
        put("param_type_names", buildJsonArray { for (item in data.paramTypeNames) add(item) })
        put("param_count", data.paramCount)
        put("is_constructor", data.isConstructor)
        put("is_static_initializer", data.isStaticInitializer)
        put("is_method", data.isMethod)
        put("invokes", buildJsonArray { for (item in data.invokes) add(methodRefJson(item)) })
        put(
            "using_fields",
            buildJsonArray {
                for (item in data.usingFields) {
                    add(buildJsonObject {
                        put("field", fieldRefJson(item.field))
                        put("using_type", item.usingType.name)
                    })
                }
            }
        )
        put("using_strings", buildJsonArray { for (item in data.usingStrings) add(item) })
    }

    private fun fieldRefJson(data: FieldData): JsonObject = buildJsonObject {
        put("descriptor", data.descriptor)
        put("name", data.name)
        put("declared_class_name", data.declaredClassName)
        put("type_name", data.typeName)
        put("modifiers", data.modifiers)
        put("access_flags", data.accessFlags)
    }

    private fun fieldJson(data: FieldData): JsonObject = buildJsonObject {
        put("descriptor", data.descriptor)
        put("name", data.name)
        put("declared_class_name", data.declaredClassName)
        put("type_name", data.typeName)
        put("type_descriptor", data.type.descriptor)
        put("modifiers", data.modifiers)
        put("access_flags", data.accessFlags)
    }
}
