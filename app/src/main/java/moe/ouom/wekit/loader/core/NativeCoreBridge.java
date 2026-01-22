package moe.ouom.wekit.loader.core;

import static moe.ouom.wekit.util.io.FileUtils.copyFile;

import android.content.Context;
import androidx.annotation.NonNull;
import com.tencent.mmkv.MMKV;
import java.io.File;
import java.io.IOException;

import moe.ouom.wekit.host.HostInfo;
import moe.ouom.wekit.util.io.FileUtils;
import moe.ouom.wekit.util.log.WeLogger;


public class NativeCoreBridge {
    static {
        // it will be an UnsatisfiedLinkError if first load..
        System.loadLibrary("dexkit");
        System.loadLibrary("wekit");
    }


    private NativeCoreBridge() {
        throw new AssertionError("No instances for you!");
    }


    private static boolean sPrimaryNativeLibraryInitialized = false;

    public static void initNativeCore() {
        Context context = HostInfo.getApplication();

        // init mmkv
        initializeMmkvForPrimaryNativeLibrary(context);
    }

    /**
     * Load native library and initialize MMKV
     *
     * @param ctx Application context
     * @throws LinkageError if failed to load native library
     */
    public static void initializeMmkvForPrimaryNativeLibrary(@NonNull Context ctx) {
        if (sPrimaryNativeLibraryInitialized) {
            return;
        }
        File filesDir = ctx.getExternalMediaDirs()[0];
        if (filesDir == null) {
            filesDir = ctx.getFilesDir();
        }

        File mmkvDir = new File(filesDir, "wekit_mmkv");
        if (!mmkvDir.exists()) {
            mmkvDir.mkdirs();
        }
        // MMKV requires a ".tmp" cache directory, we have to create it manually
        File cacheDir = new File(mmkvDir, ".tmp");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        File oldDir = new File(ctx.getFilesDir(), "wekit_mmkv");
        if (oldDir.exists() && oldDir.isDirectory()) {
            File[] files = oldDir.listFiles();
            if (files == null) return;

            for (File src : files) {
                if (!src.isFile()) continue;
                File dest = new File(mmkvDir, src.getName());
                if (!dest.exists()) {
                    try {
                        copyFile(src, dest);
                        WeLogger.i("Copy config file: " + src.getName());
                    } catch (IOException e) {
                        WeLogger.e(e);
                    }
                }
            }

            FileUtils.deleteFile(oldDir);
        }
        MMKV.initialize(ctx, mmkvDir.getAbsolutePath());
        MMKV.mmkvWithID("global_config", MMKV.MULTI_PROCESS_MODE);
        MMKV.mmkvWithID("global_cache", MMKV.MULTI_PROCESS_MODE);
        sPrimaryNativeLibraryInitialized = true;
    }

}
