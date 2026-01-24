package moe.ouom.wekit.loader.hookimpl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jf.dexlib2.AnnotationVisibility;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.Annotation;
import org.jf.dexlib2.iface.AnnotationElement;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.value.EncodedValue;
import org.jf.dexlib2.immutable.ImmutableAnnotation;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableField;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31i;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import moe.ouom.wekit.dexkit.DexMethodDescriptor;
import moe.ouom.wekit.loader.hookapi.ILoaderService;
import moe.ouom.wekit.loader.startup.StartupInfo;
import moe.ouom.wekit.util.io.IoUtils;


public class LibXposedNewApiByteCodeGenerator {

    private static final String CMD_SET_WRAPPER = "SetLibXposedNewApiByteCodeGeneratorWrapper";


    private LibXposedNewApiByteCodeGenerator() {
    }

    public static void init() {
        ILoaderService loader = StartupInfo.getLoaderService();
        Method call;
        try {
            call = LibXposedNewApiByteCodeGenerator.class.getMethod("call", int.class, Object[].class);
        } catch (NoSuchMethodException e) {
            throw IoUtils.unsafeThrow(e);
        }
        loader.queryExtension(CMD_SET_WRAPPER, call);
    }

    @NonNull
    public static byte[] call(int version, Object[] args) {
        switch (version) {
            case 1:
                return impl1(
                        (String) args[0],
                        (Integer) args[1],
                        (String) args[2],
                        (String) args[3],
                        (String) args[4],
                        (String) args[5],
                        (String) args[6],
                        (String) args[7]
                );
            default:
                throw new UnsupportedOperationException("Unsupported version: " + version);
        }
    }

    private static String classNameToDescriptor(String className) {
        return "L" + className.replace('.', '/') + ";";
    }

    private static final int ACC_CONSTRUCTOR = 0x00010000;

    @NonNull
    public static byte[] impl1(
            @NonNull String targetClassName,
            @NonNull Integer tagValue,
            @NonNull String classNameXposedInterfaceHooker,
            @NonNull String classBeforeHookCallback,
            @NonNull String classAfterHookCallback,
            @Nullable String classNameXposedHooker,
            @Nullable String classNameBeforeInvocation,
            @Nullable String classNameAfterInvocation
    ) {
        Objects.requireNonNull(targetClassName, "targetClassName");
        Objects.requireNonNull(tagValue, "tagValue");
        Objects.requireNonNull(classNameXposedInterfaceHooker, "classNameXposedInterfaceHooker");
        String typeTargetClass = classNameToDescriptor(targetClassName);
        String typeXposedInterfaceHooker = classNameToDescriptor(classNameXposedInterfaceHooker);
        String typeBeforeHookCallback = classNameToDescriptor(classBeforeHookCallback);
        String typeAfterHookCallback = classNameToDescriptor(classAfterHookCallback);
        //.field public static final tag:I = 0x32
        ImmutableField tagField = new ImmutableField(
                typeTargetClass, "tag", "I",
                Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL,
                (EncodedValue) new ImmutableIntEncodedValue(tagValue),
                null, null
        );

        ArrayList<ImmutableMethod> methods = new ArrayList<>();
        {
            ArrayList<Instruction> insCtor = new ArrayList<>();
            // invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            insCtor.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                    referenceMethod("Ljava/lang/Object;", "<init>", "()V")));
            // return-void
            insCtor.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            ImmutableMethodImplementation ctorMethodImpl = new ImmutableMethodImplementation(1, insCtor, null, null);
            ImmutableMethod ctorMethod = new ImmutableMethod(typeTargetClass, "<init>", List.of(),
                    "V", Modifier.PUBLIC | ACC_CONSTRUCTOR, null, null, ctorMethodImpl);
            methods.add(ctorMethod);
        }
        String typeInvocationParamWrapper = "Lmoe/ouom/wekit/loader/modern/Lsp100HookWrapper$InvocationParamWrapper;";
        String typeLsp100HookAgent = "Lmoe/ouom/wekit/loader/modern/Lsp100HookWrapper$Lsp100HookAgent;";
        {
            Set<ImmutableAnnotation> annotations = null;
            if (classNameBeforeInvocation != null) {
                ImmutableAnnotation annotation = new ImmutableAnnotation(
                        AnnotationVisibility.RUNTIME,
                        classNameToDescriptor(classNameBeforeInvocation),
                        (Collection<? extends AnnotationElement>) null
                );
                annotations = Set.of(annotation);
            }
            ArrayList<Instruction> insBefore = new ArrayList<>();
            insBefore.add(new ImmutableInstruction31i(Opcode.CONST, 0, tagValue));
            insBefore.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0,
                    new ImmutableMethodReference(typeLsp100HookAgent, "handleBeforeHookedMethod",
                            List.of(typeBeforeHookCallback, "I"), typeInvocationParamWrapper)));

