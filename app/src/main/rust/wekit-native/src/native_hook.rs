#![allow(non_upper_case_globals)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
#![allow(unused)]

//! Native anti-detection hooks.
//!
//! WeChat ships heavy native anti-tamper logic that bypasses every ART/Java
//! hook: it reads `/proc/self/maps`, walks the dynamic link-map via
//! `dl_iterate_phdr`, checks `TracerPid`, and increasingly issues these through
//! raw `syscall(2)` to dodge the libc wrappers entirely.
//!
//! These hooks close that gap. They are installed once, early, from
//! [`native_init`] (gated by an on-disk flag the Java feature writes), using
//! the LSPosed native API's inline-hook primitive.
//!
//! Strategy per vector — all content-sanitizing, never path-blocking:
//! - `openat`/`open`/`open64` + `syscall(SYS_openat)`: for sensitive procfs
//!   files (`maps`/`smaps`/`status`) we serve a sanitized copy from an
//!   anonymous `memfd` — lines naming injected libraries dropped, `TracerPid`
//!   zeroed — so all downstream `read`/`pread`/`mmap`/`fgets`/`lseek`
//!   transparently see clean content. Every other path falls through
//!   untouched.
//! - `dl_iterate_phdr`: the caller's callback is wrapped so injected libraries
//!   are skipped from link-map enumeration.
//! - `__system_property_get`: spoof `ro.debuggable`/`ro.secure`, blank magisk
//!   props.
//! - `ptrace` + `syscall(SYS_ptrace)`: `PTRACE_TRACEME` returns success without
//!   attaching, defeating simple self-anti-debug.
//!
//! We deliberately do NOT return `ENOENT` for module install paths from
//! `open`/`stat`/`access`: the module is loaded *into* the WeChat process from
//! its own APK, so blinding the process to those paths breaks the module's own
//! dynamic linking (e.g. `libmmkv.so`). Hiding the loaded libraries from the
//! memory maps is the meaningful, non-destructive native detection defense.

use crate::{loge, logi, logw};
include!(concat!(env!("OUT_DIR"), "/bindings.rs"));

use core::ffi::{CStr, c_char, c_int, c_long, c_void};
use std::{
    mem::transmute,
    ptr,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
};

use libc::{c_ulong, size_t, ssize_t};

// bionic's errno accessor.
unsafe extern "C" {
    fn __errno() -> *mut c_int;
}

// The unwrapped variants of the LSPosed native-API callbacks.
type RawHook = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut *mut c_void) -> c_int;
type RawUnhook = unsafe extern "C" fn(*mut c_void) -> c_int;

// `dlsym` default-search pseudo-handle. bionic differs by ABI width.
#[cfg(target_pointer_width = "64")]
const RTLD_DEFAULT: *mut c_void = ptr::null_mut();
#[cfg(target_pointer_width = "32")]
const RTLD_DEFAULT: *mut c_void = 0xffff_ffff_usize as *mut c_void;

// ─────────────────────────────────────────────────────────────────────────────
// Global state
// ─────────────────────────────────────────────────────────────────────────────

static HOOK_FUNC: OnceLock<RawHook> = OnceLock::new();
static UNHOOK_FUNC: OnceLock<RawUnhook> = OnceLock::new();

/// Master gate. Even while trampolines are installed, every replacement
/// tail-calls the original when this is false.
static ENABLED: AtomicBool = AtomicBool::new(false);

struct HookState {
    installed: bool,
    /// Addresses of the hooked functions, for `unhook_func` on disable.
    targets: Vec<usize>,
}

static STATE: Mutex<HookState> = Mutex::new(HookState {
    installed: false,
    targets: Vec::new(),
});

/// Dynamic tokens to hide (module package name, data dir, …), fed from Java.
static MODULE_TOKENS: Mutex<Vec<Vec<u8>>> = Mutex::new(Vec::new());

/// Static substrings that must never surface in anything WeChat reads back.
static KEYWORDS: &[&[u8]] = &[
    b"wekit",
    b"dexkit",
    b"xposed",
    b"lsposed",
    b"lspd",
    b"riru",
    b"zygisk",
    b"magisk",
    b"edxp",
    b"substrate",
    b"frida",
    b"dobby",
    b"sandhook",
    b"whale",
    b"taichi",
];

