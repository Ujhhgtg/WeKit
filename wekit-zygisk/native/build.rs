use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=WEKIT_ANDROID_NDK");
    println!("cargo:rerun-if-env-changed=WEKIT_ANDROID_API");
    println!("cargo:rerun-if-env-changed=CMAKE");
    let android = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android");
    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let root = manifest.parent().unwrap().parent().unwrap();
    for source in [
        manifest.join("CMakeLists.txt"),
        manifest.join("cpp"),
        root.join("third_party/xz-embedded/linux"),
        root.join("third_party/xz-embedded/userspace"),
    ] {
        println!("cargo:rerun-if-changed={}", source.display());
    }

    let mut config = cmake::Config::new(&manifest);
    config.generator("Ninja").profile("RelWithDebInfo");
    if android {
        assert_eq!(
            env::var("TARGET").unwrap(),
            "aarch64-linux-android",
            "the Zygisk loader currently supports arm64-v8a only"
        );
        let ndk = PathBuf::from(
            env::var_os("WEKIT_ANDROID_NDK")
                .expect("run ./x configure to select the pinned Android NDK"),
        );
        let api =
            env::var("WEKIT_ANDROID_API").expect("run ./x configure to select the Android API");
        config
            .define(
                "CMAKE_TOOLCHAIN_FILE",
                ndk.join("build/cmake/android.toolchain.cmake"),
            )
            .define("ANDROID_ABI", "arm64-v8a")
            .define("ANDROID_PLATFORM", format!("android-{api}"))
            .define("ANDROID_STL", "c++_static");
        let lsplant = root.join("third_party/lsplant");
        println!(
            "cargo:rerun-if-changed={}",
            lsplant.join("lsplant/src/main/jni").display()
        );
    }
    let install = config.build();

    println!(
        "cargo:rustc-link-search=native={}",
        install.join("lib").display()
    );
    println!("cargo:rustc-link-lib=static=wekit_xz");
    if !android {
        return;
    }
    for library in [
        "wekit_lsplant_bridge",
        "lsplant_static",
        "dex_builder_static",
        "wekit_compiler_rt",
    ] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    // The loader is extracted on its own by the module installer. Do not add
    // libc++_shared.so (or another application-private shared library) dependencies.
    // CMake copies only these two archives into our native search directory.
    // Adding the NDK's generic library directory to -L would shadow the Clang
    // driver's API-specific libc.so with libc.a (and duplicate Rust symbols).
    // The Android Clang driver adds the matching libunwind.a automatically.
    for library in ["c++_static", "c++abi"] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    for library in ["log", "z", "dl", "m"] {
        println!("cargo:rustc-link-lib={library}");
    }
    println!("cargo:rustc-link-arg=-Wl,--exclude-libs,ALL");
    println!("cargo:rustc-link-arg=-Wl,--no-undefined");
}
