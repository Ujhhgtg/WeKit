//! Fork-immediate-exec launcher for the isolated llama server process.
//!
//! Everything that owns memory or touches the filesystem is prepared in the
//! parent. After `fork(2)`, the child uses only raw async-signal-safe syscalls
//! before replacing itself with `app_process64`.

use std::ffi::{CString, OsStr};
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicI32, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::server;
use crate::server::HttpServerConfig;

pub const APP_PROCESS_PATH: &str = "/system/bin/app_process64";
pub const APP_PROCESS_PARENT_DIR: &str = "/system/bin";
pub const SERVER_MAIN_CLASS: &str = "dev.ujhhgtg.wekit.agent.model.local.LlamaServerProcess";

#[derive(Clone, Debug)]
pub struct ExecServerConfig {
    pub bootstrap_apk: String,
    pub native_library: String,
    pub server: HttpServerConfig,
}

struct PreparedExec {
    program: CString,
    argv: Vec<CString>,
    env: Vec<CString>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct AppProcessConfig {
    idle_timeout_sec: u64,
    temperature: f32,
    top_p: f32,
    top_k: i32,
}

#[cfg(test)]
fn build_exec_command(
    cfg: &ExecServerConfig,
    status_fd: i32,
    environment: Vec<(&str, &str)>,
) -> Result<PreparedExec, String> {
    build_exec_command_from_env(cfg, status_fd, environment)
}

fn build_exec_command_from_env<K, V>(
    cfg: &ExecServerConfig,
    status_fd: i32,
    environment: Vec<(K, V)>,
) -> Result<PreparedExec, String>
where
    K: AsRef<OsStr>,
    V: AsRef<OsStr>,
{
    let config_json = serde_json::to_string(&AppProcessConfig {
        idle_timeout_sec: cfg.server.engine.idle_timeout_secs,
        temperature: cfg.server.engine.temp,
        top_p: cfg.server.engine.top_p,
        top_k: cfg.server.engine.top_k,
    })
    .map_err(|error| format!("serializing app_process config: {error}"))?;
    let argv = [
        APP_PROCESS_PATH.to_owned(),
        APP_PROCESS_PARENT_DIR.to_owned(),
        "--application".to_owned(),
        "--nice-name=com.tencent.mm:wekit_llama".to_owned(),
        SERVER_MAIN_CLASS.to_owned(),
        "1".to_owned(),
        status_fd.to_string(),
        cfg.native_library.clone(),
        cfg.server.engine.model_path.clone(),
        cfg.server.engine.n_ctx.to_string(),
        cfg.server.engine.backend.as_str().to_owned(),
        config_json,
    ]
    .into_iter()
    .map(|value| CString::new(value).map_err(|_| "app_process argument contains NUL".to_owned()))
    .collect::<Result<Vec<_>, _>>()?;

    let mut env = Vec::with_capacity(environment.len() + 1);
    for (name, value) in environment {
        let name = name.as_ref().as_bytes();
        if name == b"CLASSPATH" {
            continue;
        }
        let value = value.as_ref().as_bytes();
        let mut entry = Vec::with_capacity(name.len() + value.len() + 1);
        entry.extend_from_slice(name);
        entry.push(b'=');
        entry.extend_from_slice(value);
        env.push(CString::new(entry).map_err(|_| "environment contains NUL".to_owned())?);
    }
    let mut classpath = Vec::with_capacity("CLASSPATH=".len() + cfg.bootstrap_apk.len());
    classpath.extend_from_slice(b"CLASSPATH=");
    classpath.extend_from_slice(cfg.bootstrap_apk.as_bytes());
    env.push(CString::new(classpath).map_err(|_| "bootstrap APK path contains NUL".to_owned())?);

    let program = CString::new(APP_PROCESS_PATH).unwrap();
    Ok(PreparedExec { program, argv, env })
}

#[derive(Debug)]
pub struct SpawnedServer {
    pub pid: i32,
    pub port: u16,
}

pub enum ChildEvent {
    Ready { port: u16 },
    Exiting { reason: String },
    Died { reason: String },
}

const READY_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_STARTUP_LINE_BYTES: usize = 64 * 1024;
const EXEC_ERROR: &[u8] = b"{\"type\":\"error\",\"msg\":\"execve app_process64 failed\"}\n";
const STATUS_FD_ERROR: &[u8] =
    b"{\"type\":\"error\",\"msg\":\"clearing status fd CLOEXEC failed\"}\n";
static IDLE_PIPE_FD: AtomicI32 = AtomicI32::new(-1);

pub fn spawn_server(
    cfg: ExecServerConfig,
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
) -> Result<SpawnedServer, String> {
    spawn_with_builder(on_event, READY_TIMEOUT, move |status_fd| {
        let environment = std::env::vars_os().collect::<Vec<_>>();
        build_exec_command_from_env(&cfg, status_fd, environment)
    })
}

#[doc(hidden)]
#[cfg(not(target_os = "android"))]
pub fn spawn_test_shell(script: &str) -> Result<SpawnedServer, String> {
    spawn_test_shell_inner(script, Arc::new(|_| {}), READY_TIMEOUT)
}

#[doc(hidden)]
#[cfg(not(target_os = "android"))]
pub fn spawn_test_shell_timeout(
    script: &str,
    ready_timeout: Duration,
) -> Result<SpawnedServer, String> {
    spawn_test_shell_inner(script, Arc::new(|_| {}), ready_timeout)
}

#[doc(hidden)]
#[cfg(not(target_os = "android"))]
pub fn spawn_test_shell_with_events(
    script: &str,
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
) -> Result<SpawnedServer, String> {
    spawn_test_shell_inner(script, on_event, READY_TIMEOUT)
}

#[cfg(not(target_os = "android"))]
fn spawn_test_shell_inner(
    script: &str,
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
    ready_timeout: Duration,
) -> Result<SpawnedServer, String> {
    let script = script.to_owned();
    spawn_with_builder(on_event, ready_timeout, move |status_fd| {
        prepare_command(
            "/bin/sh",
            vec![
                "/bin/sh".to_owned(),
                "-c".to_owned(),
                script,
                "wekit-llama-test".to_owned(),
                status_fd.to_string(),
            ],
            std::env::vars_os().collect(),
        )
    })
}

#[doc(hidden)]
#[cfg(not(target_os = "android"))]
pub fn spawn_test_program(program: &str) -> Result<SpawnedServer, String> {
    let program = program.to_owned();
    spawn_with_builder(Arc::new(|_| {}), READY_TIMEOUT, move |_| {
        prepare_command(
            &program,
            vec![program.clone()],
            std::env::vars_os().collect(),
        )
    })
}

fn prepare_command<K, V>(
    program: &str,
    arguments: Vec<String>,
    environment: Vec<(K, V)>,
) -> Result<PreparedExec, String>
where
    K: AsRef<OsStr>,
    V: AsRef<OsStr>,
{
    let program = CString::new(program).map_err(|_| "exec program contains NUL".to_owned())?;
    let argv = arguments
        .into_iter()
        .map(|value| CString::new(value).map_err(|_| "exec argument contains NUL".to_owned()))
        .collect::<Result<Vec<_>, _>>()?;
    let env = environment
        .into_iter()
        .map(|(name, value)| {
            let name = name.as_ref().as_bytes();
            let value = value.as_ref().as_bytes();
            let mut entry = Vec::with_capacity(name.len() + value.len() + 1);
            entry.extend_from_slice(name);
            entry.push(b'=');
            entry.extend_from_slice(value);
            CString::new(entry).map_err(|_| "environment contains NUL".to_owned())
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok(PreparedExec { program, argv, env })
}

fn spawn_with_builder(
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
    ready_timeout: Duration,
    build: impl FnOnce(i32) -> Result<PreparedExec, String>,
) -> Result<SpawnedServer, String> {
    let fds = status_pipe()?;
    let command = match build(fds[1]) {
        Ok(command) => command,
        Err(error) => {
            close_pair(fds);
            return Err(error);
        }
    };
    let close_fds = match snapshot_open_fds() {
        Ok(open_fds) => open_fds
            .into_iter()
            .filter(|&fd| fd > 2 && fd != fds[0] && fd != fds[1])
            .collect::<Vec<_>>(),
        Err(error) => {
            close_pair(fds);
            return Err(error);
        }
    };
    let mut argv_ptrs = command
        .argv
        .iter()
        .map(|value| value.as_ptr())
        .collect::<Vec<_>>();
    argv_ptrs.push(std::ptr::null());
    let mut env_ptrs = command
        .env
        .iter()
        .map(|value| value.as_ptr())
        .collect::<Vec<_>>();
    env_ptrs.push(std::ptr::null());

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        let error = io::Error::last_os_error();
        close_pair(fds);
        return Err(format!("fork failed: {error}"));
    }
    if pid == 0 {
        child_exec(fds[0], fds[1], &close_fds, &command, &argv_ptrs, &env_ptrs);
    }

    unsafe { libc::close(fds[1]) };
    await_ready(pid, fds[0], on_event, ready_timeout)
}

fn status_pipe() -> Result<[i32; 2], String> {
    let mut original = [-1_i32; 2];
    if unsafe { libc::pipe2(original.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        return Err(format!("pipe failed: {}", io::Error::last_os_error()));
    }
    let read_fd = unsafe { libc::fcntl(original[0], libc::F_DUPFD_CLOEXEC, 3) };
    if read_fd < 0 {
        let error = io::Error::last_os_error();
        close_pair(original);
        return Err(format!("relocating status pipe read fd: {error}"));
    }
    let write_fd = unsafe { libc::fcntl(original[1], libc::F_DUPFD_CLOEXEC, 3) };
    if write_fd < 0 {
        let error = io::Error::last_os_error();
        unsafe { libc::close(read_fd) };
        close_pair(original);
        return Err(format!("relocating status pipe write fd: {error}"));
    }
    close_pair(original);
    Ok([read_fd, write_fd])
}

fn snapshot_open_fds() -> Result<Vec<i32>, String> {
    let entries = std::fs::read_dir("/proc/self/fd")
        .map_err(|error| format!("enumerating open fds: {error}"))?;
    let mut fds = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|error| format!("enumerating open fds: {error}"))?;
        if let Some(fd) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<i32>().ok())
        {
            fds.push(fd);
        }
    }
    Ok(fds)
}

