# WeKit Python runtime container

This is an independently built, APK-shaped extension container. It is not an
installable application and is mounted by a later WeKit pack loader. The build
uses AGP 9.2.x, Chaquopy 17.0.0, Python 3.13 and arm64-v8a only.

The API AAR is compile-only and must be supplied with
`-PwekitPythonApiRepo=/path/to/controlled/maven` and
`-PwekitPythonApiVersion=1.0.0`. No `:app` dependency is permitted. Chaquopy
generates `assets/chaquopy/build.json`; the runtime pack publisher must verify
that file before accepting an APK. Task 1 intentionally leaves native loading
and the backend unavailable, with diagnostics exposed by `RuntimeEntrypoint`.
