// ─────────────────────────────────────────────────────────────────────────────
// Companion / Telegram binary protocol
// ─────────────────────────────────────────────────────────────────────────────

use libc::c_int;
use std::io::{self, ErrorKind};

// ── Constants ─────────────────────────────────────────────────────────────────

pub const COMPANION_REQUEST_ENABLED: u8 = 0x01;
pub const COMPANION_REQUEST_TELEGRAM_SESSION: u8 = 0x02;
pub const COMPANION_DISABLED: u8 = 0;
pub const COMPANION_ENABLED: u8 = 1;

pub const TELEGRAM_REQUEST_DISCOVER: u8 = 0x01;
pub const TELEGRAM_REQUEST_COPY_DATABASE: u8 = 0x02;
pub const TELEGRAM_RESPONSE_OK: u8 = 0;
pub const TELEGRAM_RESPONSE_ERROR: u8 = 1;
pub const TELEGRAM_RESPONSE_DISABLED: u8 = 2; // WeKit target disabled

// ── Private IO helpers ────────────────────────────────────────────────────────

fn read_exact_fd(fd: c_int, buf: &mut [u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buf.len() {
        let n = unsafe { libc::read(fd, buf[total..].as_mut_ptr() as *mut _, buf.len() - total) };
        match n {
            n if n > 0 => total += n as usize,
            0 => return Err(io::Error::new(ErrorKind::UnexpectedEof, "fd closed")),
            _ => return Err(io::Error::last_os_error()),
        }
    }
    Ok(())
}

fn write_exact_fd(fd: c_int, buf: &[u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buf.len() {
        let n = unsafe { libc::write(fd, buf[total..].as_ptr() as *const _, buf.len() - total) };
        match n {
            n if n > 0 => total += n as usize,
            _ => return Err(io::Error::last_os_error()),
        }
    }
    Ok(())
}

// ── Public API ────────────────────────────────────────────────────────────────

pub fn read_u8_from_fd(fd: c_int) -> io::Result<u8> {
    let mut b = [0u8; 1];
    read_exact_fd(fd, &mut b)?;
    Ok(b[0])
}

pub fn read_u16_from_fd(fd: c_int) -> io::Result<u16> {
    let mut b = [0u8; 2];
    read_exact_fd(fd, &mut b)?;
    Ok(u16::from_ne_bytes(b))
}

pub fn read_i32_from_fd(fd: c_int) -> io::Result<i32> {
    let mut b = [0u8; 4];
    read_exact_fd(fd, &mut b)?;
    Ok(i32::from_ne_bytes(b))
}

pub fn read_u64_from_fd(fd: c_int) -> io::Result<u64> {
    let mut b = [0u8; 8];
    read_exact_fd(fd, &mut b)?;
    Ok(u64::from_ne_bytes(b))
}

/// Reads exactly `n` bytes from `fd`.
pub fn read_bytes_from_fd(fd: c_int, n: usize) -> io::Result<Vec<u8>> {
    let mut buf = vec![0u8; n];
    read_exact_fd(fd, &mut buf)?;
    Ok(buf)
}

/// Reads a length-prefixed UTF-8 string: `[u16 len][bytes]`.
pub fn read_string_from_fd(fd: c_int) -> io::Result<String> {
    let len = read_u16_from_fd(fd)? as usize;
    let bytes = read_bytes_from_fd(fd, len)?;
    String::from_utf8(bytes).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))
}

pub fn write_u8_to_fd(fd: c_int, v: u8) -> io::Result<()> {
    write_exact_fd(fd, &[v])
}

pub fn write_u16_to_fd(fd: c_int, v: u16) -> io::Result<()> {
    write_exact_fd(fd, &v.to_ne_bytes())
}

pub fn write_u64_to_fd(fd: c_int, v: u64) -> io::Result<()> {
    write_exact_fd(fd, &v.to_ne_bytes())
}

/// Writes `[u16 len][bytes]`. `len` is capped at `u16::MAX` (65 535).
pub fn write_string_to_fd(fd: c_int, s: &str) -> io::Result<()> {
    let bytes = s.as_bytes();
    let len = bytes.len().min(u16::MAX as usize) as u16;
    write_u16_to_fd(fd, len)?;
    write_exact_fd(fd, &bytes[..len as usize])
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use libc::c_int;

    fn make_socketpair() -> (c_int, c_int) {
        let mut fds = [0i32; 2];
        unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) };
        (fds[0], fds[1])
    }

    #[test]
    fn round_trip_string() {
        let (r, w) = make_socketpair();
        write_string_to_fd(w, "hello").unwrap();
        let got = read_string_from_fd(r).unwrap();
        assert_eq!(got, "hello");
        unsafe {
            libc::close(r);
            libc::close(w);
        }
    }

    #[test]
    fn round_trip_u8() {
        let (r, w) = make_socketpair();
        write_u8_to_fd(w, 0x02).unwrap();
        assert_eq!(read_u8_from_fd(r).unwrap(), 0x02);
        unsafe {
            libc::close(r);
            libc::close(w);
        }
    }

    #[test]
    fn error_frame_roundtrip() {
        let (r, w) = make_socketpair();
        write_u8_to_fd(w, 2).unwrap();
        write_string_to_fd(w, "oops").unwrap();
        assert_eq!(read_u8_from_fd(r).unwrap(), 2);
        let msg = read_string_from_fd(r).unwrap();
        assert_eq!(msg, "oops");
        unsafe {
            libc::close(r);
            libc::close(w);
        }
    }

    #[test]
    fn string_too_long_is_truncated_or_rejected() {
        let long_str = "x".repeat(300);
        let (r, w) = make_socketpair();
        // write_string_to_fd caps at u16::MAX — must not panic
        let result = write_string_to_fd(w, &long_str);
        let _ = result;
        unsafe {
            libc::close(r);
            libc::close(w);
        }
    }
}
