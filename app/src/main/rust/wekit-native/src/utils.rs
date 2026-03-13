use std::ffi::CStr;

use jni::sys::{JNIEnv as RawJNIEnv, jstring};

// ─────────────────────────────────────────────────────────────────────────────
// Helper — extract a &str from a jstring without the high-level jni wrapper
// so we can keep the function signatures identical to the C++ originals.
// ─────────────────────────────────────────────────────────────────────────────

/// Calls `GetStringUTFChars` / `ReleaseStringUTFChars` via the raw JNI
/// function table, invoking `f` with the resulting `&str`.
///
/// Returns `None` if `env` or `s` is null, or the JNI call fails.
///
/// # Safety
/// `env` must be a valid `JNIEnv*` pointer for the current thread and `s`
/// must be a valid `jstring` (or null).
pub unsafe fn with_jstring<F, R>(env: *mut RawJNIEnv, s: jstring, f: F) -> Option<R>
where
    F: FnOnce(&str) -> R,
{
    unsafe {
        if env.is_null() || s.is_null() {
            return None;
        }
        // Dereference the JNIEnv pointer to reach the function table.
        let fns = *env; // *const JNINativeInterface_
        let chars = ((*fns).v1_6.GetStringUTFChars)(env, s, std::ptr::null_mut());
        if chars.is_null() {
            return None;
        }
        let result = f(CStr::from_ptr(chars).to_str().unwrap_or(""));
        ((*fns).v1_6.ReleaseStringUTFChars)(env, s, chars);
        Some(result)
    }
}
