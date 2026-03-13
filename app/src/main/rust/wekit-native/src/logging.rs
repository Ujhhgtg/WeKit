use libc::*;

// ─────────────────────────────────────────────────────────────────────────────
// Android logging
// ─────────────────────────────────────────────────────────────────────────────

unsafe extern "C" {
    /// Non-variadic Android log function — safe to call from a signal handler.
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_ERROR: c_int = 6;

static LOG_TAG: &[u8] = b"NativeCrashHandler\0";

pub fn android_log(prio: c_int, msg: &str) {
    let mut buf = [0u8; 512];
    let len = msg.len().min(buf.len() - 1);
    buf[..len].copy_from_slice(&msg.as_bytes()[..len]);
    // buf[len] is already 0 from initialisation → null-terminated
    unsafe {
        __android_log_write(
            prio,
            LOG_TAG.as_ptr() as *const c_char,
            buf.as_ptr() as *const c_char,
        );
    }
}

#[macro_export]
macro_rules! logi {
    ($($t:tt)*) => { crate::logging::android_log(crate::logging::ANDROID_LOG_INFO,  &format!($($t)*)) };
}

#[macro_export]
macro_rules! loge {
    ($($t:tt)*) => { crate::logging::android_log(crate::logging::ANDROID_LOG_ERROR, &format!($($t)*)) };
}
