// natives.rs — JNI native method registration
//
// Registers the two sets of native methods (`ArtHookBridge` and `ZygiskEntry`)
// via `RegisterNatives`.  Both classes are loaded through the
// `InMemoryDexClassLoader` built in `postAppSpecialize`, so standard
// `FindClass` is not used here.
//
// Note: this module is named `natives` rather than `jni` to avoid shadowing
// the external `jni` crate in the module namespace.

use crate::{loge, logi};
use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNIEnv as RawJNIEnv, JNINativeMethod, jboolean, jclass, jint, jobject,
    jstring,
};
use std::ffi::{CString, c_char, c_void};

// ── JNI helper: load class via ClassLoader.loadClass ─────────────────────────

pub(crate) unsafe fn load_class_from_loader(
    env: *mut RawJNIEnv,
    loader: jobject,
    dot_name: &str,
) -> jclass {
    let fns = *env;
    let jname = CString::new(dot_name).unwrap_or_default();
    let jname_obj = ((*fns).v1_6.NewStringUTF)(env, jname.as_ptr());
    if jname_obj.is_null() {
        return std::ptr::null_mut();
    }
    let loader_cls = ((*fns).v1_6.GetObjectClass)(env, loader);
    let mid = ((*fns).v1_6.GetMethodID)(
        env,
        loader_cls,
        c"loadClass".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
    );
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    ((*fns).v1_6.CallObjectMethod)(env, loader, mid, jname_obj) as jclass
}

// ── ArtHookBridge JNI implementations ────────────────────────────────────────

extern "C" fn jni_hook_method(
    env: *mut RawJNIEnv,
    _class: jclass,
    target: jobject,
    hooker: jobject,
    callback: jobject,
) -> jobject {
    crate::art::hook_method(env, target, hooker, callback)
}

