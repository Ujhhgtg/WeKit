//! Checked page access and dual-mapped executable allocations.
use anyhow::{Context, Result, bail};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

const PROT_BTI: i32 = 0x10;
const PROT_MTE: i32 = 0x20;
const MAP_FIXED_NOREPLACE: i32 = 0x100000;
const BRANCH_REACH: usize = (1 << 27) - 4096;

#[derive(Clone, Debug)]
pub struct Region {
    pub start: usize,
    pub end: usize,
    pub protection: i32,
}

pub fn page_size() -> Result<usize> {
    let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if page <= 0 || !(page as usize).is_power_of_two() {
        bail!("invalid page size {page}");
    }
    Ok(page as usize)
}

pub fn mappings() -> Result<Vec<Region>> {
    // maps omits BTI/MTE attributes. Preserve them when changing permissions on
    // existing mappings, rather than silently disabling guarded pages.
    let bytes = std::fs::read("/proc/self/smaps").context("read process mappings")?;
    let text = String::from_utf8_lossy(&bytes);
    parse_mappings(&text)
}

fn parse_mappings(text: &str) -> Result<Vec<Region>> {
    let mut regions: Vec<Region> = Vec::new();
    for line in text.lines() {
        if let Some(flags) = line.strip_prefix("VmFlags:") {
            if let Some(region) = regions.last_mut() {
                for flag in flags.split_whitespace() {
                    region.protection |= match flag {
                        "bt" => PROT_BTI,
                        "mt" => PROT_MTE,
                        _ => 0,
                    };
                }
            }
            continue;
        }
        let mut fields = line.split_whitespace();
        let Some((start, end)) = fields.next().and_then(|field| field.split_once('-')) else {
            continue;
        };
        let (Ok(start), Ok(end)) = (
            usize::from_str_radix(start, 16),
            usize::from_str_radix(end, 16),
        ) else {
            continue;
        };
        let perms = fields
            .next()
            .context("missing mapping permissions")?
            .as_bytes();
        if perms.len() != 4 || start >= end {
            bail!("invalid process mapping");
        }
        let protection = if perms[0] == b'r' { libc::PROT_READ } else { 0 }
            | if perms[1] == b'w' {
                libc::PROT_WRITE
            } else {
                0
            }
            | if perms[2] == b'x' { libc::PROT_EXEC } else { 0 };
        regions.push(Region {
            start,
            end,
            protection,
        });
    }
    if regions.is_empty() {
        bail!("no process mappings");
    }
    Ok(regions)
}

pub struct Access {
    regions: Vec<Region>,
    changed: usize,
}

impl Access {
    pub fn acquire(address: usize, size: usize, extra: i32, executable: bool) -> Result<Self> {
        let page = page_size()?;
        let end = address.checked_add(size).context("memory range overflow")?;
        if size == 0 || address == 0 {
            bail!("empty memory range");
        }
        let mut cursor = address;
        let mut selected = Vec::new();
        for region in mappings()? {
            if region.end <= cursor {
                continue;
            }
            if region.start > cursor {
                break;
            }
            if executable && region.protection & libc::PROT_EXEC == 0 {
                bail!("target is not executable");
            }
            let limit = region.end.min(end);
            selected.push(Region {
                start: cursor & !(page - 1),
                end: limit.checked_add(page - 1).context("page overflow")? & !(page - 1),
                protection: region.protection,
            });
            cursor = limit;
            if cursor == end {
                break;
            }
        }
        if cursor != end {
            bail!("unmapped memory at {cursor:#x}");
        }
        let mut access = Self {
            regions: selected,
            changed: 0,
        };
        for region in &access.regions {
            if unsafe {
                libc::mprotect(
                    region.start as *mut _,
                    region.end - region.start,
                    region.protection | extra,
                )
            } != 0
            {
                let error = std::io::Error::last_os_error();
                access.restore()?;
                return Err(error).context("change code page permissions");
            }
            access.changed += 1;
        }
        Ok(access)
    }

    pub fn restore(&mut self) -> Result<()> {
        // Retain the failed region for a retry by Drop/the caller.
        while self.changed != 0 {
            let region = &self.regions[self.changed - 1];
            if unsafe {
                libc::mprotect(
                    region.start as *mut _,
                    region.end - region.start,
                    region.protection,
                )
            } != 0
            {
                return Err(std::io::Error::last_os_error())
                    .context("restore code page permissions");
            }
            self.changed -= 1;
        }
        Ok(())
    }
}

impl Drop for Access {
    fn drop(&mut self) {
        if let Err(error) = self.restore() {
            crate::loge!("Zygisk: {error:#}");
        }
    }
}

pub fn read(address: usize, size: usize, executable: bool) -> Result<Vec<u8>> {
    let mut access = Access::acquire(address, size, libc::PROT_READ, executable)?;
    let data = unsafe { std::slice::from_raw_parts(address as *const u8, size) }.to_vec();
    access.restore()?;
    Ok(data)
}

pub struct CodeMemory {
    writable: usize,
    pub executable: usize,
    size: usize,
}

