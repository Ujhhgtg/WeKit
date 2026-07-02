use std::ffi::CString;

use libc::{c_char, c_int};

// ─────────────────────────────────────────────────────────────────────────────
// Android logging
// ─────────────────────────────────────────────────────────────────────────────

unsafe extern "C" {
    /// Non-variadic Android log function — safe to call from a signal handler.
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_WARN: c_int = 5;
pub const ANDROID_LOG_ERROR: c_int = 6;

static LOG_TAG: &std::ffi::CStr = c"WeKit";

pub fn android_log(prio: c_int, msg: &str) {
    // Prepend "NativeLib: " to the message
    let full_msg = format!("NativeLib: {}", msg);
    // CString handles null termination and interior-null sanitisation
    let Ok(cmsg) = CString::new(full_msg) else {
        return;
    };
    unsafe {
        __android_log_write(prio, LOG_TAG.as_ptr(), cmsg.as_ptr());
    }
}

#[macro_export]
macro_rules! logi {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_INFO, &format!($($t)*)) };
}

#[macro_export]
macro_rules! logw {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_WARN, &format!($($t)*)) };
}

#[macro_export]
macro_rules! loge {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_ERROR, &format!($($t)*)) };
}