extern "C" fn jni_deoptimize(env: *mut RawJNIEnv, _class: jclass, target: jobject) -> jboolean {
    if crate::art::deoptimize(env, target) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

extern "C" fn jni_allocate_instance(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_class: jclass,
) -> jobject {
    crate::art::allocate_instance(env, target_class)
}

extern "C" fn jni_hide_loaded_module_libraries(_env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    let ok = crate::so_hider::hide_path("libdexkit.so") >= 0
        && crate::so_hider::hide_path("libwekit_native.so") >= 0
        && crate::so_hider::hide_path("libmmkv.so") >= 0;
    if ok { JNI_TRUE } else { JNI_FALSE }
}

// ── ZygiskEntry JNI implementations ──────────────────────────────────────────

unsafe fn get_class_loader(env: *mut RawJNIEnv, entry_class: jclass) -> jobject {
    let fns = *env;
    let class_cls = ((*fns).v1_6.FindClass)(env, c"java/lang/Class".as_ptr());
    if class_cls.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let mid = ((*fns).v1_6.GetMethodID)(
        env,
        class_cls,
        c"getClassLoader".as_ptr(),
        c"()Ljava/lang/ClassLoader;".as_ptr(),
    );
    ((*fns).v1_6.DeleteLocalRef)(env, class_cls);
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let loader = ((*fns).v1_6.CallObjectMethod)(env, entry_class as jobject, mid);
    if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    loader
}

extern "C" fn jni_native_initialize(env: *mut RawJNIEnv, entry: jclass) -> jboolean {
    unsafe {
        if !crate::art::is_initialized() {
            loge!("Zygisk: ZygiskEntry.nativeInitialize: LSPlant bootstrap did not complete");
            return JNI_FALSE;
        }
        let loader = get_class_loader(env, entry);
        if loader.is_null() {
            return JNI_FALSE;
        }
        let ok = register_hook_bridge_natives(env, loader);
        if !ok {
            loge!("Zygisk: ZygiskEntry.nativeInitialize: failed to register ArtHookBridge");
        }
        ((*(*env)).v1_6.DeleteLocalRef)(env, loader);
        if ok { JNI_TRUE } else { JNI_FALSE }
    }
}

unsafe fn connect_telegram_socket() -> libc::c_int {
    let name = crate::TELEGRAM_SOCKET_NAME.lock().unwrap().clone();
    if name.is_empty() {
        return -1;
    }
    let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
    if fd < 0 {
        return -1;
    }
    let mut addr: libc::sockaddr_un = std::mem::zeroed();
    addr.sun_family = libc::AF_UNIX as u16;
    let nb = name.as_bytes();
    if nb.len() >= 107 {
        libc::close(fd);
        return -1;
    }
    addr.sun_path[0] = 0;
    for (i, &b) in nb.iter().enumerate() {
        addr.sun_path[1 + i] = b as libc::c_char;
    }
    let slen = (std::mem::size_of::<libc::sa_family_t>() + 1 + nb.len()) as libc::socklen_t;
    if libc::connect(fd, &addr as *const _ as *const libc::sockaddr, slen) != 0 {
        libc::close(fd);
        return -1;
    }
    fd
}

unsafe fn receive_telegram_response(sock: libc::c_int) -> Result<(), String> {
    let status = crate::protocol::read_u8_from_fd(sock)
        .map_err(|_| "Root companion connection was interrupted".to_string())?;
    match status {
        crate::protocol::TELEGRAM_RESPONSE_OK => Ok(()),
        crate::protocol::TELEGRAM_RESPONSE_DISABLED => {
            Err("WeKit Zygisk target is disabled".to_string())
        }
        crate::protocol::TELEGRAM_RESPONSE_ERROR => {
            let msg = crate::protocol::read_string_from_fd(sock)
                .unwrap_or_else(|_| "Invalid response from Telegram root companion".to_string());
            Err(msg)
        }
        _ => Err("Invalid response from Telegram root companion".to_string()),
    }
}

unsafe fn receive_snapshot_file(sock: libc::c_int, dst_fd: libc::c_int) -> Result<u64, ()> {
    let size = crate::protocol::read_u64_from_fd(sock).map_err(|_| ())?;
    if size > 1024 * 1024 * 1024 {
        return Err(());
    }
    if libc::ftruncate(dst_fd, 0) != 0 {
        return Err(());
    }
    if libc::lseek(dst_fd, 0, libc::SEEK_SET) < 0 {
        return Err(());
    }
    let mut remaining = size;
    let mut buf = [0u8; 65536];
    while remaining > 0 {
        let chunk = remaining.min(buf.len() as u64) as usize;
        let mut got = 0usize;
        while got < chunk {
            let n = libc::read(sock, buf[got..].as_mut_ptr().cast(), chunk - got);
            if n <= 0 {
                return Err(());
            }
            got += n as usize;
        }
        let mut written = 0usize;
        while written < chunk {
            let n = libc::write(dst_fd, buf[written..].as_ptr().cast(), chunk - written);
            if n <= 0 {
                return Err(());
            }
            written += n as usize;
        }
        remaining -= chunk as u64;
    }
    Ok(size)
}

fn is_valid_telegram_package(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 255
        && pkg
            .chars()
            .all(|c| c.is_alphanumeric() || c == '.' || c == '_')
}

fn throw_telegram_error(env: *mut RawJNIEnv, msg: &str) {
    unsafe {
        let fns = *env;
        let cls = ((*fns).v1_6.FindClass)(env, c"java/lang/IllegalStateException".as_ptr());
        if cls.is_null() {
            return;
        }
        let msg_c = std::ffi::CString::new(msg).unwrap_or_default();
        ((*fns).v1_6.ThrowNew)(env, cls, msg_c.as_ptr());
        ((*fns).v1_6.DeleteLocalRef)(env, cls);
    }
}

extern "C" fn jni_has_telegram_root_companion(_env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    if crate::TELEGRAM_SOCKET_NAME.lock().unwrap().is_empty() {
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}

extern "C" fn jni_list_telegram_instances(env: *mut RawJNIEnv, _class: jclass) -> jobject {
    unsafe {
        let name = crate::TELEGRAM_SOCKET_NAME.lock().unwrap().clone();
        if name.is_empty() {
            throw_telegram_error(env, "Telegram Root companion is unavailable");
            return std::ptr::null_mut();
        }
        let sock = connect_telegram_socket();
        if sock < 0 {
            throw_telegram_error(env, "Telegram Root companion is unavailable");
            return std::ptr::null_mut();
        }
        let _ = crate::protocol::write_u8_to_fd(sock, crate::protocol::TELEGRAM_REQUEST_DISCOVER);
        if let Err(e) = receive_telegram_response(sock) {
            libc::close(sock);
            throw_telegram_error(env, &e);
            return std::ptr::null_mut();
        }
        let count = match crate::protocol::read_u16_from_fd(sock) {
            Ok(n) if n > 0 && n <= 64 => n,
            _ => {
                libc::close(sock);
                throw_telegram_error(env, "Invalid Telegram instance list from root companion");
                return std::ptr::null_mut();
            }
        };
        let mut packages: Vec<String> = Vec::new();
        for _ in 0..count {
            match crate::protocol::read_string_from_fd(sock) {
                Ok(p) if is_valid_telegram_package(&p) => packages.push(p),
                _ => {
                    libc::close(sock);
                    throw_telegram_error(env, "Invalid Telegram package from root companion");
                    return std::ptr::null_mut();
                }
            }
        }
        libc::close(sock);
        let fns = *env;
        let str_cls = ((*fns).v1_6.FindClass)(env, c"java/lang/String".as_ptr());
        if str_cls.is_null() {
            return std::ptr::null_mut();
        }
        let arr = ((*fns).v1_6.NewObjectArray)(
            env,
            packages.len() as jint,
            str_cls,
            std::ptr::null_mut(),
        );
        ((*fns).v1_6.DeleteLocalRef)(env, str_cls);
        if arr.is_null() {
            return std::ptr::null_mut();
        }
        for (i, pkg) in packages.iter().enumerate() {
            let Ok(c_pkg) = std::ffi::CString::new(pkg.as_str()) else {
                ((*fns).v1_6.DeleteLocalRef)(env, arr as jobject);
                return std::ptr::null_mut();
            };
            let jstr = ((*fns).v1_6.NewStringUTF)(env, c_pkg.as_ptr());
            if jstr.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, arr as jobject);
                return std::ptr::null_mut();
            }
            ((*fns).v1_6.SetObjectArrayElement)(env, arr, i as jint, jstr);
            ((*fns).v1_6.DeleteLocalRef)(env, jstr);
            if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
                ((*fns).v1_6.DeleteLocalRef)(env, arr as jobject);
                return std::ptr::null_mut();
            }
        }
        arr as jobject
    }
}

