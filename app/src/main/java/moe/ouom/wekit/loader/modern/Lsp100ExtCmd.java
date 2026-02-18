package moe.ouom.wekit.loader.modern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Objects;

import io.github.libxposed.api.XposedInterface;
import moe.ouom.wekit.loader.ModuleLoader;
import moe.ouom.wekit.loader.modern.codegen.Lsp100ProxyClassMaker;
import moe.ouom.wekit.utils.common.CheckUtils;

public class Lsp100ExtCmd {

    private Lsp100ExtCmd() {
    }

    public static Object handleQueryExtension(@NonNull String cmd, @Nullable Object[] arg) {
        CheckUtils.checkNonNull(cmd, "cmd");
        return switch (cmd) {
            case "GetXposedInterfaceClass" -> XposedInterface.class;
            case "GetLoadPackageParam" -> null;
            case "GetInitZygoteStartupParam" -> null;
            case "GetInitErrors" -> ModuleLoader.getInitErrors();
            case "SetLibXposedNewApiByteCodeGeneratorWrapper" -> {
                Lsp100ProxyClassMaker.setWrapperMethod((Method) Objects.requireNonNull(arg)[0]);
                yield Boolean.TRUE;
            }
            default -> null;
        };
    }

}
