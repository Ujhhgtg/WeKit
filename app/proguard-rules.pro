# App
-keep interface dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex { *; }
-keep class * implements dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex

# Attributes
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,LineNumberTable,SourceFile,*Annotation*
-keep class kotlin.Metadata

# Natives
-keepclasseswithmembernames class * {
    native <methods>;
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
