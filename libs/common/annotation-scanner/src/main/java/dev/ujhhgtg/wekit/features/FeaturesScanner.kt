package dev.ujhhgtg.wekit.features

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val PACKAGE_NAME = "dev.ujhhgtg.wekit"
private const val FEATURES_CORE_PACKAGE = "$PACKAGE_NAME.features.core"
private const val EXTENSIONS_PACKAGE = "$PACKAGE_NAME.extensions"
private const val BASE_FEATURE = "$FEATURES_CORE_PACKAGE.BaseFeature"
private const val EXTENSION_PACK = "$EXTENSIONS_PACKAGE.ExtensionPack"
private const val RESOLVER_INTERFACE = "$PACKAGE_NAME.dexkit.abc.IResolveDex"

class FeaturesKspProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeaturesScanner(environment.codeGenerator, environment.logger)
}

/** Generates runtime registries for source Feature and ExtensionPack objects. */
class FeaturesScanner(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var generated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        generated = true

        val objects = resolver.getAllFiles()
            .flatMap { file -> file.declarations.flatMap(KSDeclaration::classDeclarationsRecursively) }
            .filter { it.classKind == ClassKind.OBJECT }
            .toList()
        val features = objects
            .filter { it.isSubtypeOf(BASE_FEATURE) }
            .sortedBy { it.qualifiedName!!.asString() }
        val extensionPacks = objects
            .filter { it.isSubtypeOf(EXTENSION_PACK) }
            .sortedBy { it.qualifiedName!!.asString() }

        if (features.isEmpty()) {
            logger.error("No BaseFeature objects were discovered in app sources")
            return emptyList()
        }
        if (extensionPacks.isEmpty()) {
            logger.error("No ExtensionPack objects were discovered in app sources")
            return emptyList()
        }

        features.groupBy { it.containingFile!! }
            .filterValues { it.size > 1 }
            .forEach { (file, duplicates) ->
                duplicates.forEach { symbol ->
                    logger.error(
                        "Feature source ${file.filePath} declares multiple Feature objects: " +
                            duplicates.joinToString { it.qualifiedName!!.asString() },
                        symbol,
                    )
                }
            }

        generateFeaturesProvider(features)
        generateDexResolutionRegistry(features.filter { it.isSubtypeOf(RESOLVER_INTERFACE) })
        generateExtensionPacksProvider(extensionPacks)
        return emptyList()
    }

    private fun generateFeaturesProvider(symbols: List<KSClassDeclaration>) {
        val baseFeature = ClassName(FEATURES_CORE_PACKAGE, "BaseFeature")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(baseFeature)
        val sourceMapType = ClassName("kotlin.collections", "Map").parameterizedBy(
            baseFeature,
            ClassName("kotlin", "String"),
        )
        val featureList = CodeBlock.builder().apply {
            add("validateFeatures(\n")
            indent()
            add("listOf(\n")
            indent()
            symbols.forEach { add("%T,\n", it.toClassName()) }
            unindent()
            add(")\n")
            unindent()
            add(").sortedWith(\n")
            indent()
            add("compareBy(\n")
            indent()
            add("{ feature ->\n")
            indent()
            add("when (feature) {\n")
            indent()
            add("is %T -> 1\n", ClassName(FEATURES_CORE_PACKAGE, "ClickableFeature"))
            add("is %T -> 0\n", ClassName(FEATURES_CORE_PACKAGE, "SwitchFeature"))
            add("else -> 2\n")
            unindent()
            add("}\n")
            unindent()
            add("},\n")
            add("{ feature -> feature.technicalId },\n")
            unindent()
            add(")\n")
            unindent()
            add(")")
        }.build()
        val sourceKeys = CodeBlock.builder().apply {
            add("mapOf(\n")
            indent()
            symbols.forEach { symbol ->
                val source = symbol.containingFile!!
                val sourceKey = source.packageName.asString().replace('.', '/') + "/" + source.fileName
                add("%T to %S,\n", symbol.toClassName(), sourceKey)
            }
            unindent()
            add(")")
        }.build()
        val provider = TypeSpec.objectBuilder("FeaturesProvider")
            .addProperty(PropertySpec.builder("ALL_FEATURES", listType).initializer(featureList).build())
            .addProperty(
                PropertySpec.builder("SOURCE_KEY_BY_FEATURE", sourceMapType)
                    .initializer(sourceKeys)
                    .build(),
            )
            .addKdoc("Auto-generated runtime Feature registry. Do not edit manually.\n")
            .build()
        FileSpec.builder(FEATURES_CORE_PACKAGE, "FeaturesProvider")
            .addType(provider)
            .build()
            .writeTo(codeGenerator, dependencies(symbols))
    }

    private fun generateDexResolutionRegistry(symbols: List<KSClassDeclaration>) {
        val entryType = ClassName(FEATURES_CORE_PACKAGE, "DexResolutionTestEntry")
        val stringType = ClassName("kotlin", "String")
        val entryClass = TypeSpec.classBuilder(entryType.simpleName)
            .addModifiers(com.squareup.kotlinpoet.KModifier.DATA)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("className", stringType).build())
            .addProperty(PropertySpec.builder("className", stringType).initializer("className").build())
            .build()
        val entries = CodeBlock.builder().apply {
            add("listOf(\n")
            indent()
            symbols.forEach { add("%T(%S),\n", entryType, it.qualifiedName!!.asString()) }
            unindent()
            add(")")
        }.build()
        val registry = TypeSpec.objectBuilder("DexResolutionTestRegistry")
            .addProperty(
                PropertySpec.builder(
                    "ITEMS",
                    ClassName("kotlin.collections", "List").parameterizedBy(entryType),
                ).initializer(entries).build(),
            )
            .addKdoc("Auto-generated lazy registry for desktop DexKit tests.\n")
            .build()
        FileSpec.builder(FEATURES_CORE_PACKAGE, "DexResolutionTestRegistry")
            .addType(entryClass)
            .addType(registry)
            .build()
            .writeTo(codeGenerator, dependencies(symbols))
    }

    private fun generateExtensionPacksProvider(symbols: List<KSClassDeclaration>) {
        val extensionPack = ClassName(EXTENSIONS_PACKAGE, "ExtensionPack")
        val initializer = CodeBlock.builder().apply {
            add("validateExtensionPacks(\n")
            indent()
            add("listOf(\n")
            indent()
            symbols.forEach { add("%T,\n", it.toClassName()) }
            unindent()
            add(").sortedWith(compareBy({ it.displayOrder }, { it.id })),\n")
            unindent()
            add(")")
        }.build()
        val provider = TypeSpec.objectBuilder("ExtensionPacksProvider")
            .addProperty(
                PropertySpec.builder(
                    "ALL_PACKS",
                    ClassName("kotlin.collections", "List").parameterizedBy(extensionPack),
                ).initializer(initializer).build(),
            )
            .addKdoc("Auto-generated extension pack registry. Do not edit manually.\n")
            .build()
        FileSpec.builder(EXTENSIONS_PACKAGE, "ExtensionPacksProvider")
            .addType(provider)
            .build()
            .writeTo(codeGenerator, dependencies(symbols))
    }

    private fun dependencies(symbols: List<KSClassDeclaration>): Dependencies = Dependencies(
        aggregating = true,
        *symbols.map { it.containingFile!! }.distinct().toTypedArray(),
    )
}

@OptIn(KspExperimental::class)
private fun KSClassDeclaration.isSubtypeOf(qualifiedName: String): Boolean =
    getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == qualifiedName }

private fun KSDeclaration.classDeclarationsRecursively(): Sequence<KSClassDeclaration> = sequence {
    val declaration = this@classDeclarationsRecursively
    if (declaration is KSClassDeclaration) {
        yield(declaration)
        declaration.declarations.forEach { child ->
            yieldAll(child.classDeclarationsRecursively())
        }
    }
}
