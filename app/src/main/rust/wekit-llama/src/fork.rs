//! Controlled fork-based child server process.
//!
//! Fork invariants (spec §3.1):
//!
//! - The pack library does **zero** global initialization at dlopen time;
//!   every static in this crate is const-initialized, so the child inherits
//!   a clean image.
//! - [`fork_server`] must only be called from the dedicated control thread
//!   ([`crate::controller`]), and that thread must hold **no locks** and
//!   must not touch the JNI env when it does: the child keeps only the
//!   calling thread. Nothing below acquires a lock between entry and
//!   `fork(2)` either.
//! - The child never calls back into JNI/Java and never touches the std
//!   stdout lock: all parent communication is one JSON line at a time over
//!   a raw pipe fd written with `write(2)`.
//!
//! Child sequence right after `fork(2)`:
//! `prctl(PR_SET_PDEATHSIG, SIGKILL)` → `getppid() == 1 → _exit(0)` →
//! best-effort `/proc/self/oom_score_adj = 900` → `setpriority(…, 19)` →
//! `/dev/null` dup2'd onto fd 0/1/2 → tokio runtime `block_on(serve(…))`;
//! readiness is reported as `{"type":"ready","port":N}`, failures as
//! `{"type":"error","msg":…}` followed by `_exit(1)`.

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicI32, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::server::{self, HttpServerConfig};

/// The forked inference child as seen by the parent.
#[derive(Debug)]
pub struct ForkedServer {
    pub pid: i32,
    pub port: u16,
}

/// Watchdog observations for a live child (post-ready).
pub enum ChildEvent {
    Ready {
        port: u16,
    },
    /// The child reported a clean exit (e.g. idle timeout).
    Exiting {
        reason: String,
    },
    /// The child died or reported an error.
    Died {
        reason: String,
    },
}

/// How long the parent waits for the child's first pipe message (model
/// load included) before escalating to [`stop_child`].
const READY_TIMEOUT: Duration = Duration::from_secs(60);

/// Write end of the child→parent control pipe, stored by the child right
/// after fork. `-1` outside fork mode (desktop CLI direct run), which makes
/// [`notify_idle_exit`] a no-op there.
static IDLE_PIPE_FD: AtomicI32 = AtomicI32::new(-1);

/// Fork the server child. Blocking until the child reports ready, dies, or
/// the 60s ready timeout elapses.
///
/// On success a watchdog thread has been spawned that reads the pipe until
/// EOF/exit and forwards [`ChildEvent`]s to `on_event` (terminal events
/// only; readiness is reported via the return value). Must be called from
/// the control thread with no locks held (see the module invariants).
pub fn fork_server(
    cfg: HttpServerConfig,
    on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>,
) -> Result<ForkedServer, String> {
    let mut fds = [0_i32; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        return Err(format!("pipe failed: {}", io::Error::last_os_error()));
    }
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        let error = io::Error::last_os_error();
        unsafe {
            libc::close(fds[0]);
            libc::close(fds[1]);
        }
        return Err(format!("fork failed: {error}"));
    }
    if pid == 0 {
        // Child: the read end belongs to the parent.
        unsafe { libc::close(fds[0]) };
        child_run(fds[1], cfg);
    }

    // Parent: the write end belongs to the child.
    unsafe { libc::close(fds[1]) };
    let mut reader = LineReader::new(fds[0]);
    let deadline = Instant::now() + READY_TIMEOUT;
    let line = loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            stop_child(pid);
            return Err("fork child did not become ready within 60s".to_owned());
        }
        let mut pfd = libc::pollfd {
            fd: fds[0],
            events: libc::POLLIN,
            revents: 0,
        };
        let n = unsafe {
            libc::poll(
                &mut pfd,
                1,
                remaining.as_millis().min(i32::MAX as u128) as i32,
            )
        };
        if n < 0 {
            if interrupted() {
                continue;
            }
            stop_child(pid);
            return Err(format!("poll failed: {}", io::Error::last_os_error()));
        }
        if n == 0 {
            continue; // loop head re-evaluates the deadline
        }
        match reader.line() {
            Some(line) => break line,
            None => {
                // EOF: the child died before reporting anything.
                reap(pid);
                return Err("fork child exited before becoming ready".to_owned());
            }
        }
    };

    let message: Value = serde_json::from_str(&line).unwrap_or(Value::Null);
    match message["type"].as_str() {
        Some("ready") => {
            let port = message["port"].as_u64().unwrap_or_default() as u16;
            // Watchdog: blocking reads until the pipe closes, then reap.
            // A spawn failure here only loses event reporting; the server
            // itself keeps running.
            let _ = thread::Builder::new()
                .name("wekit-llama-watch".to_owned())
                .spawn(move || watch_child(reader, pid, on_event));
            Ok(ForkedServer { pid, port })
        }
        Some("error") => {
            reap(pid);
            Err(message["msg"]
                .as_str()
                .unwrap_or("unknown child error")
                .to_owned())
        }
        Some("exiting") => {
            reap(pid);
            Err(format!(
                "fork child exited during startup: {}",
                message["reason"].as_str().unwrap_or("?")
            ))
        }
        _ => {
            reap(pid);
            Err(format!("unexpected child message: {line}"))
        }
    }
}

