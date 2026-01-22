package moe.ouom.wekit.util.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import moe.ouom.wekit.util.log.WeLogger;

/**
 * 模块资源加载器助手
 * 用于简化 Xposed 模块加载自身资源（布局、图片、字符串）的流程
 */
public class ModuleRes {

    private static Context sModuleContext;
    private static Resources sResources;
    private static String sPackageName;

    /**
     * 初始化加载器，只需在 Hook 入口处调用一次
     *
     * @param hostContext 宿主的 Context
     * @param modulePkgName 模块的包名
     */
    public static void init(Context hostContext, String modulePkgName) {
        if (sModuleContext != null) return; // 避免重复初始化

        try {
            // 创建指向模块 APK 的 Context
            sModuleContext = hostContext.createPackageContext(
                    modulePkgName,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
            sResources = sModuleContext.getResources();
            sPackageName = modulePkgName;

            // 给模块 Context 设置 Material 主题，否则在部分场景下它会崩溃
            int themeId = sResources.getIdentifier("Theme.WeKit", "style", sPackageName);
            if (themeId != 0) {
                sModuleContext.setTheme(themeId);
            } else {
                WeLogger.e("ModuleRes: 未找到 Theme.WeKit，Material 组件可能会崩溃！");
            }

            WeLogger.i("ModuleRes: 初始化成功 [" + modulePkgName + "]");
        } catch (PackageManager.NameNotFoundException e) {
            WeLogger.e("ModuleRes: 初始化失败，未找到模块包名: " + modulePkgName);
        }
    }

    /**
     * 获取模块 Context (例如用于创建 View 或 Dialog)
     */
    public static Context getContext() {
        return sModuleContext;
    }

    /**
     * 通用：根据名称获取资源 ID
     */
    public static int getId(String resName, String resType) {
        if (sResources == null) return 0;
        int id = sResources.getIdentifier(resName, resType, sPackageName);
        if (id == 0) {
            WeLogger.e("ModuleRes: 未找到资源 " + resType + "/" + resName);
        }
        return id;
    }

    public static String getString(String resName) {
        int id = getId(resName, "string");
        return id == 0 ? "" : sResources.getString(id);
    }

    public static int getColor(String resName) {
        int id = getId(resName, "color");
        return id == 0 ? 0 : sResources.getColor(id);
    }

    public static Drawable getDrawable(String resName) {
        int id = getId(resName, "drawable");
        // 尝试去 mipmap 找
        if (id == 0) id = getId(resName, "mipmap");
        return id == 0 ? null : sResources.getDrawable(id);
    }

    public static float getDimen(String resName) {
        int id = getId(resName, "dimen");
        return id == 0 ? 0 : sResources.getDimension(id);
    }

    /**
     * 加载模块内的 XML 布局
     *
     * @param layoutName 布局文件名 (不带 .xml)
     * @param root 父布局 (可以为 null)
     * @return Inflate 出来的 View
     */
    public static View inflate(String layoutName, ViewGroup root) {
        if (sModuleContext == null) return null;
        int id = getId(layoutName, "layout");
        if (id == 0) return null;

        // 使用模块的 Context 创建 LayoutInflater，这样才能解析 XML 内部引用的模块资源 (@drawable/xxx)
        return LayoutInflater.from(sModuleContext).inflate(id, root, false);
    }
}