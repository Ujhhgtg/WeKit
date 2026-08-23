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
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val PACKAGE_NAME = "dev.ujhhgtg.wekit"
private const val FEATURES_CORE_PACKAGE = "$PACKAGE_NAME.features.core"
private const val EXTENSIONS_PACKAGE = "$PACKAGE_NAME.extensions"
private const val BASE_FEATURE = "BaseFeature"
private const val EXTENSION_PACK = "$EXTENSIONS_PACKAGE.ExtensionPack"
private const val RESOLVER_INTERFACE = "$PACKAGE_NAME.dexkit.abc.IResolveDex"
private val RESOURCE_ENTRY_PATTERN = Regex("[a-z][a-z0-9_]*")
private val VALID_CATEGORY_IDS = setOf(
    "chat",
    "contacts_groups",
    "payment",
    "moments",
    "system_privacy",
    "voip",
    "notifications",
    "beautify",
    "official_accounts",
    "miniapps",
    "channels",
    "profile",
    "debug",
    "scripting_java",
    "entertain",
    "batch",
    "home_screen_menu",
    "contact_details",
    "api",
)

class FeaturesKspProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FeaturesScanner(environment.codeGenerator, environment.logger)
}

class FeaturesScanner(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var generated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        generated = true

        val symbols = resolver
            .getSymbolsWithAnnotation("$FEATURES_CORE_PACKAGE.Feature")
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (symbols.isEmpty()) return emptyList()
        val extensionPacks = resolver.getAllFiles()
            .flatMap { file -> file.declarations.flatMap(KSDeclaration::classDeclarationsRecursively) }
            .filter { it.classKind == ClassKind.OBJECT }
            .filter { symbol ->
                symbol.getAllSuperTypes().any {
                    it.declaration.qualifiedName?.asString() == EXTENSION_PACK
                }
            }
            .sortedBy { it.qualifiedName!!.asString() }
            .toList()

        symbols.forEach { symbol ->
            if (symbol.classKind != ClassKind.OBJECT) {
                logger.error(
                    "${symbol.qualifiedName?.asString()} is annotated with @Feature but is not an object",
                    symbol,
                )
            }
        }

        val metadataBySymbol = symbols.associateWith(::readMetadata)
        validateMetadata(metadataBySymbol)
        val sortedSymbols = symbols.sortedWith(
            compareBy(
                { featureTypeOrder(it) },
                { metadataBySymbol.getValue(it).technicalId },
            ),
        )
        val dependencies = Dependencies(
            aggregating = true,
            *symbols.map { it.containingFile!! }.toTypedArray(),
        )

        generateRuntimeProvider(sortedSymbols, metadataBySymbol, dependencies)
        generateMetadataRegistry(sortedSymbols, metadataBySymbol, dependencies)
        generateDexResolutionRegistry(sortedSymbols, metadataBySymbol, dependencies)
        generateExtensionPacksProvider(extensionPacks)
        return emptyList()
    }

    private fun generateExtensionPacksProvider(symbols: List<KSClassDeclaration>) {
        val extensionPack = ClassName(EXTENSIONS_PACKAGE, "ExtensionPack")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(extensionPack)
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
            .addProperty(PropertySpec.builder("ALL_PACKS", listType).initializer(initializer).build())
            .addKdoc("Auto-generated extension pack registry. Do not edit manually.\n")
            .build()
        val dependencies = Dependencies(
            aggregating = true,
            *symbols.map { it.containingFile!! }.toTypedArray(),
        )
        FileSpec.builder(EXTENSIONS_PACKAGE, "ExtensionPacksProvider")
            .addType(provider)
            .build()
            .writeTo(codeGenerator, dependencies)
    }

    private fun readMetadata(symbol: KSClassDeclaration): FeatureMetadata {
        val annotation = symbol.annotations.first { it.shortName.asString() == "Feature" }
        fun argument(name: String): Any? =
            annotation.arguments.first { it.name?.asString() == name }.value

        return FeatureMetadata(
            className = symbol.qualifiedName!!.asString(),
            technicalId = argument("id") as String,
            nameResEntry = argument("nameRes") as String,
            categoryIds = (argument("categoryIds") as List<*>).map(Any?::toString),
            descriptionResEntry = (argument("descriptionRes") as? String).orEmpty(),
        )
    }

    private fun validateMetadata(metadataBySymbol: Map<KSClassDeclaration, FeatureMetadata>) {
        metadataBySymbol.forEach { (symbol, metadata) ->
            if (metadata.technicalId.isEmpty()) logger.error("Feature ID must not be empty", symbol)
            if (!RESOURCE_ENTRY_PATTERN.matches(metadata.nameResEntry)) {
                logger.error("Invalid name resource entry: ${metadata.nameResEntry}", symbol)
            }
            if (metadata.descriptionResEntry.isNotEmpty() &&
                !RESOURCE_ENTRY_PATTERN.matches(metadata.descriptionResEntry)
            ) {
                logger.error("Invalid description resource entry: ${metadata.descriptionResEntry}", symbol)
            }
            if (metadata.categoryIds.isEmpty() || metadata.categoryIds.any(String::isEmpty)) {
                logger.error("Feature category IDs must not be empty", symbol)
            }
            val unknownCategories = metadata.categoryIds.filterNot(VALID_CATEGORY_IDS::contains)
            if (unknownCategories.isNotEmpty()) {
                logger.error("Unknown feature category IDs: $unknownCategories", symbol)
            }
        }
        metadataBySymbol.entries
            .groupBy { it.value.technicalId }
            .filterValues { it.size > 1 }
            .forEach { (technicalId, duplicates) ->
                duplicates.forEach { (symbol, _) ->
                    logger.error("Duplicate feature ID: $technicalId", symbol)
                }
            }
    }

    @OptIn(KspExperimental::class)
    private fun featureTypeOrder(symbol: KSClassDeclaration): Int {
        val superTypes = symbol.getAllSuperTypes()
            .map { it.declaration.qualifiedName?.asString() }
            .toSet()
        return when {
            "$FEATURES_CORE_PACKAGE.ClickableFeature" in superTypes -> 1
            "$FEATURES_CORE_PACKAGE.SwitchFeature" in superTypes -> 0
            else -> 2
        }
    }

    private fun generateRuntimeProvider(
        symbols: List<KSClassDeclaration>,
        metadataBySymbol: Map<KSClassDeclaration, FeatureMetadata>,
        dependencies: Dependencies,
    ) {
        val rString = ClassName(PACKAGE_NAME, "R", "string")
        val initializer = CodeBlock.builder().apply {
            addStatement("listOf(")
            indent()
            symbols.forEach { symbol ->
                val metadata = metadataBySymbol.getValue(symbol)
                val description = if (metadata.descriptionResEntry.isEmpty()) {
                    CodeBlock.of("null")
                } else {
                    CodeBlock.of("%T.%L", rString, metadata.descriptionResEntry)
                }
                addStatement(
                    "%T.apply·{ technicalId·=·%S; nameRes·=·%T.%L; categoryIds·=·listOf(%L); descriptionRes·=·%L },",
                    symbol.toClassName(),
                    metadata.technicalId,
                    rString,
                    metadata.nameResEntry,
                    metadata.categoryIds.map { CodeBlock.of("%S", it) }.joinToCode(", "),
                    description,
                )
            }
            unindent()
            add(")")
        }.build()
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ClassName(FEATURES_CORE_PACKAGE, BASE_FEATURE))
        val provider = TypeSpec.objectBuilder("FeaturesProvider")
            .addProperty(PropertySpec.builder("ALL_HOOK_ITEMS", listType).initializer(initializer).build())
            .addKdoc("Auto-generated runtime feature registry. Do not edit manually.\n")
            .build()
        FileSpec.builder(FEATURES_CORE_PACKAGE, "FeaturesProvider")
            .addType(provider)
            .build()
            .writeTo(codeGenerator, dependencies)
    }

    private fun generateMetadataRegistry(
        symbols: List<KSClassDeclaration>,
        metadataBySymbol: Map<KSClassDeclaration, FeatureMetadata>,
        dependencies: Dependencies,
    ) {
        val entryClassName = ClassName(FEATURES_CORE_PACKAGE, "FeatureMetadataEntry")
        val entryClass = metadataEntryClass("FeatureMetadataEntry")
        val entries = metadataListInitializer(symbols.map(metadataBySymbol::getValue), "FeatureMetadataEntry")
        val registry = TypeSpec.objectBuilder("FeatureMetadataRegistry")
            .addProperty(
                PropertySpec.builder(
                    "ALL",
                    ClassName("kotlin.collections", "List").parameterizedBy(entryClassName),
                ).initializer(entries).build(),
            )
            .addKdoc("Auto-generated metadata-only feature registry. Do not edit manually.\n")
            .build()
        FileSpec.builder(FEATURES_CORE_PACKAGE, "FeatureMetadataRegistry")
            .addType(entryClass)
            .addType(registry)
            .build()
            .writeTo(codeGenerator, dependencies)
    }

    @OptIn(KspExperimental::class)
    private fun generateDexResolutionRegistry(
        symbols: List<KSClassDeclaration>,
        metadataBySymbol: Map<KSClassDeclaration, FeatureMetadata>,
        dependencies: Dependencies,
    ) {
        val resolverMetadata = symbols
            .filter { symbol ->
                symbol.getAllSuperTypes().any {
                    it.declaration.qualifiedName?.asString() == RESOLVER_INTERFACE
                }
            }
            .map(metadataBySymbol::getValue)
        val entryClassName = ClassName(FEATURES_CORE_PACKAGE, "DexResolutionTestEntry")
        val registry = TypeSpec.objectBuilder("DexResolutionTestRegistry")
            .addProperty(
                PropertySpec.builder(
                    "ITEMS",
                    ClassName("kotlin.collections", "List").parameterizedBy(entryClassName),
                ).initializer(metadataListInitializer(resolverMetadata, "DexResolutionTestEntry")).build(),
            )
            .addKdoc("Auto-generated metadata-only registry for desktop DexKit tests.\n")
            .build()
        FileSpec.builder(FEATURES_CORE_PACKAGE, "DexResolutionTestRegistry")
            .addType(metadataEntryClass("DexResolutionTestEntry"))
            .addType(registry)
            .build()
            .writeTo(codeGenerator, dependencies)
    }

    private fun metadataEntryClass(name: String): TypeSpec {
        val stringType = ClassName("kotlin", "String")
        val categoryType = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val constructor = FunSpec.constructorBuilder()
            .addParameter("className", stringType)
            .addParameter("technicalId", stringType)
            .addParameter("nameResEntry", stringType)
            .addParameter("categoryIds", categoryType)
            .addParameter("descriptionResEntry", stringType.copy(nullable = true))
            .build()
        return TypeSpec.classBuilder(name)
            .addModifiers(com.squareup.kotlinpoet.KModifier.DATA)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("className", stringType).initializer("className").build())
            .addProperty(PropertySpec.builder("technicalId", stringType).initializer("technicalId").build())
            .addProperty(PropertySpec.builder("nameResEntry", stringType).initializer("nameResEntry").build())
            .addProperty(PropertySpec.builder("categoryIds", categoryType).initializer("categoryIds").build())
            .addProperty(
                PropertySpec.builder("descriptionResEntry", stringType.copy(nullable = true))
                    .initializer("descriptionResEntry")
                    .build(),
            )
            .build()
    }

    private fun metadataListInitializer(
        metadata: List<FeatureMetadata>,
        entryType: String,
    ): CodeBlock = CodeBlock.builder().apply {
        addStatement("listOf(")
        indent()
        metadata.forEach { entry ->
            addStatement(
                "%L(%S, %S, %S, listOf(%L), %L),",
                entryType,
                entry.className,
                entry.technicalId,
                entry.nameResEntry,
                entry.categoryIds.map { CodeBlock.of("%S", it) }.joinToCode(", "),
                if (entry.descriptionResEntry.isEmpty()) CodeBlock.of("null")
                else CodeBlock.of("%S", entry.descriptionResEntry),
            )
        }
        unindent()
        add(")")
    }.build()
}

private data class FeatureMetadata(
    val className: String,
    val technicalId: String,
    val nameResEntry: String,
    val categoryIds: List<String>,
    val descriptionResEntry: String,
)

private fun KSDeclaration.classDeclarationsRecursively(): Sequence<KSClassDeclaration> = sequence {
    val declaration = this@classDeclarationsRecursively
    if (declaration is KSClassDeclaration) {
        yield(declaration)
        declaration.declarations.forEach { child ->
            yieldAll(child.classDeclarationsRecursively())
        }
    }
}
