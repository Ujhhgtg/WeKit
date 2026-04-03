//! Native crash handler
//!
//! Design constraints:
//! - Inside the signal handler, only async-signal-safe primitives are used:
//!   raw `write()` / `open()` / `close()` / `read()` syscalls and the
//!   bespoke [`Writer`] that formats numbers without heap allocation.
//! - Outside the signal handler (install / uninstall / trigger paths),
//!   `format!` is fine because we are in a normal call context.
//! - `_Unwind_Backtrace` is technically not async-signal-safe, but this
//!   matches the original C++ behaviour and is standard practice on Android.

use core::{ffi::CStr, mem::MaybeUninit, sync::atomic::Ordering};
use libc::*;

use crate::{
    crash_triggerer::{HANDLER_INSTALLED, IS_HANDLING_CRASH},
    loge, logi,
};

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const SIGNALS: &[c_int] = &[SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS, SIGTRAP];
const MAX_SIGNALS: usize = 32;
const MAX_FRAMES: usize = 128;

static mut G_CRASH_LOG_DIR: [u8; 512] = [0u8; 512];
static mut G_OLD_HANDLERS: [MaybeUninit<sigaction>; MAX_SIGNALS] =
    [MaybeUninit::uninit(); MAX_SIGNALS];

// ─────────────────────────────────────────────────────────────────────────────
// Signal name / description helpers
// ─────────────────────────────────────────────────────────────────────────────

fn signal_name(sig: c_int) -> &'static str {
    match sig {
        SIGSEGV => "SIGSEGV",
        SIGABRT => "SIGABRT",
        SIGFPE => "SIGFPE",
        SIGILL => "SIGILL",
        SIGBUS => "SIGBUS",
        SIGTRAP => "SIGTRAP",
        _ => "UNKNOWN",
    }
}

