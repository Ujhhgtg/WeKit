package moe.ouom.wekit.hooks.core.factory;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate;
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem;
import moe.ouom.wekit.core.model.BaseHookItem;
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem;
import moe.ouom.wekit.hooks.gen.HookItemEntryList;

public class HookItemFactory implements IHookFactoryDelegate {
    public static final HookItemFactory INSTANCE = new HookItemFactory();

    // 使用 LinkedHashMap 保持 KSP 生成的顺序
    private static final Map<Class<? extends BaseHookItem>, BaseHookItem> ITEM_MAP = new LinkedHashMap<>();

    static {
        var items = HookItemEntryList.getAllHookItems();
        for (var item : items) {
            ITEM_MAP.put(item.getClass(), item);
        }
    }

    @Override
    public BaseSwitchFunctionHookItem findHookItemByPath(@NonNull String path) {
        return findHookItemByPathStatic(path);
    }

    @NonNull
    @Override
    public List<BaseSwitchFunctionHookItem> getAllSwitchFunctionItemList() {
        return getAllSwitchFunctionItemListStatic();
    }

    @NonNull
    @Override
    public List<BaseClickableFunctionHookItem> getAllClickableFunctionItemList() {
        return getAllClickableFunctionItemListStatic();
    }

    @NonNull
    @Override
    public List<BaseHookItem> getAllItemList() {
        return getAllItemListStatic();
    }


    public static BaseSwitchFunctionHookItem findHookItemByPathStatic(String path) {
        for (var item : ITEM_MAP.values()) {
            if (item.getPath().equals(path)) {
                return (BaseSwitchFunctionHookItem) item;
            }
        }
        return null;
    }

    public static List<BaseSwitchFunctionHookItem> getAllSwitchFunctionItemListStatic() {
        var result = new ArrayList<BaseSwitchFunctionHookItem>();
        for (var item : ITEM_MAP.values()) {
            if (item instanceof BaseSwitchFunctionHookItem) {
                result.add((BaseSwitchFunctionHookItem) item);
            }
        }
        return result;
    }

    public static List<BaseClickableFunctionHookItem> getAllClickableFunctionItemListStatic() {
        var result = new ArrayList<BaseClickableFunctionHookItem>();
        for (var item : ITEM_MAP.values()) {
            if (item instanceof BaseClickableFunctionHookItem) {
                result.add((BaseClickableFunctionHookItem) item);
            }
        }
        return result;
    }

    public static List<BaseHookItem> getAllItemListStatic() {
        return new ArrayList<>(ITEM_MAP.values());
    }
}