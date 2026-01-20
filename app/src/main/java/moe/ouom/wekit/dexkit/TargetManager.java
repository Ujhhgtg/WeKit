package moe.ouom.wekit.dexkit;

import static moe.ouom.wekit.config.ConfigManager.cGetBoolean;
import static moe.ouom.wekit.config.ConfigManager.cGetInt;
import static moe.ouom.wekit.config.ConfigManager.cGetString;
import static moe.ouom.wekit.config.ConfigManager.cPutBoolean;
import static moe.ouom.wekit.config.ConfigManager.cPutInt;
import static moe.ouom.wekit.config.ConfigManager.cPutString;
import static moe.ouom.wekit.util.Initiator.loadClass;
import static moe.ouom.wekit.util.common.Utils.findMethodByName;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import moe.ouom.wekit.config.ConfigManager;
import moe.ouom.wekit.util.log.Logger;

public class TargetManager {
    // 更新此版本可在宿主无版本更新的时候强制要求用户重新搜索被混淆的方法
    // 如果你尝试搜索了新的方法，请更新这里的版本
    public static final int TargetManager_VERSION = 2;

    // 缓存 Key 定义
    public static final String KEY_METHOD_SET_TITLE = "method_pref_setTitle";
    public static final String KEY_METHOD_SET_KEY = "method_pref_setKey";
    public static final String KEY_METHOD_GET_KEY = "method_pref_getKey";
    public static final String KEY_METHOD_ADD_PREF = "method_adapter_addPreference";

    // 稳定的包名和类名
    private static final String PKG_PREFERENCE = "com.tencent.mm.ui.base.preference";
    private static final String CLS_PREFERENCE = PKG_PREFERENCE + ".Preference";


    /* =========================================================
     * Config helpers
     * ========================================================= */
    public static boolean isNeedFindTarget() { return cGetBoolean("isNeedFindTarget", true); }
    public static void setIsNeedFindTarget(boolean b) { cPutBoolean("isNeedFindTarget", b); }
    public static void setTargetManagerVersion(int version) { cPutInt("TargetManager_VERSION", version); }
    public static int getTargetManagerVersion() { return cGetInt("TargetManager_VERSION", 0); }
    public static String getLastWeChatVersion() { return cGetString("LastWeChatVersion", ""); }
    public static void setLastWeChatVersion(String version) { cPutString("LastWeChatVersion", version); }

    /* =========================================================
     * Core Logic
     * ========================================================= */
    public static void runMethodFinder(ApplicationInfo ai, ClassLoader cl, Activity activity, OnTaskCompleteListener cb) {
        new Thread(() -> {
            try {
                String result = getResult(ai, cl);
                activity.runOnUiThread(() -> {
                    if (cb != null) cb.onTaskComplete(result);
                });
            } catch (Exception e) {
                Logger.e("TargetManager: DexKit Fatal Error", e);
                activity.runOnUiThread(() -> {
                    if (cb != null) cb.onTaskComplete("搜索失败: " + e.getMessage());
                });
            }
        }).start();
    }

    @NonNull
    private static String getResult(ApplicationInfo ai, ClassLoader cl) {
        DexKitExecutor executor = new DexKitExecutor(ai.sourceDir, cl);
        StringBuilder out = new StringBuilder();

        executor.execute((bridge, loader) -> {
            out.append(">>> 开始分析微信代码...\n");
            searchPreferenceMethods(bridge, loader, out);
            searchAdapterMethods(bridge, loader, out);
        });

        out.append("\n\n[SUCCESS] 搜索与配置更新完成");
        return out.toString();
    }

    private static void searchPreferenceMethods(DexKitBridge bridge, ClassLoader cl, StringBuilder out) {
        try {
            // 定位 Preference 类
            ClassData prefClass = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().className(CLS_PREFERENCE)))
                    .singleOrNull();

            if (prefClass == null) {
                out.append("\n[FAIL] 未找到 Preference 类: ").append(CLS_PREFERENCE);
                return;
            }

