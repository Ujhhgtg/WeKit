package moe.ouom.wekit.loader.utils

import moe.ouom.wekit.dexkit.DexMethodDescriptor
import moe.ouom.wekit.loader.startup.StartupInfo
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31i
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.lang.reflect.Modifier

object LibXposedApiByteCodeGenerator {

    const val CMD_SET_WRAPPER = "SetLibXposedApiByteCodeGeneratorWrapper"
    private const val ACC_CONSTRUCTOR = 0x00010000

    fun init() {
        val loader = StartupInfo.loaderService
        val call = LibXposedApiByteCodeGenerator::class.java
            .getMethod("call", Int::class.java, Array<Any>::class.java)
        loader.queryExtension(CMD_SET_WRAPPER, call)
    }

    @JvmStatic
    fun call(version: Int, args: Array<Any?>): ByteArray {
        if (version == 1) {
            return impl1(
                args[0] as String,
                args[1] as Int,
                args[2] as String,
                args[3] as String,
                args[4] as String
            )
        }
        error("Unsupported version: $version")
    }

    private fun classNameToDescriptor(className: String): String =
        "L${className.replace('.', '/')};"

    fun impl1(
        targetClassName: String,
        tagValue: Int,
        classNameXposedInterfaceHooker: String,
        classBeforeHookCallback: String,
        classAfterHookCallback: String
    ): ByteArray {
        val typeTargetClass = classNameToDescriptor(targetClassName)
        val typeXposedInterfaceHooker = classNameToDescriptor(classNameXposedInterfaceHooker)
        val typeBeforeHookCallback = classNameToDescriptor(classBeforeHookCallback)
        val typeAfterHookCallback = classNameToDescriptor(classAfterHookCallback)

        val tagField = ImmutableField(
            typeTargetClass, "tag", "I",
            Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL,
            ImmutableIntEncodedValue(tagValue),
            null, null
        )

        val methods = ArrayList<ImmutableMethod>()

        // Constructor
        run {
            val insCtor = ArrayList<Instruction>()
            insCtor.add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                    referenceMethod("Ljava/lang/Object;", "<init>", "()V")
                )
            )
            insCtor.add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            val ctorMethodImpl = ImmutableMethodImplementation(1, insCtor, null, null)
            val ctorMethod = ImmutableMethod(
                typeTargetClass, "<init>", listOf(),
                "V", Modifier.PUBLIC or ACC_CONSTRUCTOR, null, null, ctorMethodImpl
            )
            methods.add(ctorMethod)
        }

        val typeInvocationParamWrapper =
            $$"Lmoe/ouom/wekit/loader/modern/Lsp100HookWrapper$InvocationParamWrapper;"
        val typeLsp100HookAgent =
            $$"Lmoe/ouom/wekit/loader/modern/Lsp100HookWrapper$Lsp100HookAgent;"

        // before()
        run {
            val insBefore = ArrayList<Instruction>()
            insBefore.add(ImmutableInstruction31i(Opcode.CONST, 0, tagValue))
            insBefore.add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0,
                    ImmutableMethodReference(
                        typeLsp100HookAgent, "handleBeforeHookedMethod",
                        listOf(typeBeforeHookCallback, "I"), typeInvocationParamWrapper
                    )
                )
            )
            insBefore.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
            insBefore.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            val beforeMethodImpl = ImmutableMethodImplementation(2, insBefore, null, null)
            val beforeMethod = ImmutableMethod(
                typeTargetClass,
                "before",
                listOf(
                    ImmutableMethodParameter(typeBeforeHookCallback, null, "c")
                ),
                typeInvocationParamWrapper,
                Modifier.PUBLIC or Modifier.STATIC,
                null,
                null,
                beforeMethodImpl
            )
            methods.add(beforeMethod)
        }

        // after()
        run {
            val insAfter = ArrayList<Instruction>()
            insAfter.add(ImmutableInstruction31i(Opcode.CONST, 0, tagValue))
            insAfter.add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 3, 1, 2, 0, 0, 0,
                    ImmutableMethodReference(
                        typeLsp100HookAgent, "handleAfterHookedMethod",
                        listOf(typeAfterHookCallback, typeInvocationParamWrapper, "I"), "V"
                    )
                )
            )
            insAfter.add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            val afterMethodImpl = ImmutableMethodImplementation(3, insAfter, null, null)
            val afterMethod = ImmutableMethod(
                typeTargetClass, "after", listOf(
                    ImmutableMethodParameter(typeAfterHookCallback, null, "c"),
                    ImmutableMethodParameter(typeInvocationParamWrapper, null, "p")
                ), "V", Modifier.PUBLIC or Modifier.STATIC, null, null, afterMethodImpl
            )
            methods.add(afterMethod)
        }

        val classDef = ImmutableClassDef(
            typeTargetClass, Modifier.PUBLIC, "Ljava/lang/Object;",
            listOf(typeXposedInterfaceHooker),
            "LibXposedNewApiByteCodeGenerator.dexlib2", null,
            listOf(tagField), methods
        )
        val proxyDex = ImmutableDexFile(Opcodes.forDexVersion(35), listOf(classDef))

        val memoryDataStore = MemoryDataStore()
        val dexPool = DexPool(proxyDex.opcodes)
        for (cd: ClassDef in proxyDex.classes) {
            dexPool.internClass(cd)
        }
        try {
            dexPool.writeTo(memoryDataStore)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        return memoryDataStore.data
    }

    private fun referenceMethod(
        declaringClass: String,
        name: String,
        descriptor: String
    ): ImmutableMethodReference =
        referenceMethod(DexMethodDescriptor(declaringClass, name, descriptor))

    private fun referenceMethod(md: DexMethodDescriptor): ImmutableMethodReference =
        ImmutableMethodReference(
            md.declaringClass,
            md.name,
            md.getParameterTypes(),
            md.getReturnType()
        )
}
