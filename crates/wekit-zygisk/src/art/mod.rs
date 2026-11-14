// ART integration: Rust owns bootstrap and symbol lookup; LSPlant owns method hooks.
// Initialize in postAppSpecialize, before entering any module Java code, so ART's
// hidden-API checks see the trusted Zygote caller rather than a module class.

#[cfg(any(target_os = "android", test))]
mod elf;
#[cfg(any(target_os = "android", test))]
#[cfg_attr(test, allow(dead_code))]
mod inline_hook;

use crate::{loge, logi};
use jni::sys::{JNI_FALSE, JNIEnv as RawJNIEnv, jclass, jobject};
use std::sync::OnceLock;

#[cfg(target_os = "android")]
mod ffi {
    use jni::sys::{JNIEnv as RawJNIEnv, jobject};
    use std::ffi::{c_char, c_void};

    pub type SymbolResolver = unsafe extern "C" fn(*const c_char, usize, bool) -> *mut c_void;

    unsafe extern "C" {
        pub fn wekit_lsplant_init(
            env: *mut RawJNIEnv,
            resolver: SymbolResolver,
            hook: unsafe extern "C" fn(*mut c_void, *mut c_void) -> *mut c_void,
            unhook: unsafe extern "C" fn(*mut c_void) -> bool,
        ) -> bool;
        pub fn wekit_lsplant_hook(
            env: *mut RawJNIEnv,
            target: jobject,
            hooker: jobject,
            callback: jobject,
        ) -> jobject;
        pub fn wekit_lsplant_deoptimize(env: *mut RawJNIEnv, target: jobject) -> bool;
        pub fn wekit_lsplant_make_dex_file_trusted(env: *mut RawJNIEnv, cookie: jobject) -> bool;
    }
}

static INITIALIZED: OnceLock<bool> = OnceLock::new();
#[cfg(target_os = "android")]
static ART_SYMBOLS: OnceLock<elf::ArtSymbolResolver> = OnceLock::new();

#[cfg(target_os = "android")]
unsafe extern "C" fn resolve_symbol(
    name: *const std::ffi::c_char,
    length: usize,
    prefix: bool,
) -> *mut std::ffi::c_void {
    if name.is_null() || length == 0 {
        return std::ptr::null_mut();
    }
    // LSPlant passes a string_view: its data is not necessarily NUL-terminated.
    let bytes = unsafe { std::slice::from_raw_parts(name.cast::<u8>(), length) };
    let Ok(name) = std::str::from_utf8(bytes) else {
        return std::ptr::null_mut();
    };
    ART_SYMBOLS.get().map_or(std::ptr::null_mut(), |symbols| {
        symbols.resolve(name, prefix) as *mut std::ffi::c_void
    })
}

/// Must run from the native Zygisk bootstrap, before calling ZygiskEntry.init.
/// Cache failure too: LSPlant may have partially installed runtime hooks and its
/// initialization contract does not permit retrying a failed initialization.
pub fn init(env: *mut RawJNIEnv) -> bool {
    if env.is_null() {
        return false;
    }
    *INITIALIZED.get_or_init(|| {
        #[cfg(target_os = "android")]
        {
            let Some(symbols) = elf::ArtSymbolResolver::load() else {
                loge!("Zygisk: failed to load ART symbols for LSPlant");
                return false;
            };
            let _ = ART_SYMBOLS.set(symbols);
            let ok = unsafe {
                ffi::wekit_lsplant_init(env, resolve_symbol, inline_hook::hook, inline_hook::unhook)
            };
            if ok {
                logi!("Zygisk: LSPlant initialized");
            } else {
                loge!("Zygisk: LSPlant initialization failed");
            }
            ok
        }
        #[cfg(not(target_os = "android"))]
        {
            // The host test suite exercises APK/protocol/ELF logic, not ART.
            false
        }
    })
}

pub fn is_initialized() -> bool {
    INITIALIZED.get().copied().unwrap_or(false)
}

/// Returns a JNI local reference to LSPlant's reflected backup Method.
/// Kotlin owns publication and callback registration; never get its jmethodID.
pub fn hook_method(
    env: *mut RawJNIEnv,
    target: jobject,
    hooker: jobject,
    callback: jobject,
) -> jobject {
    if !is_initialized()
        || env.is_null()
        || target.is_null()
        || hooker.is_null()
        || callback.is_null()
    {
        return std::ptr::null_mut();
    }
    #[cfg(target_os = "android")]
    unsafe {
        ffi::wekit_lsplant_hook(env, target, hooker, callback)
    }
    #[cfg(not(target_os = "android"))]
    {
        std::ptr::null_mut()
    }
}

pub fn deoptimize(env: *mut RawJNIEnv, target: jobject) -> bool {
    if !is_initialized() || env.is_null() || target.is_null() {
        return false;
    }
    #[cfg(target_os = "android")]
    unsafe {
        ffi::wekit_lsplant_deoptimize(env, target)
    }
    #[cfg(not(target_os = "android"))]
    {
        false
    }
}

