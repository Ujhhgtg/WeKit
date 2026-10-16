# Independently built payloads call these symbols without bootstrap's R8 mapping.
-keep class dev.ujhhgtg.wekit.loader.api.** { *; }
-keep class dev.ujhhgtg.wekit.loader.abc.** { *; }
-keep class kotlin.** { *; }
-keep class dev.ujhhgtg.reflekt.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
# Native registration and injection metadata name these classes directly.
-keep class dev.ujhhgtg.wekit.bootstrap.loader.entry.** { *; }
-keep class dev.ujhhgtg.wekit.bootstrap.payload.MemoryApkNative { *; }

# BeanShell snapshots use Java serialization of AST classes and fields.
-keep class bsh.** { *; }
