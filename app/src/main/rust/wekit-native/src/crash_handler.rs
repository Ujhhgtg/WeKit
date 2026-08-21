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
/// Crash-log file-name prefix, handed over by `CrashLogsManager` at install time so both
/// writers agree on one naming scheme. Null-terminated.
static mut G_CRASH_LOG_NAME_PREFIX: [u8; 64] = [0u8; 64];
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

// ─────────────────────────────────────────────────────────────────────────────
// Signal context layout
// ─────────────────────────────────────────────────────────────────────────────

/// bionic's arm64 `sigcontext` (what `uc_mcontext` actually is).
#[cfg(target_arch = "aarch64")]
#[repr(C, align(16))]
struct BionicSigContext {
    fault_address: u64,
    regs: [u64; 31],
    sp: u64,
    pc: u64,
    pstate: u64,
    // A 4096-byte `__reserved` blob follows (FP/SIMD state); we never read it.
}

/// bionic's arm64 `ucontext_t`.
///
/// This exists because `libc::ucontext_t` for `aarch64-linux-android` is WRONG — a known upstream
/// bug: rust-lang/libc#5159, fixed by rust-lang/libc#5189, which is approved but not yet in a
/// release we can pin. The NDK header reserves `__u8 __linux_unused[1024 / 8 - sizeof(sigset_t)]`
/// between `uc_sigmask` and `uc_mcontext` so the kernel can grow `sigset_t`; the `libc` crate omits
/// it, putting `uc_mcontext` at offset 48 instead of 176.
///
/// Reading the signal context through `libc::ucontext_t` therefore lands 128 bytes early and every
/// register comes out shifted by 16 slots. That is exactly what earlier crash reports showed: the
/// fault address sitting in `x15` (48 + 8 + 15*8 == 176, the real `fault_address`), `x0`–`x14` all
/// zero because they were reading the tail of the sigmask and its padding, and impossible values
/// like `pc: 0x8` for what were really `x16`/`x17`.
///
/// Keep this struct even after upstream lands the fix: it is pinned to the ABI we verified against,
/// and [`ASSERT_MCONTEXT_OFFSET`] fails the build if that ever stops holding.
#[cfg(target_arch = "aarch64")]
#[repr(C)]
struct BionicUcontext {
    uc_flags: c_ulong,
    uc_link: *mut c_void,
    uc_stack: stack_t,
    uc_sigmask: sigset_t,
    __linux_unused: [u8; 1024 / 8 - core::mem::size_of::<sigset_t>()],
    uc_mcontext: BionicSigContext,
}

/// Offsets straight out of the NDK headers, per rust-lang/libc#5159. A mismatch here means every
/// register and both backtraces in the crash report would be silently wrong, so fail the build.
#[cfg(target_arch = "aarch64")]
#[allow(dead_code)] // const-evaluated for its assertion; never read at runtime
const ASSERT_MCONTEXT_OFFSET: () = {
    assert!(core::mem::offset_of!(BionicUcontext, uc_mcontext) == 176);
};

/// Reads one word of the crashed thread's stack, returning `None` if the address is unreadable.
///
/// A frame-pointer walk follows attacker-controlled-looking memory by definition (the stack may be
/// smashed, which is often *why* we are here), so every dereference has to be probed first. We do
/// that with a `write(2)` to a throwaway pipe: the kernel validates the buffer and hands back
/// `EFAULT` instead of raising a second SIGSEGV inside the handler.
#[cfg(target_arch = "aarch64")]
unsafe fn read_stack_word(probe_fd: c_int, addr: usize) -> Option<u64> {
    if addr == 0 || addr & 0x7 != 0 {
        return None;
    }
    unsafe {
        if write(probe_fd, addr as *const c_void, 8) != 8 {
            return None;
        }
        Some(core::ptr::read_volatile(addr as *const u64))
    }
}

