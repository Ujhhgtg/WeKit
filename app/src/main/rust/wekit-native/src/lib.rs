//! JNI entry points
//!
//! Java package: `moe.ouom.wekit.utils.crash.NativeCrashHandler`

#![allow(non_snake_case)]

mod crash_handler;
use crash_handler::{install_crash_handler, trigger_test_crash, uninstall_crash_handler};

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jobject,
    jstring,
};
use libc::c_void;
use std::ffi::CStr;

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
unsafe fn with_jstring<F, R>(env: *mut RawJNIEnv, s: jstring, f: F) -> Option<R>
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

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Install the native crash handler.
///
/// Java signature: `(Ljava/lang/String;)Z`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_installNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_log_dir: jstring,
) -> jboolean {
    unsafe {
        with_jstring(env, crash_log_dir, |dir| {
            if install_crash_handler(dir) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        })
        .unwrap_or(JNI_FALSE)
    }
}

/// Uninstall the native crash handler.
///
/// Java signature: `()V`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_uninstallNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    uninstall_crash_handler();
}

/// Trigger a deliberate test crash.
///
/// Java signature: `(I)V`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_triggerTestCrashNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_type: jint,
) {
    trigger_test_crash(crash_type);
}

/// Required JNI library entry point — returns the JNI version we target.
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
