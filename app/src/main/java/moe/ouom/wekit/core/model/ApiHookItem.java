package moe.ouom.wekit.core.model;

import de.robv.android.xposed.XC_MethodHook;
import moe.ouom.wekit.utils.common.SyncUtils;

public abstract class ApiHookItem extends BaseHookItem {

    private final int targetProcess = targetProcess();

    /**
     * 目标进程
     */
    public int targetProcess() {
        return SyncUtils.PROC_MAIN;
    }


    public int getTargetProcess() {
        return targetProcess;
    }


    protected final void tryExecute(XC_MethodHook.MethodHookParam param, HookAction hookAction) {
        super.tryExecute(param, hookAction);
    }
}