fn child_exec(
    parent_fd: i32,
    status_fd: i32,
    close_fds: &[i32],
    command: &PreparedExec,
    argv_ptrs: &[*const libc::c_char],
    env_ptrs: &[*const libc::c_char],
) -> ! {
    unsafe {
        libc::close(parent_fd);
        if libc::fcntl(status_fd, libc::F_SETFD, 0) != 0 {
            child_raw_write_all(status_fd, STATUS_FD_ERROR);
            libc::_exit(127);
        }
        for &fd in close_fds {
            libc::close(fd);
        }

        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL, 0, 0, 0);
        if libc::getppid() == 1 {
            libc::_exit(0);
        }

        let oom_fd = libc::open(c"/proc/self/oom_score_adj".as_ptr(), libc::O_WRONLY);
        if oom_fd >= 0 {
            libc::write(oom_fd, b"900".as_ptr().cast(), 3);
            libc::close(oom_fd);
        }
        libc::setpriority(libc::PRIO_PROCESS, 0, 19);

        let null_fd = libc::open(c"/dev/null".as_ptr(), libc::O_RDWR);
        if null_fd >= 0 {
            libc::dup2(null_fd, 0);
            libc::dup2(null_fd, 1);
            libc::dup2(null_fd, 2);
            if null_fd > 2 {
                libc::close(null_fd);
            }
        }

        libc::execve(
            command.program.as_ptr(),
            argv_ptrs.as_ptr(),
            env_ptrs.as_ptr(),
        );
        child_raw_write_all(status_fd, EXEC_ERROR);
        libc::_exit(127);
    }
}

