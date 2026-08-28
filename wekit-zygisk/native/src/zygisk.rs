// ─────────────────────────────────────────────────────────────────────────────
// Zygisk API v4 ABI types
//
// `#[repr(C)]` re-declarations of the structs defined in the official Zygisk
// header.  Field order and sizes must be kept in exact sync with that header;
// any deviation will silently corrupt the vtable passed by the Zygisk runtime.
// ─────────────────────────────────────────────────────────────────────────────
use std::ffi::c_void;

use jni::sys::{JNINativeMethod, jboolean, jint, jintArray, jlong, jobjectArray, jstring};
use libc::{c_char, c_int, c_long, dev_t, ino_t};

// Option values for setOption (mirrors zygisk::Option enum)
pub const DLCLOSE_MODULE_LIBRARY: c_int = 1;

/// api_table — laid out identically to zygisk::internal::api_table in zygisk.hpp.
///
/// Field order (10 fields):
///   impl_ptr, register_module, hook_jni_native_methods, plt_hook_register,
///   exempt_fd, plt_hook_commit, connect_companion, set_option,
///   get_module_dir, get_flags
#[repr(C)]
pub struct ApiTable {
    pub impl_ptr: *mut c_void,
    pub register_module: unsafe extern "C" fn(*mut ApiTable, *mut ModuleAbi) -> bool,
    pub hook_jni_native_methods: Option<
        unsafe extern "C" fn(*mut jni::sys::JNIEnv, *const c_char, *mut JNINativeMethod, c_int),
    >,
    pub plt_hook_register:
        Option<unsafe extern "C" fn(dev_t, ino_t, *const c_char, *mut c_void, *mut *mut c_void)>,
    pub exempt_fd: Option<unsafe extern "C" fn(c_int) -> bool>,
    pub plt_hook_commit: Option<unsafe extern "C" fn() -> bool>,
    pub connect_companion: Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub set_option: Option<unsafe extern "C" fn(*mut c_void, c_int)>,
    pub get_module_dir: Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub get_flags: Option<unsafe extern "C" fn(*mut c_void) -> u32>,
}

/// module_abi — laid out identically to zygisk::internal::module_abi.
///
/// `api_version` is C `long`, which is pointer-sized on Android.
#[repr(C)]
pub struct ModuleAbi {
    pub api_version: c_long,
    pub impl_ptr: *mut c_void,
    pub pre_app_specialize: unsafe extern "C" fn(*mut c_void, *mut AppSpecializeArgs),
    pub post_app_specialize: unsafe extern "C" fn(*mut c_void, *const AppSpecializeArgs),
    pub pre_server_specialize: unsafe extern "C" fn(*mut c_void, *mut ServerSpecializeArgs),
    pub post_server_specialize: unsafe extern "C" fn(*mut c_void, *const ServerSpecializeArgs),
}

/// AppSpecializeArgs — C++ references become *mut T; C++ const pointers become *const T.
///
/// Read a reference field:  `unsafe { *(*args).nice_name }`
#[repr(C)]
pub struct AppSpecializeArgs {
    // Required fields (C++ references — always valid pointers)
    pub uid: *mut jint,
    pub gid: *mut jint,
    pub gids: *mut jintArray,
    pub runtime_flags: *mut jint,
    pub rlimits: *mut jobjectArray,
    pub mount_external: *mut jint,
    pub se_info: *mut jstring,
    pub nice_name: *mut jstring,
    pub instruction_set: *mut jstring,
    pub app_data_dir: *mut jstring,
    // Optional fields (C++ const pointers — may be null)
    pub fds_to_ignore: *const jintArray,
    pub is_child_zygote: *const jboolean,
    pub is_top_app: *const jboolean,
    pub pkg_data_info_list: *const jobjectArray,
    pub whitelisted_data_info_list: *const jobjectArray,
    pub mount_data_dirs: *const jboolean,
    pub mount_storage_dirs: *const jboolean,
}

/// ServerSpecializeArgs — C++ references become *mut T.
#[repr(C)]
pub struct ServerSpecializeArgs {
    pub uid: *mut jint,
    pub gid: *mut jint,
    pub gids: *mut jintArray,
    pub runtime_flags: *mut jint,
    pub permitted_capabilities: *mut jlong,
    pub effective_capabilities: *mut jlong,
}

/// Convenience wrappers around ApiTable function pointers.
/// All impl-taking calls pass self.impl_ptr as the first argument,
/// matching C++ Api:: inline methods.
impl ApiTable {
    pub unsafe fn connect_companion(&mut self) -> c_int {
        match self.connect_companion {
            Some(f) => unsafe { f(self.impl_ptr) },
            None => -1,
        }
    }

    pub unsafe fn get_module_dir(&mut self) -> c_int {
        match self.get_module_dir {
            Some(f) => unsafe { f(self.impl_ptr) },
            None => -1,
        }
    }

    pub unsafe fn set_option(&mut self, opt: c_int) {
        if let Some(f) = self.set_option {
            unsafe { f(self.impl_ptr, opt) };
        }
    }

    pub unsafe fn get_flags(&mut self) -> u32 {
        match self.get_flags {
            Some(f) => unsafe { f(self.impl_ptr) },
            None => 0,
        }
    }
}
