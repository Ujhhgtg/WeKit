package moe.ouom.wekit.utils.common;

import android.content.Context;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import moe.ouom.wekit.config.WeConfig;
import moe.ouom.wekit.utils.hookstatus.AbiUtils;


public class CheckAbiVariantModel {

    private CheckAbiVariantModel() {
        throw new AssertionError("no instance");
    }

    public static final String[] HOST_PACKAGES = new String[]{
            "com.tencent.mm",
    };

    @NonNull
    public static AbiInfo collectAbiInfo(@NonNull Context context) {
        var abiInfo = new AbiInfo();
        var uts = Os.uname();
        var sysAbi = uts.machine;
        abiInfo.sysArchName = sysAbi;
        abiInfo.sysArch = AbiUtils.archStringToArchInt(sysAbi);

        var requestAbis = new HashSet<String>();
        requestAbis.add(AbiUtils.archStringToLibDirName(sysAbi));
        for (var pkg : HOST_PACKAGES) {
            var activeAbi = AbiUtils.getApplicationActiveAbi(pkg);
            if (activeAbi == null) {
                continue;
            }
            var abi = AbiUtils.archStringToLibDirName(activeAbi);
            if (!isPackageIgnored(pkg)) {
                requestAbis.add(abi);
            }
            var pi = new AbiInfo.Package();
            pi.abi = AbiUtils.archStringToArchInt(activeAbi);
            pi.ignored = isPackageIgnored(pkg);
            pi.packageName = pkg;
            abiInfo.packages.put(pkg, pi);
        }
        var modulesAbis = AbiUtils.queryModuleAbiList();
        var missingAbis = new HashSet<String>();
        // check if modulesAbis contains all requestAbis
        for (var abi : requestAbis) {
            if (!Arrays.asList(modulesAbis).contains(abi)) {
                missingAbis.add(abi);
            }
        }
        abiInfo.isAbiMatch = missingAbis.isEmpty();
        var abi = 0;
        for (var name : requestAbis) {
            abi |= AbiUtils.archStringToArchInt(name);
        }
        abiInfo.suggestedApkAbiVariant = AbiUtils.getSuggestedAbiVariant(abi);
        return abiInfo;
    }

    public static void setPackageIgnored(@NonNull String packageName, boolean ignored) {
        var cfg = WeConfig.getDefaultConfig();
        cfg.putBoolean("native_lib_abi_ignore." + packageName, ignored);
    }

    public static boolean isPackageIgnored(@NonNull String packageName) {
        var cfg = WeConfig.getDefaultConfig();
        return cfg.getBoolean("native_lib_abi_ignore." + packageName, false);
    }

    public static class AbiInfo {

        public static class Package {

            public String packageName;
            public int abi;
            public boolean ignored;
        }

        @NonNull
        public Map<String, Package> packages = new HashMap<>();
        public String sysArchName;
        public int sysArch;
        public boolean isAbiMatch;
        @Nullable
        public String suggestedApkAbiVariant;
    }

}