            // ----------------------------------------------------------------
            // 查找 setKey
            // paramTypes("java.lang.String").usingStrings("Preference").returnType("void")
            // ----------------------------------------------------------------
            try {
                List<MethodData> keyCandidates = prefClass.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .paramTypes("java.lang.String") // String
                                .usingStrings("Preference"))
                );

                if (!keyCandidates.isEmpty()) {
                    // 反编译代码用了 singleOrThrow，这里我们稳妥取第0个，效果一样
                    cacheMethod(keyCandidates.get(0), cl, KEY_METHOD_SET_KEY, out);
                } else {
                    out.append("\n[FAIL] setKey 未找到");
                }
            } catch (Throwable t) {
                Logger.e("setKey search error", t);
            }

            // ----------------------------------------------------------------
            // 查找 setTitle
            // paramTypes("java.lang.CharSequence").returnType("void")
            // methodData 取最后一个元素
            // ----------------------------------------------------------------
            try {
                List<MethodData> charSeqMethods = prefClass.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .paramTypes("java.lang.CharSequence")) // CharSequence
                );

                if (charSeqMethods.isEmpty()) {
                    out.append("\n[FAIL] setTitle (CharSequence) 未找到");
                } else {
                    MethodData target = charSeqMethods.get(charSeqMethods.size() - 1);
                    cacheMethod(target, cl, KEY_METHOD_SET_TITLE, out);

                    if (charSeqMethods.size() > 1) {
                        out.append("\n[INFO] setTitle 取最后一个 (共").append(charSeqMethods.size()).append("个)");
                    }
                }
            } catch (Throwable t) {
                Logger.e("setTitle search error", t);
            }

            // ----------------------------------------------------------------
            // 查找 getKey
            // 无参返回 String，排除 "toString"，取第一个
            // ----------------------------------------------------------------
            try {
                List<MethodData> getKeyCandidates = prefClass.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramCount(0)
                                .returnType("java.lang.String"))
                );

                String targetGetKey = null;
                for (MethodData md : getKeyCandidates) {
                    if (!"toString".equals(md.getMethodName())) {
                        targetGetKey = md.getMethodName();
                        break; // 找到第一个就退出
                    }
                }

                if (targetGetKey != null) {
                    String sig = CLS_PREFERENCE + "#" + targetGetKey;
                    cPutString(KEY_METHOD_GET_KEY, sig);
                    out.append("\n[OK] ").append(KEY_METHOD_GET_KEY).append(" -> ").append(targetGetKey);
                } else {
                    out.append("\n[FAIL] getKey 未找到");
                }
            } catch (Throwable t) {
                Logger.e("getKey search error", t);
            }

        } catch (Throwable t) {
            Logger.e("Preference class error", t);
            out.append("\n[FAIL] Preference 类分析严重错误");
        }
    }

    private static void searchAdapterMethods(DexKitBridge bridge, ClassLoader cl, StringBuilder out) {
        try {
            // 定位 Adapter 类
            // 包名 + 继承 BaseAdapter + 有getView + 有<init>
            ClassData adapterClass = bridge.findClass(FindClass.create()
                    .searchPackages(PKG_PREFERENCE)
                    .matcher(ClassMatcher.create()
                            .superClass("android.widget.BaseAdapter")
                            .methods(MethodsMatcher.create()
                                    .add(MethodMatcher.create().modifiers(Modifier.PUBLIC).name("getView").paramCount(3))
                                    .add(MethodMatcher.create().name("<init>").paramCount(3))
                            )
                    )
            ).singleOrNull();

            if (adapterClass == null) {
                out.append("\n[FAIL] Adapter 类未找到");
                return;
            }

            // ----------------------------------------------------------------
            // 查找 addPreference
            // paramTypes(CLS_PREFERENCE, "int").returnType("void")
            // 直接 get(0)
            // ----------------------------------------------------------------
            List<MethodData> candidates = adapterClass.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes(CLS_PREFERENCE, "int")
                            .returnType("void"))
            );

            if (candidates.isEmpty()) {
                out.append("\n[FAIL] addPreference 方法未找到");
            } else {
                // 直接取第 0 个
                MethodData target = candidates.get(0);
                cacheMethod(target, cl, KEY_METHOD_ADD_PREF, out);

                if (candidates.size() > 1) {
                    out.append("\n[INFO] addPreference 发现多个，取第1个: ").append(target.getMethodName());
                }
            }

        } catch (Throwable t) {
            Logger.e("Adapter search error", t);
            out.append("\n[FAIL] Adapter 类分析出错");
        }
    }

    // 统一的缓存工具方法
    private static void cacheMethod(MethodData md, ClassLoader cl, String key, StringBuilder out) {
        try {
            Method m = md.getMethodInstance(cl);
            String sig = m.getDeclaringClass().getName() + "#" + m.getName();
            cPutString(key, sig);
            out.append("\n[OK] ").append(key).append(" -> ").append(m.getName());
        } catch (Exception e) {
            out.append("\n[ERROR] 缓存失败: ").append(key);
            Logger.e("Cache error for " + key, e);
        }
    }

    public static void removeAllMethodSignature() {
        ConfigManager cfg = ConfigManager.getCache();
        android.content.SharedPreferences.Editor e = cfg.edit();
        for (String k : cfg.getAll().keySet()) if (k.startsWith("method_")) e.remove(k);
        e.apply();
    }

    public static Method requireMethod(String key) {
        try {
            String sig = cGetString(key, null);
            if (TextUtils.isEmpty(sig)) return null;
            String[] p = sig.split("#");
            return findMethodByName(loadClass(p[0]), p[1]);
        } catch (Throwable t) {
            Logger.e("requireMethod failed for " + key, t);
            return null;
        }
    }

    public interface OnTaskCompleteListener {
        void onTaskComplete(String result);
    }
}