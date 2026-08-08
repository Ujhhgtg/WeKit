use serde::Serialize;
use std::{
    future,
    net::{Ipv4Addr, SocketAddr},
    path::{Path, PathBuf},
    sync::{Arc, Condvar, Mutex, OnceLock},
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};
use tokio::sync::oneshot;
use wekit_read_receipts_server::{
    BoundServer as ServerHandle, RouteProfile, ServerConfig, ServerError, bind_and_serve,
};

use crate::{loge, logi};

const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);
#[cfg(not(test))]
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);
#[cfg(test)]
const SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(50);
const MAX_ERROR_CHARS: usize = 256;

const DIFFERENT_CONFIG_ERROR: &str =
    "read receipts server is already active with a different configuration";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BoundServer {
    local_addr: SocketAddr,
}

impl BoundServer {
    pub fn local_addr(self) -> SocketAddr {
        self.local_addr
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ServerStatus {
    Stopped,
    Starting,
    Running { port: u16 },
    Stopping { port: Option<u16> },
    Failed { error: String },
}

impl ServerStatus {
    pub fn to_json(&self) -> String {
        #[derive(Serialize)]
        struct WireStatus<'a> {
            state: &'static str,
            port: Option<u16>,
            error: Option<&'a str>,
        }

        let wire = match self {
            Self::Stopped => WireStatus {
                state: "stopped",
                port: None,
                error: None,
            },
            Self::Starting => WireStatus {
                state: "starting",
                port: None,
                error: None,
            },
            Self::Running { port } => WireStatus {
                state: "running",
                port: Some(*port),
                error: None,
            },
            Self::Stopping { port } => WireStatus {
                state: "stopping",
                port: *port,
                error: None,
            },
            Self::Failed { error } => WireStatus {
                state: "failed",
                port: None,
                error: Some(error),
            },
        };
        serde_json::to_string(&wire).expect("serializing a read receipts server status cannot fail")
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ActiveConfig {
    database_path: PathBuf,
    requested_port: u16,
}

struct Lifecycle {
    generation: u64,
    status: ServerStatus,
    active_config: Option<ActiveConfig>,
    startup: Option<Arc<StartupSignal>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl Default for Lifecycle {
    fn default() -> Self {
        Self {
            generation: 0,
            status: ServerStatus::Stopped,
            active_config: None,
            startup: None,
            shutdown: None,
            thread: None,
        }
    }
}

#[derive(Default)]
struct SharedLifecycle {
    lifecycle: Mutex<Lifecycle>,
}

static LIFECYCLE: OnceLock<SharedLifecycle> = OnceLock::new();

struct StartupSignal {
    deadline: Instant,
    result: Mutex<Option<Result<BoundServer, String>>>,
    changed: Condvar,
}

impl StartupSignal {
    fn new(deadline: Instant) -> Self {
        Self {
            deadline,
            result: Mutex::new(None),
            changed: Condvar::new(),
        }
    }

    fn publish(&self, result: Result<BoundServer, String>) -> Result<BoundServer, String> {
        let mut stored = self.result.lock().unwrap();
        if stored.is_none() {
            *stored = Some(result);
            self.changed.notify_all();
        }
        stored.as_ref().unwrap().clone()
    }

    fn wait_until(&self) -> Option<Result<BoundServer, String>> {
        #[cfg(test)]
        TEST_STARTUP_WAITERS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let mut stored = self.result.lock().unwrap();
        while stored.is_none() {
            let remaining = self.deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let (guard, wait) = self.changed.wait_timeout(stored, remaining).unwrap();
            stored = guard;
            if wait.timed_out() {
                break;
            }
        }
        let result = stored.clone();
        #[cfg(test)]
        TEST_STARTUP_WAITERS.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
        result
    }
}

fn shared_lifecycle() -> &'static SharedLifecycle {
    LIFECYCLE.get_or_init(SharedLifecycle::default)
}

pub fn start(database_path: &str, port: u16) -> Result<BoundServer, String> {
    let database_path = Path::new(database_path);
    if !database_path.is_absolute() {
        return Err("database path must be absolute".to_owned());
    }
    let config = ActiveConfig {
        database_path: database_path.to_path_buf(),
        requested_port: port,
    };
    let deadline = Instant::now() + STARTUP_TIMEOUT;

    loop {
        let shared = shared_lifecycle();
        let mut lifecycle = shared.lifecycle.lock().unwrap();

        if let Some(active_config) = &lifecycle.active_config {
            if active_config != &config {
                return Err(DIFFERENT_CONFIG_ERROR.to_owned());
            }
            match &lifecycle.status {
                ServerStatus::Running { port } => {
                    return Ok(BoundServer {
                        local_addr: SocketAddr::from((Ipv4Addr::LOCALHOST, *port)),
                    });
                }
                ServerStatus::Starting => {
                    let generation = lifecycle.generation;
                    let startup = Arc::clone(lifecycle.startup.as_ref().unwrap());
                    drop(lifecycle);
                    return wait_for_existing_start(generation, startup);
                }
                ServerStatus::Stopping { .. } => {
                    return Err("read receipts server is stopping".to_owned());
                }
                ServerStatus::Failed { error } => return Err(error.clone()),
                ServerStatus::Stopped => {}
            }
        }

        if lifecycle.thread.is_some() {
            let server_thread = lifecycle.thread.take().unwrap();
            drop(lifecycle);
            let _ = server_thread.join();
            continue;
        }

        lifecycle.generation = lifecycle.generation.wrapping_add(1);
        let generation = lifecycle.generation;
        let (shutdown_sender, shutdown_receiver) = oneshot::channel();
        let (startup_sender, startup_receiver) = std::sync::mpsc::sync_channel(1);
        let startup = Arc::new(StartupSignal::new(deadline));
        lifecycle.status = ServerStatus::Starting;
        lifecycle.active_config = Some(config.clone());
        lifecycle.startup = Some(Arc::clone(&startup));
        lifecycle.shutdown = Some(shutdown_sender);

        let thread_config = config.clone();
        let thread_startup = Arc::clone(&startup);
        let server_thread = thread::Builder::new()
            .name("wekit-read-receipts".to_owned())
            .spawn(move || {
                let terminal_status = run_server(
                    generation,
                    thread_config,
                    shutdown_receiver,
                    thread_startup,
                    startup_sender,
                );
                finish_owner(generation, terminal_status);
            });
        match server_thread {
            Ok(server_thread) => lifecycle.thread = Some(server_thread),
            Err(error) => {
                loge!("failed to create read receipts server thread: {error}");
                let message = bounded_error("failed to create read receipts server thread");
                lifecycle.status = ServerStatus::Failed {
                    error: message.clone(),
                };
                lifecycle.active_config = None;
                lifecycle.startup = None;
                lifecycle.shutdown = None;
                return Err(message);
            }
        }
        drop(lifecycle);

        let remaining = startup.deadline.saturating_duration_since(Instant::now());
        return match startup_receiver.recv_timeout(remaining) {
            Ok(Ok(bound_server)) => confirm_running(generation, bound_server),
            Ok(Err(error)) => Err(error),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                finish_startup_wait(generation, &startup)
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                let result = startup.publish(Err("read receipts server startup failed".to_owned()));
                if result.is_err() {
                    cancel_startup(generation);
                }
                result.and_then(|bound_server| confirm_running(generation, bound_server))
            }
        };
    }
}

pub fn stop() {
    let shared = shared_lifecycle();
    let (startup, shutdown) = {
        let mut lifecycle = shared.lifecycle.lock().unwrap();
        let port = match lifecycle.status {
            ServerStatus::Running { port } => Some(port),
            ServerStatus::Starting => None,
            _ => return,
        };
        lifecycle.status = ServerStatus::Stopping { port };
        (lifecycle.startup.clone(), lifecycle.shutdown.take())
    };
    if let Some(startup) = startup {
        let _ = startup.publish(Err("read receipts server startup was cancelled".to_owned()));
    }
    if let Some(shutdown) = shutdown {
        let _ = shutdown.send(());
    }
}

pub fn status() -> ServerStatus {
    shared_lifecycle().lifecycle.lock().unwrap().status.clone()
}

fn wait_for_existing_start(
    generation: u64,
    startup: Arc<StartupSignal>,
) -> Result<BoundServer, String> {
    match startup.wait_until() {
        Some(Ok(bound_server)) => confirm_running(generation, bound_server),
        Some(Err(error)) => Err(error),
        None => finish_startup_wait(generation, &startup),
    }
}

fn finish_startup_wait(generation: u64, startup: &StartupSignal) -> Result<BoundServer, String> {
    let result = startup.publish(Err("read receipts server startup timed out".to_owned()));
    if result.is_err() {
        cancel_startup(generation);
    }
    result.and_then(|bound_server| confirm_running(generation, bound_server))
}

fn confirm_running(generation: u64, bound_server: BoundServer) -> Result<BoundServer, String> {
    let lifecycle = shared_lifecycle().lifecycle.lock().unwrap();
    if lifecycle.generation == generation
        && matches!(
            lifecycle.status,
            ServerStatus::Running { port } if port == bound_server.local_addr().port()
        )
    {
        Ok(bound_server)
    } else {
        Err("read receipts server stopped during startup".to_owned())
    }
}

fn run_server(
    generation: u64,
    config: ActiveConfig,
    shutdown_receiver: oneshot::Receiver<()>,
    startup: Arc<StartupSignal>,
    startup_sender: std::sync::mpsc::SyncSender<Result<BoundServer, String>>,
) -> ServerStatus {
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("wekit-read-receipts-io")
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            loge!("failed to create read receipts Tokio runtime: {error}");
            let message = bounded_error("failed to create read receipts server runtime");
            let result = startup.publish(Err(message));
            let _ = startup_sender.send(result.clone());
            return terminal_startup_failure(result);
        }
    };

