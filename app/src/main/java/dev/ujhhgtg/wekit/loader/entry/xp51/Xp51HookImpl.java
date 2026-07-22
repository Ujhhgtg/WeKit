package dev.ujhhgtg.wekit.loader.entry.xp51;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import dev.ujhhgtg.wekit.BuildConfig;
import dev.ujhhgtg.wekit.loader.abc.IClassLoaderHelper;
import dev.ujhhgtg.wekit.loader.abc.IHookBridge;
import dev.ujhhgtg.wekit.loader.abc.ILoaderService;
import lombok.SneakyThrows;

public class Xp51HookImpl implements IHookBridge, ILoaderService {

    public static final Xp51HookImpl INSTANCE = new Xp51HookImpl();

    private IClassLoaderHelper mClassLoaderHelper;

    @NonNull
    @Override
    public String getLoaderName() {
        return "旧式 Xposed API " + XposedBridge.getXposedVersion();
    }

    @NonNull
    @Override
    public String getHookBridgeName() {
        return getLoaderName();
    }

    @Override
    public int getApiLevel() {
        return XposedBridge.getXposedVersion();
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return "Xposed";
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return String.valueOf(XposedBridge.getXposedVersion());
    }

    @Override
    public long getFrameworkVersionCode() {
        return XposedBridge.getXposedVersion();
    }

    @NonNull
    @Override
    public MemberUnhookHandle hookMethod(@NonNull Member member, @NonNull IMemberHookCallback callback, int priority) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(callback, "callback");
        // check member is method or constructor
        if (!(member instanceof java.lang.reflect.Method) && !(member instanceof java.lang.reflect.Constructor)) {
            throw new IllegalArgumentException("member must be method or constructor");
        }
        var cb = new Xp51HookWrapper.Xp51HookCallback(callback, priority);
        var unhook = XposedBridge.hookMethod(member, cb);
        if (unhook == null) {
            throw new UnsupportedOperationException("XposedBridge.hookMethod return null for member: " + member);
        }
        // add to hooked methods set
        Xp51HookWrapper.getHookedMethodsRaw().add(member);
        return new Xp51HookWrapper.Xp51UnhookHandle(unhook, member, cb);
    }

    @SneakyThrows
    @Nullable
    public Object invokeOriginalMethod(@NonNull Method method, @Nullable Object thisObject, @NonNull Object[] args) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(args, "args");
        return XposedBridge.invokeOriginalMethod(method, thisObject, args);
    }

    @SneakyThrows
    @Override
    public <T> void invokeOriginalConstructor(@NonNull Constructor<T> ctor, @NonNull T thisObject, @NonNull Object[] args) {
        Objects.requireNonNull(ctor, "ctor");
        Objects.requireNonNull(thisObject, "thisObject");
        Objects.requireNonNull(args, "args");
        XposedBridge.invokeOriginalMethod(ctor, thisObject, args);
    }

    @NonNull
    @Override
    public <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, @NonNull Object... args)
            throws IllegalArgumentException {
        // TODO: 2024-07-22 allocate instance
        throw new UnsupportedOperationException("allocate instance is not supported");
    }

    @Override
    public boolean isDeoptimizationSupported() {
        return false;
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return false;
    }

    @Nullable
    @Override
    public Object queryExtension(@NonNull String key, @Nullable Object... args) {
        return Xp51ExtCmd.handleQueryExtension(key);
    }

    @NonNull
    @Override
    public String getEntryPointName() {
        return "dev.ujhhgtg.wekit.loader.entry.xp51.Xp51HookImpl";
    }

    @NonNull
    @Override
    public String getLoaderVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public int getLoaderVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @NonNull
    @Override
    public String getMainModulePath() {
        return Xp51HookEntry.getModulePath();
    }

    @Override
    public void log(@NonNull String msg) {
        if (TextUtils.isEmpty(msg)) {
            return;
        }
        XposedBridge.log(msg);
    }

    @Override
    public void log(@NonNull Throwable tr) {
        XposedBridge.log(tr);
    }

    @Nullable
    @Override
    public IClassLoaderHelper getClassLoaderHelper() {
        return mClassLoaderHelper;
    }

    @Override
    public void setClassLoaderHelper(@Nullable IClassLoaderHelper helper) {
        mClassLoaderHelper = helper;
    }

    @Override
    public long getHookCounter() {
        return Xp51HookWrapper.getHookCounter();
    }

    @Override
    public Set<Member> getHookedMethods() {
        // return a read-only set
        return Collections.unmodifiableSet(Xp51HookWrapper.getHookedMethodsRaw());
    }
}
