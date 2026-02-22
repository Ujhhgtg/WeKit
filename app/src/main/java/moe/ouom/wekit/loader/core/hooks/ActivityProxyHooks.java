package moe.ouom.wekit.loader.core.hooks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.*;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lombok.SneakyThrows;
import moe.ouom.wekit.config.RuntimeConfig;
import moe.ouom.wekit.constants.PackageConstants;
import moe.ouom.wekit.utils.common.ModuleRes;
import moe.ouom.wekit.utils.log.WeLogger;

/**
 * Activity 占位 Hook 实现
 * 允许模块启动未在宿主 Manifest 中注册的 Activity
 */
public class ActivityProxyHooks {

    private static boolean __stub_hooked = false;

    public static class ActProxyMgr {
        public static final String ACTIVITY_PROXY_INTENT_TOKEN = "wekit_target_intent_token";

        // 这个 Activity 必须在微信的 AndroidManifest.xml 中真实存在且 exported=false 也可以，只要同进程
        public static final String STUB_DEFAULT_ACTIVITY = "com.tencent.mm.plugin.facedetect.ui.FaceTransparentStubUI";

        /**
         * 判断是否为模块内的 Activity
         */
        public static boolean isModuleProxyActivity(String className) {
            return className != null && className.startsWith("moe.ouom.wekit");
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static void initForStubActivity(Context ctx) {
        if (__stub_hooked) {
            return;
        }
        try {
            // 获取 ActivityThread 实例
            var clazz_ActivityThread = Class.forName("android.app.ActivityThread");
            var currentActivityThread = clazz_ActivityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            var sCurrentActivityThread = currentActivityThread.invoke(null);

            // Hook Instrumentation
            var mInstrumentation = clazz_ActivityThread.getDeclaredField("mInstrumentation");
            mInstrumentation.setAccessible(true);
            var instrumentation = (Instrumentation) mInstrumentation.get(sCurrentActivityThread);
            if (!(instrumentation instanceof ProxyInstrumentation)) {
                // 创建代理对象
                ProxyInstrumentation proxy = new ProxyInstrumentation(instrumentation);
                // 替换掉系统的实例
                mInstrumentation.set(sCurrentActivityThread, proxy);
            }

            // Hook Handler (mH)
            var field_mH = clazz_ActivityThread.getDeclaredField("mH");
            field_mH.setAccessible(true);
            var oriHandler = (Handler) field_mH.get(sCurrentActivityThread);
            var field_mCallback = Handler.class.getDeclaredField("mCallback");
            field_mCallback.setAccessible(true);
            var current = (Handler.Callback) field_mCallback.get(oriHandler);
            if (current == null || !current.getClass().getName().equals(ProxyHandlerCallback.class.getName())) {
                field_mCallback.set(oriHandler, new ProxyHandlerCallback(current));
            }

            // Hook AMS (IActivityManager / IActivityTaskManager)
            hookIActivityManager();

            // Hook PackageManager
            hookPackageManager(ctx, sCurrentActivityThread, clazz_ActivityThread);

            __stub_hooked = true;
            WeLogger.i("ActivityProxyHooks", "Activity Proxy Hooks installed successfully");
        } catch (Exception e) {
            WeLogger.e("ActivityProxyHooks", "Failed to init stub activity hooks", e);
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static void hookIActivityManager() throws Exception {
        Class<?> activityManagerClass;
        Field gDefaultField;
        // 兼容 Android 8.0 以前和以后的获取方式
        try {
            activityManagerClass = Class.forName("android.app.ActivityManagerNative");
            gDefaultField = activityManagerClass.getDeclaredField("gDefault");
        } catch (Exception err1) {
            activityManagerClass = Class.forName("android.app.ActivityManager");
            //noinspection JavaReflectionMemberAccess
            gDefaultField = activityManagerClass.getDeclaredField("IActivityManagerSingleton");
        }
        gDefaultField.setAccessible(true);
        var gDefault = gDefaultField.get(null);

        var singletonClass = Class.forName("android.util.Singleton");

        var mInstanceField = singletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);

        try {
            var getMethod = singletonClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            getMethod.invoke(gDefault);
        } catch (Exception ignored) {}

        var mInstance = mInstanceField.get(gDefault);
        if (mInstance == null) {
            WeLogger.e("ActivityProxyHooks", "IActivityManager instance is null, abort hook.");
            return;
        }

        // 创建 IActivityManager 代理
        var amProxy = Proxy.newProxyInstance(
                ActivityProxyHooks.class.getClassLoader(),
                new Class[]{Class.forName("android.app.IActivityManager")},
                new IActivityManagerHandler(mInstance));
        mInstanceField.set(gDefault, amProxy);

        // 兼容 Android 10+ (Q) 的 ActivityTaskManager
        try {
            var activityTaskManagerClass = Class.forName("android.app.ActivityTaskManager");
            var fIActivityTaskManagerSingleton = activityTaskManagerClass.getDeclaredField("IActivityTaskManagerSingleton");
            fIActivityTaskManagerSingleton.setAccessible(true);
            var singleton = fIActivityTaskManagerSingleton.get(null);

            // 触发 Singleton 加载
            singletonClass.getMethod("get").invoke(singleton);

            var mDefaultTaskMgr = mInstanceField.get(singleton);
            if (mDefaultTaskMgr != null) {
                var proxy2 = Proxy.newProxyInstance(
                        ActivityProxyHooks.class.getClassLoader(),
                        new Class[]{Class.forName("android.app.IActivityTaskManager")},
                        new IActivityManagerHandler(mDefaultTaskMgr));
                mInstanceField.set(singleton, proxy2);
            }
        } catch (Exception ignored) {
            // Android 9 及以下没有这个类，忽略
        }
    }

    @SuppressLint("PrivateApi")
    private static void hookPackageManager(Context ctx, Object sCurrentActivityThread, Class<?> clazz_ActivityThread) {
        try {
            var sPackageManagerField = clazz_ActivityThread.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            var packageManagerImpl = sPackageManagerField.get(sCurrentActivityThread);

            var iPackageManagerInterface = Class.forName("android.content.pm.IPackageManager");

            // 既替换 ActivityThread 中的缓存，也替换 Application Context 中的缓存
            var pm = ctx.getPackageManager();
            var mPmField = pm.getClass().getDeclaredField("mPM");
            mPmField.setAccessible(true);

            var pmProxy = Proxy.newProxyInstance(
                    iPackageManagerInterface.getClassLoader(),
                    new Class[]{iPackageManagerInterface},
                    new PackageManagerInvocationHandler(packageManagerImpl));

            sPackageManagerField.set(sCurrentActivityThread, pmProxy);
            mPmField.set(pm, pmProxy);
        } catch (Exception e) {
            WeLogger.e("ActivityProxyHooks", "Failed to hook PackageManager (Non-fatal)", e);
        }
    }

    /**
     * AMS 动态代理：拦截 startActivity，将目标 Intent 替换为 Stub Activity
     */
    public static class IActivityManagerHandler implements InvocationHandler {
        private final Object mOrigin;

        public IActivityManagerHandler(Object origin) {
            mOrigin = origin;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // 拦截 startActivity 以及 startActivities (Intent[])
            if (name.startsWith("startActivity") || name.startsWith("startActivities")) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent raw) {
                        if (shouldProxy(raw)) {
                            args[i] = createTokenWrapper(raw);
                        }
                    } else if (args[i] instanceof Intent[] rawIntents) {
                        for (var j = 0; j < rawIntents.length; j++) {
                            if (shouldProxy(rawIntents[j])) {
                                rawIntents[j] = createTokenWrapper(rawIntents[j]);
                            }
                        }
                    }
                }
            }

            try {
                return method.invoke(mOrigin, args);
            } catch (InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        }

        /**
         * 判断 Intent 是否需要被代理
         */
        private boolean shouldProxy(Intent intent) {
            if (intent == null) return false;
            var component = intent.getComponent();
            // 检查 Component 是否存在且属于模块包名
            return component != null && ActProxyMgr.isModuleProxyActivity(component.getClassName());
        }

        /**
         * 构建携带 Token 的替身 Intent
         * 原始 Intent 入库 -> 生成 Token -> 构造仅含 Token 的 Wrapper
         */
        private Intent createTokenWrapper(Intent raw) {
            // 将原始 Intent 存入静态缓存，获取 Token
            var token = IntentTokenCache.put(new Intent(raw));

            var wrapper = new Intent();
            wrapper.setComponent(new ComponentName(PackageConstants.PACKAGE_NAME_WECHAT, ActProxyMgr.STUB_DEFAULT_ACTIVITY));
            wrapper.setFlags(raw.getFlags());
            wrapper.setAction(raw.getAction());
            wrapper.setDataAndType(raw.getData(), raw.getType());

            // 复制 Categories
            if (raw.getCategories() != null) {
                for (var cat : raw.getCategories()) {
                    wrapper.addCategory(cat);
                }
            }
            wrapper.putExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN, token);

            // 强制设置 HybridClassLoader，避免序列化问题
            var hybridCL = ParcelableFixer.getHybridClassLoader();
            if (hybridCL != null) {
                wrapper.setExtrasClassLoader(hybridCL);
            }

            WeLogger.d("ActivityProxyHooks", "Hijacked startActivity via Token: " +
                    Objects.requireNonNull(raw.getComponent()).getClassName() + " -> " + ActProxyMgr.STUB_DEFAULT_ACTIVITY);

            return wrapper;
        }
    }

