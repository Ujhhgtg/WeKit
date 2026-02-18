package moe.ouom.wekit.hooks.core.factory;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import moe.ouom.wekit.core.model.BaseHookItem;
import moe.ouom.wekit.utils.log.LogUtils;


/**
 * 异常处理工厂
 * NOTE: 不要移除这个类名前面的 "_"，他能保护此方法不进入 Hidden DEX
 */
public class _ExceptionFactory {
    private final static Map<BaseHookItem, List<Throwable>> exceptionMap = new HashMap<>();

    /**
     * 检查是否超过3个或重复
     *
     * @return 为true则已经超过三个或者添加过
     */
    private static boolean check(BaseHookItem item, Throwable throwable) {
        // 每个 item 最多只保存 3 个 Throwable, 不然添加太多会占用太多不必要的内存
        var exceptionsList = exceptionMap.get(item);
        if (exceptionsList == null || exceptionsList.size() < 3) {
            return false;
        }
        // 判断是否已经添加过了 添加过则不再重复添加
        for (var ex : exceptionsList) {
            if (Objects.equals(ex.getMessage(), throwable.getMessage())) {
                return true;
            }
        }
        return false;
    }

    public static void add(BaseHookItem item, Throwable throwable) {
        if (check(item, throwable)) {
            return;
        }
        var exceptionsList = exceptionMap.get(item);
        if (exceptionsList == null) {
            exceptionsList = new ArrayList<>();
        }
        exceptionsList.add(0, throwable);
        exceptionMap.put(item, exceptionsList);
        XposedBridge.log(throwable);
        try {
            LogUtils.addError("item_" + item.getItemName(), throwable);
        } catch (NoClassDefFoundError ignored) {
        }


    }

    public static String getStackTrace(BaseHookItem hookItem) {
        var builder = new StringBuilder();
        var exceptionsList = exceptionMap.get(hookItem);
        if (exceptionsList == null) {
            return builder.toString();
        }
        for (var ex : exceptionsList) {
            builder.append(Log.getStackTraceString(ex));
            builder.append("\n");
        }
        return builder.toString();
    }

}
