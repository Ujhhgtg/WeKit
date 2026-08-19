use libc::{c_char, ioctl, pid_t, winsize, EIO, SIGTERM, TIOCSCTTY, TIOCSWINSZ};
use std::ffi::CString;
use std::io;
use std::os::fd::RawFd;

pub struct Pty { pub master: RawFd, pub pid: pid_t, pub pgid: pid_t, killed: bool }
unsafe impl Send for Pty {}

pub fn start(argv: Vec<String>, environment: Vec<String>, cwd: String, cols: i32, rows: i32) -> Result<Pty, String> {
    if argv.is_empty() { return Err("empty argv".into()); }
    let master = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
    if master < 0 { return Err(io::Error::last_os_error().to_string()); }
    let result = (|| unsafe {
        if libc::grantpt(master) < 0 || libc::unlockpt(master) < 0 { return Err(io::Error::last_os_error().to_string()); }
        let name = libc::ptsname(master); if name.is_null() { return Err(io::Error::last_os_error().to_string()); }
        let pid = libc::fork(); if pid < 0 { return Err(io::Error::last_os_error().to_string()); }
        if pid == 0 {
            let slave = libc::open(name, libc::O_RDWR);
            if slave < 0 { libc::_exit(127); }
            libc::setsid(); ioctl(slave, TIOCSCTTY, 0); let ws = winsize { ws_row: rows as u16, ws_col: cols as u16, ws_xpixel: 0, ws_ypixel: 0 }; ioctl(slave, TIOCSWINSZ, &ws);
            for fd in [0, 1, 2] { libc::dup2(slave, fd); } if slave > 2 { libc::close(slave); }
            let dir = CString::new(cwd).unwrap(); libc::chdir(dir.as_ptr());
            let cargs: Vec<CString> = argv.iter().map(|v| CString::new(v.as_str()).unwrap()).collect(); let mut ptrs: Vec<*const c_char> = cargs.iter().map(|v| v.as_ptr()).collect(); ptrs.push(std::ptr::null());
            let cenv: Vec<CString> = environment.iter().map(|v| CString::new(v.as_str()).unwrap()).collect(); let mut envp: Vec<*const c_char> = cenv.iter().map(|v| v.as_ptr()).collect(); envp.push(std::ptr::null());
            libc::execve(cargs[0].as_ptr(), ptrs.as_ptr(), envp.as_ptr()); libc::_exit(127);
        }
        Ok(Pty { master, pid, pgid: pid, killed: false })
    })();
    if result.is_err() { unsafe { libc::close(master); } } result
}
pub fn write(pty: &mut Pty, bytes: &[u8]) -> Result<(), String> { let result = unsafe { libc::write(pty.master, bytes.as_ptr() as *const _, bytes.len()) }; if result < 0 { Err(io::Error::last_os_error().to_string()) } else { Ok(()) } }
pub fn read(pty: &mut Pty, max: usize) -> Result<Vec<u8>, String> { let mut bytes = vec![0u8; max]; let result = unsafe { libc::read(pty.master, bytes.as_mut_ptr() as *mut _, max) }; if result >= 0 { bytes.truncate(result as usize); Ok(bytes) } else if io::Error::last_os_error().raw_os_error() == Some(EIO) { Ok(Vec::new()) } else { Err(io::Error::last_os_error().to_string()) } }
pub fn resize(pty: &mut Pty, cols: i32, rows: i32) -> Result<(), String> { let ws = winsize { ws_row: rows as u16, ws_col: cols as u16, ws_xpixel: 0, ws_ypixel: 0 }; if unsafe { ioctl(pty.master, TIOCSWINSZ, &ws) } < 0 { Err(io::Error::last_os_error().to_string()) } else { Ok(()) } }
pub fn wait(pty: &mut Pty) -> Result<i32, String> { let mut status = 0; let result = unsafe { libc::waitpid(pty.pid, &mut status, 0) }; if result < 0 { return Err(io::Error::last_os_error().to_string()); } unsafe { libc::close(pty.master); } Ok(if libc::WIFEXITED(status) { libc::WEXITSTATUS(status) } else { -1 }) }
pub fn kill(pty: &mut Pty) -> Result<(), String> { if pty.killed { return Ok(()); } pty.killed = true; unsafe { libc::kill(-pty.pgid, SIGTERM); } Ok(()) }