/// Builds a backtrace for the thread that actually faulted, seeded from its signal context.
///
/// Frame 0 is `pc` and frame 1 is `lr`; the rest come from walking the frame-pointer chain, where
/// each frame stores `[fp] = caller fp` and `[fp + 8] = caller lr`. Code built without frame
/// pointers truncates the walk early, so this complements rather than replaces the unwinder — but
/// even when it only yields `pc` and `lr` that is strictly more than the handler unwind gives.
#[cfg(target_arch = "aarch64")]
fn capture_fault_backtrace(
    frames: &mut [usize; MAX_FRAMES],
    pc: usize,
    lr: usize,
    fp: usize,
) -> usize {
    let mut n = 0usize;
    if pc != 0 {
        frames[n] = pc;
        n += 1;
    }
    if lr != 0 && lr != pc {
        frames[n] = lr;
        n += 1;
    }

    // A pipe whose write end we only ever use as a "is this address readable?" oracle.
    let mut fds = [-1 as c_int; 2];
    if unsafe { pipe(fds.as_mut_ptr()) } != 0 {
        return n;
    }
    let (probe_r, probe_w) = (fds[0], fds[1]);

    let mut cur_fp = fp;
    let mut prev_fp = 0usize;
    let mut first_walked = true;
    while n < MAX_FRAMES {
        // The chain must climb monotonically; anything else means the stack is corrupt or we have
        // wandered off it, and following it further would just print noise.
        if cur_fp == 0 || cur_fp <= prev_fp {
            break;
        }
        let Some(next_fp) = (unsafe { read_stack_word(probe_w, cur_fp) }) else {
            break;
        };
        let Some(ret_addr) = (unsafe { read_stack_word(probe_w, cur_fp + 8) }) else {
            break;
        };
        if ret_addr == 0 {
            break;
        }
        // A standard AArch64 prologue (`stp x29, x30, [sp, #-N]!; mov x29, sp`) stores the live
        // return address at [fp + 8], so once the faulting function's prologue has run, `lr` and the
        // first walked frame are the SAME address. Emitting both makes an ordinary stack look like a
        // self-recursive one — which is exactly how the first report from this handler got
        // misread. Only `lr` is kept, since it is also correct for a leaf that never saved x30.
        if first_walked && n >= 2 && ret_addr as usize == frames[n - 1] {
            first_walked = false;
            prev_fp = cur_fp;
            cur_fp = next_fp as usize;
            continue;
        }
        first_walked = false;
        frames[n] = ret_addr as usize;
        n += 1;
        prev_fp = cur_fp;
        cur_fp = next_fp as usize;

        // Drain so the pipe buffer cannot fill up and turn probes into blocking writes.
        let mut sink = [0u8; 256];
        unsafe { read(probe_r, sink.as_mut_ptr() as *mut c_void, sink.len()) };
    }

    unsafe {
        close(probe_r);
        close(probe_w);
    }
    n
}