    let server_config = ServerConfig {
        database_path: config.database_path,
        bind_addr: Ipv4Addr::LOCALHOST.into(),
        bind_port: config.requested_port,
        route_profile: RouteProfile::Embedded,
    };
    #[cfg(test)]
    spawn_test_runtime_task(&runtime);
    #[cfg(test)]
    wait_on_test_gate(&TEST_BEFORE_BIND_GATE);
    let server = match runtime.block_on(bind_and_serve(server_config, future::pending())) {
        Ok(server) => server,
        Err(error) => {
            let message = server_error_message(&error, "startup");
            loge!("{message}");
            let result = startup.publish(Err(message));
            let _ = startup_sender.send(result.clone());
            let terminal_status = terminal_startup_failure(result);
            drop(runtime);
            return terminal_status;
        }
    };
    let bound_server = BoundServer {
        local_addr: server.local_addr(),
    };

    if !mark_running(generation, bound_server.local_addr().port()) {
        let error = "read receipts server startup was cancelled".to_owned();
        let result = startup.publish(Err(error));
        let _ = startup_sender.send(result);
        return shutdown_server(runtime, server, shutdown_receiver);
    }
    logi!(
        "read receipts server listening on 127.0.0.1:{}",
        bound_server.local_addr().port()
    );
    let result = startup.publish(Ok(bound_server));
    if result.is_err() {
        request_stop(generation);
    }
    if startup_sender.send(result).is_err() {
        request_stop(generation);
    }
    shutdown_server(runtime, server, shutdown_receiver)
}

