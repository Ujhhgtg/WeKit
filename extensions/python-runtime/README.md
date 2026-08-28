# WeKit Python runtime container

This is an independently built, APK-shaped extension container. It is not an
installable application and is mounted by WeKit's `PythonRuntimeLoader`. The build
reads its AGP, Gradle, JDK, SDK, Chaquopy, Python, NDK and ABI versions from
WeKit's root version catalog. The first supported runtime is Python 3.13 on
arm64-v8a.

The API AAR is compile-only and is supplied by `xtask extensions pack` from a
controlled local Maven repository. No `:app` dependency is permitted. Chaquopy
generates `assets/chaquopy/build.json`; the runtime pack publisher owns its
artifact validation. The release output is intentionally unsigned so the
container cannot be installed as an application. The loader-neutral
`RuntimeEntrypoint` Kotlin object loads the manifest-ordered native libraries
before resolving the Chaquopy backend.

DexKit's Python bindings are generated from the pinned DexKit AAR by
`runtime:generateDexKitPythonBindings`. The runtime package and developer SDK
therefore expose the same complete set of matcher classes, matcher collections,
enums, snake-case methods, canonical aliases and typed overloads without a
manually maintained API list.

Runtime Python dependencies, including HTTPX2 and its HTTP/2, Brotli, Zstandard
and SOCKS extras, are pinned in `runtime/requirements.txt`. Native packages use
the CPython 3.13 arm64 Android wheels published by Chaquopy. The
xtask build resolves a matching Python 3.13 build interpreter through `uv` and
passes its absolute executable path to the standalone Gradle project. Because
Chaquopy's published libxml2, libxslt and freetype dependency wheels predate
16 KB pages, and its repository doesn't provide the HTTPX2-compatible
backports.zstd and Brotli versions, xtask builds those recipes with the catalog
NDK and caches the wheels under `target/`.