impl CodeMemory {
    pub fn allocate(near: Option<usize>, required: bool) -> Result<Self> {
        let size = page_size()?;
        let raw_fd = unsafe {
            libc::syscall(
                libc::SYS_memfd_create,
                c"wekit-inline-hook".as_ptr(),
                libc::MFD_CLOEXEC,
            )
        };
        if raw_fd < 0 {
            return Err(std::io::Error::last_os_error()).context("memfd_create");
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw_fd as i32) };
        if unsafe { libc::ftruncate(fd.as_raw_fd(), size as libc::off_t) } != 0 {
            return Err(std::io::Error::last_os_error()).context("size executable mapping");
        }
        let writable = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                size,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                fd.as_raw_fd(),
                0,
            )
        };
        if writable == libc::MAP_FAILED {
            return Err(std::io::Error::last_os_error()).context("map writable alias");
        }
        let rx = |hint: usize, fixed: bool| unsafe {
            libc::mmap(
                hint as *mut _,
                size,
                libc::PROT_READ | libc::PROT_EXEC,
                libc::MAP_SHARED | if fixed { MAP_FIXED_NOREPLACE } else { 0 },
                fd.as_raw_fd(),
                0,
            )
        };
        let allocate_rx = || -> Result<usize> {
            if let Some(near) = near {
                let fits = |address: usize| address.abs_diff(near) < BRANCH_REACH;
                let hint = near & !(size - 1);
                let p = rx(hint, false);
                if p != libc::MAP_FAILED {
                    if fits(p as usize) {
                        return Ok(p as usize);
                    }
                    unsafe {
                        libc::munmap(p, size);
                    }
                }
                let regions = mappings()?;
                let low = near.saturating_sub(BRANCH_REACH).max(size);
                let high = near.saturating_add(BRANCH_REACH);
                let mut candidates = Vec::new();
                for pair in regions.windows(2) {
                    let start = pair[0].end.max(low).saturating_add(size - 1) & !(size - 1);
                    let end = pair[1].start.min(high) & !(size - 1);
                    if end.saturating_sub(start) >= size {
                        candidates.push(hint.clamp(start, end - size));
                    }
                }
                candidates.sort_unstable_by_key(|address| address.abs_diff(near));
                for address in candidates {
                    let p = rx(address, true);
                    if p != libc::MAP_FAILED {
                        // Old kernels may ignore MAP_FIXED_NOREPLACE. Never use
                        // MAP_FIXED and never overwrite an existing mapping.
                        if fits(p as usize) {
                            return Ok(p as usize);
                        }
                        unsafe {
                            libc::munmap(p, size);
                        }
                    }
                }
            }
            if required {
                bail!("no executable memory within branch reach");
            }
            let p = rx(0, false);
            if p == libc::MAP_FAILED {
                return Err(std::io::Error::last_os_error()).context("map executable alias");
            }
            Ok(p as usize)
        };
        match allocate_rx() {
            Ok(executable) => Ok(Self {
                writable: writable as usize,
                executable,
                size,
            }),
            Err(error) => {
                unsafe {
                    libc::munmap(writable, size);
                }
                Err(error)
            }
        }
    }

    pub fn publish(&self, code: &[u8]) -> Result<()> {
        if code.len() > self.size {
            bail!("generated code exceeds allocation");
        }
        unsafe {
            std::ptr::copy_nonoverlapping(code.as_ptr(), self.writable as *mut u8, code.len());
        }
        synchronize(self.writable, self.executable, code.len());
        if unsafe { libc::mprotect(self.writable as *mut _, self.size, libc::PROT_READ) } != 0 {
            return Err(std::io::Error::last_os_error()).context("seal writable code alias");
        }
        Ok(())
    }
}

impl Drop for CodeMemory {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.writable as *mut _, self.size);
            libc::munmap(self.executable as *mut _, self.size);
        }
    }
}

pub fn synchronize(writable: usize, executable: usize, size: usize) {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        use core::arch::asm;
        let ctr: usize;
        asm!("mrs {ctr}, ctr_el0", ctr = out(reg) ctr, options(nomem, nostack, preserves_flags));
        if ctr & (1 << 28) == 0 {
            let line = 4 << ((ctr >> 16) & 15);
            let mut address = writable & !(line - 1);
            while address < writable + size {
                asm!("dc cvau, {addr}", addr = in(reg) address, options(nostack, preserves_flags));
                address += line;
            }
        }
        asm!("dsb ish", options(nostack, preserves_flags));
        if ctr & (1 << 29) == 0 {
            let line = 4 << (ctr & 15);
            let mut address = executable & !(line - 1);
            while address < executable + size {
                asm!("ic ivau, {addr}", addr = in(reg) address, options(nostack, preserves_flags));
                address += line;
            }
        }
        asm!("dsb ish", "isb", options(nostack, preserves_flags));
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        let _ = (writable, executable, size);
        std::sync::atomic::fence(std::sync::atomic::Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_guarded_mapping_attributes() {
        let maps = parse_mappings("1000-2000 --xp 0000 00:00 0 /libart.so\nVmFlags: ex bt\n2000-3000 r--p 0000 00:00 0\nVmFlags: rd mt\n").unwrap();
        assert_eq!(maps[0].protection, libc::PROT_EXEC | PROT_BTI);
        assert_eq!(maps[1].protection, libc::PROT_READ | PROT_MTE);
    }

    #[test]
    fn aliases_share_bytes_and_permissions_are_restored_across_pages() {
        let memory = CodeMemory::allocate(None, false).unwrap();
        memory.publish(&[1, 2, 3, 4]).unwrap();
        assert_eq!(read(memory.executable, 4, true).unwrap(), [1, 2, 3, 4]);
        let page = page_size().unwrap();
        let p = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                page * 2,
                libc::PROT_READ,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        assert_ne!(p, libc::MAP_FAILED);
        let address = p as usize;
        let mut access = Access::acquire(address + page - 4, 8, libc::PROT_WRITE, false).unwrap();
        unsafe {
            std::ptr::write_bytes((address + page - 4) as *mut u8, 0x42, 8);
        }
        access.restore().unwrap();
        let regions = mappings().unwrap();
        for addr in [address, address + page] {
            assert_eq!(
                regions
                    .iter()
                    .find(|r| r.start <= addr && addr < r.end)
                    .unwrap()
                    .protection,
                libc::PROT_READ
            );
        }
        unsafe {
            libc::munmap(p, page * 2);
        }
    }
}