fn terminal_startup_failure(result: Result<BoundServer, String>) -> ServerStatus {
    ServerStatus::Failed {
        error: result
            .err()
            .unwrap_or_else(|| "read receipts server startup was cancelled".to_owned()),
    }
}

fn shutdown_server(
    runtime: tokio::runtime::Runtime,
    server: ServerHandle,
    shutdown_receiver: oneshot::Receiver<()>,
) -> ServerStatus {
    let result = runtime.block_on(async {
        let _ = shutdown_receiver.await;
        tokio::time::timeout(SHUTDOWN_TIMEOUT, async {
            let result = server.shutdown().await;
            #[cfg(test)]
            await_test_shutdown_gate().await;
            result
        })
        .await
    });
    let completion = match result {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => {
            let message = server_error_message(&error, "shutdown");
            loge!("{message}");
            Err(message)
        }
        Err(_) => {
            #[cfg(test)]
            TEST_SHUTDOWN_TIMED_OUT.store(true, std::sync::atomic::Ordering::SeqCst);
            let message = bounded_error("read receipts server shutdown timed out");
            loge!("{message}");
            Err(message)
        }
    };
    drop(runtime);
    match completion {
        Ok(()) => ServerStatus::Stopped,
        Err(error) => ServerStatus::Failed { error },
    }
}

