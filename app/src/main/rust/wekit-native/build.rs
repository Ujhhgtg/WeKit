fn main() {
    // Android log library — needed for __android_log_write
    println!("cargo:rustc-link-lib=log");
    // libdl — needed for dladdr() used in backtrace symbolisation
    println!("cargo:rustc-link-lib=dl");
    // libunwind is part of the NDK toolchain and linked automatically,
    // but add it explicitly in case the linker needs a nudge.
    println!("cargo:rustc-link-lib=unwind");
}
