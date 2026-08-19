use libc::{EINTR, EIO, POLLIN, SIGKILL, TIOCSCTTY, TIOCSWINSZ, c_char, ioctl, pid_t, winsize};
use std::ffi::CString;
use std::io;
use std::os::fd::RawFd;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

pub struct Pty {
    master: Mutex<Option<RawFd>>,
    pid: pid_t,
    pgid: pid_t,
    killed: AtomicBool,
}

fn last_error() -> String {
    io::Error::last_os_error().to_string()
}

pub fn start(
    argv: Vec<String>,
    environment: Vec<String>,
    cwd: String,
    cols: i32,
    rows: i32,
) -> Result<Pty, String> {
    if argv.is_empty() {
        return Err("empty argv".into());
    }
    let cargs: Vec<CString> = argv
        .iter()
        .map(|value| CString::new(value.as_str()).map_err(|_| "argv contains NUL".to_owned()))
        .collect::<Result<_, _>>()?;
    let cenv: Vec<CString> = environment
        .iter()
        .map(|value| {
            CString::new(value.as_str()).map_err(|_| "environment contains NUL".to_owned())
        })
        .collect::<Result<_, _>>()?;
    let dir = CString::new(cwd).map_err(|_| "cwd contains NUL".to_owned())?;
    let mut arg_ptrs: Vec<*const c_char> = cargs.iter().map(|value| value.as_ptr()).collect();
    arg_ptrs.push(std::ptr::null());
    let mut env_ptrs: Vec<*const c_char> = cenv.iter().map(|value| value.as_ptr()).collect();
    env_ptrs.push(std::ptr::null());

    let master = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
    if master < 0 {
        return Err(last_error());
    }
    let result = (|| unsafe {
        if libc::grantpt(master) < 0 || libc::unlockpt(master) < 0 {
            return Err(last_error());
        }
        let name = libc::ptsname(master);
        if name.is_null() {
            return Err(last_error());
        }
        let pid = libc::fork();
        if pid < 0 {
            return Err(last_error());
        }
        if pid == 0 {
            let slave = libc::open(name, libc::O_RDWR);
            if slave < 0 {
                libc::_exit(127);
            }
            libc::setsid();
            ioctl(slave, TIOCSCTTY, 0);
            let size = winsize {
                ws_row: rows as u16,
                ws_col: cols as u16,
                ws_xpixel: 0,
                ws_ypixel: 0,
            };
            ioctl(slave, TIOCSWINSZ, &size);
            for fd in [0, 1, 2] {
                libc::dup2(slave, fd);
            }
            if slave > 2 {
                libc::close(slave);
            }
            if libc::chdir(dir.as_ptr()) < 0 {
                libc::_exit(127);
            }
            libc::execve(cargs[0].as_ptr(), arg_ptrs.as_ptr(), env_ptrs.as_ptr());
            libc::_exit(127);
        }
        Ok(Pty {
            master: Mutex::new(Some(master)),
            pid,
            pgid: pid,
            killed: AtomicBool::new(false),
        })
    })();
    if result.is_err() {
        unsafe { libc::close(master) };
    }
    result
}

pub fn write(pty: &Pty, bytes: &[u8]) -> Result<(), String> {
    let guard = pty
        .master
        .lock()
        .map_err(|_| "PTY lock poisoned".to_owned())?;
    let fd = guard.ok_or_else(|| "PTY is closed".to_owned())?;
    let mut written = 0;
    while written < bytes.len() {
        let result = unsafe {
            libc::write(
                fd,
                bytes[written..].as_ptr() as *const _,
                bytes.len() - written,
            )
        };
        if result > 0 {
            written += result as usize;
        } else if result < 0 && io::Error::last_os_error().raw_os_error() == Some(EINTR) {
            continue;
        } else {
            return Err(if result == 0 {
                "PTY write returned zero".into()
            } else {
                last_error()
            });
        }
    }
    Ok(())
}

pub fn read(pty: &Pty, max: usize) -> Result<Vec<u8>, String> {
    loop {
        let guard = pty
            .master
            .lock()
            .map_err(|_| "PTY lock poisoned".to_owned())?;
        let Some(fd) = *guard else {
            return Ok(Vec::new());
        };
        let mut poll_fd = libc::pollfd {
            fd,
            events: POLLIN,
            revents: 0,
        };
        let polled = unsafe { libc::poll(&mut poll_fd, 1, 100) };
        if polled == 0 {
            drop(guard);
            std::thread::yield_now();
            continue;
        }
        if polled < 0 {
            if io::Error::last_os_error().raw_os_error() == Some(EINTR) {
                continue;
            }
            return Err(last_error());
        }
        let mut bytes = vec![0u8; max];
        let result = unsafe { libc::read(fd, bytes.as_mut_ptr() as *mut _, max) };
        if result >= 0 {
            bytes.truncate(result as usize);
            return Ok(bytes);
        }
        let error = io::Error::last_os_error();
        if error.raw_os_error() == Some(EINTR) {
            continue;
        }
        if error.raw_os_error() == Some(EIO) {
            return Ok(Vec::new());
        }
        return Err(error.to_string());
    }
}

pub fn resize(pty: &Pty, cols: i32, rows: i32) -> Result<(), String> {
    let guard = pty
        .master
        .lock()
        .map_err(|_| "PTY lock poisoned".to_owned())?;
    let fd = guard.ok_or_else(|| "PTY is closed".to_owned())?;
    let size = winsize {
        ws_row: rows as u16,
        ws_col: cols as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    if unsafe { ioctl(fd, TIOCSWINSZ, &size) } < 0 {
        Err(last_error())
    } else {
        Ok(())
    }
}

pub fn wait(pty: &Pty) -> Result<i32, String> {
    let mut status = 0;
    let result = loop {
        let result = unsafe { libc::waitpid(pty.pid, &mut status, 0) };
        if result < 0 && io::Error::last_os_error().raw_os_error() == Some(EINTR) {
            continue;
        }
        break result;
    };
    close_master(pty)?;
    if result < 0 {
        return Err(last_error());
    }
    Ok(if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else {
        -1
    })
}

pub fn kill(pty: &Pty) -> Result<(), String> {
    if pty.killed.swap(true, Ordering::AcqRel) {
        return Ok(());
    }
    let result = unsafe { libc::kill(-pty.pgid, SIGKILL) };
    if result < 0 {
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::ESRCH) {
            return Err(error.to_string());
        }
        if unsafe { libc::kill(pty.pid, SIGKILL) } < 0
            && io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
        {
            return Err(last_error());
        }
    }
    Ok(())
}

fn close_master(pty: &Pty) -> Result<(), String> {
    let mut guard = pty
        .master
        .lock()
        .map_err(|_| "PTY lock poisoned".to_owned())?;
    if let Some(fd) = guard.take() {
        if unsafe { libc::close(fd) } < 0 {
            return Err(last_error());
        }
    }
    Ok(())
}

impl Drop for Pty {
    fn drop(&mut self) {
        let _ = close_master(self);
    }
}