fn mark_running(generation: u64, port: u16) -> bool {
    let shared = shared_lifecycle();
    let mut lifecycle = shared.lifecycle.lock().unwrap();
    if lifecycle.generation != generation || !matches!(lifecycle.status, ServerStatus::Starting) {
        return false;
    }
    lifecycle.status = ServerStatus::Running { port };
    true
}

fn request_stop(generation: u64) {
    let shared = shared_lifecycle();
    let shutdown = {
        let mut lifecycle = shared.lifecycle.lock().unwrap();
        if lifecycle.generation != generation {
            return;
        }
        let port = match lifecycle.status {
            ServerStatus::Running { port } => Some(port),
            ServerStatus::Starting => None,
            _ => return,
        };
        lifecycle.status = ServerStatus::Stopping { port };
        lifecycle.shutdown.take()
    };
    if let Some(shutdown) = shutdown {
        let _ = shutdown.send(());
    }
}

fn cancel_startup(generation: u64) {
    let shared = shared_lifecycle();
    let shutdown = {
        let mut lifecycle = shared.lifecycle.lock().unwrap();
        if lifecycle.generation != generation {
            return;
        }
        let port = match lifecycle.status {
            ServerStatus::Running { port } => Some(port),
            ServerStatus::Starting => None,
            _ => return,
        };
        lifecycle.status = ServerStatus::Stopping { port };
        lifecycle.shutdown.take()
    };
    if let Some(shutdown) = shutdown {
        let _ = shutdown.send(());
    }
}

fn finish_owner(generation: u64, terminal_status: ServerStatus) {
    let shared = shared_lifecycle();
    let mut lifecycle = shared.lifecycle.lock().unwrap();
    if lifecycle.generation != generation {
        return;
    }
    lifecycle.status = terminal_status;
    lifecycle.active_config = None;
    lifecycle.startup = None;
    lifecycle.shutdown = None;
}

fn server_error_message(error: &ServerError, operation: &str) -> String {
    let category = match error {
        ServerError::Database(_) => "database initialization",
        ServerError::Io(_) => "listener I/O",
        ServerError::Task(_) => "server task",
    };
    bounded_error(&format!(
        "read receipts server {operation} failed ({category})"
    ))
}

fn bounded_error(message: &str) -> String {
    message.chars().take(MAX_ERROR_CHARS).collect()
}

#[cfg(test)]
type TestGate = (Arc<std::sync::Barrier>, Arc<std::sync::Barrier>);

#[cfg(test)]
static TEST_BEFORE_BIND_GATE: Mutex<Option<TestGate>> = Mutex::new(None);
#[cfg(test)]
static TEST_RUNTIME_TASK_GATE: Mutex<Option<TestGate>> = Mutex::new(None);
#[cfg(test)]
static TEST_SHUTDOWN_TIMEOUT_GATE: Mutex<Option<TestGate>> = Mutex::new(None);
#[cfg(test)]
static TEST_STARTUP_WAITERS: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(0);
#[cfg(test)]
static TEST_SHUTDOWN_TIMED_OUT: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

#[cfg(test)]
fn wait_on_test_gate(gate: &Mutex<Option<TestGate>>) {
    if let Some((entered, release)) = gate.lock().unwrap().clone() {
        entered.wait();
        release.wait();
    }
}

#[cfg(test)]
fn set_test_before_bind_gate(gate: Option<TestGate>) {
    *TEST_BEFORE_BIND_GATE.lock().unwrap() = gate;
}

