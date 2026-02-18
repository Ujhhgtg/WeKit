package moe.ouom.wekit.util.io;

import android.content.Context;
import android.os.Environment;

import java.io.File;

import moe.ouom.wekit.host.impl.HostInfo;


public class PathTool {


    public static String getDataSavePath(Context context, String dirName) {
        // getExternalFilesDir()：SDCard/Android/data/你的应用的包名/files/dirName
        return context.getExternalFilesDir(dirName).getAbsolutePath();
    }

    public static String getStorageDirectory() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static String getModuleDataPath() {
        var directory = getStorageDirectory() + "/Android/data/" + HostInfo.getHostInfo().getPackageName() + "/WeKit";
        var file = new File(directory);
        if (!file.exists()) {
            file.mkdirs();
        }
        return directory;
    }

    public static String getModuleCachePath(String dirName) {
        var cache = new File(getModuleDataPath() + "/cache/" + dirName);
        if (!cache.exists()) {
            cache.mkdirs();
        }
        return cache.getAbsolutePath();
    }


}
