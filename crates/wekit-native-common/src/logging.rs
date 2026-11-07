use libc::c_int;

#[cfg(target_os = "android")]
use std::ffi::{CString, c_char};

pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_WARN: c_int = 5;
pub const ANDROID_LOG_ERROR: c_int = 6;

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, message: *const c_char) -> c_int;
}

pub struct Logger {
    android_prefix: &'static str,
    host_prefix: &'static str,
}

impl Logger {
    pub const fn new(android_prefix: &'static str, host_prefix: &'static str) -> Self {
        Self {
            android_prefix,
            host_prefix,
        }
    }

    pub fn write(&self, priority: c_int, message: &str) {
        if cfg!(target_os = "android") {
            self.write_message(priority, self.android_prefix, message);
        } else {
            self.write_message(priority, self.host_prefix, message);
        }
    }

    #[cfg(target_os = "android")]
    fn write_message(&self, priority: c_int, prefix: &str, message: &str) {
        let message = if prefix.is_empty() {
            CString::new(message)
        } else {
            CString::new(format!("{prefix}{message}"))
        };
        let Ok(message) = message else {
            return;
        };
        unsafe {
            __android_log_write(priority, c"WeKit".as_ptr(), message.as_ptr());
        }
    }

    #[cfg(not(target_os = "android"))]
    fn write_message(&self, _priority: c_int, prefix: &str, message: &str) {
        eprintln!("{prefix}{message}");
    }
}

pub const LOGGER: Logger = Logger::new("", "[WeKit] ");

#[macro_export]
macro_rules! logi {
    ($logger:expr; $($arguments:tt)*) => {
        ($logger).write($crate::logging::ANDROID_LOG_INFO, &::std::format!($($arguments)*))
    };
}

#[macro_export]
macro_rules! logw {
    ($logger:expr; $($arguments:tt)*) => {
        ($logger).write($crate::logging::ANDROID_LOG_WARN, &::std::format!($($arguments)*))
    };
}

#[macro_export]
macro_rules! loge {
    ($logger:expr; $($arguments:tt)*) => {
        ($logger).write($crate::logging::ANDROID_LOG_ERROR, &::std::format!($($arguments)*))
    };
}