#[cfg(test)]
fn set_test_runtime_task_gate(gate: Option<TestGate>) {
    *TEST_RUNTIME_TASK_GATE.lock().unwrap() = gate;
}

#[cfg(test)]
fn spawn_test_runtime_task(runtime: &tokio::runtime::Runtime) {
    if let Some((entered, release)) = TEST_RUNTIME_TASK_GATE.lock().unwrap().clone() {
        runtime.spawn_blocking(move || {
            entered.wait();
            release.wait();
        });
    }
}

#[cfg(test)]
fn set_test_shutdown_timeout_gate(gate: Option<TestGate>) {
    TEST_SHUTDOWN_TIMED_OUT.store(false, std::sync::atomic::Ordering::SeqCst);
    *TEST_SHUTDOWN_TIMEOUT_GATE.lock().unwrap() = gate;
}

#[cfg(test)]
async fn await_test_shutdown_gate() {
    if let Some((entered, release)) = TEST_SHUTDOWN_TIMEOUT_GATE.lock().unwrap().clone() {
        let _ = tokio::task::spawn_blocking(move || {
            entered.wait();
            release.wait();
        })
        .await;
    }
}

#[cfg(test)]
fn test_shutdown_timed_out() -> bool {
    TEST_SHUTDOWN_TIMED_OUT.load(std::sync::atomic::Ordering::SeqCst)
}

#[cfg(test)]
fn startup_waiter_count() -> usize {
    TEST_STARTUP_WAITERS.load(std::sync::atomic::Ordering::SeqCst)
}