// Backups of the original function entry points (stored as usize; 0 = unset).
static ORIG_OPENAT: AtomicUsize = AtomicUsize::new(0);
static ORIG_OPEN: AtomicUsize = AtomicUsize::new(0);
static ORIG_OPEN64: AtomicUsize = AtomicUsize::new(0);
static ORIG_FACCESSAT: AtomicUsize = AtomicUsize::new(0);
static ORIG_ACCESS: AtomicUsize = AtomicUsize::new(0);
static ORIG_PROP_GET: AtomicUsize = AtomicUsize::new(0);
static ORIG_DL_ITER: AtomicUsize = AtomicUsize::new(0);
static ORIG_PTRACE: AtomicUsize = AtomicUsize::new(0);
static ORIG_READLINKAT: AtomicUsize = AtomicUsize::new(0);
static ORIG_SYSCALL: AtomicUsize = AtomicUsize::new(0);
static ORIG_STAT: AtomicUsize = AtomicUsize::new(0);
static ORIG_LSTAT: AtomicUsize = AtomicUsize::new(0);

const ALL_BACKUPS: &[&AtomicUsize] = &[
    &ORIG_OPENAT,
    &ORIG_OPEN,
    &ORIG_OPEN64,
    &ORIG_FACCESSAT,
    &ORIG_ACCESS,
    &ORIG_PROP_GET,
    &ORIG_DL_ITER,
    &ORIG_PTRACE,
    &ORIG_READLINKAT,
    &ORIG_SYSCALL,
    &ORIG_STAT,
    &ORIG_LSTAT,
];

// Original-function typedefs.
type OpenatFn = unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int;
type OpenFn = unsafe extern "C" fn(*const c_char, c_int, c_int) -> c_int;
type FaccessatFn = unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int;
type AccessFn = unsafe extern "C" fn(*const c_char, c_int) -> c_int;
type PropGetFn = unsafe extern "C" fn(*const c_char, *mut c_char) -> c_int;
type PtraceFn = unsafe extern "C" fn(c_int, c_ulong, c_ulong, c_ulong) -> c_long;
type ReadlinkatFn = unsafe extern "C" fn(c_int, *const c_char, *mut c_char, size_t) -> ssize_t;
type SyscallFn =
    unsafe extern "C" fn(c_long, c_ulong, c_ulong, c_ulong, c_ulong, c_ulong, c_ulong) -> c_long;
type StatFn = unsafe extern "C" fn(*const c_char, *mut c_void) -> c_int;
type DlCbRaw = unsafe extern "C" fn(*mut libc::dl_phdr_info, size_t, *mut c_void) -> c_int;
type DlIterFn = unsafe extern "C" fn(Option<DlCbRaw>, *mut c_void) -> c_int;

// ─────────────────────────────────────────────────────────────────────────────
// Matching helpers
// ─────────────────────────────────────────────────────────────────────────────

fn contains(hay: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() || needle.len() > hay.len() {
        return false;
    }
    hay.windows(needle.len()).any(|w| w == needle)
}

/// Does this path/line/name reference something we must hide?
fn blob_is_hidden(blob: &[u8]) -> bool {
    for kw in KEYWORDS {
        if contains(blob, kw) {
            return true;
        }
    }
    if let Ok(tokens) = MODULE_TOKENS.lock() {
        for tok in tokens.iter() {
            if !tok.is_empty() && contains(blob, tok) {
                return true;
            }
        }
    }
    false
}

/// procfs files whose *content* leaks hooks/tracing and must be sanitized.
fn proc_sensitive(path: &[u8]) -> bool {
    path.starts_with(b"/proc/")
        && (path.ends_with(b"/maps") || path.ends_with(b"/smaps") || path.ends_with(b"/status"))
}

fn set_enoent() {
    unsafe { *__errno() = libc::ENOENT };
}

