package moe.ouom.wekit.utils.hookstatus;

/**
 * Hook status detection, NO KOTLIN, NO ANDROIDX!
 */
public class HookStatusImpl {

    static volatile boolean zygoteHookMode = false;
    static volatile String zygoteHookProvider = null;
    static volatile boolean isLsposedDexObfsEnabled = false;
}