#[cfg(test)]
fn lifecycle_generation() -> u64 {
    shared_lifecycle().lifecycle.lock().unwrap().generation
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        io::{Read, Write},
        net::TcpStream,
        path::{Path, PathBuf},
        sync::{
            Arc, Barrier, Mutex,
            atomic::{AtomicU64, Ordering},
        },
        thread,
        time::{Duration, Instant},
    };

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            static NEXT_ID: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "wekit-native-read-receipts-{}-{}",
                std::process::id(),
                NEXT_ID.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn database_path(&self, name: &str) -> PathBuf {
            self.0.join(name)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.0).unwrap();
        }
    }

    fn get(port: u16, path: &str) -> String {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(
            stream,
            "GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        .unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        response
    }

    fn wait_until_stopped() {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !matches!(status(), ServerStatus::Stopped) {
            assert!(
                Instant::now() < deadline,
                "server did not stop; current status: {:?}",
                status()
            );
            thread::sleep(Duration::from_millis(10));
        }
    }

    fn wait_until_failed() {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !matches!(status(), ServerStatus::Failed { .. }) {
            assert!(
                Instant::now() < deadline,
                "server did not fail; current status: {:?}",
                status()
            );
            thread::sleep(Duration::from_millis(1));
        }
    }

    fn wait_for_startup_waiter() {
        let deadline = Instant::now() + Duration::from_secs(5);
        while startup_waiter_count() == 0 {
            assert!(
                Instant::now() < deadline,
                "identical start never began waiting"
            );
            thread::sleep(Duration::from_millis(1));
        }
    }

    fn wait_until_shutdown_timed_out() {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !test_shutdown_timed_out() {
            assert!(Instant::now() < deadline, "shutdown did not time out");
            thread::sleep(Duration::from_millis(1));
        }
    }

    #[test]
    fn rejects_relative_database_paths() {
        let _guard = TEST_LOCK.lock().unwrap();
        assert_eq!(
            start(Path::new("relative/read_receipts.db").to_str().unwrap(), 0),
            Err("database path must be absolute".to_owned())
        );
        assert_eq!(status(), ServerStatus::Stopped);
    }

    #[test]
    fn lifecycle_is_loopback_embedded_idempotent_and_conflict_safe() {
        let _guard = TEST_LOCK.lock().unwrap();
        let directory = TestDirectory::new();
        let database_path = directory.database_path("read_receipts.db");
        let database_path = database_path.to_str().unwrap();

        let first = start(database_path, 0).unwrap();
        assert!(first.local_addr().ip().is_loopback());
        assert_ne!(first.local_addr().port(), 0);
        assert_eq!(
            status(),
            ServerStatus::Running {
                port: first.local_addr().port()
            }
        );
        assert!(get(first.local_addr().port(), "/health").starts_with("HTTP/1.1 204"));
        assert!(get(first.local_addr().port(), "/").starts_with("HTTP/1.1 404"));

        assert_eq!(start(database_path, 0).unwrap(), first);
        let conflict_path = directory.database_path("conflict.db");
        assert_eq!(
            start(conflict_path.to_str().unwrap(), 0),
            Err("read receipts server is already active with a different configuration".to_owned())
        );

        stop();
        stop();
        wait_until_stopped();
    }

    #[test]
    fn concurrent_failed_starts_share_one_generation_until_owner_completion() {
        let _guard = TEST_LOCK.lock().unwrap();
        let directory = TestDirectory::new();
        let database_path = directory.database_path("read_receipts.db");
        let database_path = database_path.to_str().unwrap().to_owned();
        let occupied = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = occupied.local_addr().unwrap().port();
        let before_bind_entered = Arc::new(Barrier::new(2));
        let before_bind_release = Arc::new(Barrier::new(2));
        let runtime_task_entered = Arc::new(Barrier::new(2));
        let runtime_task_release = Arc::new(Barrier::new(2));
        set_test_before_bind_gate(Some((
            Arc::clone(&before_bind_entered),
            Arc::clone(&before_bind_release),
        )));
        set_test_runtime_task_gate(Some((
            Arc::clone(&runtime_task_entered),
            Arc::clone(&runtime_task_release),
        )));
        let initial_generation = lifecycle_generation();

        let first_path = database_path.clone();
        let first = thread::spawn(move || start(&first_path, port));
        runtime_task_entered.wait();
        before_bind_entered.wait();
        let second_path = database_path.clone();
        let second = thread::spawn(move || start(&second_path, port));
        wait_for_startup_waiter();
        before_bind_release.wait();

        let first_error = first.join().unwrap().unwrap_err();
        let second_error = second.join().unwrap().unwrap_err();
        assert_eq!(second_error, first_error);
        assert_eq!(start(&database_path, port), Err(first_error));
        assert_eq!(lifecycle_generation(), initial_generation.wrapping_add(1));
        assert_eq!(status(), ServerStatus::Starting);

        runtime_task_release.wait();
        wait_until_failed();
        set_test_before_bind_gate(None);
        set_test_runtime_task_gate(None);
        drop(occupied);
        let restarted = start(&database_path, port).unwrap();
        assert_eq!(restarted.local_addr().port(), port);
        stop();
        wait_until_stopped();
    }

    #[test]
    fn timed_out_shutdown_stays_nonrestartable_until_runtime_work_finishes() {
        let _guard = TEST_LOCK.lock().unwrap();
        let directory = TestDirectory::new();
        let database_path = directory.database_path("read_receipts.db");
        let database_path = database_path.to_str().unwrap();
        let server = start(database_path, 0).unwrap();
        let shutdown_entered = Arc::new(Barrier::new(2));
        let shutdown_release = Arc::new(Barrier::new(2));
        set_test_shutdown_timeout_gate(Some((
            Arc::clone(&shutdown_entered),
            Arc::clone(&shutdown_release),
        )));

        let stop_started = Instant::now();
        stop();
        assert!(stop_started.elapsed() < Duration::from_millis(100));
        shutdown_entered.wait();
        wait_until_shutdown_timed_out();
        assert_eq!(
            status(),
            ServerStatus::Stopping {
                port: Some(server.local_addr().port())
            }
        );
        assert_eq!(
            start(database_path, 0),
            Err("read receipts server is stopping".to_owned())
        );

        shutdown_release.wait();
        wait_until_failed();
        set_test_shutdown_timeout_gate(None);
        let restarted = start(database_path, 0).unwrap();
        assert_ne!(restarted.local_addr().port(), 0);
        stop();
        wait_until_stopped();
    }
}
