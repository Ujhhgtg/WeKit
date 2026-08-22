//! Link directives the sys crate intentionally leaves to this top-level crate.
//!
//! With the `opencl` feature enabled, `libwekit_llama.so` must record a
//! `DT_NEEDED` entry for `libOpenCL.so` so the dynamic linker resolves the
//! OpenCL symbols against the vendor runtime on the device. No Android NDK
//! ships an OpenCL import library, so for android targets we compile a
//! definition-free stub `libOpenCL.so` into `OUT_DIR` purely to satisfy the
//! final link. The stub exports no symbol the cdylib references, so a plain
//! `-lOpenCL` is dropped again by the linker's default `--as-needed` — instead
//! the stub (with `libOpenCL.so` as its SONAME) is linked by path under
//! `--no-as-needed`, recording the SONAME as the `DT_NEEDED` entry. The
//! device's vendor `libOpenCL.so` provides the real symbols at load time.
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    // GGML_OPENCL link directives are emitted by this top-level crate (the sys
    // side deliberately leaves them blank): at runtime the vendor libOpenCL.so
    // provides the DT_NEEDED resolution.
    if std::env::var("CARGO_FEATURE_OPENCL").is_ok() {
        let target = std::env::var("TARGET").unwrap();
        if target.contains("android") {
            let out = std::env::var("OUT_DIR").unwrap();
            std::fs::write(format!("{out}/opencl_stub.c"), "int wekit_opencl_stub = 0;\n").unwrap();
            let cc = std::env::var(format!("CC_{}", target.replace('-', "_"))).unwrap();
            let status = std::process::Command::new(&cc)
                .args(["-shared", "-fPIC", "-Wl,-soname,libOpenCL.so", "-o", &format!("{out}/libOpenCL.so"), &format!("{out}/opencl_stub.c")])
                .status().expect("failed to spawn NDK clang for OpenCL stub");
            assert!(status.success(), "OpenCL stub build failed");
            println!("cargo:rustc-link-arg=-Wl,--no-as-needed,{out}/libOpenCL.so");
        } else {
            println!("cargo:rustc-link-lib=dylib=OpenCL");
        }
    }
}
