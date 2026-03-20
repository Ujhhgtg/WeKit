# App
-keep class **.R$* { *; }
-keep interface dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex { *; }
-keep class * implements dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex {
    public void resolveDex(...);
}
-keep class dev.ujhhgtg.wekit.core.model.SwitchHookItem { *; }
-keep class dev.ujhhgtg.wekit.core.model.ClickableHookItem { *; }
-keep class dev.ujhhgtg.wekit.core.model.ApiHookItem { *; }
-keep class dev.ujhhgtg.wekit.core.model.BaseHookItem { *; }
-keep class dev.ujhhgtg.wekit.loader.entry.lsp10x.Lsp10xUnifiedHookEntry { *; }
-keep class dev.ujhhgtg.wekit.loader.entry.xp51.Xp51HookEntry { *; }
-keep class dev.ujhhgtg.wekit.loader.entry.frida.FridaInjectEntry { *; }
-keep class io.github.libxposed.** { *; }

# Attributes
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,LineNumberTable,SourceFile,*Annotation*

# Natives
-keepclasseswithmembernames class * {
    native <methods>;
}

# Android
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Suppress warnings
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.beans.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn jdk.jfr.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn io.netty.pkitesting.**
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
-dontwarn org.osgi.annotation.**