/// Terminate a child: SIGTERM, 3s grace, then SIGKILL; reaps either way.
/// Safe to call for an already-dead pid.
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
    for _ in 0..150 {
        if exited(pid) {
            return;
        }
        thread::sleep(Duration::from_millis(20));
    }
}

/// Called by the server's idle watcher inside the child just before
/// `std::process::exit(0)`: report the exit over the control pipe so the
/// parent's watchdog sees a clean `exiting` instead of an EOF "died".
/// No-op in direct (non-fork) mode.
pub fn notify_idle_exit() {
    let fd = IDLE_PIPE_FD.load(Ordering::SeqCst);
    if fd >= 0 {
        pipe_write(fd, &exiting_line("idle"));
    }
}

/// Child-side entry; never returns.
fn child_run(pipe_fd: i32, cfg: HttpServerConfig) -> ! {
    unsafe {
        // WeChat's death must not orphan the inference child.
        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL, 0, 0, 0);
        // The parent died between fork and prctl: follow it.
        if libc::getppid() == 1 {
            libc::_exit(0);
        }
        // Best-effort: make the lmkd prefer this process over the host app
        // (an unprivileged process may only raise the score).
        let fd = libc::open(c"/proc/self/oom_score_adj".as_ptr(), libc::O_WRONLY);
        if fd >= 0 {
            let score = b"900";
            libc::write(fd, score.as_ptr().cast(), score.len());
            libc::close(fd);
        }
        // Background scheduling: never compete with the host app.
        libc::setpriority(libc::PRIO_PROCESS, 0, 19);
        // Detach stdio from the parent's.
        let null = libc::open(c"/dev/null".as_ptr(), libc::O_RDWR);
        if null >= 0 {
            libc::dup2(null, 0);
            libc::dup2(null, 1);
            libc::dup2(null, 2);
            if null > 2 {
                libc::close(null);
            }
        }
    }
    IDLE_PIPE_FD.store(pipe_fd, Ordering::SeqCst);

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("wekit-llama-io")
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(e) => {
            pipe_write(pipe_fd, &error_line(&format!("tokio runtime: {e}")));
            unsafe { libc::_exit(1) }
        }
    };

    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<u16>();
    let server_pipe_fd = pipe_fd;
    runtime.spawn(async move {
        if let Err(e) = server::serve(cfg, move |port| {
            let _ = ready_tx.send(port);
        })
        .await
        {
            pipe_write(server_pipe_fd, &error_line(&e));
            unsafe { libc::_exit(1) }
        }
        // serve only returns Ok if the accept loop ends without an error,
        // which the current design never triggers; treat it as a clean stop.
        pipe_write(server_pipe_fd, &exiting_line("server stopped"));
        unsafe { libc::_exit(0) }
    });
    runtime.block_on(async move {
        if let Ok(port) = ready_rx.await {
            pipe_write(pipe_fd, &ready_line(port));
        }
        // The spawned task serves forever; park this thread with it.
        std::future::pending::<()>().await
    });
    // block_on(pending) never resolves; this only guards the `-> !` type.
    unsafe { libc::_exit(1) }
}

