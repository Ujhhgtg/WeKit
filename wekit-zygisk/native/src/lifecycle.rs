// lifecycle — Zygisk module lifecycle callbacks
//
// Implements the three specialization hooks called by the Zygisk framework:
// `preAppSpecialize` (allow-list + companion IPC + resource acquisition),
// `postAppSpecialize` (APK publication, in-memory DEX bootstrap), and
// `preServerSpecialize` (dlclose — module does not inject into system_server).

use crate::protocol::{
    COMPANION_ENABLED, COMPANION_REQUEST_ENABLED, COMPANION_REQUEST_TELEGRAM_SESSION,
    read_u8_from_fd, write_string_to_fd, write_u8_to_fd,
};
use crate::zygisk::{ApiTable, AppSpecializeArgs, DLCLOSE_MODULE_LIBRARY, ServerSpecializeArgs};
use crate::{loge, logi};
use anyhow::{Result, ensure};
use jni::sys::{JNIEnv as RawJNIEnv, jobject, jstring};
use std::{
    ffi::{CStr, c_char},
    fs::File,
    io::{Cursor, Read},
    os::{
        fd::{FromRawFd, OwnedFd},
        unix::io::AsRawFd,
    },
};

pub struct WeKitModule {
    pub api: *mut ApiTable,
    pub env: *mut RawJNIEnv,
    // filled in preAppSpecialize
    pub apk_bytes: Option<Vec<u8>>,
    pub data_dir: String,
    pub process_name: String,
    // DirectByteBuffers reference these allocations for the process lifetime.
    pub dex_buffers: Vec<Vec<u8>>,
    pub telegram_socket_name: Option<String>,
    pub enabled: bool,
    // filled in postAppSpecialize
    pub module_classloader: Option<jobject>,
}

impl WeKitModule {
    pub fn new(api: *mut ApiTable, env: *mut RawJNIEnv) -> Self {
        Self {
            api,
            env,
            apk_bytes: None,
            data_dir: String::new(),
            process_name: String::new(),
            dex_buffers: Vec::new(),
            telegram_socket_name: None,
            enabled: false,
            module_classloader: None,
        }
    }
}

// Helper: dereference a C++ reference-field (stored as *mut T) and read the jstring.
unsafe fn read_jstring(env: *mut RawJNIEnv, field_ptr: *mut jstring) -> Option<String> {
    if field_ptr.is_null() {
        return None;
    }
    let jstr = *field_ptr;
    if jstr.is_null() {
        return None;
    }
    let fns = *env;
    let chars = ((*fns).v1_6.GetStringUTFChars)(env, jstr, std::ptr::null_mut());
    if chars.is_null() {
        return None;
    }
    let s = CStr::from_ptr(chars as *const c_char)
        .to_string_lossy()
        .into_owned();
    ((*fns).v1_6.ReleaseStringUTFChars)(env, jstr, chars);
    Some(s)
}

fn send_check_request(api: *mut ApiTable, uid: i32, process_name: &str) -> u8 {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return 2; // COMPANION_ERROR
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_ENABLED);
    // Write uid as i32 little-endian
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    unsafe { libc::close(fd) };
    status
}

fn negotiate_telegram_socket(api: *mut ApiTable, uid: i32, process_name: &str) -> Option<String> {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return None;
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_TELEGRAM_SESSION);
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    if status != COMPANION_ENABLED {
        unsafe { libc::close(fd) };
        return None;
    }
    let name = crate::protocol::read_string_from_fd(fd).ok();
    unsafe { libc::close(fd) };
    name
}

