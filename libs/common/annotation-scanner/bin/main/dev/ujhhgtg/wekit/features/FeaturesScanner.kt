package dev.ujhhgtg.wekit.features

import com.google.devtools.ksp.KspExperimental
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
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val PACKAGE_NAME = "dev.ujhhgtg.wekit"
private const val FEATURES_CORE_PACKAGE = "$PACKAGE_NAME.features.core"
private const val BASE_FEATURE = "BaseFeature"

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
            .getSymbolsWithAnnotation("$FEATURES_CORE_PACKAGE.Feature")
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
                    val superTypes = symbol.superTypes
                        .map { it.resolve().declaration.qualifiedName?.asString() }
                        .toSet()
                    when {
                        superTypes.contains("$FEATURES_CORE_PACKAGE.SwitchFeature") -> 0
                        superTypes.contains("$FEATURES_CORE_PACKAGE.ClickableFeature") -> 1
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

        val baseFeatureClass = ClassName(FEATURES_CORE_PACKAGE, BASE_FEATURE)
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
                    categories.joinToString(", ") { "\"$it\"" },
                    description,
                )
            }
            unindent()
            add(")")
        }.build()

        // `val ALL_FEATURES: List<BaseFeature> = listOf(...)`
        val property = PropertySpec.builder("ALL_FEATURES", listType)
            .initializer(initializerBlock)
            .build()

        val objectSpec = TypeSpec.objectBuilder("FeaturesProvider")
            .addProperty(property)
            .addKdoc(
                "Auto-generated by [${PACKAGE_NAME}.features.FeaturesScanner]. Do not edit manually.\n" +
                        "Contains all classes annotated with [%T].\n",
                ClassName(FEATURES_CORE_PACKAGE, "Feature"),
            )
            .build()

        val dependencies = Dependencies(
            aggregating = true,
            *symbols.map { it.containingFile!! }.toTypedArray()
        )

        FileSpec.builder(FEATURES_CORE_PACKAGE, "FeaturesProvider")
            .addType(objectSpec)
            .build()
            .writeTo(codeGenerator, dependencies)

        return emptyList()
    }
}
