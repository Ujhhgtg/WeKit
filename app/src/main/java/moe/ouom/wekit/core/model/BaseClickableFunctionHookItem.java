package moe.ouom.wekit.core.model;

import android.content.Context;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XC_MethodHook;
import moe.ouom.wekit.config.WeConfig;
import moe.ouom.wekit.constants.Constants;
import moe.ouom.wekit.loader.startup.HybridClassLoader;
import moe.ouom.wekit.utils.common.SyncUtils;
import moe.ouom.wekit.utils.log.WeLogger;

public abstract class BaseClickableFunctionHookItem extends BaseHookItem {
    private boolean enabled;
    private final int targetProcess = targetProcess();
    private final boolean alwaysRun = alwaysRun();
    private boolean isLoaded = false;
    private Runnable toggleCompletionCallback;

    /**
     * 设置切换完成回调,用于异步确认后更新UI
     *
     * @param callback 完成回调,通常由UI层设置用于更新开关状态
     */
    public void setToggleCompletionCallback(Runnable callback) {
        this.toggleCompletionCallback = callback;
    }

    /**
     * 应用切换状态(保存配置+更新状态+更新UI)
     * 用于异步确认对话框中,在用户确认后调用此方法完成切换
     *
     * @param newState 新的状态 (true: 启用, false: 禁用)
     */
    public void applyToggle(boolean newState) {
        // 保存配置
        var configKey = Constants.PrekClickableXXX + this.getPath();
        WeConfig.getDefaultConfig().edit().putBoolean(configKey, newState).apply();

        // 更新状态
        this.setEnabled(newState);

        // 触发UI更新回调
        if (toggleCompletionCallback != null) {
            toggleCompletionCallback.run();
        }
    }

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
        if (this.enabled == enabled) return;

        this.enabled = enabled;
        if (!enabled) {
            if (isLoaded) {
                WeLogger.i("[BaseClickableFunctionHookItem] Unloading HookItem: " + getPath());
                try {
                    this.unload(HybridClassLoader.getHostClassLoader());
                    isLoaded = false;
                } catch (Throwable e) {
                    WeLogger.e("[BaseClickableFunctionHookItem] Unload HookItem Failed", e);
                }
            }
        } else {
            WeLogger.i("[BaseClickableFunctionHookItem] Loading HookItem: " + getPath());
            this.startLoad();
            isLoaded = true;
        }
    }

    protected final void tryExecute(@NonNull XC_MethodHook.MethodHookParam param, @NonNull HookAction hookAction) {
        if (isEnabled()) {
            super.tryExecute(param, hookAction);
        }
    }

    public boolean noSwitchWidget() {
        return false;
    }

    /**
     * 在开关状态切换前调用,用于确认是否允许切换
     *
     * @param newState 即将切换到的新状态 (true: 启用, false: 禁用)
     * @param context  上下文对象,可用于显示对话框等UI操作
     * @return true: 允许切换, false: 取消切换
     * <p>
     * 默认返回 true
     */
    public boolean onBeforeToggle(boolean newState, Context context) {
        return true;
    }

    /**
     * 点击事件处理
     *
     * @param context 上下文
     */
    public abstract void onClick(Context context);

}
