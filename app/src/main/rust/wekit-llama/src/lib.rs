// src/lib.rs — JNI controller surface; heavy init only after fork (see fork.rs invariants).
pub mod llama;
pub mod parse;
pub mod template;
pub mod truncate;
pub mod wire;