fn signal_description(sig: c_int) -> &'static str {
    match sig {
        SIGSEGV => "Segmentation fault (invalid memory access)",
        SIGABRT => "Abort signal (abnormal termination)",
        SIGFPE => "Floating point exception (division by zero, etc.)",
        SIGILL => "Illegal instruction",
        SIGBUS => "Bus error (invalid memory alignment)",
        SIGTRAP => "Trace/breakpoint trap",
        _ => "Unknown signal",
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stack unwinding via libunwind (same approach as the original)
// ─────────────────────────────────────────────────────────────────────────────

/// Opaque libunwind context — we only ever hold a raw pointer to it.
#[repr(C)]
struct UnwindContext(c_void);

type UnwindReasonCode = c_int;
const URC_NO_REASON: UnwindReasonCode = 0;

unsafe extern "C" {
    fn _Unwind_Backtrace(
        cb: unsafe extern "C" fn(*mut UnwindContext, *mut c_void) -> UnwindReasonCode,
        arg: *mut c_void,
    ) -> UnwindReasonCode;

    fn _Unwind_GetIP(ctx: *mut UnwindContext) -> usize;
}

struct BacktraceState {
    frames: *mut usize,
    count: usize,
    max: usize,
}

unsafe extern "C" fn unwind_cb(ctx: *mut UnwindContext, arg: *mut c_void) -> UnwindReasonCode {
    unsafe {
        let state = &mut *(arg as *mut BacktraceState);
        let pc = _Unwind_GetIP(ctx);
        if pc != 0 && state.count < state.max {
            *state.frames.add(state.count) = pc;
            state.count += 1;
        }
        URC_NO_REASON
    }
}

fn capture_backtrace(frames: &mut [usize; MAX_FRAMES]) -> usize {
    let mut state = BacktraceState {
        frames: frames.as_mut_ptr(),
        count: 0,
        max: MAX_FRAMES,
    };
    unsafe { _Unwind_Backtrace(unwind_cb, &mut state as *mut _ as *mut c_void) };
    state.count
}

// ─────────────────────────────────────────────────────────────────────────────
// Signal-safe writer — no heap, writes directly to an fd
// ─────────────────────────────────────────────────────────────────────────────

struct Writer {
    fd: c_int,
}

impl Writer {
    fn new(fd: c_int) -> Self {
        Self { fd }
    }

    fn bytes(&self, data: &[u8]) {
        let mut ptr = data.as_ptr();
        let mut rem = data.len();
        while rem > 0 {
            let n = unsafe { write(self.fd, ptr as *const c_void, rem) };
            if n <= 0 {
                break;
            }
            unsafe {
                ptr = ptr.add(n as usize);
            }
            rem -= n as usize;
        }
    }

    fn s(&self, s: &str) {
        self.bytes(s.as_bytes());
    }

    fn u64_dec(&self, mut n: u64) {
        let mut buf = [0u8; 20];
        if n == 0 {
            self.bytes(b"0");
            return;
        }
        let mut i = 20usize;
        while n > 0 {
            i -= 1;
            buf[i] = b'0' + (n % 10) as u8;
            n /= 10;
        }
        self.bytes(&buf[i..]);
    }

    fn i32_dec(&self, n: i32) {
        if n < 0 {
            self.bytes(b"-");
            self.u64_dec(n.unsigned_abs() as u64);
        } else {
            self.u64_dec(n as u64);
        }
    }

    /// Write `n` as zero-padded hex of exactly `width` nibbles (clamped to 16).
    fn hex(&self, n: u64, width: usize) {
        const HEX: &[u8] = b"0123456789abcdef";
        let mut buf = [b'0'; 16];
        let mut v = n;
        for i in (0..16).rev() {
            buf[i] = HEX[(v & 0xf) as usize];
            v >>= 4;
        }
        let start = 16usize.saturating_sub(width.min(16));
        self.bytes(&buf[start..]);
    }

    fn ptr(&self, p: *const c_void) {
        self.bytes(b"0x");
        let w = if cfg!(target_pointer_width = "64") {
            16
        } else {
            8
        };
        self.hex(p as u64, w);
    }

    fn sep(&self, title: &str) {
        self.s("========================================\n");
        self.s(title);
        self.s("\n========================================\n");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: write a decimal u64 zero-padded to `width` into a raw byte slice.
// Returns the number of bytes written.  Used only for filename/timestamp
// building inside the signal handler where format! is avoided.
// ─────────────────────────────────────────────────────────────────────────────

fn write_padded_u64(buf: &mut [u8], mut n: u64, width: usize) -> usize {
    let mut tmp = [b'0'; 20];
    let mut i = 20usize;
    if n == 0 {
        tmp[19] = b'0';
        i = 19;
    } else {
        while n > 0 {
            i -= 1;
            tmp[i] = b'0' + (n % 10) as u8;
            n /= 10;
        }
    }
    let digits = &tmp[i..];
    let pad = width.saturating_sub(digits.len());
    let total = pad + digits.len();
    for j in 0..pad {
        buf[j] = b'0';
    }
    buf[pad..total].copy_from_slice(digits);
    total
}

// ─────────────────────────────────────────────────────────────────────────────
// Crash log writer — called from the signal handler
// ─────────────────────────────────────────────────────────────────────────────

/// # Safety
/// Must only be called from a signal handler.  `info` and `ctx` are the
/// pointers delivered by the kernel; both may be null.
unsafe fn write_crash_log(sig: c_int, info: *mut siginfo_t, ctx: *mut c_void) {
    unsafe {
        // ── Build the log filename without any heap allocation ───────────────────
        let now: time_t = time(core::ptr::null_mut());
        let mut tm_buf: tm = core::mem::zeroed();
        localtime_r(&now, &mut tm_buf);

        let dir_len = (&raw const G_CRASH_LOG_DIR)
            .as_ref()
            .unwrap()
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(0);
        let dir = &G_CRASH_LOG_DIR[..dir_len];

        let mut fname = [0u8; 300];
        let mut p = 0usize;

        fname[p..p + dir_len].copy_from_slice(dir);
        p += dir_len;
        let pfx = b"/crash_";
        fname[p..p + pfx.len()].copy_from_slice(pfx);
        p += pfx.len();
        p += write_padded_u64(&mut fname[p..], (tm_buf.tm_year + 1900) as u64, 4);
        p += write_padded_u64(&mut fname[p..], (tm_buf.tm_mon + 1) as u64, 2);
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_mday as u64, 2);
        fname[p] = b'_';
        p += 1;
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_hour as u64, 2);
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_min as u64, 2);
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_sec as u64, 2);
        let sfx = b".log";
        fname[p..p + sfx.len()].copy_from_slice(sfx);
        p += sfx.len();
        fname[p] = 0; // null-terminate

        // ── Open log file ────────────────────────────────────────────────────────
        let fd = open(
            fname.as_ptr() as *const c_char,
            O_WRONLY | O_CREAT | O_TRUNC,
            0o644u32,
        );
        if fd < 0 {
            return;
        }

        let w = Writer::new(fd);

        // ── Header ───────────────────────────────────────────────────────────────
        w.s("========================================\n");
        w.s("WeKit Native Crash Report\n");
        w.s("========================================\n\n");
        w.s("Crash Time: ");
        // YYYY-MM-DD HH:MM:SS
        {
            let mut ts = [0u8; 32];
            let mut tp = 0usize;
            tp += write_padded_u64(&mut ts[tp..], (tm_buf.tm_year + 1900) as u64, 4);
            ts[tp] = b'-';
            tp += 1;
            tp += write_padded_u64(&mut ts[tp..], (tm_buf.tm_mon + 1) as u64, 2);
            ts[tp] = b'-';
            tp += 1;
            tp += write_padded_u64(&mut ts[tp..], tm_buf.tm_mday as u64, 2);
            ts[tp] = b' ';
            tp += 1;
            tp += write_padded_u64(&mut ts[tp..], tm_buf.tm_hour as u64, 2);
            ts[tp] = b':';
            tp += 1;
            tp += write_padded_u64(&mut ts[tp..], tm_buf.tm_min as u64, 2);
            ts[tp] = b':';
            tp += 1;
            tp += write_padded_u64(&mut ts[tp..], tm_buf.tm_sec as u64, 2);
            w.bytes(&ts[..tp]);
        }
        w.s("\nCrash Type: NATIVE\n\n");

        // ── Signal information ────────────────────────────────────────────────────
        w.sep("Signal Information");
        w.s("Signal: ");
        w.u64_dec(sig as u64);
        w.s(" (");
        w.s(signal_name(sig));
        w.s(")\n");
        w.s("Description: ");
        w.s(signal_description(sig));
        w.s("\n");
        w.s("Signal Code: ");
        w.i32_dec((*info).si_code);
        w.s("\n");
        w.s("Fault Address: ");
        w.ptr((*info).si_addr() as *const c_void);
        w.s("\n\n");

        // ── Register state ────────────────────────────────────────────────────────
        if !ctx.is_null() {
            w.sep("Register State");

            #[cfg(target_arch = "aarch64")]
            {
                let uc = &*(ctx as *const ucontext_t);
                let mc = &uc.uc_mcontext;
                for i in 0..31usize {
                    w.s("x");
                    if i < 10 {
                        w.s(" ");
                    }
                    w.u64_dec(i as u64);
                    w.s(": ");
                    w.hex(mc.regs[i], 16);
                    w.s("\n");
                }
                w.s("sp:  ");
                w.hex(mc.sp, 16);
                w.s("\n");
                w.s("pc:  ");
                w.hex(mc.pc, 16);
                w.s("\n\n");
            }

            #[cfg(target_arch = "arm")]
            {
                let uc = &*(ctx as *const ucontext_t);
                let mc = &uc.uc_mcontext;
                let regs: [u32; 13] = [
                    mc.arm_r0, mc.arm_r1, mc.arm_r2, mc.arm_r3, mc.arm_r4, mc.arm_r5, mc.arm_r6,
                    mc.arm_r7, mc.arm_r8, mc.arm_r9, mc.arm_r10, mc.arm_fp, mc.arm_ip,
                ];
                for (i, &val) in regs.iter().enumerate() {
                    w.s("r");
                    if i < 10 {
                        w.s(" ");
                    }
                    w.u64_dec(i as u64);
                    w.s(": ");
                    w.hex(val as u64, 8);
                    w.s("\n");
                }
                w.s("sp:  ");
                w.hex(mc.arm_sp as u64, 8);
                w.s("\n");
                w.s("lr:  ");
                w.hex(mc.arm_lr as u64, 8);
                w.s("\n");
                w.s("pc:  ");
                w.hex(mc.arm_pc as u64, 8);
                w.s("\n\n");
            }

            #[cfg(target_arch = "x86_64")]
            {
                use libc::{
                    REG_CSGSFS, REG_EFL, REG_R8, REG_R9, REG_R10, REG_R11, REG_R12, REG_R13,
                    REG_R14, REG_R15, REG_RAX, REG_RBP, REG_RBX, REG_RCX, REG_RDI, REG_RDX,
                    REG_RIP, REG_RSI, REG_RSP,
                };
                let uc = &*(ctx as *const ucontext_t);
                let gregs = &uc.uc_mcontext.gregs;

                let named: &[(&str, i32)] = &[
                    ("rax", REG_RAX),
                    ("rbx", REG_RBX),
                    ("rcx", REG_RCX),
                    ("rdx", REG_RDX),
                    ("rsi", REG_RSI),
                    ("rdi", REG_RDI),
                    ("rbp", REG_RBP),
                    ("rsp", REG_RSP),
                    ("r8 ", REG_R8),
                    ("r9 ", REG_R9),
                    ("r10", REG_R10),
                    ("r11", REG_R11),
                    ("r12", REG_R12),
                    ("r13", REG_R13),
                    ("r14", REG_R14),
                    ("r15", REG_R15),
                    ("rip", REG_RIP),
                    ("efl", REG_EFL),
                    ("csgsfs", REG_CSGSFS),
                ];
                for &(name, idx) in named {
                    w.s(name);
                    w.s(": ");
                    w.hex(gregs[idx as usize] as u64, 16);
                    w.s("\n");
                }
                w.s("\n");
            }

            #[cfg(target_arch = "x86")]
            {
                use libc::{
                    REG_DS, REG_EAX, REG_EBP, REG_EBX, REG_ECX, REG_EDI, REG_EDX, REG_EFL, REG_EIP,
                    REG_ES, REG_ESI, REG_ESP, REG_FS, REG_GS,
                };
                let uc = &*(ctx as *const ucontext_t);
                let gregs = &uc.uc_mcontext.gregs;

                let named: &[(&str, i32)] = &[
                    ("eax", REG_EAX),
                    ("ebx", REG_EBX),
                    ("ecx", REG_ECX),
                    ("edx", REG_EDX),
                    ("esi", REG_ESI),
                    ("edi", REG_EDI),
                    ("ebp", REG_EBP),
                    ("esp", REG_ESP),
                    ("eip", REG_EIP),
                    ("efl", REG_EFL),
                    ("gs", REG_GS),
                    ("fs", REG_FS),
                    ("es", REG_ES),
                    ("ds", REG_DS),
                ];
                for &(name, idx) in named {
                    w.s(name);
                    w.s(": ");
                    w.hex(gregs[idx as usize] as u64, 8);
                    w.s("\n");
                }
                w.s("\n");
            }
        }

        // ── Stack trace ───────────────────────────────────────────────────────────
        w.sep("Stack Trace");
        let mut frames = [0usize; MAX_FRAMES];
        let count = capture_backtrace(&mut frames);

        for (i, &frame) in frames[..count].iter().enumerate() {
            let mut dl: Dl_info = core::mem::zeroed();
            if dladdr(frame as *const c_void, &mut dl) != 0 && !dl.dli_fname.is_null() {
                let lib = CStr::from_ptr(dl.dli_fname).to_bytes();
                let sym = if dl.dli_sname.is_null() {
                    b"<unknown>" as &[u8]
                } else {
                    CStr::from_ptr(dl.dli_sname).to_bytes()
                };
                let offset = frame.wrapping_sub(dl.dli_saddr as usize);

                w.s("#");
                w.u64_dec(i as u64);
                w.s(" pc ");
                w.ptr(frame as *const c_void);
                w.s("  ");
                w.bytes(lib);
                w.s(" (");
                w.bytes(sym);
                w.s("+");
                w.u64_dec(offset as u64);
                w.s(")\n");
            } else {
                w.s("#");
                w.u64_dec(i as u64);
                w.s(" pc ");
                w.ptr(frame as *const c_void);
                w.s("  <unknown>\n");
            }
        }

        // ── Memory maps ───────────────────────────────────────────────────────────
        w.s("\n");
        w.sep("Memory Maps");
        let maps_fd = open(b"/proc/self/maps\0".as_ptr() as *const c_char, O_RDONLY);
        if maps_fd >= 0 {
            let mut buf = [0u8; 8192];
            loop {
                let n = read(maps_fd, buf.as_mut_ptr() as *mut c_void, buf.len());
                if n <= 0 {
                    break;
                }
                w.bytes(&buf[..n as usize]);
            }
            close(maps_fd);
        }

        // ── Footer ────────────────────────────────────────────────────────────────
        w.s("\n");
        w.sep("End of Crash Report");
        close(fd);

        // ── Pending-crash flag file ───────────────────────────────────────────────
        let mut flag_path = [0u8; 560];
        let mut fp = 0usize;
        flag_path[fp..fp + dir_len].copy_from_slice(dir);
        fp += dir_len;
        let flag_name = b"/pending_crash.flag\0";
        flag_path[fp..fp + flag_name.len()].copy_from_slice(flag_name);

        let flag_fd = open(
            flag_path.as_ptr() as *const c_char,
            O_WRONLY | O_CREAT | O_TRUNC,
            0o644u32,
        );
        if flag_fd >= 0 {
            // Write the bare log filename (skip the directory prefix + '/')
            if let Some(slash) = fname[..p].iter().rposition(|&b| b == b'/') {
                let fw = Writer::new(flag_fd);
                fw.bytes(&fname[slash + 1..p]);
            }
            close(flag_fd);
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Signal handler
// ─────────────────────────────────────────────────────────────────────────────

unsafe extern "C" fn signal_handler(sig: c_int, info: *mut siginfo_t, ctx: *mut c_void) {
    unsafe {
        // Guard against recursive crashes
        if IS_HANDLING_CRASH.swap(true, Ordering::SeqCst) {
            _exit(1);
        }

        loge!("========================================");
        loge!("!!! Native crash detected !!!");
        loge!("Signal: {} ({})", sig, signal_name(sig));
        loge!("Description: {}", signal_description(sig));

        write_crash_log(sig, info, ctx);

        logi!("Crash log written — calling original handler");

        // ── Invoke the previously-registered handler ──────────────────────────────
        let old = G_OLD_HANDLERS[sig as usize].assume_init_ref();
        if old.sa_flags & SA_SIGINFO != 0 {
            // The old handler wants the full siginfo calling convention.
            // sa_sigaction is stored as a sighandler_t (fn(c_int)); we reinterpret
            // it as the SA_SIGINFO variant via transmute — same as the C++ original.
            let h: unsafe extern "C" fn(c_int, *mut siginfo_t, *mut c_void) =
                core::mem::transmute(old.sa_sigaction);
            h(sig, info, ctx);
        } else if old.sa_sigaction != SIG_DFL as sighandler_t
            && old.sa_sigaction != SIG_IGN as sighandler_t
        {
            // Plain signal(2)-style handler.
            let h: unsafe extern "C" fn(c_int) = core::mem::transmute(old.sa_sigaction);
            h(sig);
        }

        // Restore the default disposition and re-raise to produce the correct
        // termination status / core dump.
        logi!("restoring default handler and re-raising signal {}", sig);
        signal(sig, SIG_DFL);
        raise(sig);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/// Install the native crash handler.  Thread-safe via atomic flag.
/// Returns `true` on success.
pub fn install_crash_handler(log_dir: &str) -> bool {
    if HANDLER_INSTALLED.load(Ordering::SeqCst) {
        logi!("native crash handler already installed");
        return true;
    }

    let bytes = log_dir.as_bytes();
    if bytes.is_empty() {
        loge!("invalid crash log directory");
        return false;
    }

    // Store the directory path globally (null-terminated).
    unsafe {
        let len = bytes
            .len()
            .min((&raw const G_CRASH_LOG_DIR).as_ref().unwrap().len() - 1);
        G_CRASH_LOG_DIR[..len].copy_from_slice(&bytes[..len]);
        G_CRASH_LOG_DIR[len] = 0;
        // Ensure the directory exists.
        mkdir((&raw const G_CRASH_LOG_DIR) as *const c_char, 0o755);
    }

    // Build the new sigaction — SA_SIGINFO so we receive siginfo_t + ucontext_t.
    let mut sa: sigaction = unsafe { core::mem::zeroed() };
    unsafe { sigemptyset(&mut sa.sa_mask) };
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sa.sa_sigaction = signal_handler as *const () as sighandler_t;

    let mut all_ok = true;
    for &sig in SIGNALS {
        let slot = sig as usize;
        let ok = unsafe { sigaction(sig, &sa, G_OLD_HANDLERS[slot].as_mut_ptr()) == 0 };
        if ok {
            logi!(
                "installed handler for signal {} ({})",
                sig,
                signal_name(sig)
            );
        } else {
            loge!(
                "failed to install handler for signal {} ({})",
                sig,
                signal_name(sig)
            );
            all_ok = false;
        }
    }

    if all_ok {
        HANDLER_INSTALLED.store(true, Ordering::SeqCst);
        logi!("native crash handler installed successfully");
    }

    all_ok
}

/// Remove the crash handler and restore the original signal dispositions.
pub fn uninstall_crash_handler() {
    if !HANDLER_INSTALLED.load(Ordering::SeqCst) {
        return;
    }
    for &sig in SIGNALS {
        unsafe {
            sigaction(
                sig,
                G_OLD_HANDLERS[sig as usize].as_ptr(),
                core::ptr::null_mut(),
            );
        }
    }
    HANDLER_INSTALLED.store(false, Ordering::SeqCst);
    logi!("Native crash handler uninstalled");
}
