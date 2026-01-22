package moe.ouom.wekit.core.model;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import moe.ouom.wekit.util.common.SyncUtils;

public abstract class BaseClickableFunctionHookItem extends BaseHookItem {

    private boolean enabled;
    private final int targetProcess = targetProcess();
    private final boolean alwaysRun = alwaysRun();

    /**
     * 目标进程
     */
    public int targetProcess() {
        return SyncUtils.PROC_MAIN;
    }


    public int getTargetProcess() {
        return targetProcess;
    }

    public boolean alwaysRun() {
        return false;
    }

    public boolean getAlwaysRun() {
        return alwaysRun;
    }



    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    protected final void tryExecute(XC_MethodHook.MethodHookParam param, HookAction hookAction) {
        if (isEnabled()) {
            super.tryExecute(param, hookAction);
        }
    }

    public boolean noSwitchWidget() {
        return false;
    }

    public void onClick(Context context) {}

}