/// Symbolises and prints a set of return addresses.
unsafe fn write_frames(w: &Writer, frames: &[usize]) {
    for (i, &frame) in frames.iter().enumerate() {
        let mut dl: Dl_info = unsafe { core::mem::zeroed() };
        w.s("#");
        w.u64_dec(i as u64);
        w.s(" pc ");
        w.ptr(frame as *const c_void);
        if unsafe { dladdr(frame as *const c_void, &mut dl) } != 0 && !dl.dli_fname.is_null() {
            let lib = unsafe { CStr::from_ptr(dl.dli_fname) }.to_bytes();
            let sym = if dl.dli_sname.is_null() {
                b"<unknown>" as &[u8]
            } else {
                unsafe { CStr::from_ptr(dl.dli_sname) }.to_bytes()
            };
            let offset = frame.wrapping_sub(dl.dli_saddr as usize);
            w.s("  ");
            w.bytes(lib);
            w.s(" (");
            w.bytes(sym);
            w.s("+");
            w.u64_dec(offset as u64);
            w.s(")\n");
        } else {
            w.s("  <unknown>\n");
        }
    }
}

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
    buf[..pad].fill(b'0');
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
        // Must stay byte-compatible with the Java-side naming in `CrashLogsManager`:
        // "<prefix>yyyy-MM-dd_HH-mm-ss-SSS.log". `allCrashLogs` filters on that prefix
        // and suffix, so a report named any other way never surfaces in the logs UI.
        let mut wall: timespec = core::mem::zeroed();
        clock_gettime(CLOCK_REALTIME, &mut wall);
        let now: time_t = wall.tv_sec as time_t;
        let millis = (wall.tv_nsec / 1_000_000) as u64;
        let mut tm_buf: tm = core::mem::zeroed();
        localtime_r(&now, &mut tm_buf);

        let dir_len = (&raw const G_CRASH_LOG_DIR)
            .as_ref()
            .unwrap()
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(0);
        let dir = &G_CRASH_LOG_DIR[..dir_len];

        let prefix_len = (&raw const G_CRASH_LOG_NAME_PREFIX)
            .as_ref()
            .unwrap()
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(0);
        let prefix = &G_CRASH_LOG_NAME_PREFIX[..prefix_len];

        let mut fname = [0u8; 640];
        // dir + '/' + prefix + 23-byte timestamp + ".log" + NUL
        if dir_len + 1 + prefix_len + 23 + 4 + 1 > fname.len() {
            return;
        }
        let mut p = 0usize;

        fname[p..p + dir_len].copy_from_slice(dir);
        p += dir_len;
        fname[p] = b'/';
        p += 1;
        fname[p..p + prefix_len].copy_from_slice(prefix);
        p += prefix_len;
        p += write_padded_u64(&mut fname[p..], (tm_buf.tm_year + 1900) as u64, 4);
        fname[p] = b'-';
        p += 1;
        p += write_padded_u64(&mut fname[p..], (tm_buf.tm_mon + 1) as u64, 2);
        fname[p] = b'-';
        p += 1;
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_mday as u64, 2);
        fname[p] = b'_';
        p += 1;
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_hour as u64, 2);
        fname[p] = b'-';
        p += 1;
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_min as u64, 2);
        fname[p] = b'-';
        p += 1;
        p += write_padded_u64(&mut fname[p..], tm_buf.tm_sec as u64, 2);
        fname[p] = b'-';
        p += 1;
        p += write_padded_u64(&mut fname[p..], millis, 3);
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
                // NB: BionicUcontext, not libc::ucontext_t — see its doc comment.
                let uc = &*(ctx as *const BionicUcontext);
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
                w.s("\n");
                w.s("pstate: ");
                w.hex(mc.pstate, 16);
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
        }

        // ── Stack trace ───────────────────────────────────────────────────────────
        //
        // Two backtraces, because they answer different questions.
        //
        // The faulting one is seeded from the signal context (pc, then lr, then a frame-pointer
        // walk) and is the one that actually says where the process died. The handler one comes
        // from `_Unwind_Backtrace`, which unwinds the *signal handler's own* call stack — it always
        // begins with this library, libsigchain and __kernel_rt_sigreturn, and whether it manages
        // to step across the signal frame at all is luck. Relying on it alone is why earlier
        // reports sometimes carried a single meaningless frame.
        let mut frames = [0usize; MAX_FRAMES];
        // Only the aarch64 branch below writes to this; other targets keep the unwinder-only path.
        #[allow(unused_mut)]
        let mut count = 0usize;

        #[cfg(target_arch = "aarch64")]
        if !ctx.is_null() {
            let uc = &*(ctx as *const BionicUcontext);
            let mc = &uc.uc_mcontext;
            count = capture_fault_backtrace(
                &mut frames,
                mc.pc as usize,
                mc.regs[30] as usize,
                mc.regs[29] as usize,
            );
        }

        if count > 0 {
            w.sep("Stack Trace (faulting context)");
            write_frames(&w, &frames[..count]);
            w.s("\n");
        }

        w.sep("Stack Trace (handler unwind)");
        let count = capture_backtrace(&mut frames);
        write_frames(&w, &frames[..count]);

        // ── Memory maps ───────────────────────────────────────────────────────────
        w.s("\n");
        w.sep("Memory Maps");
        let maps_fd = open(c"/proc/self/maps".as_ptr(), O_RDONLY);
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
///
/// `log_file_name_prefix` comes from `CrashLogsManager` so the reports this handler writes
/// carry the same file names as the Java-side ones.
///
/// Returns `true` on success.
pub fn install_crash_handler(log_dir: &str, log_file_name_prefix: &str) -> bool {
    if HANDLER_INSTALLED.load(Ordering::SeqCst) {
        logi!("native crash handler already installed");
        return true;
    }

    let bytes = log_dir.as_bytes();
    if bytes.is_empty() {
        loge!("invalid crash log directory");
        return false;
    }

    let prefix_bytes = log_file_name_prefix.as_bytes();
    if prefix_bytes.is_empty() {
        loge!("invalid crash log file name prefix");
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

    // Same for the file-name prefix (null-terminated).
    unsafe {
        let len = prefix_bytes
            .len()
            .min((&raw const G_CRASH_LOG_NAME_PREFIX).as_ref().unwrap().len() - 1);
        G_CRASH_LOG_NAME_PREFIX[..len].copy_from_slice(&prefix_bytes[..len]);
        G_CRASH_LOG_NAME_PREFIX[len] = 0;
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
