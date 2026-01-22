package moe.ouom.wekit.loader.startup;

import static moe.ouom.wekit.constants.Constants.CLAZZ_BASE_APPLICATION;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.ouom.wekit.BuildConfig;
import moe.ouom.wekit.loader.hookapi.IHookBridge;
import moe.ouom.wekit.loader.hookapi.ILoaderService;
import moe.ouom.wekit.util.log.WeLogger;

@Keep
@SuppressWarnings("unused")
public class UnifiedEntryPoint {

    private static boolean sInitialized = false;

    private UnifiedEntryPoint() {}

    @Keep
    public static void entry(
            @NonNull String modulePath,
            @NonNull String hostDataDir,
            @NonNull ILoaderService loaderService,
            @NonNull ClassLoader hostClassLoader,
            @Nullable IHookBridge hookBridge
    ) {
        if (sInitialized) {
            throw new IllegalStateException("UnifiedEntryPoint already initialized");
        }
        sInitialized = true;
        // fix up the class loader
        HybridClassLoader loader = HybridClassLoader.INSTANCE;
        ClassLoader self = UnifiedEntryPoint.class.getClassLoader();
        assert self != null;
        ClassLoader parent = self.getParent();
        HybridClassLoader.setLoaderParentClassLoader(parent);
        injectClassLoader(self, loader);
        callNextStep(modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge);
    }

    private static void callNextStep(
            @NonNull String modulePath,
            @NonNull String hostDataDir,
            @NonNull ILoaderService loaderService,
            @NonNull ClassLoader hostClassLoader,
            @Nullable IHookBridge hookBridge
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                CLAZZ_BASE_APPLICATION,
                hostClassLoader,
                "attachBaseContext",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        Context context = (Context) param.thisObject;

                        WeLogger.i("UnifiedEntryPoint", "Application attached, invoking StartupAgent...");

                        try {
                            Class<?> kStartupAgent = Class.forName("moe.ouom.wekit.loader.startup.StartupAgent", false, UnifiedEntryPoint.class.getClassLoader());
                            kStartupAgent.getMethod("startup", String.class, String.class, ILoaderService.class, ClassLoader.class, IHookBridge.class)
                                    .invoke(null, modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge);

                        } catch (ReflectiveOperationException e) {
                            Throwable cause = getInvocationTargetExceptionCause(e);
                            Log.e(BuildConfig.TAG,"StartupAgent.startup: failed inside hook", cause);
                            throw unsafeThrow(cause);
                        }

                        try {
                            Class<?> kStartupAgent = Class.forName("moe.ouom.wekit.loader.startup.StartupAgent", false, UnifiedEntryPoint.class.getClassLoader());
                            kStartupAgent.getMethod("startup", String.class, String.class, ILoaderService.class, ClassLoader.class, IHookBridge.class)
                                    .invoke(null, modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge);

                        } catch (ReflectiveOperationException e) {
                            Throwable cause = getInvocationTargetExceptionCause(e);
                            Log.e(BuildConfig.TAG,"StartupAgent.startup: failed inside hook", cause);
                            throw unsafeThrow(cause);
                        }
                    }
                }
            );


            XposedHelpers.findAndHookMethod(
                CLAZZ_BASE_APPLICATION,
                hostClassLoader,
                "onCreate",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    WeLogger.i("UnifiedEntryPoint", "Application onCreate!");
                    Application hostApp = (Application) param.thisObject;
                    StartupInfo.setHostApp(hostApp);
                    }
                }
            );

            Log.i(BuildConfig.TAG,"Hook applied: waiting for Application.attachBaseContext");

        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    @SuppressLint("DiscouragedPrivateApi")
    private static void injectClassLoader(ClassLoader self, ClassLoader newParent) {
        try {
            Field fParent = ClassLoader.class.getDeclaredField("parent");
            fParent.setAccessible(true);
            fParent.set(self, newParent);
        } catch (Exception e) {
            WeLogger.e("injectClassLoader: failed", e);
        }
    }

    @NonNull
    private static Throwable getInvocationTargetExceptionCause(@NonNull Throwable e) {
        while (e instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) e).getTargetException();
            if (cause != null) {
                e = cause;
            } else {
                break;
            }
        }
        return e;
    }


    @SuppressWarnings("unchecked")
    @NonNull
    private static <T extends Throwable> AssertionError unsafeThrow(@NonNull Throwable e) throws T {
        throw (T) e;
    }

}
