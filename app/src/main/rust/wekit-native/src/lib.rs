//! JNI entry points

#![allow(non_upper_case_globals)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
include!(concat!(env!("OUT_DIR"), "/bindings.rs"));

mod crash_handler;
mod crash_triggerer;
mod logging;
mod shared;
mod utils;

use std::{
    ffi::{CStr, CString, c_char, c_int},
    ptr,
    sync::OnceLock,
};

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jobject,
    jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Install the native crash handler.
///
/// Java signature: `(Ljava/lang/String;)Z`
#[unsafe(no_mangle)]
pub extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_installNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_log_dir: jstring,
) -> jboolean {
    with_jstring(env, crash_log_dir, |dir| {
        if install_crash_handler(dir) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Uninstall the native crash handler.
///
/// Java signature: `()V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_uninstallNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    uninstall_crash_handler();
}

/// Trigger a deliberate test crash.
///
/// Java signature: `(I)V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_triggerTestCrashNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_type: jint,
) {
    trigger_test_crash(crash_type);
}

/// Convert a Markdown string to HTML.
///
/// Java signature: `(Ljava/lang/String;)Ljava/lang/String;`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_moe_ouom_wekit_hooks_items_chat_MarkdownRendering_convertMarkdownToHtmlNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    markdown_string: jstring,
) -> jstring {
    let result = with_jstring(env, markdown_string, |md_text| {
        markdown::to_html_with_options(md_text, &markdown::Options::gfm())
    });

    match result {
        Ok(html) => unsafe {
            let fns = *env;
            let c_str = CString::new(html).unwrap_or_default();
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
        Err(_) => std::ptr::null_mut(),
    }
}

static HOOK_FUNC: OnceLock<HookFunType> = OnceLock::new();

fn hook_func() -> HookFunType {
    *HOOK_FUNC.get().expect("native_init not called yet")
}

unsafe extern "C" fn on_library_loaded(name: *const c_char, handle: *mut c_void) {
    unsafe {
        let lib_name = CStr::from_ptr(name).to_string_lossy();

        if lib_name.ends_with("libtarget.so") {
            let sym = b"target_fun\0";
            let target = libc::dlsym(handle, sym.as_ptr() as *const c_char);
            if !target.is_null() {
                if let Some(hook) = hook_func() {
                    let mut backup: *mut c_void = ptr::null_mut();
                    hook(target, fake_target as *mut c_void, &mut backup);
                    BACKUP_TARGET = std::mem::transmute(backup);
                }
            }
        }
    }
}

// --- target_fun hook ---

static mut BACKUP_TARGET: Option<unsafe extern "C" fn() -> c_int> = None;

unsafe extern "C" fn fake_target() -> c_int {
    (unsafe { BACKUP_TARGET.unwrap()() }) + 1
}

static mut ORIG_FOPEN: Option<unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_void> =
    None;

unsafe extern "C" fn fake_fopen(filename: *const c_char, mode: *const c_char) -> *mut c_void {
    let name = unsafe { CStr::from_ptr(filename).to_bytes() };
    if name.windows(6).any(|w| w == b"banned") {
        return ptr::null_mut();
    }
    unsafe { ORIG_FOPEN.unwrap()(filename, mode) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn native_init(entries: *const NativeAPIEntries) -> NativeOnModuleLoaded {
    let hook = (unsafe { *entries }).hook_func;
    HOOK_FUNC.set(hook).ok();

    if let Some(hook_fn) = hook {
        let fopen_ptr = libc::fopen as *mut c_void;
        let mut backup: *mut c_void = ptr::null_mut();
        unsafe {
            hook_fn(fopen_ptr, fake_fopen as *mut c_void, &mut backup);
            ORIG_FOPEN = Some(std::mem::transmute(backup));
        }
    }

    Some(on_library_loaded)
}

/// Required JNI library entry point — returns the JNI version we target.
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