/// Consume a real fd for a procfs file, produce a sanitized `memfd` fd.
/// Closes `real`. Returns -1 on failure (caller should fall back).
unsafe fn build_sanitized(real: c_int) -> c_int {
    unsafe {
        let mut buf: Vec<u8> = Vec::new();
        let mut tmp = [0u8; 8192];
        loop {
            let n = libc::read(real, tmp.as_mut_ptr() as *mut c_void, tmp.len());
            if n <= 0 {
                break;
            }
            buf.extend_from_slice(&tmp[..n as usize]);
        }
        libc::close(real);

        let mut out: Vec<u8> = Vec::with_capacity(buf.len());
        for line in buf.split_inclusive(|&b| b == b'\n') {
            if blob_is_hidden(line) {
                continue;
            }
            if line.starts_with(b"TracerPid:") {
                out.extend_from_slice(b"TracerPid:\t0\n");
                continue;
            }
            out.extend_from_slice(line);
        }

        let fd = libc::syscall(
            libc::SYS_memfd_create,
            c"wk".as_ptr(),
            libc::MFD_CLOEXEC as c_ulong,
        ) as c_int;
        if fd < 0 {
            return -1;
        }

        let mut off = 0usize;
        while off < out.len() {
            let n = libc::write(fd, out.as_ptr().add(off) as *const c_void, out.len() - off);
            if n <= 0 {
                break;
            }
            off += n as usize;
        }
        libc::lseek(fd, 0, libc::SEEK_SET);
        fd
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Replacement functions
// ─────────────────────────────────────────────────────────────────────────────

unsafe extern "C" fn my_openat(
    dirfd: c_int,
    path: *const c_char,
    flags: c_int,
    mode: c_int,
) -> c_int {
    unsafe {
        let orig: OpenatFn = transmute(ORIG_OPENAT.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire)
            && !path.is_null()
            && proc_sensitive(CStr::from_ptr(path).to_bytes())
        {
            let real = orig(dirfd, path, flags, mode);
            if real >= 0 {
                let fd = build_sanitized(real);
                return if fd >= 0 { fd } else { -1 };
            }
            return real;
        }
        orig(dirfd, path, flags, mode)
    }
}

unsafe extern "C" fn my_open(path: *const c_char, flags: c_int, mode: c_int) -> c_int {
    unsafe {
        let orig: OpenFn = transmute(ORIG_OPEN.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire)
            && !path.is_null()
            && proc_sensitive(CStr::from_ptr(path).to_bytes())
        {
            let real = orig(path, flags, mode);
            if real >= 0 {
                let fd = build_sanitized(real);
                return if fd >= 0 { fd } else { -1 };
            }
            return real;
        }
        orig(path, flags, mode)
    }
}

unsafe extern "C" fn my_open64(path: *const c_char, flags: c_int, mode: c_int) -> c_int {
    unsafe {
        let orig: OpenFn = transmute(ORIG_OPEN64.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire) && !path.is_null() {
            let bytes = CStr::from_ptr(path).to_bytes();
            if proc_sensitive(bytes) {
                let real = orig(path, flags, mode);
                if real >= 0 {
                    let fd = build_sanitized(real);
                    return if fd >= 0 { fd } else { -1 };
                }
                return real;
            }
        }
        orig(path, flags, mode)
    }
}

unsafe extern "C" fn my_prop_get(name: *const c_char, value: *mut c_char) -> c_int {
    unsafe {
        let orig: PropGetFn = transmute(ORIG_PROP_GET.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire) && !name.is_null() {
            let n = CStr::from_ptr(name).to_bytes();
            let spoof: Option<&[u8]> = if n == b"ro.debuggable" {
                Some(b"0")
            } else if n == b"ro.secure" {
                Some(b"1")
            } else if contains(n, b"magisk") || contains(n, b"supolicy") {
                Some(b"")
            } else {
                None
            };
            if let Some(v) = spoof {
                let mut i = 0usize;
                while i < v.len() {
                    *value.add(i) = v[i] as c_char;
                    i += 1;
                }
                *value.add(i) = 0;
                return v.len() as c_int;
            }
        }
        orig(name, value)
    }
}

unsafe extern "C" fn my_ptrace(
    request: c_int,
    pid: c_ulong,
    addr: c_ulong,
    data: c_ulong,
) -> c_long {
    unsafe {
        let orig: PtraceFn = transmute(ORIG_PTRACE.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire) && request as i64 == libc::PTRACE_TRACEME as i64 {
            return 0;
        }
        orig(request, pid, addr, data)
    }
}

// dl_iterate_phdr proxy: skips injected libraries during link-map enumeration.
struct DlProxy {
    cb: Option<DlCbRaw>,
    data: *mut c_void,
}

unsafe extern "C" fn dl_proxy_cb(
    info: *mut libc::dl_phdr_info,
    size: size_t,
    data: *mut c_void,
) -> c_int {
    unsafe {
        let proxy = &*(data as *const DlProxy);
        if ENABLED.load(Ordering::Acquire) && !(*info).dlpi_name.is_null() {
            let name = CStr::from_ptr((*info).dlpi_name).to_bytes();
            if blob_is_hidden(name) {
                return 0; // skip; keep iterating
            }
        }
        match proxy.cb {
            Some(cb) => cb(info, size, proxy.data),
            None => 0,
        }
    }
}

unsafe extern "C" fn my_dl_iterate_phdr(cb: Option<DlCbRaw>, data: *mut c_void) -> c_int {
    unsafe {
        let orig: DlIterFn = transmute(ORIG_DL_ITER.load(Ordering::Acquire));
        if !ENABLED.load(Ordering::Acquire) {
            return orig(cb, data);
        }
        let mut proxy = DlProxy { cb, data };
        orig(Some(dl_proxy_cb), &mut proxy as *mut _ as *mut c_void)
    }
}

// Raw syscall dispatcher — the bypass path around the libc wrappers above.
unsafe extern "C" fn my_syscall(
    num: c_long,
    a: c_ulong,
    b: c_ulong,
    c: c_ulong,
    d: c_ulong,
    e: c_ulong,
    f: c_ulong,
) -> c_long {
    unsafe {
        let orig: SyscallFn = transmute(ORIG_SYSCALL.load(Ordering::Acquire));
        if ENABLED.load(Ordering::Acquire) {
            if num == libc::SYS_openat {
                let path = b as *const c_char;
                if !path.is_null() {
                    let bytes = CStr::from_ptr(path).to_bytes();
                    if proc_sensitive(bytes) {
                        let real = orig(num, a, b, c, d, e, f) as c_int;
                        if real >= 0 {
                            let fd = build_sanitized(real);
                            return if fd >= 0 {
                                fd as c_long
                            } else {
                                real as c_long
                            };
                        }
                        return real as c_long;
                    }
                }
            } else if num == libc::SYS_ptrace && a as i64 == libc::PTRACE_TRACEME as i64 {
                return 0;
            }
        }
        orig(num, a, b, c, d, e, f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Install / uninstall
// ─────────────────────────────────────────────────────────────────────────────

unsafe fn hook_one(
    hook: RawHook,
    sym: &CStr,
    replace: *mut c_void,
    backup: &AtomicUsize,
    targets: &mut Vec<usize>,
) {
    unsafe {
        let target = libc::dlsym(RTLD_DEFAULT, sym.as_ptr());
        if target.is_null() {
            logw!("anti-detect: symbol not found: {:?}", sym);
            return;
        }
        let mut b: *mut c_void = ptr::null_mut();
        hook(target, replace, &mut b);
        if b.is_null() {
            // Common & harmless: aliased symbols (e.g. open64 == open in bionic)
            // resolve to an already-hooked address, so the second hook no-ops.
            logw!("anti-detect: hook skipped (no backup): {:?}", sym);
            return;
        }
        backup.store(b as usize, Ordering::Release);
        targets.push(target as usize);
    }
}

/// Store the module tokens (package name, data dir, …) to hide.
fn set_module_tokens(tokens: Vec<Vec<u8>>) {
    if let Ok(mut guard) = MODULE_TOKENS.lock() {
        *guard = tokens;
    }
}

/// Install the anti-detection hooks (idempotent). Returns false if the native
/// hook primitive was never handed to us by the framework.
fn install_hooks() -> bool {
    let Some(&hook) = HOOK_FUNC.get() else {
        loge!("anti-detect: hook_func unavailable (native API not active)");
        return false;
    };

    let mut st = match STATE.lock() {
        Ok(s) => s,
        Err(_) => return false,
    };

    if st.installed {
        ENABLED.store(true, Ordering::Release);
        return true;
    }

    unsafe {
        hook_one(
            hook,
            c"openat",
            my_openat as *mut c_void,
            &ORIG_OPENAT,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"open",
            my_open as *mut c_void,
            &ORIG_OPEN,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"open64",
            my_open64 as *mut c_void,
            &ORIG_OPEN64,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"__system_property_get",
            my_prop_get as *mut c_void,
            &ORIG_PROP_GET,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"dl_iterate_phdr",
            my_dl_iterate_phdr as *mut c_void,
            &ORIG_DL_ITER,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"ptrace",
            my_ptrace as *mut c_void,
            &ORIG_PTRACE,
            &mut st.targets,
        );
        hook_one(
            hook,
            c"syscall",
            my_syscall as *mut c_void,
            &ORIG_SYSCALL,
            &mut st.targets,
        );
    }

    st.installed = true;
    ENABLED.store(true, Ordering::Release);
    logi!(
        "anti-detect: hooks installed ({} symbols)",
        st.targets.len()
    );
    true
}

// ─────────────────────────────────────────────────────────────────────────────
// Flag file
// ─────────────────────────────────────────────────────────────────────────────

/// Path of the on-disk flag that the Java feature writes to request the hooks.
///
/// Must match `NativeAntiDetection.FLAG_FILE` on the Kotlin side:
/// `/storage/emulated/0/Android/data/<host-pkg>/WeKit/native_anti_detection_enabled.flag`
/// where `<host-pkg>` is the WeChat process we are running inside.
fn flag_file_path() -> Option<std::path::PathBuf> {
    // We are injected into the host (WeChat) process, so its own package name
    // is the current package. Read it from the app data dir the runtime exposes.
    let pkg = current_package_name()?;
    Some(
        std::path::Path::new("/storage/emulated/0/Android/data")
            .join(&pkg)
            .join("WeKit")
            .join("native_anti_detection_enabled.flag"),
    )
}

/// Best-effort recovery of the host package name from `/proc/self/cmdline`
/// (Android sets argv[0] to the process/package name).
fn current_package_name() -> Option<String> {
    let raw = std::fs::read("/proc/self/cmdline").ok()?;
    let end = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
    let name = std::str::from_utf8(&raw[..end]).ok()?;
    // Strip any `:process` suffix so we key off the base package.
    let base = name.split(':').next().unwrap_or(name);
    if base.is_empty() {
        None
    } else {
        Some(base.to_string())
    }
}

/// Read the flag file. Returns `Some(tokens)` when present. Each non-empty
/// line is one token the native layer must hide (module pkg, data dir, …).
fn read_flag_tokens() -> Option<Vec<Vec<u8>>> {
    let path = flag_file_path()?;
    let data = std::fs::read(&path).ok()?;
    let tokens: Vec<Vec<u8>> = data
        .split(|&b| b == b'\n')
        .map(|line| {
            let s = line.strip_suffix(b"\r").unwrap_or(line);
            s.to_vec()
        })
        .filter(|t| !t.is_empty())
        .collect();
    Some(tokens)
}

// ─────────────────────────────────────────────────────────────────────────────
// LSPosed native-API entry point
// ─────────────────────────────────────────────────────────────────────────────

/// Called by the LSPosed native API the moment `libwekit_native.so` is loaded
/// into the host process — before WeChat runs its own anti-tamper checks.
///
/// All hook installation happens here (never later from Java): if the flag file
/// exists we read the module tokens from it and install the libc/libdl hooks
/// immediately. Toggling the feature from Java only creates/removes the flag,
/// so enabling or disabling anti-detection takes effect on the next launch.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn native_init(entries: *const NativeAPIEntries) -> NativeOnModuleLoaded {
    unsafe {
        if !entries.is_null() {
            if let Some(hook) = (*entries).hook_func {
                let _ = HOOK_FUNC.set(hook);
            }
            if let Some(unhook) = (*entries).unhook_func {
                let _ = UNHOOK_FUNC.set(unhook);
            }
        }
    }

    match read_flag_tokens() {
        Some(tokens) => {
            logi!(
                "anti-detect: flag present, installing hooks with {} token(s)",
                tokens.len()
            );
            set_module_tokens(tokens);
            install_hooks();
        }
        None => {
            logi!("anti-detect: flag absent, hooks not installed");
        }
    }

    logi!("native hook initialized");
    None
}