fn trust_dex_file(env: *mut RawJNIEnv, dex_file: jobject) -> bool {
    unsafe {
        let fns = *env;
        let dex_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexFile".as_ptr());
        if dex_cls.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            return false;
        }
        let cookie_fid = ((*fns).v1_6.GetFieldID)(
            env,
            dex_cls,
            c"mCookie".as_ptr(),
            c"Ljava/lang/Object;".as_ptr(),
        );
        ((*fns).v1_6.DeleteLocalRef)(env, dex_cls);
        if cookie_fid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            return false;
        }
        let cookie = ((*fns).v1_6.GetObjectField)(env, dex_file, cookie_fid);
        if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE || cookie.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            if !cookie.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, cookie);
            }
            return false;
        }
        #[cfg(target_os = "android")]
        let ok = ffi::wekit_lsplant_make_dex_file_trusted(env, cookie);
        #[cfg(not(target_os = "android"))]
        let ok = false;
        ((*fns).v1_6.DeleteLocalRef)(env, cookie);
        if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
            ((*fns).v1_6.ExceptionClear)(env);
            loge!("Zygisk: LSPlant MakeDexFileTrusted raised an exception");
            return false;
        }
        ok
    }
}

/// Trust the module's in-memory DEX files before any of its Java code runs.
pub fn trust_class_loader(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    if !is_initialized() || env.is_null() || class_loader.is_null() {
        return false;
    }
    unsafe {
        let fns = *env;
        let base_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/BaseDexClassLoader".as_ptr());
        let plist_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexPathList".as_ptr());
        let elem_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexPathList$Element".as_ptr());
        if base_cls.is_null() || plist_cls.is_null() || elem_cls.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            for p in [base_cls, plist_cls, elem_cls]
                .iter()
                .filter(|&&p| !p.is_null())
            {
                ((*fns).v1_6.DeleteLocalRef)(env, *p);
            }
            return false;
        }
        let plist_fid = ((*fns).v1_6.GetFieldID)(
            env,
            base_cls,
            c"pathList".as_ptr(),
            c"Ldalvik/system/DexPathList;".as_ptr(),
        );
        let elems_fid = ((*fns).v1_6.GetFieldID)(
            env,
            plist_cls,
            c"dexElements".as_ptr(),
            c"[Ldalvik/system/DexPathList$Element;".as_ptr(),
        );
        let dexfile_fid = ((*fns).v1_6.GetFieldID)(
            env,
            elem_cls,
            c"dexFile".as_ptr(),
            c"Ldalvik/system/DexFile;".as_ptr(),
        );
        if plist_fid.is_null() || elems_fid.is_null() || dexfile_fid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            ((*fns).v1_6.DeleteLocalRef)(env, base_cls);
            ((*fns).v1_6.DeleteLocalRef)(env, plist_cls);
            ((*fns).v1_6.DeleteLocalRef)(env, elem_cls);
            loge!("Zygisk: could not resolve BaseDexClassLoader DexFile fields");
            return false;
        }
        let path_list = ((*fns).v1_6.GetObjectField)(env, class_loader, plist_fid);
        let elements = if !path_list.is_null() {
            ((*fns).v1_6.GetObjectField)(env, path_list, elems_fid) as jni::sys::jobjectArray
        } else {
            std::ptr::null_mut()
        };
        if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE || elements.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            if !path_list.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, path_list);
            }
            if !elements.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, elements as jobject);
            }
            for p in [base_cls, plist_cls, elem_cls] {
                ((*fns).v1_6.DeleteLocalRef)(env, p);
            }
            return false;
        }
        let count = ((*fns).v1_6.GetArrayLength)(env, elements);
        let mut success = true;
        let mut trusted_count = 0i32;
        for i in 0..count {
            let elem = ((*fns).v1_6.GetObjectArrayElement)(env, elements, i);
            let dex_file = if !elem.is_null() {
                ((*fns).v1_6.GetObjectField)(env, elem, dexfile_fid)
            } else {
                std::ptr::null_mut()
            };
            if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
                ((*fns).v1_6.ExceptionClear)(env);
                success = false;
            } else if !dex_file.is_null() {
                if trust_dex_file(env, dex_file) {
                    trusted_count += 1;
                } else {
                    success = false;
                }
            }
            if !dex_file.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, dex_file);
            }
            if !elem.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, elem);
            }
        }
        ((*fns).v1_6.DeleteLocalRef)(env, elements as jobject);
        if !path_list.is_null() {
            ((*fns).v1_6.DeleteLocalRef)(env, path_list);
        }
        for p in [base_cls, plist_cls, elem_cls] {
            ((*fns).v1_6.DeleteLocalRef)(env, p);
        }
        if !success || trusted_count == 0 {
            loge!(
                "Zygisk: failed to trust every DexFile in class loader (trusted={})",
                trusted_count
            );
            return false;
        }
        logi!(
            "Zygisk: trusted {} DexFile(s) for class loader {:p}",
            trusted_count,
            class_loader
        );
        true
    }
}

pub fn allocate_instance(env: *mut RawJNIEnv, cls: jclass) -> jobject {
    if cls.is_null() {
        return std::ptr::null_mut();
    }
    unsafe { ((*(*env)).v1_6.AllocObject)(env, cls) }
}
