//! Android/AArch64 function-entry hooks used by LSPlant's initialization.
//!
//! The registry serializes our installers, not execution by arbitrary host threads.
//! Like the former Dobby backend, publication requires an initialization/quiescent
//! context. Neither a short patch nor cache maintenance makes general hot patching
//! atomic. No signals are installed and no host threads are suspended here.
//!
//! Backups (and their branch relays) remain mapped even after unhook. This avoids
//! freeing code which an already-entered call may still be executing.

mod aarch64;
mod memory;

use anyhow::{Context, Result, anyhow, bail};
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};

const VENEER_OFFSET: usize = 512;

struct Hook {
    target: usize,
    backup: usize,
    original: Vec<u8>,
    patch: Vec<u8>,
    _memory: Vec<memory::CodeMemory>,
}

#[derive(Default)]
struct Backend {
    hooks: HashMap<usize, Hook>,
    retired: Vec<Hook>,
}

fn backend() -> &'static Mutex<Backend> {
    static BACKEND: OnceLock<Mutex<Backend>> = OnceLock::new();
    BACKEND.get_or_init(|| Mutex::new(Backend::default()))
}

fn prepare(target: usize, replacement: usize) -> Result<Hook> {
    if target == 0
        || replacement == 0
        || target & 3 != 0
        || replacement & 3 != 0
        || target == replacement
    {
        bail!("invalid inline hook addresses");
    }
    let first = memory::read(target, 4, true)?;
    let first = u32::from_le_bytes(
        first
            .try_into()
            .map_err(|_| anyhow!("invalid instruction"))?,
    );
    // A near veneer usually reduces the live patch to a single B instruction.
    // If no nearby page exists, retain the full absolute-entry fallback.
    let allocation = memory::CodeMemory::allocate(Some(target), false)?;
    let backup = allocation.executable;
    let veneer = backup + VENEER_OFFSET;
    let patch = aarch64::entry_patch(target as u64, veneer as u64, aarch64::landing_pad(first))
        .map_err(|error| anyhow!("entry encoding: {error:?}"))?;
    let original = memory::read(target, patch.len(), true)?;
    if original[..4] != first.to_le_bytes() {
        bail!("target changed while selecting its entry patch");
    }
    let words: Vec<_> = original
        .chunks_exact(4)
        .map(|bytes| u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
        .collect();
    let mut allocations = Vec::new();
    let mut relays = HashMap::new();
    let mut code = aarch64::relocate(
        &words,
        target as u64,
        backup as u64,
        |destination| {
            if let Some(&address) = relays.get(&destination) {
                return Ok(address);
            }
            let relay = memory::CodeMemory::allocate(Some(destination as usize), true)
                .map_err(|error| aarch64::Error::Memory(format!("{error:#}")))?;
            let address = relay.executable as u64;
            let bytes = aarch64::relay_code(address, destination)?;
            relay
                .publish(&bytes)
                .map_err(|error| aarch64::Error::Memory(format!("{error:#}")))?;
            allocations.push(relay);
            relays.insert(destination, address);
            Ok(address)
        },
        |address, size| {
            memory::read(address as usize, size, false)
                .map_err(|error| aarch64::Error::Memory(format!("{error:#}")))
        },
    )
    .map_err(|error| anyhow!("instruction relocation: {error:?}"))?;
    if code.len() > VENEER_OFFSET {
        bail!("backup overlaps entry veneer");
    }
    code.resize(VENEER_OFFSET, 0);
    code.extend(
        aarch64::entry_patch(veneer as u64, replacement as u64, false)
            .map_err(|error| anyhow!("veneer encoding: {error:?}"))?,
    );
    allocation.publish(&code)?;
    allocations.push(allocation);
    Ok(Hook {
        target,
        backup,
        original,
        patch,
        _memory: allocations,
    })
}

/// An Err always means that the target was not changed. Once written, permission
/// restoration errors are logged while retaining the live backup/record. Returning
/// a null backup for an already-active hook would leave LSPlant in an invalid state.
fn replace_code(target: usize, expected: &[u8], replacement: &[u8]) -> Result<()> {
    if expected.len() != replacement.len() || target & 3 != 0 || expected.len() % 4 != 0 {
        bail!("invalid entry patch");
    }
    let mut access = memory::Access::acquire(
        target,
        expected.len(),
        libc::PROT_READ | libc::PROT_WRITE,
        true,
    )?;
    let current = unsafe { std::slice::from_raw_parts(target as *const u8, expected.len()) };
    if current != expected {
        bail!("target changed since preparation; refusing to overwrite another hook");
    }
    // Finish the tail before redirecting the first instruction. This reduces the
    // publication window but is NOT a guarantee for concurrent instruction fetch.
    for (index, bytes) in replacement.chunks_exact(4).enumerate().rev() {
        let word = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        unsafe {
            ((target + index * 4) as *mut u32).write_volatile(word);
        }
    }
    memory::synchronize(target, target, replacement.len());
    if let Err(error) = access.restore() {
        crate::loge!(
            "Zygisk: inline patch at {target:#x} applied, permission restore failed: {error:#}"
        );
    }
    Ok(())
}

fn install(target: usize, replacement: usize) -> Result<usize> {
    let mut backend = backend()
        .lock()
        .map_err(|_| anyhow!("inline hook registry poisoned"))?;
    if backend.hooks.contains_key(&target) {
        bail!("address already hooked");
    }
    let hook = prepare(target, replacement)?;
    let backup = hook.backup;
    // Allocate the registry entry before publishing live code.
    backend
        .hooks
        .try_reserve(1)
        .context("reserve inline hook record")?;
    replace_code(target, &hook.original, &hook.patch)?;
    backend.hooks.insert(target, hook);
    Ok(backup)
}

fn remove(target: usize) -> Result<()> {
    let mut backend = backend()
        .lock()
        .map_err(|_| anyhow!("inline hook registry poisoned"))?;
    backend
        .retired
        .try_reserve(1)
        .context("reserve retired hook record")?;
    let hook = backend
        .hooks
        .get(&target)
        .context("address is not hooked")?;
    replace_code(hook.target, &hook.patch, &hook.original)?;
    if let Some(hook) = backend.hooks.remove(&target) {
        backend.retired.push(hook);
    }
    Ok(())
}

pub unsafe extern "C" fn hook(target: *mut c_void, replacement: *mut c_void) -> *mut c_void {
    match install(target as usize, replacement as usize) {
        Ok(backup) => backup as *mut c_void,
        Err(error) => {
            crate::loge!("Zygisk: inline hook {target:p} failed: {error:#}");
            std::ptr::null_mut()
        }
    }
}

pub unsafe extern "C" fn unhook(target: *mut c_void) -> bool {
    match remove(target as usize) {
        Ok(()) => true,
        Err(error) => {
            crate::loge!("Zygisk: inline unhook {target:p} failed: {error:#}");
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn install_restore_rehook_and_refuse_foreign_patch() {
        // Exercise ownership and actual memory publication on the host, without
        // executing the A64 bytes. Instruction execution is tested with Unicorn.
        let source = memory::CodeMemory::allocate(None, false).unwrap();
        source
            .publish(&aarch64::words_bytes(&[aarch64::NOP; 8]))
            .unwrap();
        let target = source.executable;
        let original = memory::read(target, 20, true).unwrap();
        let backup = install(target, target + 128).unwrap();
        assert!(install(target, target + 256).is_err());
        assert_ne!(memory::read(target, 4, true).unwrap(), original[..4]);
        remove(target).unwrap();
        assert_eq!(memory::read(target, 20, true).unwrap(), original);
        assert!(memory::read(backup, 4, true).is_ok());
        assert!(remove(target).is_err());
        let next = install(target, target + 256).unwrap();
        assert_ne!(next, backup);
        let current = memory::read(target, 4, true).unwrap();
        replace_code(target, &current, &aarch64::words_bytes(&[aarch64::NOP])).unwrap();
        assert!(remove(target).is_err());
        replace_code(target, &aarch64::words_bytes(&[aarch64::NOP]), &current).unwrap();
        remove(target).unwrap();
    }

    #[test]
    fn unsupported_prologue_leaves_target_unchanged() {
        let source = memory::CodeMemory::allocate(None, false).unwrap();
        source.publish(&[0; 32]).unwrap();
        assert!(install(source.executable, source.executable + 128).is_err());
        assert_eq!(memory::read(source.executable, 32, true).unwrap(), [0; 32]);
    }
}
