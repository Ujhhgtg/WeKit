# Chaquopy runtime patch

The runtime uses Chaquopy 17.0.0 source revision
`e01057c72fdd737f202bd1be1de85af51e06cad0`. The complete WeKit patch is
[`patches/chaquopy/runtime-classloader.patch`](../../../patches/chaquopy/runtime-classloader.patch).

Chaquopy normally caches `Python.class.getClassLoader()` in
`product/runtime/src/main/python/java/jvm.pxi`. WeKit adds
`java.chaquopy.set_java_class_loader`, clears the native `FindClass` cache when
the loader changes, and makes `dynamic_proxy` define proxies through the same
loader. `ChaquopyRuntimeBackend` calls the operation with
`ClassLoaders.HYBRID` immediately after `Python.start` and before loading the
WeKit SDK or any plugin.

`xtask extensions pack` builds `java/chaquopy.so` from that pinned source and
patch with Cython 3.0.11, the Android NDK
version from WeKit's version catalog, API 28, `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`,
and Chaquopy's `target-3.13.9-0-arm64-v8a.zip`. The build output replaces only
`assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy.so`; all other
Chaquopy runtime artifacts remain the upstream 17.0.0 outputs.
