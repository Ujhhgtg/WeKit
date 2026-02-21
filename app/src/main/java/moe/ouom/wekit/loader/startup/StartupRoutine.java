package moe.ouom.wekit.loader.startup;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;

import moe.ouom.wekit.host.impl.HostInfo;
import moe.ouom.wekit.loader.core.NativeCoreBridge;
import moe.ouom.wekit.loader.core.WeLauncher;
import moe.ouom.wekit.loader.hookimpl.InMemoryClassLoaderHelper;
import moe.ouom.wekit.loader.hookimpl.LibXposedNewApiByteCodeGenerator;
import moe.ouom.wekit.utils.common.SyncUtils;
import moe.ouom.wekit.utils.log.WeLogger;

public class StartupRoutine {

    /**
     * From now on, kotlin, androidx or third party libraries may be accessed without crashing the ART.
     * <p>
     * Kotlin and androidx are dangerous, and should be invoked only after the class loader is ready.
     *
     * @param ctx         Application context for host
     */
    public static void execPostStartupInit(@NonNull Context ctx) {
        // init all kotlin utils here
        HostInfo.init((Application) ctx);
        // perform full initialization for native core -- including primary and secondary native libraries
        StartupInfo.getLoaderService().setClassLoaderHelper(InMemoryClassLoaderHelper.INSTANCE);
        LibXposedNewApiByteCodeGenerator.init();
        NativeCoreBridge.initNativeCore();
        WeLogger.d("execPostStartupInit -> processName: " + SyncUtils.getProcessName());
        var launcher = new WeLauncher();
        launcher.init(ctx.getClassLoader(), ctx.getApplicationInfo(), ctx.getApplicationInfo().sourceDir, ctx);
    }
}