/// Watchdog body: forward pipe messages to `on_event` until the pipe ends,
/// then reap the child so it does not zombify.
fn watch_child(mut reader: LineReader, pid: i32, on_event: Arc<dyn Fn(ChildEvent) + Send + Sync>) {
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
                break;
            }
            Some("error") => {
                on_event(ChildEvent::Died {
                    reason: message["msg"]
                        .as_str()
                        .unwrap_or("unknown child error")
                        .to_owned(),
                });
                break;
            }
            _ => {}
        }
    }
    // EOF or terminal line: the child is gone or about to be.
    reap(pid);
}

// ─────────────────────────────────────────────────────────────────────────────
// Pipe plumbing
// ─────────────────────────────────────────────────────────────────────────────

fn ready_line(port: u16) -> String {
    json_line(&serde_json::json!({ "type": "ready", "port": port }))
}

fn error_line(msg: &str) -> String {
    json_line(&serde_json::json!({ "type": "error", "msg": msg }))
}

fn exiting_line(reason: &str) -> String {
    json_line(&serde_json::json!({ "type": "exiting", "reason": reason }))
}

fn json_line(value: &Value) -> String {
    value.to_string()
}

/// Best-effort raw `write(2)` of one newline-terminated message; never
/// allocates locks on the std io machinery.
fn pipe_write(fd: i32, line: &str) {
    let msg = format!("{line}\n");
    let bytes = msg.as_bytes();
    let mut written = 0;
    while written < bytes.len() {
        let n = unsafe { libc::write(fd, bytes[written..].as_ptr().cast(), bytes.len() - written) };
        if n < 0 {
            if interrupted() {
                continue;
            }
            return; // broken pipe: the parent is going away too
        }
        written += n as usize;
    }
}

/// Buffered newline-delimited reader over a raw fd (blocking reads). Closes
/// the fd on drop.
struct LineReader {
    fd: i32,
    buf: Vec<u8>,
}

impl LineReader {
    fn new(fd: i32) -> Self {
        Self {
            fd,
            buf: Vec::new(),
        }
    }

    /// Next complete line without its `\n`; `None` on EOF or read error.
    fn line(&mut self) -> Option<String> {
        loop {
            if let Some(pos) = self.buf.iter().position(|&b| b == b'\n') {
                let line: Vec<u8> = self.buf.drain(..=pos).collect();
                return Some(String::from_utf8_lossy(&line[..pos]).into_owned());
            }
            let mut chunk = [0_u8; 512];
            let n = unsafe { libc::read(self.fd, chunk.as_mut_ptr().cast(), chunk.len()) };
            if n < 0 {
                if interrupted() {
                    continue;
                }
                return None;
            }
            if n == 0 {
                return None;
            }
            self.buf.extend_from_slice(&chunk[..n as usize]);
        }
    }
}

impl Drop for LineReader {
    fn drop(&mut self) {
        unsafe { libc::close(self.fd) };
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Process helpers
// ─────────────────────────────────────────────────────────────────────────────

fn interrupted() -> bool {
    io::Error::last_os_error().raw_os_error() == Some(libc::EINTR)
}

/// Non-blocking reaping with a short retry window (the child may need a few
/// ms to fully exit after writing its terminal line). `ECHILD` counts as
/// reaped (someone else already collected it).
fn reap(pid: i32) {
    if pid <= 0 {
        return;
    }
    for _ in 0..100 {
        if exited(pid) {
            return;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn exited(pid: i32) -> bool {
    let r = unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) };
    r == pid || (r == -1 && io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD))
}
