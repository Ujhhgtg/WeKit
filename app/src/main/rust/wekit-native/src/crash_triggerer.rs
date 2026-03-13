use crate::shared::G_CRASH_LOG_DIR;
use std::sync::atomic::{AtomicBool, Ordering};

use libc::abort;

use crate::{loge, logi};

pub static HANDLER_INSTALLED: AtomicBool = AtomicBool::new(false);
pub static IS_HANDLING_CRASH: AtomicBool = AtomicBool::new(false);

/// Trigger a deliberate crash for testing.
/// `crash_type` mirrors the values used by the Java-side API.
pub fn trigger_test_crash(crash_type: i32) {
    logi!("========================================");
    logi!("Triggering test crash: type={}", crash_type);
    logi!(
        "Handler installed: {}",
        HANDLER_INSTALLED.load(Ordering::SeqCst)
    );
    unsafe {
        logi!(
            "Crash log dir: {}",
            core::str::from_utf8_unchecked(
                &G_CRASH_LOG_DIR[..(&raw const G_CRASH_LOG_DIR)
                    .as_ref()
                    .unwrap()
                    .iter()
                    .position(|&b| b == 0)
                    .unwrap_or(0)]
            )
        );
    }
    logi!("========================================");

    match crash_type {
        0 => {
            logi!("Triggering SIGSEGV (null pointer dereference)…");
            let p: *mut i32 = core::ptr::null_mut();
            unsafe { core::ptr::write_volatile(p, 42) };
        }
        1 => {
            logi!("Triggering SIGABRT (abort)…");
            unsafe { abort() };
        }
        2 => {
            logi!("Triggering SIGFPE (division by zero)…");
            unsafe {
                let zero: i32 = core::ptr::read_volatile(&0i32);
                let _r = core::ptr::read_volatile(&42i32) / zero;
            }
        }
        3 => {
            logi!("Triggering SIGILL (illegal instruction)…");
            unsafe {
                #[cfg(any(target_arch = "aarch64", target_arch = "arm"))]
                core::arch::asm!(".word 0xf7f0a000");
                #[cfg(target_arch = "x86_64")]
                core::arch::asm!("ud2");
            }
        }
        4 => {
            logi!("Triggering SIGBUS (unaligned write)…");
            let mut buf = [0u8; 16];
            unsafe {
                let unaligned = buf.as_mut_ptr().add(1) as *mut u64;
                core::ptr::write_volatile(unaligned, 0x1234_5678_90AB_CDEF);
            }
        }
        other => loge!("Unknown crash type: {}", other),
    }

    loge!("Test crash did not occur — this should not happen.");
}
