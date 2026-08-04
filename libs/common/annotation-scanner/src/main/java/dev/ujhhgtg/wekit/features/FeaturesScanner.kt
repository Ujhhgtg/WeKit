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
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val PACKAGE_NAME = "dev.ujhhgtg.wekit"
private const val HOOKS_CORE_PACKAGE = "$PACKAGE_NAME.features.core"
private const val BASE_HOOK_ITEM = "BaseFeature"
private const val RESOLVER_INTERFACE = "$PACKAGE_NAME.dexkit.abc.IResolveDex"

class FeaturesKspProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return FeaturesScanner(environment.codeGenerator, environment.logger)
    }
}

class FeaturesScanner(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    // Guard against being called multiple times (KSP can call process() more than once)
    private var generated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        generated = true

        val symbols = resolver
            .getSymbolsWithAnnotation("$HOOKS_CORE_PACKAGE.Feature")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (symbols.isEmpty()) return emptyList()

        // Validate: every annotated symbol must be an `object`
        symbols.forEach { symbol ->
            if (symbol.classKind != com.google.devtools.ksp.symbol.ClassKind.OBJECT) {
                logger.error(
                    "${symbol.qualifiedName?.asString()} is annotated with @Feature but is not an `object`. " +
                            "Only Kotlin objects are supported.",
                    symbol
                )
            }
        }

        val sortedSymbols = symbols.sortedWith(
            compareBy(
                // SwitchFeature first, ClickableFeature second
                { symbol ->
                    // The whole chain, not just the direct supertypes: features are free to extend
                    // an intermediate base class (e.g. AutoMomentsBase : ClickableFeature()), and
                    // those must still be bucketed by the core class they ultimately derive from.
                    val superTypes = symbol.getAllSuperTypes()
                        .map { it.declaration.qualifiedName?.asString() }
                        .toSet()
                    when {
                        // ClickableFeature extends SwitchFeature, so the more specific one wins
                        superTypes.contains("$HOOKS_CORE_PACKAGE.ClickableFeature") -> 1
                        superTypes.contains("$HOOKS_CORE_PACKAGE.SwitchFeature") -> 0
                        else -> 2
                    }
                },
                // Then alphabetically by item name safely
                { symbol ->
                    val annotation = symbol.annotations.first { it.shortName.asString() == "Feature" }
                    val nameArg = annotation.arguments.find { it.name?.asString() == "name" } ?: annotation.arguments[0]
                    nameArg.value as String
                }
            )
        )

        val baseFeatureClass = ClassName(HOOKS_CORE_PACKAGE, BASE_HOOK_ITEM)
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(baseFeatureClass)

        // Build the list initializer as a CodeBlock
        val initializerBlock = CodeBlock.builder().apply {
            addStatement("listOf(")
            indent()
            for (symbol in sortedSymbols) {
                val typeName = symbol.toClassName()

                // Extract annotation values safely using standard KSP API
                val annotation = symbol.annotations.first { it.shortName.asString() == "Feature" }
                val name = (annotation.arguments.find { it.name?.asString() == "name" } ?: annotation.arguments[0]).value as String
                val categories = (annotation.arguments.find { it.name?.asString() == "categories" } ?: annotation.arguments[1]).value as List<*>
                val description = (annotation.arguments.find { it.name?.asString() == "description" } ?: annotation.arguments.getOrNull(2))?.value as? String ?: ""

                // Inline apply {} so path/description are set on construction
                addStatement(
                    "%T.apply·{ name·=·%S; categories·=·listOf(%L); description·=·%S },",
                    typeName,
                    name,
                    // %S so KotlinPoet escapes quotes/backslashes/`$` in category names for us
                    categories.map { CodeBlock.of("%S", it.toString()) }.joinToCode(", "),
                    description,
                )
            }
            unindent()
            add(")")
        }.build()

        // `val ALL_HOOK_ITEMS: List<BaseFeature> = listOf(...)`
        val property = PropertySpec.builder("ALL_HOOK_ITEMS", listType)
            .initializer(initializerBlock)
            .build()

        val objectSpec = TypeSpec.objectBuilder("FeaturesProvider")
            .addProperty(property)
            .addKdoc(
                "Auto-generated by [${PACKAGE_NAME}.features.FeaturesScanner]. Do not edit manually.\n" +
                        "Contains all classes annotated with [%T].\n",
                ClassName(HOOKS_CORE_PACKAGE, "Feature"),
            )
            .build()

        val dependencies = Dependencies(
            aggregating = true,
            *symbols.map { it.containingFile!! }.toTypedArray()
        )

        FileSpec.builder(HOOKS_CORE_PACKAGE, "FeaturesProvider")
            .addType(objectSpec)
            .build()
            .writeTo(codeGenerator, dependencies)

        val resolverSymbols = sortedSymbols.filter { symbol ->
            symbol.getAllSuperTypes().any {
                it.declaration.qualifiedName?.asString() == RESOLVER_INTERFACE
            }
        }
        val resolverStringType = ClassName("kotlin", "String")
        val resolverCategoriesType = ClassName("kotlin.collections", "List").parameterizedBy(resolverStringType)
        val resolverEntryClass = TypeSpec.classBuilder("DexResolutionTestEntry")
            .primaryConstructor(
                com.squareup.kotlinpoet.FunSpec.constructorBuilder()
                    .addParameter("className", resolverStringType)
                    .addParameter("name", resolverStringType)
                    .addParameter("categories", resolverCategoriesType)
                    .addParameter("description", resolverStringType)
                    .build()
            )
            .addProperty(PropertySpec.builder("className", resolverStringType).initializer("className").build())
            .addProperty(PropertySpec.builder("name", resolverStringType).initializer("name").build())
            .addProperty(PropertySpec.builder("categories", resolverCategoriesType).initializer("categories").build())
            .addProperty(PropertySpec.builder("description", resolverStringType).initializer("description").build())
            .build()

        val resolverItems = CodeBlock.builder().apply {
            addStatement("listOf(")
            indent()
            for (symbol in resolverSymbols) {
                val annotation = symbol.annotations.first { it.shortName.asString() == "Feature" }
                val name = (annotation.arguments.find { it.name?.asString() == "name" } ?: annotation.arguments[0]).value as String
                val categories = (annotation.arguments.find { it.name?.asString() == "categories" } ?: annotation.arguments[1]).value as List<*>
                val description = (annotation.arguments.find { it.name?.asString() == "description" } ?: annotation.arguments.getOrNull(2))?.value as? String ?: ""
                addStatement(
                    "DexResolutionTestEntry(%S, %S, listOf(%L), %S),",
                    symbol.qualifiedName!!.asString(),
                    name,
                    categories.map { CodeBlock.of("%S", it.toString()) }.joinToCode(", "),
                    description,
                )
            }
            unindent()
            add(")")
        }.build()

        val resolverRegistry = TypeSpec.objectBuilder("DexResolutionTestRegistry")
            .addProperty(
                PropertySpec.builder(
                    "ITEMS",
                    ClassName("kotlin.collections", "List").parameterizedBy(ClassName(HOOKS_CORE_PACKAGE, "DexResolutionTestEntry")),
                )
                    .initializer(resolverItems)
                    .build()
            )
            .addKdoc("Auto-generated lazy metadata for desktop DexKit resolution tests.\n")
            .build()

        FileSpec.builder(HOOKS_CORE_PACKAGE, "DexResolutionTestRegistry")
            .addType(resolverEntryClass)
            .addType(resolverRegistry)
            .build()
            .writeTo(codeGenerator, dependencies)

        return emptyList()
    }
}
