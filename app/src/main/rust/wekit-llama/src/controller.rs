//! JNI lifecycle controller for the exec-isolated inference child.
//!
//! Generation-guarded state machine in the shape of
//! `wekit-native/src/read_receipts_server.rs`, adapted to the exec design:
//! the "server thread" is a separate process, so every state transition is
//! either a command executed on the one-shot `wekit-llama-ctl` control
//! thread or a watchdog [`ChildEvent`] applied under the generation guard.
//!
//! Launch invariant: JNI entries only ever talk to this module (`CONTROL` +
//! the control thread + its mpsc), never to [`crate::exec_process`] directly.
//! The control thread calls [`crate::exec_process::spawn_server`] with **no
//! locks held** — every code path between reading `CONTROL` and spawning the
//! child drops the guard first (see [`handle_start`]).
//!
//! The core is deliberately not `#[cfg(target_os = "android")]` so the
//! validation and state-machine paths stay desktop-testable; only the JNI
//! export surface in `lib.rs` is android-only.

use std::path::Path;
use std::sync::mpsc;
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};

use serde_json::json;

use crate::exec_process::{self, ChildEvent, ExecServerConfig, SpawnedServer};
use crate::llama::{Backend, EngineConfig, detect_threads};
use crate::server::HttpServerConfig;

/// Idle self-exit after 10 minutes without a request (spec §3.2 preset).
const IDLE_TIMEOUT_SECS: u64 = 600;
/// Inclusive n_ctx bounds accepted from the Kotlin caller.
const N_CTX_MIN: u32 = 4096;
const N_CTX_MAX: u32 = 262_144;

/// Lifecycle state mirrored into the JNI status JSON.
#[derive(Clone, Debug, PartialEq)]
pub enum Status {
    Stopped,
    Starting,
    Running { port: u16, pid: i32 },
    Failed { error: String },
}

impl Status {
    /// `{"state":"stopped|starting|running|failed","port":N,"pid":N,"error":"…"}`
    /// (`port`/`pid`/`error` are `null` when not applicable).
    pub fn to_json(&self) -> String {
        let (state, port, pid, error) = match self {
            Status::Stopped => ("stopped", None, None, None),
            Status::Starting => ("starting", None, None, None),
            Status::Running { port, pid } => ("running", Some(port), Some(pid), None),
            Status::Failed { error } => ("failed", None, None, Some(error.as_str())),
        };
        json!({ "state": state, "port": port, "pid": pid, "error": error }).to_string()
    }
}

/// Everything the controller knows about the (at most one) child.
pub struct ControlState {
    pub generation: u64,
    pub status: Status,
    /// The `(bootstrap APK, native library, model, n_ctx, backend)` tuple the
    /// starting/running child belongs to; `None` whenever no child is active.
    pub active: Option<(String, String, String, u32, Backend)>,
    pub child: Option<SpawnedServer>,
    /// Quiescence waiter: joins once a terminal `ChildEvent` (or a stop) has
    /// been processed for the child's generation, so restart/stop sequences
    /// know the old child's events can no longer arrive.
    pub watcher: Option<JoinHandle<()>>,
}

impl Default for ControlState {
    fn default() -> Self {
        Self {
            generation: 0,
            status: Status::Stopped,
            active: None,
            child: None,
            watcher: None,
        }
    }
}

static CONTROL: OnceLock<Mutex<ControlState>> = OnceLock::new();
static CONTROL_TX: OnceLock<mpsc::Sender<Command>> = OnceLock::new();

enum Command {
    Start {
        bootstrap_apk: String,
        native_library: String,
        model_path: String,
        n_ctx: u32,
        backend: String,
        temp: f32,
        top_p: f32,
        top_k: i32,
        reply: mpsc::Sender<Result<(), String>>,
    },
    Stop {
        reply: mpsc::Sender<()>,
    },
}

