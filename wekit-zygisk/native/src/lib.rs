#![allow(unsafe_op_in_unsafe_fn)]

mod apk;
mod art;
mod companion;
mod lifecycle;
mod logging;
mod natives;
mod payload;
mod protocol;
mod so_hider;
mod zygisk;

use lifecycle::WeKitModule;
use std::ffi::c_void;
use std::sync::Mutex;

use jni::sys::JNIEnv as RawJNIEnv;
use libc::c_int;
use zygisk::{AppSpecializeArgs, ModuleAbi, ServerSpecializeArgs};

use crate::zygisk::ApiTable;

/// Telegram companion socket name, negotiated in preAppSpecialize.
/// Accessed by JNI functions (nativeHasTelegramRootCompanion, etc.) via this global.
pub static TELEGRAM_SOCKET_NAME: Mutex<String> = Mutex::new(String::new());

extern "C" fn pre_app(m: *mut c_void, args: *mut AppSpecializeArgs) {
    unsafe { lifecycle::do_pre_app_specialize(&mut *(m as *mut WeKitModule), args) }
}

extern "C" fn post_app(m: *mut c_void, args: *const AppSpecializeArgs) {
    unsafe { lifecycle::do_post_app_specialize(&mut *(m as *mut WeKitModule), args) }
}

extern "C" fn pre_server(m: *mut c_void, args: *mut ServerSpecializeArgs) {
    unsafe { lifecycle::do_pre_server_specialize(&mut *(m as *mut WeKitModule), args) }
}

extern "C" fn post_server(_m: *mut c_void, _args: *const ServerSpecializeArgs) {}

/// # Safety
///
/// Called exclusively by the Zygisk framework with a valid `api_table` and `JNIEnv`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_module_entry(table: *mut ApiTable, env: *mut RawJNIEnv) {
    let module = Box::leak(Box::new(WeKitModule::new(table, env)));
    let abi = Box::leak(Box::new(ModuleAbi {
        api_version: 4,
        impl_ptr: module as *mut WeKitModule as *mut c_void,
        pre_app_specialize: pre_app,
        post_app_specialize: post_app,
        pre_server_specialize: pre_server,
        post_server_specialize: post_server,
    }));
    unsafe { ((*table).register_module)(table, abi) };
}

/// # Safety
///
/// Called exclusively by the Zygisk framework with a valid connected Unix domain socket fd.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_companion_entry(sock: c_int) {
    companion::handle(sock);
}