    /**
     * Handler 代理：在 Activity 启动消息处理前，将 Intent 还原
     */
    public static class ProxyHandlerCallback implements Handler.Callback {
        private final Handler.Callback mNextCallbackHook;

        public ProxyHandlerCallback(Handler.Callback next) {
            mNextCallbackHook = next;
        }

        @Override
        public boolean handleMessage(Message msg) {
            // LAUNCH_ACTIVITY (Android < 9.0)
            if (msg.what == 100) {
                handleLaunchActivity(msg);
            }
            // EXECUTE_TRANSACTION (Android >= 9.0)
            else if (msg.what == 159) {
                handleExecuteTransaction(msg);
            }

            var handledByNext = false;
            if (mNextCallbackHook != null) {
                try {
                    handledByNext = mNextCallbackHook.handleMessage(msg);
                } catch (Throwable t) {
                    WeLogger.e("ActivityProxyHooks", "Next callback failed", t);
                }
            }

            return handledByNext;
        }

        /**
         * 统一还原 Intent 的逻辑
         */
        private Intent unwrapIntent(Intent wrapper) {
            if (wrapper == null) return null;

            // 先修复 wrapper 的 ClassLoader，防止读取 token 时报错
            var hybridCL = ParcelableFixer.getHybridClassLoader();
            if (hybridCL != null) {
                wrapper.setExtrasClassLoader(hybridCL);
            }

            // 尝试读取 Token
            if (wrapper.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)) {
                var token = wrapper.getStringExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN);
                var realIntent = IntentTokenCache.getAndRemove(token); // 取回并移除

