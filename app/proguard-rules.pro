# R8: missing classes from compile-time-only dependencies (kotlinpoet references javax.lang.model.*)
-dontwarn javax.lang.model.**
-dontwarn com.squareup.kotlinpoet.**

# Disable R8 optimization (keep only shrinking/obfuscation) to avoid breaking reflection
-dontoptimize

# Xposed entry points (loaded by framework via reflection)
-keep class dev.ujhhgtg.wekit.loader.** { *; }

# @Feature objects (referenced by KSP-generated FeaturesProvider, inheriting BaseFeature)
-keep class dev.ujhhgtg.wekit.features.core.FeaturesProvider { *; }
-keep class dev.ujhhgtg.wekit.features.core.BaseFeature { *; }
-keep class * extends dev.ujhhgtg.wekit.features.core.BaseFeature { *; }

# DexKit IResolveDex + cache + descriptors (loaded via filterIsInstance)
-keep class dev.ujhhgtg.wekit.dexkit.** { *; }

# WeAgent model/provider/enums/data/settings/service (all runtime agent classes)
-keep class dev.ujhhgtg.wekit.agent.** { *; }

# UI utilities (showComposeDialog, VectorPathDrawable, icons, etc.)
-keep class dev.ujhhgtg.wekit.ui.** { *; }

# WeChat API classes (context menu, message, database, etc.)
-keep class dev.ujhhgtg.wekit.features.api.** { *; }

# All utils (WePrefs, WeLogger, etc.)
-keep class dev.ujhhgtg.wekit.utils.** { *; }

# Activity, application, constants, preferences
-keep class dev.ujhhgtg.wekit.activity.** { *; }
-keep class dev.ujhhgtg.wekit.application.** { *; }
-keep class dev.ujhhgtg.wekit.constants.** { *; }
-keep class dev.ujhhgtg.wekit.preferences.** { *; }

# BeanShell serialization
-keep class bsh.** { *; }