/// Start (or reuse) the inference child for `(bootstrap_apk, native_library,
/// model_path, n_ctx, backend)` with the given sampling preset. Blocks until
/// the child is ready or the start has failed (bounded by `spawn_server`'s
/// 60s ready timeout plus the teardown escalation).
///
/// Same tuple and already `Running` → immediate `Ok`. Different tuple →
/// stop the old child, start a new one. Validation failures (and an unknown
/// `backend` spelling) mark the status `Failed`.
#[allow(clippy::too_many_arguments)] // Mirrors the fixed parent JNI contract.
pub fn start(
    bootstrap_apk: &str,
    native_library: &str,
    model_path: &str,
    n_ctx: u32,
    backend: &str,
    temp: f32,
    top_p: f32,
    top_k: i32,
) -> Result<(), String> {
    let Some(backend) = Backend::parse(backend) else {
        let error = format!("unknown backend: {backend}");
        mark_failed(&error);
        return Err(error);
    };
    if let Err(error) = validate(bootstrap_apk, native_library, model_path, n_ctx) {
        mark_failed(&error);
        return Err(error);
    }
    let (tx, rx) = mpsc::channel();
    control_tx()
        .send(Command::Start {
            bootstrap_apk: bootstrap_apk.to_owned(),
            native_library: native_library.to_owned(),
            model_path: model_path.to_owned(),
            n_ctx,
            backend: backend.as_str().to_owned(),
            temp,
            top_p,
            top_k,
            reply: tx,
        })
        .map_err(|_| "control thread unavailable".to_owned())?;
    rx.recv()
        .map_err(|_| "control thread dropped a pending start".to_owned())?
}

/// Stop the child (SIGTERM → 3s → SIGKILL) and reset the state to
/// `Stopped`; returns once the old child's watcher has gone quiet.
pub fn stop() {
    let (tx, rx) = mpsc::channel();
    if control_tx().send(Command::Stop { reply: tx }).is_ok() {
        let _ = rx.recv();
    }
}

pub fn status() -> Status {
    control().status.clone()
}

pub fn status_json() -> String {
    status().to_json()
}

/// Mark the state `Failed` without touching the control thread (JNI-side
/// pre-validation failures); invalidates any in-flight generation.
pub fn mark_failed(error: &str) {
    let mut state = control();
    state.generation += 1;
    state.status = Status::Failed {
        error: error.to_owned(),
    };
    state.active = None;
}

fn validate(
    bootstrap_apk: &str,
    native_library: &str,
    model_path: &str,
    n_ctx: u32,
) -> Result<(), String> {
    if !Path::new(bootstrap_apk).is_absolute() {
        return Err("bootstrap APK path must be absolute".to_owned());
    }
    if !Path::new(native_library).is_absolute() {
        return Err("native library path must be absolute".to_owned());
    }
    if !Path::new(model_path).is_absolute() {
        return Err("model path must be absolute".to_owned());
    }
    if !(N_CTX_MIN..=N_CTX_MAX).contains(&n_ctx) {
        return Err(format!(
            "n_ctx must be within {N_CTX_MIN}..={N_CTX_MAX}, got {n_ctx}"
        ));
    }
    Ok(())
}

