// src/lib.rs — JNI controller surface; heavy init only after fork (see fork.rs invariants).
pub mod parse;
pub mod placeholder {
    pub const CRATE: &str = "wekit-llama";
}
