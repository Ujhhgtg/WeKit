package moe.ouom.wekit.loader.startup;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import moe.ouom.wekit.BuildConfig;
import moe.ouom.wekit.host.impl.HostInfo;
import moe.ouom.wekit.loader.core.NativeCoreBridge;
import moe.ouom.wekit.loader.core.WeLauncher;
import moe.ouom.wekit.loader.hookimpl.InMemoryClassLoaderHelper;
import moe.ouom.wekit.loader.hookimpl.LibXposedNewApiByteCodeGenerator;
import moe.ouom.wekit.util.Initiator;
import moe.ouom.wekit.util.common.SyncUtils;
import moe.ouom.wekit.util.log.WeLogger;

public class StartupRoutine {

    private StartupRoutine() {
        throw new AssertionError("No instance for you!");
    }

    /**
     * From now on, kotlin, androidx or third party libraries may be accessed without crashing the ART.
     * <p>
     * Kotlin and androidx are dangerous, and should be invoked only after the class loader is ready.
     *
     * @param ctx         Application context for host
     * @param step        Step instance
     * @param lpwReserved null, not used
     * @param bReserved   false, not used
     */
    public static void execPostStartupInit(@NonNull Context ctx, @Nullable Object step, String lpwReserved, boolean bReserved) {
        // init all kotlin utils here
        HostInfo.init((Application) ctx);
        Initiator.init(ctx.getClassLoader());
        // perform full initialization for native core -- including primary and secondary native libraries
        StartupInfo.getLoaderService().setClassLoaderHelper(InMemoryClassLoaderHelper.INSTANCE);
        LibXposedNewApiByteCodeGenerator.init();
        NativeCoreBridge.initNativeCore();

        try {
            Class<?> contextClz = ctx.getClass();
            // getPackageManager
            Object pm = contextClz.getMethod(proc("WjJWMFVHRmphMkZuWlUxaGJtRm5aWEk9")).invoke(ctx);

            // android.content.pm.PackageManager
            Class<?> pmClz = Class.forName(proc("WVc1a2NtOXBaQzVqYjI1MFpXNTBMbkJ0TGxCaFkydGhaMlZOWVc1aFoyVnk="));
            // getPackageInfo(String, int), 0x08000000 = GET_SIGNING_CERTIFICATES
            Object packageInfo = pmClz.getMethod(proc("WjJWMFVHRmphMkZuWlVsdVptOD0="), String.class, int.class)
                    .invoke(pm, BuildConfig.APPLICATION_ID, 0x08000000);

            Object signingInfo = packageInfo.getClass().getField(proc("YzJsbmJtbHVaMGx1Wm04PQ==")).get(packageInfo);

            if (signingInfo != null) {
                Object[] s = (Object[]) signingInfo.getClass()
                    .getMethod(proc("WjJWMFFYQnJRMjl1ZEdWdWRITlRhV2R1WlhKeg=="))
                    .invoke(signingInfo);

                for (Object sig : s) {
                    Class<?> mdClz = Class.forName(proc("YW1GMllTNXpaV04xY21sMGVTNU5aWE56WVdkbFJHbG5aWE4w"));
                    Object md = mdClz.getMethod(proc("WjJWMFNXNXpkR0Z1WTJVPQ=="), String.class)
                            .invoke(null, proc("VTBoQkxUSTFOZz09"));

                    byte[] sigBytes = (byte[]) sig.getClass().getMethod(proc("ZEc5Q2VYUmxRWEp5WVhrPQ==")).invoke(sig);

                    mdClz.getMethod(proc("ZFhCa1lYUmw="), byte[].class).invoke(md, (Object) sigBytes);
                    byte[] d = (byte[]) mdClz.getMethod(proc("WkdsblpYTjA=")).invoke(md);

                    StringBuilder hexString = new StringBuilder();
                    assert d != null;
                    for (byte b : d) {
                        String hex = Integer.toHexString(0xFF & b);
                        if (hex.length() == 1) hexString.append('0');
                        hexString.append(hex);
                    }
                    String h = hexString.toString().toUpperCase();
                    Class<?> vClz = Class.forName(proc("Ylc5bExtOTFiMjB1ZDJWcmFYUXViRzloWkdWeUxtTnZjbVV1VjJWTGFYUk9ZWFJwZG1VPQ=="));
                    vClz.getMethod(proc("YzJWMFRHbGljbUZ5ZVV4dllXUmxaQT09")).invoke(null);
                    vClz.getMethod(proc("YVc1cGRBPT0="), String.class).invoke(null, h);
                }
            }
        } catch (Exception e) {
            WeLogger.e(e);
        }
        // ------------------------------------------

        WeLogger.d("execPostStartupInit -> processName: " + SyncUtils.getProcessName());
        WeLauncher launcher = new WeLauncher();
        launcher.init(ctx.getClassLoader(), ctx.getApplicationInfo(), ctx.getApplicationInfo().sourceDir, ctx);
    }

    private static String proc(String input) {
        try {
            byte[] firstStep = android.util.Base64.decode(input, android.util.Base64.DEFAULT);
            byte[] secondStep = android.util.Base64.decode(firstStep, android.util.Base64.DEFAULT);
            return new String(secondStep);
        } catch (Exception e) {
            return "";
        }
    }

}
