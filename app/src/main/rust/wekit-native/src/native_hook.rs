#![allow(non_upper_case_globals)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
#![allow(unused)]

use crate::logi;
include!(concat!(env!("OUT_DIR"), "/bindings.rs"));

// use std::{
//     ffi::{CStr, c_char, c_int, c_void},
//     ptr,
//     sync::OnceLock,
// };

// static HOOK_FUNC: OnceLock<HookFunType> = OnceLock::new();

// fn hook_func() -> HookFunType {
//     *HOOK_FUNC.get().expect("native_init not called yet")
// }

// unsafe extern "C" fn on_library_loaded(name: *const c_char, handle: *mut c_void) {
//     unsafe {
//         let lib_name = CStr::from_ptr(name).to_string_lossy();

//         if lib_name.ends_with("libtarget.so") {
//             let sym = b"target_fun\0";
//             let target = libc::dlsym(handle, sym.as_ptr() as *const c_char);
//             if !target.is_null() {
//                 if let Some(hook) = hook_func() {
//                     let mut backup: *mut c_void = ptr::null_mut();
//                     hook(target, fake_target as *mut c_void, &mut backup);
//                     BACKUP_TARGET = std::mem::transmute(backup);
//                 }
//             }
//         }
//     }
// }

// --- target_fun hook ---

// static mut BACKUP_TARGET: Option<unsafe extern "C" fn() -> c_int> = None;

// unsafe extern "C" fn fake_target() -> c_int {
//     (unsafe { BACKUP_TARGET.unwrap()() }) + 1
// }

// static mut ORIG_FOPEN: Option<unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_void> =
//     None;

// unsafe extern "C" fn fake_fopen(filename: *const c_char, mode: *const c_char) -> *mut c_void {
//     let name = unsafe { CStr::from_ptr(filename).to_bytes() };
//     if name.windows(6).any(|w| w == b"banned") {
//         return ptr::null_mut();
//     }
//     unsafe { ORIG_FOPEN.unwrap()(filename, mode) }
// }

#[unsafe(no_mangle)]
pub unsafe extern "C" fn native_init(entries: *const NativeAPIEntries) -> NativeOnModuleLoaded {
    // let hook = (unsafe { *entries }).hook_func;
    // HOOK_FUNC.set(hook).ok();

    // if let Some(hook_fn) = hook {
    //     let fopen_ptr = libc::fopen as *mut c_void;
    //     let mut backup: *mut c_void = ptr::null_mut();
    //     unsafe {
    //         hook_fn(fopen_ptr, fake_fopen as *mut c_void, &mut backup);
    //         ORIG_FOPEN = Some(std::mem::transmute(backup));
    //     }
    // }

    // Some(on_library_loaded)

    logi!("native hook initialized");
    None
}
