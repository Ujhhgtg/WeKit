package moe.ouom.wekit.ui.creator.center;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import moe.ouom.wekit.dexkit.TargetManager;
import moe.ouom.wekit.util.common.ModuleRes;
import moe.ouom.wekit.util.log.Logger;

public class MethodFinderDialog extends Dialog {
    private final Activity activity;
    private final ClassLoader cl;
    private final ApplicationInfo ai;
    private boolean flag = false;

    public MethodFinderDialog(@NonNull Context context, Activity activity, @NonNull ClassLoader cl, @NonNull ApplicationInfo ai) {
        // 使用一个无标题的 Dialog 主题，避免丑陋的默认边框
        super(context, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth);
        this.activity = activity;
        this.cl = cl;
        this.ai = ai;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置背景透明，这样圆角才能显示出来
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        // 使用 ModuleRes 加载布局
        // 第二个参数传 null，因为 Dialog 会自动处理 LayoutParams
        View rootView = ModuleRes.inflate("dialog_methodfinder_layout", null);

        if (rootView == null) {
            // 如果加载失败，就显示一个空的 View 避免崩溃
            setContentView(new View(getContext()));
            return;
        }

        // 设置给 Dialog
        setContentView(rootView);

        // 点击 外部、返回键 不消失
        setCanceledOnTouchOutside(false);
        setCancelable(false);

        // 初始化 View 和逻辑
        initViews(rootView);
    }

    private void initViews(View rootView) {
        // 使用 ModuleRes 动态查找 ID
        int idBtnClose = ModuleRes.getId("btn_close", "id");
        int idBtnFind = ModuleRes.getId("btn_find_method", "id");
        int idTvTip = ModuleRes.getId("tv_tip", "id");
        int idProgress = ModuleRes.getId("progress_bar", "id");

        Button btnClose = rootView.findViewById(idBtnClose);
        View btnFindMethod = rootView.findViewById(idBtnFind);
        TextView tvTip = rootView.findViewById(idTvTip);
        ProgressBar progressBar = rootView.findViewById(idProgress);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                dismiss();
                if (flag) {
                    activity.finish();
                    stopAllServices(activity);
                }
            });
        }

        if (btnFindMethod != null) {
            btnFindMethod.setOnClickListener(v -> {
                btnFindMethod.setVisibility(View.GONE);
                if (btnClose != null) btnClose.setVisibility(View.GONE);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

                TargetManager.INSTANCE.runMethodFinder(ai, cl, activity,
                    result -> {
                        Logger.i("MethodFinderDialog: onTaskComplete", result);
                        if (tvTip != null) tvTip.setText(result);
                        if (btnClose != null) {
                            btnClose.setText("重启微信");
                            btnClose.setVisibility(View.VISIBLE);
                        }
                        flag = true;
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        TargetManager.INSTANCE.setNeedFindTarget(false);
                        return null;
                    }
                );
            });
        }
    }

    private void stopAllServices(Context context) {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if (context.getPackageName().equals(service.service.getPackageName())) {
                    try {
                        context.stopService(new Intent().setComponent(service.service));
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}