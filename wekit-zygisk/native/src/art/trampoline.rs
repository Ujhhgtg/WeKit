// art/trampoline.rs — executable trampoline pool
//
// Allocates small machine-code stubs used to redirect ART method dispatch.
// Uses a dual-mapped `memfd`: one `PROT_READ|PROT_WRITE` alias for writing the
// stub bytes and one `PROT_READ|PROT_EXEC` alias for executing them.  This
// avoids any `mprotect(PROT_EXEC)` call, which is blocked by SELinux on
// modern Android. Supports an arm64 20-byte stub.

use crate::loge;
use libc::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};

const POOL_SIZE: usize = 1024 * 1024; // 1 MB
const TRAMPOLINE_STRIDE: usize = 32;

pub struct TrampolinePool {
    writable: *mut u8,     // PROT_READ|PROT_WRITE, MAP_SHARED
    executable: *const u8, // PROT_READ|PROT_EXEC, MAP_SHARED
    next_slot: AtomicUsize,
}

// SAFETY: the dual-mapped memfd makes both pointers safe to use from any thread
unsafe impl Send for TrampolinePool {}
unsafe impl Sync for TrampolinePool {}

impl TrampolinePool {
    pub fn new() -> Option<Self> {
        unsafe {
            let mfd = libc::syscall(
                libc::SYS_memfd_create,
                c"jit-cache".as_ptr(),
                libc::MFD_CLOEXEC as libc::c_ulong,
            ) as c_int;
            if mfd < 0 {
                loge!("Zygisk: memfd_create failed");
                return None;
            }
            if libc::ftruncate(mfd, POOL_SIZE as libc::off_t) < 0 {
                libc::close(mfd);
                return None;
            }
            let writable = libc::mmap(
                std::ptr::null_mut(),
                POOL_SIZE,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                mfd,
                0,
            );
            let executable = libc::mmap(
                std::ptr::null_mut(),
                POOL_SIZE,
                libc::PROT_READ | libc::PROT_EXEC,
                libc::MAP_SHARED,
                mfd,
                0,
            );
            libc::close(mfd);
            if writable == libc::MAP_FAILED || executable == libc::MAP_FAILED {
                if writable != libc::MAP_FAILED {
                    libc::munmap(writable, POOL_SIZE);
                }
                if executable != libc::MAP_FAILED {
                    libc::munmap(executable as *mut _, POOL_SIZE);
                }
                return None;
            }
            Some(TrampolinePool {
                writable: writable as *mut u8,
                executable: executable as *const u8,
                next_slot: AtomicUsize::new(0),
            })
        }
    }

    /// Allocate one trampoline slot and return its executable address.
    pub fn allocate(&self, bridge_art_method: usize, entry_point_offset: usize) -> *const u8 {
        let slot = self
            .next_slot
            .fetch_add(TRAMPOLINE_STRIDE, Ordering::Relaxed);
        if slot + TRAMPOLINE_STRIDE > POOL_SIZE {
            loge!("Zygisk: trampoline pool exhausted");
            return std::ptr::null();
        }
        let w = unsafe { self.writable.add(slot) };
        let exec = unsafe { self.executable.add(slot) };
        // SAFETY: we write through writable alias, execute through executable alias
        unsafe {
            write_trampoline(w, bridge_art_method, entry_point_offset);
            flush_icache(exec, exec.add(TRAMPOLINE_STRIDE));
        }
        exec
    }
}

// ── arm64 trampoline (20 bytes, padded to 32) ─────────────────────────────────
// ldr x0, #12          ; load bridge_art_method (8-byte literal at +12)
// ldur x16, [x0, #ep]  ; load quick entry point from bridge ArtMethod
// br x16
// nop (4 bytes padding)
// .8byte bridge_art_method

#[cfg(target_arch = "aarch64")]
unsafe fn write_trampoline(dst: *mut u8, bridge_art_method: usize, ep_offset: usize) {
    let ldur_x16 = arm64_ldur_x16_from_x0(ep_offset);
    let instr: [u32; 3] = [
        0x5800_0060, // ldr x0, #12
        ldur_x16,    // ldur x16, [x0, #ep_offset]
        0xD61F_0200, // br x16
    ];
    (dst as *mut [u32; 3]).write_unaligned(instr);
    let ptr_off = dst.add(12) as *mut usize;
    ptr_off.write_unaligned(bridge_art_method);
}

#[cfg(any(target_arch = "aarch64", test))]
fn arm64_ldur_x16_from_x0(ep_offset: usize) -> u32 {
    let ep = (ep_offset & 0x1ff) as u32;
    // LDUR Xt, [Xn, #imm9]: Xn is encoded in bits 9:5 and Xt in bits 4:0.
    0xF840_0010u32 | (ep << 12)
}

#[cfg(not(target_arch = "aarch64"))]
unsafe fn write_trampoline(_dst: *mut u8, _bridge: usize, _ep: usize) {}

#[cfg(target_arch = "aarch64")]
unsafe fn flush_icache(start: *const u8, end: *const u8) {
    use core::arch::asm;

    let start = start as usize;
    let end = end as usize;
    if start >= end {
        return;
    }

    let ctr: usize;
    unsafe {
        asm!("mrs {ctr}, ctr_el0", ctr = out(reg) ctr, options(nomem, nostack, preserves_flags));
    }

    // Clean data-cache lines to the point of unification when IDC is absent.
    if ctr & (1 << 28) == 0 {
        let line_size = 4usize << ((ctr >> 16) & 0xf);
        let mut cursor = start & !(line_size - 1);
        while cursor < end {
            unsafe {
                asm!("dc cvau, {addr}", addr = in(reg) cursor, options(nostack, preserves_flags));
            }
            cursor += line_size;
        }
    }
    unsafe {
        asm!("dsb ish", options(nostack, preserves_flags));
    }

    // Invalidate instruction-cache lines to the point of unification when DIC is absent.
    if ctr & (1 << 29) == 0 {
        let line_size = 4usize << (ctr & 0xf);
        let mut cursor = start & !(line_size - 1);
        while cursor < end {
            unsafe {
                asm!("ic ivau, {addr}", addr = in(reg) cursor, options(nostack, preserves_flags));
            }
            cursor += line_size;
        }
    }
    unsafe {
        asm!("dsb ish", "isb", options(nostack, preserves_flags));
    }
}

#[cfg(not(target_arch = "aarch64"))]
unsafe fn flush_icache(_start: *const u8, _end: *const u8) {}

#[cfg(test)]
mod tests {
    use super::arm64_ldur_x16_from_x0;

    #[test]
    fn arm64_ldur_reads_bridge_entry_from_x0() {
        assert_eq!(arm64_ldur_x16_from_x0(24), 0xF841_8010);
        assert_eq!((arm64_ldur_x16_from_x0(24) >> 5) & 0x1f, 0);
        assert_eq!(arm64_ldur_x16_from_x0(24) & 0x1f, 16);
    }
}
