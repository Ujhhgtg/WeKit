package moe.ouom.wekit.loader.modern.codegen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;

import io.github.libxposed.api.XposedInterface;
import moe.ouom.wekit.loader.modern.Lsp100HookImpl;
import moe.ouom.wekit.loader.modern.Lsp100HookWrapper;
import moe.ouom.wekit.loader.modern.dyn.Lsp100CallbackProxy;

public class Lsp100ProxyClassMaker {

    private static Lsp100ProxyClassMaker sInstance = null;

    private static ClassLoader sProxyClassLoader = null;

    private static Method sWrapperMethod = null;

    private static Throwable sLoadClassException = null;
    private String mXposedHookerClassName;
    private String mBeforeInvocationClassName;
    private String mAfterInvocationClassName;

    private Lsp100ProxyClassMaker() {
        Class<?> templateClass = Lsp100CallbackProxy.P0000000050.class;
        {
            // get XposedHooker annotation class name
            var annotations = templateClass.getAnnotations();
            // pick the first annotation
            if (annotations.length > 0) {
                mXposedHookerClassName = annotations[0].annotationType().getName();
            }
        }
        try {
            // get BeforeInvocation annotation class name
            var before = templateClass.getMethod("before", XposedInterface.BeforeHookCallback.class);
            var annotations = before.getAnnotations();
            // pick the first annotation
            if (annotations.length > 0) {
                mBeforeInvocationClassName = annotations[0].annotationType().getName();
            }
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException("Method before not found in template class", e);
        }
        try {
            // get AfterInvocation annotation class name
            var after = templateClass.getMethod("after",
                    XposedInterface.AfterHookCallback.class, Lsp100HookWrapper.InvocationParamWrapper.class);
            var annotations = after.getAnnotations();
            // pick the first annotation
            if (annotations.length > 0) {
                mAfterInvocationClassName = annotations[0].annotationType().getName();
            }
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException("Method after not found in template class", e);
        }
    }

    public static Method getWrapperMethod() {
        return sWrapperMethod;
    }

    public static void setWrapperMethod(Method method) {
        sWrapperMethod = method;
    }

    public static Lsp100ProxyClassMaker getInstance() throws UnsupportedOperationException {
        if (sInstance == null) {
            sInstance = new Lsp100ProxyClassMaker();
        }
        return sInstance;
    }

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
        var wrapperMethod = sWrapperMethod;
        if (wrapperMethod == null) {
            throw new UnsupportedOperationException("Wrapper method not set");
        }
        var args = new Object[]{
                targetClassName,
                tagValue,
                classNameXposedInterfaceHooker,
                classBeforeHookCallback,
                classAfterHookCallback,
                classNameXposedHooker,
                classNameBeforeInvocation,
                classNameAfterInvocation
        };
        Integer version = 1;
        try {
            return (byte[]) Objects.requireNonNull(wrapperMethod.invoke(null, version, args));
        } catch (ReflectiveOperationException e) {
            if (e instanceof InvocationTargetException) {
                var targetException = ((InvocationTargetException) e).getTargetException();
                if (targetException instanceof RuntimeException) {
                    throw (RuntimeException) targetException;
                }
            }
            throw new UnsupportedOperationException("Failed to invoke wrapper method", e);
        }
    }

    private static String priorityToShortName(int priority) {
        // positive number and zero
        // 0:         P0000000000
        // 49:        P0000000049
        // 2147483647 P2147483647
        // negative number
        // -1:        N0000000001
        // -2147483648N2147483648
        if (priority >= 0) {
            return "P" + String.format(Locale.ROOT, "%010d", priority);
        } else {
            return "N" + String.format(Locale.ROOT, "%010d", -(long) priority);
        }
    }

    private static String getClassNameForPriority(int priority) {
        return "moe.ouom.wekit.loader.modern.dyn.Lsp100CallbackProxy$" + priorityToShortName(priority);
    }

    public Class<?> createProxyClass(int priority) {
        var className = getClassNameForPriority(priority);
        // is already loaded?
        try {
            return Lsp100ProxyClassMaker.class.getClassLoader().loadClass(className);
        } catch (ClassNotFoundException ignored) {
        }
        if (sLoadClassException != null) {
            throw new UnsupportedOperationException("reject to try again due to previous exception", sLoadClassException);
        }
        if (sProxyClassLoader != null) {
            try {
                return sProxyClassLoader.loadClass(className);
            } catch (ClassNotFoundException ignored) {
            }
        }
        var dex = makeClassByteCodeForPriority(priority);
        return loadProxyClass(className, dex);
    }

    private Class<?> loadProxyClass(@NonNull String className, @NonNull byte[] dex) {
        var helper = Lsp100HookImpl.INSTANCE.getClassLoaderHelper();
        if (helper == null) {
            throw new UnsupportedOperationException("ClassLoaderHelper not set");
        }
        if (sLoadClassException != null) {
            throw new UnsupportedOperationException("reject to try again due to previous exception", sLoadClassException);
        }
        if (sProxyClassLoader == null) {
            synchronized (Lsp100ProxyClassMaker.class) {
                if (sProxyClassLoader == null) {
                    sProxyClassLoader = helper.createEmptyInMemoryMultiDexClassLoader(Objects.requireNonNull(Lsp100HookImpl.class.getClassLoader()));
                }
            }
        }
        // already loaded?
        try {
            return sProxyClassLoader.loadClass(className);
        } catch (ClassNotFoundException ignored) {
        }
        // load dex
        helper.injectDexToClassLoader(sProxyClassLoader, dex, null);
        // load class
        Class<?> proxyClass;
        try {
            // force a resolution
            proxyClass = Class.forName(className, true, sProxyClassLoader);
        } catch (ClassNotFoundException e) {
            sLoadClassException = e;
            throw new UnsupportedOperationException("Failed to load proxy class", e);
        }
        return proxyClass;
    }

    @NonNull
    private byte[] makeClassByteCodeForPriority(int priority) {
        var className = getClassNameForPriority(priority);
        return impl1(
                className,
                priority,
                XposedInterface.Hooker.class.getName(),
                XposedInterface.BeforeHookCallback.class.getName(),
                XposedInterface.AfterHookCallback.class.getName(),
                mXposedHookerClassName,
                mBeforeInvocationClassName,
                mAfterInvocationClassName
        );
    }

}