extern "C" fn jni_copy_telegram_database_snapshot(
    env: *mut RawJNIEnv,
    _class: jclass,
    package_name: jstring,
    database_fd: jint,
    wal_fd: jint,
    shm_fd: jint,
) -> jint {
    unsafe {
        let fns = *env;
        let chars = ((*fns).v1_6.GetStringUTFChars)(env, package_name, std::ptr::null_mut());
        if chars.is_null() {
            return 0;
        }
        let pkg = std::ffi::CStr::from_ptr(chars)
            .to_string_lossy()
            .into_owned();
        ((*fns).v1_6.ReleaseStringUTFChars)(env, package_name, chars);
        if !is_valid_telegram_package(&pkg) || database_fd < 0 || wal_fd < 0 || shm_fd < 0 {
            throw_telegram_error(env, "Invalid Telegram database snapshot request");
            return 0;
        }
        let name = crate::TELEGRAM_SOCKET_NAME.lock().unwrap().clone();
        if name.is_empty() {
            throw_telegram_error(env, "Telegram Root companion is unavailable");
            return 0;
        }
        let sock = connect_telegram_socket();
        if sock < 0 {
            throw_telegram_error(env, "Telegram Root companion is unavailable");
            return 0;
        }
        let _ =
            crate::protocol::write_u8_to_fd(sock, crate::protocol::TELEGRAM_REQUEST_COPY_DATABASE);
        let _ = crate::protocol::write_string_to_fd(sock, &pkg);
        if let Err(e) = receive_telegram_response(sock) {
            libc::close(sock);
            throw_telegram_error(env, &e);
            return 0;
        }
        let db_size = receive_snapshot_file(sock, database_fd).unwrap_or(0);
        let wal_size = receive_snapshot_file(sock, wal_fd).unwrap_or(0);
        let shm_size = receive_snapshot_file(sock, shm_fd).unwrap_or(0);
        libc::close(sock);
        if db_size == 0 {
            throw_telegram_error(env, "Telegram database snapshot transfer failed");
            return 0;
        }
        ((if wal_size > 0 { 1 } else { 0 }) | (if shm_size > 0 { 2 } else { 0 })) as jint
    }
}

