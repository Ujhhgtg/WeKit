# WeKit Python runtime container

This is an independently built, APK-shaped extension container. It is not an
installable application and is mounted by a later WeKit pack loader. The build
uses AGP 9.2.x, Chaquopy 17.0.0, Python 3.13 and arm64-v8a only.

The API AAR is compile-only and must be supplied by the later pack build from a
controlled local Maven repository. No `:app` dependency is permitted. Chaquopy
generates `assets/chaquopy/build.json`; the runtime pack publisher owns its
artifact validation. The release output is intentionally unsigned so the
container cannot be installed as an application. Task 1 intentionally leaves
native loading and the backend unavailable, with diagnostics exposed by the
loader-neutral `RuntimeEntrypoint` Kotlin object.