fn child_raw_write_all(fd: i32, bytes: &[u8]) {
    let mut written = 0;
    while written < bytes.len() {
        let count =
            unsafe { libc::write(fd, bytes[written..].as_ptr().cast(), bytes.len() - written) };
        if count > 0 {
            written += count as usize;
        } else if count < 0 && raw_errno() == libc::EINTR {
            continue;
        } else {
            return;
        }
    }
}

#[cfg(target_os = "android")]
fn raw_errno() -> i32 {
    unsafe { *libc::__errno() }
}

#[cfg(not(target_os = "android"))]
fn raw_errno() -> i32 {
    unsafe { *libc::__errno_location() }
}

fn await_ready(
    pid: i32,
    read_fd: i32,
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
    ready_timeout: Duration,
) -> Result<SpawnedServer, String> {
    let mut reader = LineReader::new(read_fd);
    let deadline = Instant::now() + ready_timeout;
    let line = loop {
        match reader.take_startup_line() {
            Ok(Some(line)) => break line,
            Ok(None) => {}
            Err(error) => {
                stop_child(pid);
                return Err(error);
            }
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            stop_child(pid);
            return Err("exec child did not become ready before deadline".to_owned());
        }
        let mut poll_fd = libc::pollfd {
            fd: read_fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let count = unsafe {
            libc::poll(
                &mut poll_fd,
                1,
                remaining.as_millis().clamp(1, i32::MAX as u128) as i32,
            )
        };
        if count < 0 {
            if interrupted() {
                continue;
            }
            stop_child(pid);
            return Err(format!("poll failed: {}", io::Error::last_os_error()));
        }
        if count == 0 {
            continue;
        }
        match reader.read_more() {
            Ok(true) => {}
            Ok(false) => {
                stop_child(pid);
                return Err("exec child exited before becoming ready".to_owned());
            }
            Err(error) => {
                stop_child(pid);
                return Err(format!("reading exec child status: {error}"));
            }
        }
    };

    let message: Value = serde_json::from_str(&line).unwrap_or(Value::Null);
    match message["type"].as_str() {
        Some("ready") => {
            let port = message["port"].as_u64().unwrap_or_default() as u16;
            let watch_reader = match reader.try_clone() {
                Ok(reader) => reader,
                Err(error) => {
                    stop_child(pid);
                    return Err(format!("duplicating child watchdog fd: {error}"));
                }
            };
            let watch_event = on_event.clone();
            match thread::Builder::new()
                .name("wekit-llama-watch".to_owned())
                .spawn(move || watch_child(watch_reader, pid, watch_event))
            {
                Ok(_) => {
                    drop(reader);
                    Ok(SpawnedServer { pid, port })
                }
                Err(error) => {
                    stop_child(pid);
                    Err(format!("starting child watchdog: {error}"))
                }
            }
        }
        Some("error") => {
            let error = message["msg"]
                .as_str()
                .unwrap_or("unknown child error")
                .to_owned();
            stop_child(pid);
            Err(error)
        }
        Some("exiting") => {
            let error = format!(
                "exec child exited during startup: {}",
                message["reason"].as_str().unwrap_or("?")
            );
            stop_child(pid);
            Err(error)
        }
        _ => {
            stop_child(pid);
            Err(format!("unexpected child message: {line}"))
        }
    }
}

pub fn stop_child(pid: i32) {
    if pid <= 0 {
        return;
    }
    unsafe { libc::kill(pid, libc::SIGTERM) };
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline {
        if exited(pid) {
            return;
        }
        thread::sleep(Duration::from_millis(50));
    }
    unsafe { libc::kill(pid, libc::SIGKILL) };
    loop {
        let result = unsafe { libc::waitpid(pid, std::ptr::null_mut(), 0) };
        if result == pid {
            return;
        }
        if result == -1 {
            if io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            return;
        }
    }
}

pub fn notify_idle_exit() {
    let fd = IDLE_PIPE_FD.load(Ordering::SeqCst);
    if fd >= 0 {
        pipe_write(fd, &exiting_line("idle"));
    }
}

pub fn run_server_process(cfg: HttpServerConfig, status_fd: i32) -> Result<(), String> {
    IDLE_PIPE_FD.store(status_fd, Ordering::SeqCst);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("wekit-llama-io")
        .enable_all()
        .build()
        .map_err(|error| format!("tokio runtime: {error}"))?;
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<u16>();
    let server_fd = status_fd;
    runtime.spawn(async move {
        let exit_code = match server::serve(cfg, move |port| {
            let _ = ready_tx.send(port);
        })
        .await
        {
            Ok(()) => {
                pipe_write(server_fd, &exiting_line("server stopped"));
                0
            }
            Err(error) => {
                pipe_write(server_fd, &error_line(&error));
                1
            }
        };
        std::process::exit(exit_code);
    });
    runtime.block_on(async move {
        let port = ready_rx
            .await
            .map_err(|_| "server exited before ready".to_owned())?;
        pipe_write(status_fd, &ready_line(port));
        std::future::pending::<Result<(), String>>().await
    })
}

fn watch_child(mut reader: LineReader, pid: i32, on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>) {
    let mut terminal_seen = false;
    while let Some(line) = reader.line() {
        let message: Value = serde_json::from_str(&line).unwrap_or(Value::Null);
        match message["type"].as_str() {
            Some("ready") => {
                if let Some(port) = message["port"].as_u64() {
                    on_event(ChildEvent::Ready { port: port as u16 });
                }
            }
            Some("exiting") => {
                on_event(ChildEvent::Exiting {
                    reason: message["reason"].as_str().unwrap_or("?").to_owned(),
                });
                terminal_seen = true;
                break;
            }
            Some("error") => {
                on_event(ChildEvent::Died {
                    reason: message["msg"]
                        .as_str()
                        .unwrap_or("unknown child error")
                        .to_owned(),
                });
                terminal_seen = true;
                break;
            }
            _ => {}
        }
    }
    if !terminal_seen {
        on_event(ChildEvent::Died {
            reason: "pipe closed unexpectedly".to_owned(),
        });
    }
    stop_child(pid);
}

fn ready_line(port: u16) -> String {
    json_line(&serde_json::json!({ "type": "ready", "port": port }))
}

fn error_line(message: &str) -> String {
    json_line(&serde_json::json!({ "type": "error", "msg": message }))
}

fn exiting_line(reason: &str) -> String {
    json_line(&serde_json::json!({ "type": "exiting", "reason": reason }))
}

fn json_line(value: &Value) -> String {
    value.to_string()
}

fn pipe_write(fd: i32, line: &str) {
    let message = format!("{line}\n");
    let bytes = message.as_bytes();
    let mut written = 0;
    while written < bytes.len() {
        let count =
            unsafe { libc::write(fd, bytes[written..].as_ptr().cast(), bytes.len() - written) };
        if count < 0 {
            if interrupted() {
                continue;
            }
            return;
        }
        written += count as usize;
    }
}

struct LineReader {
    fd: i32,
    buffer: Vec<u8>,
}

impl LineReader {
    fn new(fd: i32) -> Self {
        Self {
            fd,
            buffer: Vec::new(),
        }
    }

    fn try_clone(&self) -> io::Result<Self> {
        let fd = unsafe { libc::fcntl(self.fd, libc::F_DUPFD_CLOEXEC, 3) };
        if fd < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(Self {
                fd,
                buffer: self.buffer.clone(),
            })
        }
    }

    fn line(&mut self) -> Option<String> {
        loop {
            if let Some(position) = self.buffer.iter().position(|&byte| byte == b'\n') {
                let line = self.buffer.drain(..=position).collect::<Vec<_>>();
                return Some(String::from_utf8_lossy(&line[..position]).into_owned());
            }
            match self.read_more() {
                Ok(true) => {}
                Ok(false) | Err(_) => return None,
            }
        }
    }

    fn take_startup_line(&mut self) -> Result<Option<String>, String> {
        if let Some(position) = self.buffer.iter().position(|&byte| byte == b'\n') {
            if position > MAX_STARTUP_LINE_BYTES {
                return Err(format!(
                    "exec child startup line exceeds {MAX_STARTUP_LINE_BYTES} bytes"
                ));
            }
            let line = self.buffer.drain(..=position).collect::<Vec<_>>();
            return Ok(Some(
                String::from_utf8_lossy(&line[..position]).into_owned(),
            ));
        }
        if self.buffer.len() > MAX_STARTUP_LINE_BYTES {
            return Err(format!(
                "exec child startup line exceeds {MAX_STARTUP_LINE_BYTES} bytes"
            ));
        }
        Ok(None)
    }

    fn read_more(&mut self) -> io::Result<bool> {
        loop {
            let mut chunk = [0_u8; 512];
            let count = unsafe { libc::read(self.fd, chunk.as_mut_ptr().cast(), chunk.len()) };
            if count > 0 {
                self.buffer.extend_from_slice(&chunk[..count as usize]);
                return Ok(true);
            }
            if count == 0 {
                return Ok(false);
            }
            if interrupted() {
                continue;
            }
            return Err(io::Error::last_os_error());
        }
    }
}

