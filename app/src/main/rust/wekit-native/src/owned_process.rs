use libc::{EINTR, SIGKILL, SIGTERM, c_char, pid_t};
use std::ffi::CString;
use std::io;
use std::os::fd::RawFd;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub struct OwnedProcess {
    pid: pid_t,
    pgid: pid_t,
    status: Mutex<Option<i32>>,
    drained: AtomicBool,
}

pub struct StartedProcess {
    pub process: OwnedProcess,
    pub stdin: RawFd,
    pub stdout: RawFd,
    pub stderr: RawFd,
}

pub fn start(
    argv: Vec<String>,
    environment: Vec<String>,
    cwd: String,
) -> Result<StartedProcess, String> {
    if argv.is_empty() || !argv[0].starts_with('/') {
        return Err("owned process executable must be an absolute path".into());
    }
    let cargs = strings(&argv, "argv")?;
    let cenv = strings(&environment, "environment")?;
    let directory = CString::new(cwd).map_err(|_| "cwd contains NUL".to_owned())?;
    let mut arg_ptrs: Vec<*const c_char> = cargs.iter().map(|value| value.as_ptr()).collect();
    arg_ptrs.push(std::ptr::null());
    let mut env_ptrs: Vec<*const c_char> = cenv.iter().map(|value| value.as_ptr()).collect();
    env_ptrs.push(std::ptr::null());

    unsafe {
        let mut stdin_pipe = pipe()?;
        let mut stdout_pipe = pipe()?;
        let mut stderr_pipe = pipe()?;
        let mut spawn_error = pipe_cloexec()?;
        let pid = libc::fork();
        if pid < 0 {
            close_all(
                &mut stdin_pipe,
                &mut stdout_pipe,
                &mut stderr_pipe,
                &mut spawn_error,
            );
            return Err(last_error());
        }
        if pid == 0 {
            libc::close(stdin_pipe[1]);
            libc::close(stdout_pipe[0]);
            libc::close(stderr_pipe[0]);
            libc::close(spawn_error[0]);
            let fail = |error: i32| -> ! {
                let bytes = error.to_ne_bytes();
                let _ = libc::write(spawn_error[1], bytes.as_ptr().cast(), bytes.len());
                libc::_exit(127);
            };
            if libc::setsid() < 0 {
                fail(errno());
            }
            for (source, target) in [(stdin_pipe[0], 0), (stdout_pipe[1], 1), (stderr_pipe[1], 2)] {
                if libc::dup2(source, target) < 0 {
                    fail(errno());
                }
            }
            for fd in [stdin_pipe[0], stdout_pipe[1], stderr_pipe[1]] {
                if fd > 2 {
                    libc::close(fd);
                }
            }
            if libc::chdir(directory.as_ptr()) < 0 {
                fail(errno());
            }
            libc::execve(cargs[0].as_ptr(), arg_ptrs.as_ptr(), env_ptrs.as_ptr());
            fail(errno());
        }

        libc::close(stdin_pipe[0]);
        libc::close(stdout_pipe[1]);
        libc::close(stderr_pipe[1]);
        libc::close(spawn_error[1]);
        let spawn_result = read_spawn_error(spawn_error[0]);
        libc::close(spawn_error[0]);
        if let Err(error) = spawn_result {
            libc::close(stdin_pipe[1]);
            libc::close(stdout_pipe[0]);
            libc::close(stderr_pipe[0]);
            let _ = libc::kill(pid, SIGKILL);
            reap(pid, false);
            return Err(error);
        }
        Ok(StartedProcess {
            process: OwnedProcess {
                pid,
                pgid: pid,
                status: Mutex::new(None),
                drained: AtomicBool::new(false),
            },
            stdin: stdin_pipe[1],
            stdout: stdout_pipe[0],
            stderr: stderr_pipe[0],
        })
    }
}

impl OwnedProcess {
    pub fn pid(&self) -> pid_t {
        self.pid
    }
    pub fn pgid(&self) -> pid_t {
        self.pgid
    }

    pub fn poll_exit(&self) -> Result<Option<i32>, String> {
        let mut status = self
            .status
            .lock()
            .map_err(|_| "owned process lock poisoned".to_owned())?;
        if status.is_some() {
            return Ok(*status);
        }
        let mut raw = 0;
        let result = loop {
            let result = unsafe { libc::waitpid(self.pid, &mut raw, libc::WNOHANG) };
            if result < 0 && errno() == EINTR {
                continue;
            }
            break result;
        };
        if result < 0 {
            return Err(last_error());
        }
        if result == 0 {
            return Ok(None);
        }
        *status = Some(exit_code(raw));
        Ok(*status)
    }

