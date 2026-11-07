// so_hider.rs — /proc/self/maps parsing + memfd/mprotect/MAP_FIXED remapping

use crate::{loge, logi};
use libc::c_int;

#[derive(Debug)]
pub struct MapEntry {
    pub start: usize,
    pub end: usize,
    pub prot: c_int,
    pub path: String,
}

/// Parse one line of /proc/self/maps.
/// Returns None for anonymous or pseudo-file entries (empty path or path starting with '[').
pub fn parse_maps_line(line: &[u8]) -> Option<MapEntry> {
    let s = std::str::from_utf8(line).ok()?;
    let mut parts = s.splitn(6, ' ');
    let range = parts.next()?;
    let perms = parts.next()?;
    let _offset = parts.next()?;
    let _dev = parts.next()?;
    let _inode = parts.next()?;
    let path = parts.next().unwrap_or("").trim();
    if path.is_empty() || path.starts_with('[') {
        return None;
    }
    let (start_s, end_s) = range.split_once('-')?;
    let start = usize::from_str_radix(start_s, 16).ok()?;
    let end = usize::from_str_radix(end_s, 16).ok()?;
    let mut prot = 0i32;
    if perms.contains('r') {
        prot |= libc::PROT_READ;
    }
    if perms.contains('w') {
        prot |= libc::PROT_WRITE;
    }
    if perms.contains('x') {
        prot |= libc::PROT_EXEC;
    }
    Some(MapEntry {
        start,
        end,
        prot,
        path: path.to_owned(),
    })
}

fn collect_matching_entries(needle: &str) -> Vec<MapEntry> {
    // SAFETY: standard POSIX open/read/close on /proc/self/maps
    let fd = unsafe { libc::open(c"/proc/self/maps".as_ptr(), libc::O_RDONLY) };
    if fd < 0 {
        return Vec::new();
    }
    let mut bytes = Vec::with_capacity(65536);
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n <= 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe { libc::close(fd) };
    // Collect ALL matching entries first (close maps fd), THEN remap — matches C++ order
    bytes
        .split(|&b| b == b'\n')
        .filter_map(parse_maps_line)
        .filter(|e| e.path.contains(needle))
        .collect()
}

// SAFETY: All raw pointer/syscall operations are contained in this function.
unsafe fn remap_segment(start: usize, len: usize, orig_prot: c_int) -> bool {
    if orig_prot == libc::PROT_NONE {
        return true;
    }
    // Temporarily add PROT_READ so we can copy contents; track whether we changed it
    let read_prot_changed = orig_prot & libc::PROT_READ == 0;
    if read_prot_changed && libc::mprotect(start as *mut _, len, orig_prot | libc::PROT_READ) != 0 {
        return false;
    }
    // If memfd_create fails, restore original protection and return
    let mfd = libc::syscall(
        libc::SYS_memfd_create,
        c"wk".as_ptr(),
        libc::MFD_CLOEXEC as libc::c_ulong,
    ) as c_int;
    if mfd < 0 {
        if read_prot_changed {
            libc::mprotect(start as *mut _, len, orig_prot);
        }
        return false;
    }
    if libc::ftruncate(mfd, len as libc::off_t) < 0 {
        libc::close(mfd);
        if read_prot_changed {
            libc::mprotect(start as *mut _, len, orig_prot);
        }
        return false;
    }
    // Copy segment contents into the memfd; fail + cleanup if write is incomplete
    let mut written = 0usize;
    while written < len {
        let n = libc::write(mfd, (start + written) as *const _, len - written);
        if n <= 0 {
            loge!("SoHider: remap: write to memfd failed");
            libc::close(mfd);
            if read_prot_changed {
                libc::mprotect(start as *mut _, len, orig_prot);
            }
            return false;
        }
        written += n as usize;
    }
    // Restore protection if we temporarily changed it
    if orig_prot & libc::PROT_READ == 0 {
        libc::mprotect(start as *mut _, len, orig_prot);
    }
    // Remap over the original address
    let ok = if orig_prot & libc::PROT_EXEC != 0 {
        // Executable: map with final prot directly to avoid non-exec window
        let addr = libc::mmap(
            start as *mut _,
            len,
            orig_prot,
            libc::MAP_PRIVATE | libc::MAP_FIXED,
            mfd,
            0,
        );
        addr != libc::MAP_FAILED
    } else {
        let addr = libc::mmap(
            start as *mut _,
            len,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_FIXED,
            mfd,
            0,
        );
        if addr == libc::MAP_FAILED {
            libc::close(mfd);
            return false;
        }
        if orig_prot != (libc::PROT_READ | libc::PROT_WRITE) {
            libc::mprotect(addr, len, orig_prot);
        }
        true
    };
    libc::close(mfd);
    ok
}

/// Remap all segments whose path contains `needle`.
/// Returns number of segments remapped, or -1 on fatal error.
/// Order: collect ALL entries first (closes /proc/self/maps fd), then remap.
pub fn hide_path(needle: &str) -> i32 {
    let entries = collect_matching_entries(needle);
    if entries.is_empty() {
        return 0;
    }
    let mut count = 0i32;
    for e in &entries {
        let len = e.end - e.start;
        if unsafe { remap_segment(e.start, len, e.prot) } {
            count += 1;
        } else {
            loge!("Zygisk: remap_segment failed for {}", e.path);
        }
    }
    logi!("Zygisk: hide_path({needle}) remapped {count} segments");
    count
}

// ── Host unit tests ───────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_normal_line() {
        let line = b"7f4a000000-7f4a001000 r-xp 00000000 fd:00 12345 /system/lib64/libwekit.so";
        let entry = parse_maps_line(line).expect("should parse");
        assert_eq!(entry.start, 0x7f4a000000);
        assert_eq!(entry.end, 0x7f4a001000);
        assert!(entry.prot & libc::PROT_READ != 0);
        assert!(entry.prot & libc::PROT_EXEC != 0);
        assert_eq!(entry.path, "/system/lib64/libwekit.so");
    }

    #[test]
    fn skip_anonymous_no_path() {
        let line = b"7f00000000-7f00001000 rw-p 00000000 00:00 0";
        assert!(parse_maps_line(line).is_none());
    }

    #[test]
    fn skip_pseudo_bracket() {
        let line = b"7f00000000-7f00001000 r--p 00000000 00:00 0 [vvar]";
        assert!(parse_maps_line(line).is_none());
    }

    #[test]
    fn needle_match() {
        let entry = MapEntry {
            start: 0x1000,
            end: 0x2000,
            prot: libc::PROT_READ,
            path: "/data/app/libwekit_native.so".to_string(),
        };
        assert!(entry.path.contains("libwekit_native.so"));
        assert!(!entry.path.contains("libwekit.so"));
    }

    #[test]
    fn parse_write_only_segment() {
        let line = b"aabbcc000-aabbdd000 -w-- 00000000 00:05 99 /dev/shm/something";
        let entry = parse_maps_line(line).expect("should parse");
        assert_eq!(entry.prot & libc::PROT_READ, 0);
        assert!(entry.prot & libc::PROT_WRITE != 0);
    }
}