impl Drop for LineReader {
    fn drop(&mut self) {
        unsafe { libc::close(self.fd) };
    }
}

fn close_pair(fds: [i32; 2]) {
    unsafe {
        libc::close(fds[0]);
        libc::close(fds[1]);
    }
}

fn interrupted() -> bool {
    io::Error::last_os_error().raw_os_error() == Some(libc::EINTR)
}

fn exited(pid: i32) -> bool {
    let result = unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) };
    result == pid
        || (result == -1 && io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llama::{Backend, EngineConfig};
    use crate::server::HttpServerConfig;

    fn fixture_exec_config() -> ExecServerConfig {
        ExecServerConfig {
            bootstrap_apk: "/data/app/wekit/base.apk".to_owned(),
            native_library: "/data/user/0/com.tencent.mm/files/libwekit_llama.so".to_owned(),
            server: HttpServerConfig {
                engine: EngineConfig {
                    model_path: "/data/user/0/com.tencent.mm/files/model.gguf".to_owned(),
                    n_ctx: 4096,
                    threads: 4,
                    backend: Backend::Cpu,
                    temp: 0.6,
                    top_p: 0.95,
                    top_k: 20,
                    idle_timeout_secs: 600,
                },
                bind_port: 0,
            },
        }
    }

    fn bytes(values: &[CString]) -> Vec<&str> {
        values.iter().map(|value| value.to_str().unwrap()).collect()
    }

    fn env_value<'a>(environment: &'a [CString], name: &str) -> Option<&'a str> {
        let prefix = format!("{name}=");
        environment
            .iter()
            .find_map(|entry| entry.to_str().unwrap().strip_prefix(&prefix))
    }

    #[test]
    fn app_process_command_uses_fixed_binary_classpath_main_and_status_fd() {
        let cfg = fixture_exec_config();
        let command = build_exec_command(&cfg, 47, vec![("ANDROID_ROOT", "/system")]).unwrap();
        assert_eq!(command.program.to_bytes(), b"/system/bin/app_process64");
        assert_eq!(
            bytes(&command.argv),
            vec![
                "/system/bin/app_process64",
                "/system/bin",
                "--application",
                "--nice-name=com.tencent.mm:wekit_llama",
                "dev.ujhhgtg.wekit.agent.model.local.LlamaServerProcess",
                "1",
                "47",
                "/data/user/0/com.tencent.mm/files/libwekit_llama.so",
                "/data/user/0/com.tencent.mm/files/model.gguf",
                "4096",
                "cpu",
                "{\"idleTimeoutSec\":600,\"temperature\":0.6,\"topP\":0.95,\"topK\":20}",
            ]
        );
        assert_eq!(
            env_value(&command.env, "CLASSPATH"),
            Some("/data/app/wekit/base.apk")
        );
        assert_eq!(env_value(&command.env, "ANDROID_ROOT"), Some("/system"));
    }

    #[test]
    fn app_process_command_rejects_nul_in_external_strings() {
        let mut cfg = fixture_exec_config();
        cfg.native_library = "/data/lib\0bad.so".into();
        assert!(build_exec_command(&cfg, 47, Vec::new()).is_err());
    }

    #[test]
    fn status_pipe_relocates_both_ends_above_standard_fds() {
        let mut report = [-1_i32; 2];
        assert_eq!(
            unsafe { libc::pipe2(report.as_mut_ptr(), libc::O_CLOEXEC) },
            0
        );
        let report_read = unsafe { libc::fcntl(report[0], libc::F_DUPFD_CLOEXEC, 10) };
        let report_write = unsafe { libc::fcntl(report[1], libc::F_DUPFD_CLOEXEC, 10) };
        assert!(report_read >= 10);
        assert!(report_write >= 10);
        close_pair(report);

        let pid = unsafe { libc::fork() };
        assert!(pid >= 0);
        if pid == 0 {
            unsafe {
                libc::close(report_read);
                libc::close(0);
                libc::close(1);
                libc::close(2);
            }
            let pipe = status_pipe().unwrap_or([-1, -1]);
            unsafe {
                libc::write(
                    report_write,
                    pipe.as_ptr().cast(),
                    std::mem::size_of_val(&pipe),
                );
                libc::_exit(i32::from(pipe[0] < 3 || pipe[1] < 3));
            }
        }

        unsafe { libc::close(report_write) };
        let mut bytes = [0_u8; 8];
        assert_eq!(
            unsafe { libc::read(report_read, bytes.as_mut_ptr().cast(), bytes.len()) },
            bytes.len() as isize
        );
        unsafe { libc::close(report_read) };
        let mut status = -1;
        assert_eq!(unsafe { libc::waitpid(pid, &mut status, 0) }, pid);
        assert_eq!(status, 0);
        assert!(i32::from_ne_bytes(bytes[..4].try_into().unwrap()) >= 3);
        assert!(i32::from_ne_bytes(bytes[4..].try_into().unwrap()) >= 3);
    }
}
