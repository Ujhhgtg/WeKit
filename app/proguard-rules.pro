# ==========================================================
# Global Attributes & Basics
# ==========================================================

# 保留泛型、注解、行号
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,LineNumberTable,SourceFile,*Annotation*

# 保持所有 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持 Parcelable 实现
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# 保持枚举类的标准方法
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==========================================================
# 请将 wekit\app\src\main\java 下的包放在这里，避免被优化
# 如果你创建了自己的包，也请放在这里，谢谢！
# ==========================================================

-keep class moe.ouom.wekit.** { *; }

# ==========================================================
# Xposed & LSPosed
# ==========================================================

-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onPackageLoaded(...);
    public void onSystemServerLoaded(...);
}

-keep,allowoptimization,allowobfuscation @io.github.libxposed.api.annotations.* class * {
    @io.github.libxposed.api.annotations.BeforeInvocation <methods>;
    @io.github.libxposed.api.annotations.AfterInvocation <methods>;
}

# ==========================================================
# Kotlin 标准库优化
# ==========================================================
# 保留 Hidden Dex 可能用到的基本反射和 Intrinsic 检查
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.Metadata { *; }

# 保留 Kotlin 标准库核心类（Hidden Dex 依赖）
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.intellij.lang.annotations.** { *; }
-keep class org.jetbrains.annotations.** { *; }

# 协程
-keep class kotlinx.coroutines.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    <init>(...);
}

# 必须保留 Kotlin 属性委托相关的接口
-keep @interface kotlin.jvm.JvmField
-keep @interface kotlin.jvm.JvmStatic
-keep @interface kotlin.jvm.JvmName

-keep class kotlin.properties.PropertyDelegateProvider { *; }
-keep class kotlin.properties.ReadWriteProperty { *; }
-keep class kotlin.properties.ReadOnlyProperty { *; }

# 保留 Kotlin 内部生成的委托字段
-keepclassmembers class * {
    kotlin.properties.PropertyDelegateProvider *;
    kotlin.reflect.KProperty *;
    kotlin.properties.ReadWriteProperty *;
    kotlin.properties.ReadOnlyProperty *;
}

-keep interface kotlin.** { *; }

# ==========================================================
# 第三方库优化
# ==========================================================
# OkHttp/Retrofit
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Protocol Buffers
-keep class com.google.protobuf.GeneratedMessageLite { *; }

# ==========================================================
# Material Design & AndroidX
# ==========================================================
-keep class com.google.android.material.internal.** { *; }
-keep public class com.google.android.material.internal.CheckableImageButton { *; }

# ==========================================================
# DexKit
# ==========================================================
# 保留 DexKit 核心类和方法
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }

# 保留 DexKit 的 JNI 方法
-keepclasseswithmembernames class org.luckypray.dexkit.** {
    native <methods>;
}

# 保留 DexKit 使用的反射相关类
-keep class * implements org.luckypray.dexkit.** { *; }


# ==========================================================
# MMKV
# ==========================================================

-keep class com.tencent.mmkv.MMKV {
    public long decodeLong(java.lang.String, long);
    public static com.tencent.mmkv.MMKV defaultMMKV();
}

# ==========================================================
# 忽略警告
# ==========================================================
# 忽略 ByteBuddy 和 Mocking 相关的类
-dontwarn net.bytebuddy.**
-dontwarn java.lang.instrument.**
-dontwarn org.mockito.**

# 忽略 FindBugs/SpotBugs 注解
-dontwarn edu.umd.cs.findbugs.**
-dontwarn javax.annotation.**

# 忽略 KotlinPoet 和 Java Model 类
-dontwarn com.squareup.kotlinpoet.**
-dontwarn javax.lang.model.**

# 忽略 ServiceProvider 相关的 OSGi 注解
-dontwarn aQute.bnd.annotation.spi.**

# 修复 Kotlin Experimental 报错 #
# 忽略旧版 Kotlin 注解缺失的警告
-dontwarn kotlin.Experimental
-dontwarn kotlin.Experimental$Level

# 针对报错中提到的 kotlinx.io 相关的忽略
-dontwarn kotlinx.io.**

# 修复 Stax2 XML 相关的警告 #
-dontwarn org.codehaus.stax2.**

# 忽略 DexKit 警告
-dontwarn org.luckypray.dexkit.**

# 忽略被抽离隐藏的 hooks 包，它们会在运行时通过内存加载
-dontwarn moe.ouom.wekit.hooks.**

# Material Dialogs
-keep class com.afollestad.materialdialogs.** { *; }
-dontwarn com.afollestad.materialdialogs.**

# OTHER #
-dontwarn com.sun.jna.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.android.material.**
-dontwarn androidx.**

# ==========================================================
# Build Behavior
# ==========================================================
-dontoptimize
-renamesourcefileattribute SourceFile
-dontobfuscate