fn control() -> std::sync::MutexGuard<'static, ControlState> {
    CONTROL
        .get_or_init(|| Mutex::new(ControlState::default()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn control_tx() -> &'static mpsc::Sender<Command> {
    CONTROL_TX.get_or_init(|| {
        let (tx, rx) = mpsc::channel::<Command>();
        // A spawn failure leaves `tx` disconnected: every later command
        // send fails and callers get a clean Err instead of a panic.
        let _ = thread::Builder::new()
            .name("wekit-llama-ctl".to_owned())
            .spawn(move || control_loop(rx));
        tx
    })
}

fn control_loop(rx: mpsc::Receiver<Command>) {
    while let Ok(command) = rx.recv() {
        match command {
            Command::Start {
                bootstrap_apk,
                native_library,
                model_path,
                n_ctx,
                backend,
                temp,
                top_p,
                top_k,
                reply,
            } => {
                let _ = reply.send(handle_start(
                    bootstrap_apk,
                    native_library,
                    model_path,
                    n_ctx,
                    backend,
                    temp,
                    top_p,
                    top_k,
                ));
            }
            Command::Stop { reply } => {
                handle_stop();
                let _ = reply.send(());
            }
        }
    }
}

#[allow(clippy::too_many_arguments)] // Preserves the command fields without a test-only wrapper.
fn handle_start(
    bootstrap_apk: String,
    native_library: String,
    model_path: String,
    n_ctx: u32,
    backend: String,
    temp: f32,
    top_p: f32,
    top_k: i32,
) -> Result<(), String> {
    let backend = Backend::parse(&backend).expect("backend was validated before dispatch");
    let tuple = (
        bootstrap_apk.clone(),
        native_library.clone(),
        model_path.clone(),
        n_ctx,
        backend,
    );

    // Phase 1 (locked): reuse-or-restart decision; bump the generation and
    // take the old child out of the state machine.
    let (generation, old_child, old_watcher) = {
        let mut state = control();
        if state.active.as_ref() == Some(&tuple) && matches!(state.status, Status::Running { .. }) {
            return Ok(());
        }
        state.generation += 1;
        state.status = Status::Starting;
        state.active = Some(tuple);
        (state.generation, state.child.take(), state.watcher.take())
    };

    // Phase 2 (NO locks held — the launch invariant): tear the old child down
    // and spawn the new one. All of stop_child/join/spawn_server run lock-free.
    if let Some(child) = old_child {
        exec_process::stop_child(child.pid);
    }
    if let Some(watcher) = old_watcher {
        let _ = watcher.join();
    }
    let cfg = ExecServerConfig {
        bootstrap_apk,
        native_library,
        server: HttpServerConfig {
            engine: EngineConfig {
                model_path,
                n_ctx,
                threads: detect_threads(),
                backend,
                temp,
                top_p,
                top_k,
                idle_timeout_secs: IDLE_TIMEOUT_SECS,
            },
            bind_port: 0,
        },
    };
    let (done_tx, done_rx) = mpsc::channel::<()>();
    let on_event = Arc::new(move |event: ChildEvent| apply_event(generation, &done_tx, event));
    match exec_process::spawn_server(cfg, on_event) {
        Ok(child) => {
            let watcher = thread::Builder::new()
                .name("wekit-llama-quiesce".to_owned())
                .spawn(move || {
                    let _ = done_rx.recv();
                })
                .ok();
            let mut state = control();
            if state.generation == generation && state.status == Status::Starting {
                // The normal path: no terminal event landed while we spawned.
                state.status = Status::Running {
                    port: child.port,
                    pid: child.pid,
                };
                state.child = Some(child);
                state.watcher = watcher;
                Ok(())
            } else if state.generation != generation {
                // Superseded by a stop/restart: the freshly-spawned child is
                // still ours and still alive — kill it here, or it would keep
                // serving (and holding its model mmap) until the 600s idle
                // exit. Release the lock first (stop_child blocks up to ~6s).
                drop(state);
                exec_process::stop_child(child.pid);
                Err("start superseded before the child stabilized".to_owned())
            } else {
                // A watchdog Died event won the race (the child died right
                // after reporting ready); its status already reflects that
                // and the child is dead — no kill needed.
                Err(match &state.status {
                    Status::Failed { error } => error.clone(),
                    _ => "start superseded before the child stabilized".to_owned(),
                })
            }
        }
        Err(error) => {
            let mut state = control();
            if state.generation == generation {
                state.status = Status::Failed {
                    error: error.clone(),
                };
                state.active = None;
            }
            Err(error)
        }
    }
}

fn handle_stop() {
    // Bump the generation first so terminal events from the dying child are
    // ignored by apply_event, then tear down with no lock held.
    let (old_child, old_watcher) = {
        let mut state = control();
        state.generation += 1;
        (state.child.take(), state.watcher.take())
    };
    if let Some(child) = old_child {
        exec_process::stop_child(child.pid);
    }
    if let Some(watcher) = old_watcher {
        let _ = watcher.join();
    }
    let mut state = control();
    state.status = Status::Stopped;
    state.active = None;
}

/// Watchdog-event sink: only events belonging to the current generation may
/// mutate the state machine. Terminal events always signal the quiescence
/// watcher, even when stale (so a stop's join returns).
fn apply_event(generation: u64, done: &mpsc::Sender<()>, event: ChildEvent) {
    match event {
        ChildEvent::Ready { .. } => {} // readiness is consumed by spawn_server's return
        ChildEvent::Exiting { .. } | ChildEvent::Died { .. } => {
            let _ = done.send(());
        }
    }
    let mut state = control();
    if state.generation != generation {
        return;
    }
    match event {
        ChildEvent::Ready { .. } => {}
        ChildEvent::Exiting { .. } => {
            state.status = Status::Stopped;
            state.active = None;
            state.child = None;
            state.watcher = None;
        }
        ChildEvent::Died { reason } => {
            state.status = Status::Failed { error: reason };
            state.active = None;
            state.child = None;
            state.watcher = None;
        }
    }
}
