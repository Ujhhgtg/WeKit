package moe.ouom.wekit.constants;


import moe.ouom.wekit.BuildConfig;

public class PackageConstants {

    private PackageConstants() {
        throw new AssertionError("No instance for you!");
    }

    public static final String PACKAGE_NAME_WECHAT = "com.tencent.mm";
    public static final String PACKAGE_NAME_SELF = BuildConfig.APPLICATION_ID;

}