    pub fn terminate_group(&self, grace: Duration) -> Result<(), String> {
        if self.drained.load(Ordering::Acquire) {
            return Ok(());
        }
        signal_group(self.pgid, SIGTERM)?;
        let deadline = Instant::now() + grace;
        while Instant::now() < deadline {
            let _ = self.poll_exit();
            if !group_exists(self.pgid)? {
                self.drained.store(true, Ordering::Release);
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        signal_group(self.pgid, SIGKILL)
            .or_else(|error| signal_pid(self.pid, SIGKILL).map_err(|_| error))?;
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline {
            let _ = self.poll_exit();
            if !group_exists(self.pgid)? {
                self.drained.store(true, Ordering::Release);
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        Err(format!("process group {} survived SIGKILL", self.pgid))
    }
}

impl Drop for OwnedProcess {
    fn drop(&mut self) {
        let _ = self.terminate_group(Duration::from_millis(100));
        if self
            .status
            .get_mut()
            .ok()
            .and_then(|status| *status)
            .is_none()
        {
            reap(self.pid, true);
        }
    }
}

fn strings(values: &[String], label: &str) -> Result<Vec<CString>, String> {
    values
        .iter()
        .map(|value| CString::new(value.as_str()).map_err(|_| format!("{label} contains NUL")))
        .collect()
}

unsafe fn pipe() -> Result<[RawFd; 2], String> {
    let mut fds = [-1; 2];
    if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) } < 0 {
        Err(last_error())
    } else {
        Ok(fds)
    }
}

unsafe fn pipe_cloexec() -> Result<[RawFd; 2], String> {
    unsafe { pipe() }
}

unsafe fn close_all(
    pipes: &mut [RawFd; 2],
    a: &mut [RawFd; 2],
    b: &mut [RawFd; 2],
    c: &mut [RawFd; 2],
) {
    for fd in pipes.iter().chain(a.iter()).chain(b.iter()).chain(c.iter()) {
        if *fd >= 0 {
            unsafe {
                libc::close(*fd);
            }
        }
    }
}

fn read_spawn_error(fd: RawFd) -> Result<(), String> {
    let mut bytes = [0u8; std::mem::size_of::<i32>()];
    let mut read = 0;
    loop {
        let count =
            unsafe { libc::read(fd, bytes[read..].as_mut_ptr().cast(), bytes.len() - read) };
        if count > 0 {
            read += count as usize;
            if read == bytes.len() {
                return Err(io::Error::from_raw_os_error(i32::from_ne_bytes(bytes)).to_string());
            }
        } else if count == 0 {
            return if read == 0 {
                Ok(())
            } else {
                Err("short owned-process spawn error record".into())
            };
        } else if errno() != EINTR {
            return Err(last_error());
        }
    }
}

fn signal_group(pgid: pid_t, signal: i32) -> Result<(), String> {
    let result = unsafe { libc::kill(-pgid, signal) };
    if result == 0 || errno() == libc::ESRCH {
        Ok(())
    } else {
        Err(last_error())
    }
}

fn signal_pid(pid: pid_t, signal: i32) -> Result<(), String> {
    let result = unsafe { libc::kill(pid, signal) };
    if result == 0 || errno() == libc::ESRCH {
        Ok(())
    } else {
        Err(last_error())
    }
}

fn group_exists(pgid: pid_t) -> Result<bool, String> {
    if unsafe { libc::kill(-pgid, 0) } == 0 {
        return Ok(true);
    }
    match errno() {
        libc::ESRCH => Ok(false),
        libc::EPERM => Ok(true),
        _ => Err(last_error()),
    }
}

fn reap(pid: pid_t, no_hang: bool) {
    let mut status = 0;
    let options = if no_hang { libc::WNOHANG } else { 0 };
    while unsafe { libc::waitpid(pid, &mut status, options) } < 0 && errno() == EINTR {}
}

fn exit_code(status: i32) -> i32 {
    if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        128 + libc::WTERMSIG(status)
    } else {
        -1
    }
}

fn errno() -> i32 {
    io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(libc::EIO)
}
fn last_error() -> String {
    io::Error::last_os_error().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::os::fd::FromRawFd;

    #[test]
    fn retained_group_kills_child_after_leader_exits() {
        let started = start(
            vec![
                "/bin/sh".into(),
                "-c".into(),
                "(trap '' TERM; while :; do sleep 1; done) & echo $!".into(),
            ],
            vec!["PATH=/usr/bin:/bin".into()],
            "/".into(),
        )
        .unwrap();
        unsafe {
            libc::close(started.stdin);
            libc::close(started.stderr);
        }
        let mut output = String::new();
        unsafe { BufReader::new(std::fs::File::from_raw_fd(started.stdout)) }
            .read_line(&mut output)
            .unwrap();
        let child: pid_t = output.trim().parse().unwrap();
        while started.process.poll_exit().unwrap().is_none() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(group_exists(started.process.pgid()).unwrap());
        started
            .process
            .terminate_group(Duration::from_millis(50))
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline && group_exists(started.process.pgid()).unwrap() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(!group_exists(started.process.pgid()).unwrap());
        assert!(unsafe { libc::kill(child, 0) } < 0);
    }

    #[test]
    fn normal_exit_retains_status_and_drains_group() {
        let started = start(
            vec!["/bin/sh".into(), "-c".into(), "exit 7".into()],
            vec!["PATH=/usr/bin:/bin".into()],
            "/".into(),
        )
        .unwrap();
        unsafe {
            libc::close(started.stdin);
            libc::close(started.stdout);
            libc::close(started.stderr);
        }
        let exit = loop {
            if let Some(exit) = started.process.poll_exit().unwrap() {
                break exit;
            }
            std::thread::sleep(Duration::from_millis(10));
        };
        assert_eq!(7, exit);
        started
            .process
            .terminate_group(Duration::from_millis(10))
            .unwrap();
    }
}
