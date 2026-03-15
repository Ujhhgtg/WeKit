package moe.ouom.wekit.hooks

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import moe.ouom.wekit.hooks.utils.annotation.HookItem

/**
 * KSP 处理器根据 @HookItem 注解扫描并生成代码
 */

class HookItemProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return HookItemScanner(environment.codeGenerator)
    }
}

class HookItemScanner(
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols =
            resolver.getSymbolsWithAnnotation("moe.ouom.wekit.hooks.utils.annotation.HookItem")
                .filterIsInstance<KSClassDeclaration>()
                .toList()

        if (symbols.isEmpty()) return emptyList()

        // 对 symbols 进行排序
        // 1. BaseSwitchFunctionHookItem 优先级高于 BaseClickableFunctionHookItem
        // 2. 同类型内按照 path 中的最后一部分（itemname）的首字母排序
        val sortedSymbols = symbols.sortedWith(compareBy(
            // 第一排序：按照类型排序，BaseSwitchFunctionHookItem 优先
            { symbol ->
                val superTypes = symbol.superTypes.map { it.resolve().declaration.qualifiedName?.asString() }
                when {
                    superTypes.contains("moe.ouom.wekit.core.model.SwitchHookItem") -> 0
                    superTypes.contains("moe.ouom.wekit.core.model.ClickableHookItem") -> 1
                    else -> 2
                }
            },
            // 第二排序：按照 path 中的最后一部分的首字母排序
            { symbol ->
                val hookItem = symbol.getAnnotationsByType(HookItem::class).firstOrNull()
                val path = hookItem?.path ?: ""
                val itemName = path.substringAfterLast("/", path)
                itemName
            }
        ))

        // 准备返回类型和基类
        val returnType = ClassName("kotlin.collections", "List")
        val genericsType = ClassName("moe.ouom.wekit.core.model", "BaseHookItem")

        // 创建方法构建器
        val methodBuilder = FunSpec.builder("getAllHookItems")
            .returns(returnType.parameterizedBy(genericsType)) // 泛型返回

        // 构建方法体
        methodBuilder.addCode(
            CodeBlock.Builder().apply {
                addStatement("val list = mutableListOf<BaseHookItem>()")

                // 遍历所有被 @HookItem 注解的类（已排序）
                for (symbol in sortedSymbols) {
                    val typeName = symbol.toClassName()
                    val hookItem = symbol.getAnnotationsByType(HookItem::class).first()
                    val itemName = hookItem.path
                    val desc = hookItem.desc
                    val valName = symbol.toClassName().simpleName

                    val isKtObject =
                        symbol.classKind == ClassKind.OBJECT
                    if (!isKtObject) {
                        addStatement("val %N = %T()", valName, typeName)
                    }
                    else {
                        addStatement("val %N = %T", valName, typeName)
                    }
                    addStatement("%N.path = %S", valName, itemName)
                    addStatement("%N.description = %S", valName, desc)
                    addStatement("list.add(%N)", valName)
                }
                addStatement("return list")
            }.build()
        )

        // 创建最终类
        val classSpec = TypeSpec.objectBuilder("HookItemEntryList")
            .addFunction(methodBuilder.build())
            .build()

        // 输出文件到指定目录
        val dependencies = Dependencies(true, *symbols.map { it.containingFile!! }.toTypedArray())
        FileSpec.builder("moe.ouom.wekit.hooks.gen", "HookItemEntryList")
            .addType(classSpec)
            .build()
            .writeTo(codeGenerator, dependencies)

        return emptyList()
    }
}