// Read module.apk relative to the module directory fd, before the fd and the
// privileged context are gone. The directory fd is consumed here.
fn read_module_apk(module_dir: OwnedFd) -> Result<Vec<u8>> {
    let fd = unsafe {
        libc::openat(
            module_dir.as_raw_fd(),
            c"module.apk".as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    ensure!(
        fd >= 0,
        "open module.apk: {}",
        std::io::Error::last_os_error()
    );
    let mut apk = File::from_raw_fd(fd);
    let metadata = apk.metadata()?;
    ensure!(metadata.is_file(), "module.apk is not a regular file");
    let size = metadata.len();
    ensure!(size > 0, "empty module APK");
    ensure!(size <= crate::apk::MAX_APK_BYTES, "module APK exceeds size limit");
    // Cap the reservation, not just the limit: the declared size can disagree with
    // a module swapped between stat and read, so let the buffer grow into the real
    // length instead of trusting it for a single large allocation.
    let mut bytes = Vec::with_capacity(usize::min(size as usize, 8 * 1024 * 1024));
    apk.read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= crate::apk::MAX_APK_BYTES,
        "module APK exceeds size limit"
    );
    Ok(bytes)
}

// ── Lifecycle callbacks ───────────────────────────────────────────────────────

pub unsafe fn do_pre_app_specialize(module: &mut WeKitModule, args: *mut AppSpecializeArgs) {
    let nice_name = match read_jstring(module.env, (*args).nice_name) {
        Some(s) if !s.is_empty() && s.len() <= 255 => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let app_data_dir = match read_jstring(module.env, (*args).app_data_dir) {
        Some(s) if !s.is_empty() => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let uid = *(*args).uid;

    let status = send_check_request(module.api, uid, &nice_name);
    if status != COMPANION_ENABLED {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    let mod_fd = (*module.api).get_module_dir();
    if mod_fd < 0 {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }
    // Read the payload now, while this process is still privileged and the module
    // directory fd is still open. `getModuleDir()` hands out an fd that is only
    // guaranteed inside the pre\*Specialize window: the host closes every fd it has
    // not whitelisted before postAppSpecialize runs, and after the UID/SELinux
    // switch the path itself is unreachable. Whether an fd survives is host-specific
    // (ZygiskNext keeps a long-lived one, NyaZygisk opens a fresh one per request),
    // so carry the bytes across specialization instead of an fd.
    match read_module_apk(OwnedFd::from_raw_fd(mod_fd)) {
        Ok(bytes) => module.apk_bytes = Some(bytes),
        Err(error) => {
            loge!("Zygisk: cannot read module.apk: {error:#}");
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    }
    module.data_dir = app_data_dir;
    module.process_name = nice_name.clone();

    // Non-isolated processes: negotiate Telegram socket, write to global
    if !nice_name.contains(':')
        && let Some(name) = negotiate_telegram_socket(module.api, uid, &nice_name)
    {
        *crate::TELEGRAM_SOCKET_NAME.lock().unwrap() = name.clone();
        module.telegram_socket_name = Some(name);
        logi!("Zygisk: retained Telegram root companion socket for {nice_name}");
    }

    module.enabled = true;
    logi!("Zygisk: preAppSpecialize OK for {nice_name}");
}

pub unsafe fn do_post_app_specialize(module: &mut WeKitModule, _args: *const AppSpecializeArgs) {
    if !module.enabled {
        return;
    }
    module.enabled = false;
    // Publish from the bytes captured in preAppSpecialize. Only the app itself may
    // own these files, so publication still happens after the UID switch.
    let Some(apk_bytes) = module.apk_bytes.take() else {
        loge!("Zygisk: module APK payload was not captured");
        return;
    };
    let data_dir = module.data_dir.clone();
    let (apk_dst, dex_bufs) = match crate::payload::publish_apk(Cursor::new(apk_bytes), &data_dir) {
        Ok(payload) => payload,
        Err(error) => {
            loge!("Zygisk: cannot prepare module APK: {error:#}");
            return;
        }
    };

    // Build InMemoryDexClassLoader
    let fns = *module.env;
    let sys_cl_class = ((*fns).v1_6.FindClass)(module.env, c"java/lang/ClassLoader".as_ptr());
    let get_sys_id = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        sys_cl_class,
        c"getSystemClassLoader".as_ptr(),
        c"()Ljava/lang/ClassLoader;".as_ptr(),
    );
    let parent = ((*fns).v1_6.CallStaticObjectMethod)(module.env, sys_cl_class, get_sys_id);
    let cl = crate::payload::build_dex_classloader(module.env, &dex_bufs, parent);
    if cl.is_null() {
        loge!("Zygisk: failed to build InMemoryDexClassLoader");
        return;
    }

    // Once Java can see the loader, classes may escape even if later startup
    // throws. Retain its direct-buffer backing memory in the process-lifetime
    // module object, rather than freeing it on those later failure paths.
    module.dex_buffers = dex_bufs;

    // LSPlant needs unrestricted hidden-API JNI access during initialization.
    // Do this while still in the native Zygote callback, before a module Java
    // frame becomes the caller. Keep the loader resident after any partial ART
    // initialization: LSPlant's runtime inline hooks live until process exit.
    if !crate::art::init(module.env) || !crate::art::trust_class_loader(module.env, cl) {
        if ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE {
            ((*fns).v1_6.ExceptionDescribe)(module.env);
            ((*fns).v1_6.ExceptionClear)(module.env);
        }
        loge!("Zygisk: failed to initialize LSPlant or trust module DEX");
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Load ZygiskEntry class
    let entry_name = "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry";
    let entry_cls = crate::natives::load_class_from_loader(module.env, cl, entry_name);
    if entry_cls.is_null() {
        loge!("Zygisk: ZygiskEntry class not found");
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Register ZygiskEntry native methods
    if !crate::natives::register_entry_natives(module.env, cl) {
        loge!("Zygisk: failed to register ZygiskEntry bootstrap JNI");
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Call ZygiskEntry.init(processName, dataDir, apkPath)
    let init_mid = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        entry_cls,
        c"init".as_ptr(),
        c"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V".as_ptr(),
    );
    if init_mid.is_null() {
        loge!("Zygisk: ZygiskEntry.init method not found");
        ((*fns).v1_6.ExceptionClear)(module.env);
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    let process_name_c = std::ffi::CString::new(module.process_name.as_str()).unwrap_or_default();
    let data_dir_c = std::ffi::CString::new(data_dir.as_str()).unwrap_or_default();
    let apk_path_c = std::ffi::CString::new(apk_dst.to_str().unwrap()).unwrap_or_default();
    let j_process = ((*fns).v1_6.NewStringUTF)(module.env, process_name_c.as_ptr());
    let j_data = ((*fns).v1_6.NewStringUTF)(module.env, data_dir_c.as_ptr());
    let j_apk = ((*fns).v1_6.NewStringUTF)(module.env, apk_path_c.as_ptr());
    if j_process.is_null()
        || j_data.is_null()
        || j_apk.is_null()
        || ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE
    {
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init argument allocation failed");
        for j in [j_process, j_data, j_apk].iter().filter(|&&p| !p.is_null()) {
            ((*fns).v1_6.DeleteLocalRef)(module.env, *j);
        }
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    ((*fns).v1_6.CallStaticVoidMethod)(module.env, entry_cls, init_mid, j_process, j_data, j_apk);
    let failed = ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE;
    if failed {
        ((*fns).v1_6.ExceptionDescribe)(module.env);
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init failed");
    } else {
        logi!(
            "Zygisk: ZygiskEntry.init completed for {}",
            module.process_name
        );
    }
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_process);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_data);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_apk);
    ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
    if failed {
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    // Keep classloader alive for hook bridge class resolution
    module.module_classloader = Some(cl);
    logi!("Zygisk: postAppSpecialize complete");
}

pub unsafe fn do_pre_server_specialize(module: &mut WeKitModule, _args: *mut ServerSpecializeArgs) {
    (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
}