            insBefore.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            insBefore.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
            ImmutableMethodImplementation beforeMethodImpl = new ImmutableMethodImplementation(2, insBefore, null, null);
            ImmutableMethod beforeMethod = new ImmutableMethod(typeTargetClass, "before", List.of(
                    new ImmutableMethodParameter(typeBeforeHookCallback, (Set<? extends Annotation>) null, "c")
            ), typeInvocationParamWrapper, Modifier.PUBLIC | Modifier.STATIC, annotations, null, beforeMethodImpl);
            methods.add(beforeMethod);
        }
        {
            // check if we need an annotation
            Set<ImmutableAnnotation> annotations = null;
            if (classNameAfterInvocation != null) {
                ImmutableAnnotation annotation = new ImmutableAnnotation(
                        AnnotationVisibility.RUNTIME,
                        classNameToDescriptor(classNameAfterInvocation),
                        (Collection<? extends AnnotationElement>) null
                );
                annotations = Set.of(annotation);
            }
            ArrayList<Instruction> insAfter = new ArrayList<>();
            insAfter.add(new ImmutableInstruction31i(Opcode.CONST, 0, tagValue));
            insAfter.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 0, 0, 0,
                    new ImmutableMethodReference(typeLsp100HookAgent, "handleAfterHookedMethod",
                            List.of(typeAfterHookCallback, typeInvocationParamWrapper, "I"), "V")));
            // return-void
            insAfter.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            ImmutableMethodImplementation afterMethodImpl = new ImmutableMethodImplementation(3, insAfter, null, null);
            ImmutableMethod afterMethod = new ImmutableMethod(typeTargetClass, "after", List.of(
                    new ImmutableMethodParameter(typeAfterHookCallback, (Set<? extends Annotation>) null, "c"),
                    new ImmutableMethodParameter(typeInvocationParamWrapper, (Set<? extends Annotation>) null, "p")
            ), "V", Modifier.PUBLIC | Modifier.STATIC, annotations, null, afterMethodImpl);
            methods.add(afterMethod);
        }

        ImmutableDexFile proxyDex;
        {
            ImmutableClassDef classDef;
            // check if we need an annotation
            Set<ImmutableAnnotation> annotations = null;
            if (classNameXposedHooker != null) {
                ImmutableAnnotation annotation = new ImmutableAnnotation(
                        AnnotationVisibility.RUNTIME,
                        classNameToDescriptor(classNameXposedHooker),
                        (Collection<? extends AnnotationElement>) null
                );
                annotations = Set.of(annotation);
            }
            classDef = new ImmutableClassDef(typeTargetClass, Modifier.PUBLIC, "Ljava/lang/Object;",
                    Collections.singletonList(typeXposedInterfaceHooker),
                    "LibXposedNewApiByteCodeGenerator.dexlib2", annotations,
                    Collections.singletonList(tagField), methods);
            proxyDex = new ImmutableDexFile(Opcodes.forDexVersion(35), Collections.singletonList(classDef));
        }
        // to bytes
        MemoryDataStore memoryDataStore = new MemoryDataStore();
        DexPool dexPool = new DexPool(proxyDex.getOpcodes());
        for (ClassDef classDef : proxyDex.getClasses()) {
            dexPool.internClass(classDef);
        }
        try {
            dexPool.writeTo(memoryDataStore);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        byte[] data = memoryDataStore.getData();
        return data;
    }

    private static ImmutableMethodReference referenceMethod(String declaringClass, String name, String descriptor) {
        return referenceMethod(new DexMethodDescriptor(declaringClass, name, descriptor));
    }

    private static ImmutableMethodReference referenceMethod(DexMethodDescriptor md) {
        return new ImmutableMethodReference(md.declaringClass, md.name, md.getParameterTypes(), md.getReturnType());
    }

    public static ImmutableFieldReference referenceField(String declaringClass, String name, String type) {
        return new ImmutableFieldReference(declaringClass, name, type);
    }

}