                if (realIntent != null) {
                    // 修复真实 Intent 的 ClassLoader
                    if (hybridCL != null) {
                        realIntent.setExtrasClassLoader(hybridCL);
                        var extras = realIntent.getExtras();
                        if (extras != null) {
                            extras.setClassLoader(hybridCL);
                        }
                    }
                    return realIntent;
                } else {
                    WeLogger.w("ActivityProxyHooks", "Token expired or lost in Handler: " + token);
                }
            }
            return null;
        }

        private void handleLaunchActivity(Message msg) {
            try {
                var record = msg.obj;
                var intentField = record.getClass().getDeclaredField("intent");
                intentField.setAccessible(true);
                var wrapper = (Intent) intentField.get(record);

                var real = unwrapIntent(wrapper);
                if (real != null) {
                    intentField.set(record, real);
                }
            } catch (Exception e) {
                WeLogger.e("ActivityProxyHooks", "handleLaunchActivity error", e);
            }
        }

        private void handleExecuteTransaction(Message msg) {
            try {
                var transaction = msg.obj;
                var getCallbacks = transaction.getClass().getDeclaredMethod("getCallbacks");
                getCallbacks.setAccessible(true);
                var callbacks = (List<?>) getCallbacks.invoke(transaction);
                if (callbacks != null) {
                    for (var item : callbacks) {
                        if (item.getClass().getName().contains("LaunchActivityItem")) {
                            var intentField = item.getClass().getDeclaredField("mIntent");
                            intentField.setAccessible(true);
                            var wrapper = (Intent) intentField.get(item);

                            var real = unwrapIntent(wrapper);
                            if (real != null) {
                                intentField.set(item, real);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                WeLogger.e("ActivityProxyHooks", "handleExecuteTransaction error", e);
            }
        }
    }

    /**
     * Instrumentation 代理：负责实例化 Activity 和注入资源
     */
    @SuppressLint("NewApi")
    public static class ProxyInstrumentation extends Instrumentation {
        private final Instrumentation mBase;

        public ProxyInstrumentation(Instrumentation base) {
            mBase = base;
        }

        /**
         * 尝试在 newActivity 阶段最后一次还原 Intent
         */
        private Intent tryRecoverIntent(Intent intent) {
            if (intent != null && intent.hasExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN)) {
                var hybridCL = ParcelableFixer.getHybridClassLoader();
                if (hybridCL != null) intent.setExtrasClassLoader(hybridCL);

                var token = intent.getStringExtra(ActProxyMgr.ACTIVITY_PROXY_INTENT_TOKEN);
                var real = IntentTokenCache.getAndRemove(token);

                if (real != null) {
                    if (hybridCL != null) {
                        real.setExtrasClassLoader(hybridCL);
                        var extras = real.getExtras();
                        if (extras != null) extras.setClassLoader(hybridCL);
                    }
                    return real;
                }
            }
            return null;
        }

        /**
         * 实例化 Activity
         * 如果系统 ClassLoader 找不到类，则尝试使用模块 ClassLoader 加载
         */
        @SneakyThrows
        @Override
        public Activity newActivity(ClassLoader cl, String className, Intent intent) {

            // 兜底：如果 intent 仍然是 stub 的 wrapper，尝试还原
            var recovered = tryRecoverIntent(intent);
            if (recovered != null && recovered.getComponent() != null) {
                intent = recovered;
                className = recovered.getComponent().getClassName();
                WeLogger.w("ProxyInstrumentation", "Recovered intent in newActivity fallback: " + className);
            }

            try {
                return mBase.newActivity(cl, className, intent);
            } catch (ClassNotFoundException e) {
                if (ActProxyMgr.isModuleProxyActivity(className)) {
                    var moduleCL = Objects.requireNonNull(getClass().getClassLoader());
                    return (Activity) moduleCL.loadClass(className).getDeclaredConstructor().newInstance();
                }
                throw e;
            }
        }

        // 兼容新版 Android 的 newActivity 重载
        @Override
        public Activity newActivity(Class<?> clazz, Context context, IBinder token, Application application, Intent intent, ActivityInfo info, CharSequence title, Activity parent, String id, Object lastNonConfigurationInstance) throws InstantiationException, IllegalAccessException {
            return mBase.newActivity(clazz, context, token, application, intent, info, title, parent, id, lastNonConfigurationInstance);
        }

        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle) {
            WeLogger.d("ProxyInst",
                    "callActivityOnCreate: " + activity.getClass().getName() +
                            " isModule=" + ActProxyMgr.isModuleProxyActivity(activity.getClass().getName()));

            var isModuleAct = ActProxyMgr.isModuleProxyActivity(activity.getClass().getName());

            if (isModuleAct) {
                checkAndInjectResources(activity);

                var hybridCL = ParcelableFixer.getHybridClassLoader();
                if (hybridCL != null) {
                    try {
                        @SuppressWarnings("JavaReflectionMemberAccess")
                        var f = Activity.class.getDeclaredField("mClassLoader");
                        f.setAccessible(true);
                        f.set(activity, hybridCL);
                    } catch (Throwable ignored) {}

                    var intent = activity.getIntent();
                    if (intent != null) {
                        intent.setExtrasClassLoader(hybridCL);
                        var ex = intent.getExtras();
                        if (ex != null) ex.setClassLoader(hybridCL);
                    }
                }
            }

            mBase.callActivityOnCreate(activity, icicle);
        }

        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
            checkAndInjectResources(activity);
            mBase.callActivityOnCreate(activity, icicle, persistentState);
        }

        private void checkAndInjectResources(Activity activity) {
            if (ActProxyMgr.isModuleProxyActivity(activity.getClass().getName())) {
                ModuleRes.init(activity, PackageConstants.PACKAGE_NAME_SELF);
            }
        }

        @Override
        public void onCreate(Bundle arguments) {
            mBase.onCreate(arguments);
        }

        @Override
        public void start() {
            mBase.start();
        }

        @Override
        public void onStart() {
            mBase.onStart();
        }

        @Override
        public boolean onException(Object obj, Throwable e) {
            return mBase.onException(obj, e);
        }

        @Override
        public void sendStatus(int resultCode, Bundle results) {
            mBase.sendStatus(resultCode, results);
        }

        @Override
        public void addResults(Bundle results) {
            mBase.addResults(results);
        }

        @Override
        public void finish(int resultCode, Bundle results) {
            mBase.finish(resultCode, results);
        }

        @Override
        public void setAutomaticPerformanceSnapshots() {
            mBase.setAutomaticPerformanceSnapshots();
        }

        @Override
        public void startPerformanceSnapshot() {
            mBase.startPerformanceSnapshot();
        }

        @Override
        public void endPerformanceSnapshot() {
            mBase.endPerformanceSnapshot();
        }

        @Override
        public void onDestroy() {
            mBase.onDestroy();
        }

        @Override
        public Context getContext() {
            return mBase.getContext();
        }

        @Override
        public ComponentName getComponentName() {
            return mBase.getComponentName();
        }

        @Override
        public Context getTargetContext() {
            return mBase.getTargetContext();
        }

        @Override
        public String getProcessName() {
            return mBase.getProcessName();
        }

        @Override
        public boolean isProfiling() {
            return mBase.isProfiling();
        }

        @Override
        public void startProfiling() {
            mBase.startProfiling();
        }

        @Override
        public void stopProfiling() {
            mBase.stopProfiling();
        }

        @Override
        public void setInTouchMode(boolean inTouch) {
            mBase.setInTouchMode(inTouch);
        }

        @Override
        public void waitForIdle(Runnable recipient) {
            mBase.waitForIdle(recipient);
        }

        @Override
        public void waitForIdleSync() {
            mBase.waitForIdleSync();
        }

        @Override
        public void runOnMainSync(Runnable runner) {
            mBase.runOnMainSync(runner);
        }

        @Override
        public Activity startActivitySync(Intent intent) {
            return mBase.startActivitySync(intent);
        }

        @NonNull
        @Override
        public Activity startActivitySync(@NonNull Intent intent, Bundle options) {
            return mBase.startActivitySync(intent, options);
        }

        @Override
        public void addMonitor(ActivityMonitor monitor) {
            mBase.addMonitor(monitor);
        }

        @Override
        public ActivityMonitor addMonitor(IntentFilter filter, ActivityResult result, boolean block) {
            return mBase.addMonitor(filter, result, block);
        }

        @Override
        public ActivityMonitor addMonitor(String cls, ActivityResult result, boolean block) {
            return mBase.addMonitor(cls, result, block);
        }

        @Override
        public boolean checkMonitorHit(ActivityMonitor monitor, int minHits) {
            return mBase.checkMonitorHit(monitor, minHits);
        }

        @Override
        public Activity waitForMonitor(ActivityMonitor monitor) {
            return mBase.waitForMonitor(monitor);
        }

        @Override
        public Activity waitForMonitorWithTimeout(ActivityMonitor monitor, long timeOut) {
            return mBase.waitForMonitorWithTimeout(monitor, timeOut);
        }

        @Override
        public void removeMonitor(ActivityMonitor monitor) {
            mBase.removeMonitor(monitor);
        }

        @Override
        public boolean invokeMenuActionSync(Activity targetActivity, int id, int flag) {
            return mBase.invokeMenuActionSync(targetActivity, id, flag);
        }

        @Override
        public boolean invokeContextMenuAction(Activity targetActivity, int id, int flag) {
            return mBase.invokeContextMenuAction(targetActivity, id, flag);
        }

        @Override
        public void sendStringSync(String text) {
            mBase.sendStringSync(text);
        }

        @Override
        public void sendKeySync(KeyEvent event) {
            mBase.sendKeySync(event);
        }

        @Override
        public void sendKeyDownUpSync(int key) {
            mBase.sendKeyDownUpSync(key);
        }

        @Override
        public void sendCharacterSync(int keyCode) {
            mBase.sendCharacterSync(keyCode);
        }

        @Override
        public void sendPointerSync(MotionEvent event) {
            mBase.sendPointerSync(event);
        }

        @Override
        public void sendTrackballEventSync(MotionEvent event) {
            mBase.sendTrackballEventSync(event);
        }

        @Override
        public Application newApplication(ClassLoader cl, String className, Context context) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
            return mBase.newApplication(cl, className, context);
        }

        @Override
        public void callApplicationOnCreate(Application app) {
            mBase.callApplicationOnCreate(app);
        }

        @Override
        public void callActivityOnDestroy(Activity activity) {
            mBase.callActivityOnDestroy(activity);
        }

        @Override
        public void callActivityOnRestoreInstanceState(@NonNull Activity activity, @NonNull Bundle savedInstanceState) {
            mBase.callActivityOnRestoreInstanceState(activity, savedInstanceState);
        }

        @Override
        public void callActivityOnRestoreInstanceState(@NonNull Activity activity, Bundle savedInstanceState, PersistableBundle persistentState) {
            mBase.callActivityOnRestoreInstanceState(activity, savedInstanceState, persistentState);
        }

        @Override
        public void callActivityOnPostCreate(@NonNull Activity activity, Bundle savedInstanceState) {
            mBase.callActivityOnPostCreate(activity, savedInstanceState);
        }

        @Override
        public void callActivityOnPostCreate(@NonNull Activity activity, @Nullable Bundle savedInstanceState, @Nullable PersistableBundle persistentState) {
            mBase.callActivityOnPostCreate(activity, savedInstanceState, persistentState);
        }

        @Override
        public void callActivityOnNewIntent(Activity activity, Intent intent) {
            mBase.callActivityOnNewIntent(activity, intent);
        }

        @Override
        public void callActivityOnStart(Activity activity) {
            mBase.callActivityOnStart(activity);
        }

        @Override
        public void callActivityOnRestart(Activity activity) {
            mBase.callActivityOnRestart(activity);
        }

        @Override
        public void callActivityOnResume(Activity activity) {
            mBase.callActivityOnResume(activity);
        }

        @Override
        public void callActivityOnStop(Activity activity) {
            mBase.callActivityOnStop(activity);
        }

        @Override
        public void callActivityOnSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            mBase.callActivityOnSaveInstanceState(activity, outState);
        }

        @Override
        public void callActivityOnSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState, @NonNull PersistableBundle outPersistentState) {
            mBase.callActivityOnSaveInstanceState(activity, outState, outPersistentState);
        }

        @Override
        public void callActivityOnPause(Activity activity) {
            mBase.callActivityOnPause(activity);
        }

        @Override
        public void callActivityOnUserLeaving(Activity activity) {
            mBase.callActivityOnUserLeaving(activity);
        }

        @SuppressWarnings("deprecation")
        @Deprecated
        @Override
        public void startAllocCounting() {
            mBase.startAllocCounting();
        }

        @SuppressWarnings("deprecation")
        @Deprecated
        @Override
        public void stopAllocCounting() {
            mBase.stopAllocCounting();
        }

        @Override
        public Bundle getAllocCounts() {
            return mBase.getAllocCounts();
        }

        @Override
        public Bundle getBinderCounts() {
            return mBase.getBinderCounts();
        }

        @Override
        public UiAutomation getUiAutomation() {
            return mBase.getUiAutomation();
        }

        @Override
        public UiAutomation getUiAutomation(int flags) {
            return mBase.getUiAutomation(flags);
        }

        @Override
        public TestLooperManager acquireLooperManager(Looper looper) {
            return mBase.acquireLooperManager(looper);
        }
    }

    /**
     * PackageManager 代理：拦截 getActivityInfo，为模块 Activity 返回伪造的 ActivityInfo
     */
    public static class PackageManagerInvocationHandler implements InvocationHandler {
        private final Object mTarget;

        public PackageManagerInvocationHandler(Object target) {
            if (target == null) throw new NullPointerException("IPackageManager is null");
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getActivityInfo".equals(method.getName())) {
                ComponentName component = null;
                var flags = 0;
                var flagsFound = false;

                for (var arg : args) {
                    if (arg instanceof ComponentName) {
                        component = (ComponentName) arg;
                    } else if (arg instanceof Long) {
                        flags = ((Long) arg).intValue();
                        flagsFound = true;
                    } else if (arg instanceof Integer && !flagsFound) {
                        flags = (Integer) arg;
                    }
                }

                if (component != null && ActProxyMgr.isModuleProxyActivity(component.getClassName())) {
                    return CounterfeitActivityInfoFactory.makeProxyActivityInfo(component.getClassName(), flags);
                }
            }

            try {
                return method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }

    /**
     * 工厂类：生成伪造的 ActivityInfo
     */
    public static class CounterfeitActivityInfoFactory {
        public static ActivityInfo makeProxyActivityInfo(String className, int flags) {
            var ai = new ActivityInfo();
            ai.name = className;
            ai.packageName = PackageConstants.PACKAGE_NAME_WECHAT; // 必须假装是宿主的包名
            ai.enabled = true;
            ai.exported = false;
            ai.processName = PackageConstants.PACKAGE_NAME_WECHAT;

            // 复制宿主的 ApplicationInfo
            try {
                ai.applicationInfo = RuntimeConfig.getHostApplicationInfo();
                if (ai.applicationInfo == null) {
                    // Fallback
                    ai.applicationInfo = new ApplicationInfo();
                    ai.applicationInfo.packageName = PackageConstants.PACKAGE_NAME_WECHAT;
                }
            } catch (Exception e) {
                ai.applicationInfo = new ApplicationInfo();
            }

            ai.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            return ai;
        }
    }

    private static class IntentTokenCache {
        private static class Entry {
            Intent intent;
            long timestamp;

            Entry(Intent i) {
                intent = i;
                timestamp = System.currentTimeMillis();
            }
        }

        private static final Map<String, Entry> sCache = new ConcurrentHashMap<>();
        private static final long EXPIRE_MS = 60 * 1000;

        static String put(Intent intent) {
            cleanup();

            var token = UUID.randomUUID().toString();
            sCache.put(token, new Entry(intent));
            return token;
        }

        static Intent getAndRemove(String token) {
            if (token == null) return null;
            var entry = sCache.remove(token);
            if (entry == null) return null;

            var now = System.currentTimeMillis();
            if (now - entry.timestamp > EXPIRE_MS) {
                return null;
            }
            return entry.intent;
        }

        static Intent peek(String token) {
            if (token == null) return null;
            var entry = sCache.get(token);
            if (entry == null) return null;

            if (isExpired(entry)) {
                sCache.remove(token);
                return null;
            }
            return entry.intent;
        }

        private static boolean isExpired(Entry entry) {
            return System.currentTimeMillis() - entry.timestamp > EXPIRE_MS;
        }

        private static void cleanup() {
            var now = System.currentTimeMillis();
            sCache.entrySet().removeIf(stringEntryEntry -> now - stringEntryEntry.getValue().timestamp > EXPIRE_MS);
        }
    }
}