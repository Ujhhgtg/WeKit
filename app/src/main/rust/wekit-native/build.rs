use std::{env, path::PathBuf};

fn main() {
    println!("cargo:rustc-link-lib=log");
    println!("cargo:rustc-link-lib=dl");
    println!("cargo:rustc-link-lib=unwind");

    let bindings = bindgen::Builder::default()
        .header("include/native_hook.h")
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .generate()
        .expect("Failed to generate bindings");

    let out_path = PathBuf::from(env::var("OUT_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Failed to write bindings");
}