// ── RegisterNatives ───────────────────────────────────────────────────────────

/// Register ArtHookBridge native methods.
/// Class must be loaded via class_loader (InMemoryDexClassLoader).
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer. `class_loader` must be a valid
/// reference to the InMemoryDexClassLoader holding ArtHookBridge.
pub unsafe fn register_hook_bridge_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_from_loader(
        env,
        class_loader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge",
    );
    if class.is_null() {
        loge!("Zygisk: failed to load ArtHookBridge class");
        return false;
    }

    let methods = [
        JNINativeMethod {
            name: c"nativeHookMethod".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;".as_ptr() as *mut c_char,
            fnPtr: jni_hook_method as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeDeoptimize".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/reflect/Executable;)Z".as_ptr() as *mut c_char,
            fnPtr: jni_deoptimize as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeAllocateInstance".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/Class;)Ljava/lang/Object;".as_ptr() as *mut c_char,
            fnPtr: jni_allocate_instance as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHideLoadedModuleLibraries".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_hide_loaded_module_libraries as *mut c_void,
        },
    ];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_ptr(), methods.len() as jint);
    ((*fns).v1_6.DeleteLocalRef)(env, class);
    if ret == 0 {
        logi!("Zygisk: ArtHookBridge natives registered");
        true
    } else {
        loge!("Zygisk: RegisterNatives(ArtHookBridge) failed: {ret}");
        false
    }
}

/// Register ZygiskEntry native methods.
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer. `class_loader` must be a valid
/// reference to the InMemoryDexClassLoader holding ZygiskEntry.
pub unsafe fn register_entry_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_from_loader(
        env,
        class_loader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry",
    );
    if class.is_null() {
        loge!("Zygisk: failed to load ZygiskEntry class");
        return false;
    }

    let mut methods: [JNINativeMethod; 4] = [
        JNINativeMethod {
            name: c"nativeInitialize".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_native_initialize as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHasTelegramRootCompanion".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_has_telegram_root_companion as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeListTelegramInstances".as_ptr() as *mut c_char,
            signature: c"()[Ljava/lang/String;".as_ptr() as *mut c_char,
            fnPtr: jni_list_telegram_instances as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeCopyTelegramDatabaseSnapshot".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/String;III)I".as_ptr() as *mut c_char,
            fnPtr: jni_copy_telegram_database_snapshot as *mut c_void,
        },
    ];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_mut_ptr(), 4);
    if ret == 0 {
        logi!("Zygisk: ZygiskEntry natives registered");
        true
    } else {
        loge!("Zygisk: RegisterNatives(ZygiskEntry) failed: {ret}");
        false
    }
}
