use std::io;
use std::sync::Arc;
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use wekit_llama::exec_process::{self, ChildEvent, SpawnedServer, stop_child};

fn spawn_test_shell(script: &str) -> Result<SpawnedServer, String> {
    exec_process::spawn_test_shell(script)
}

fn spawn_test_program(program: &str) -> Result<SpawnedServer, String> {
    exec_process::spawn_test_program(program)
}

fn assert_child_reaped(pid: i32) {
    assert_eq!(
        unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) },
        -1
    );
    assert_eq!(
        io::Error::last_os_error().raw_os_error(),
        Some(libc::ECHILD)
    );
}

fn assert_child_reaped_eventually(pid: i32) {
    for _ in 0..200 {
        let result = unsafe { libc::kill(pid, 0) };
        if result == -1 && io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH) {
            assert_child_reaped(pid);
            return;
        }
        thread::sleep(Duration::from_millis(10));
    }
    panic!("watchdog did not reap child {pid}");
}

fn event_name(event: ChildEvent) -> String {
    match event {
        ChildEvent::Ready { port } => format!("ready port={port}"),
        ChildEvent::Exiting { reason } => format!("exiting: {reason}"),
        ChildEvent::Died { reason } => format!("died: {reason}"),
    }
}

#[test]
fn real_exec_child_reports_ready_and_is_reaped() {
    let child =
        spawn_test_shell(r#"printf '{\"type\":\"ready\",\"port\":43123}\n' >&$1; sleep 30"#)
            .unwrap();
    assert_eq!(child.port, 43123);
    stop_child(child.pid);
    assert_child_reaped(child.pid);
}

#[test]
fn exec_failure_returns_terminal_error_and_reaps() {
    let error = spawn_test_program("/definitely/missing/app_process").unwrap_err();
    assert!(error.contains("execve app_process64 failed"));
}

#[test]
fn partial_startup_line_cannot_bypass_ready_deadline() {
    let started = Instant::now();
    let error = exec_process::spawn_test_shell_timeout(
        r#"printf '{\"type\":\"ready\"' >&$1; sleep 30"#,
        Duration::from_millis(200),
    )
    .unwrap_err();
    assert!(
        error.contains("did not become ready"),
        "unexpected: {error}"
    );
    assert!(started.elapsed() < Duration::from_secs(2));
}

#[test]
fn watchdog_terminal_event_terminates_and_reaps_child() {
    let (send, receive) = mpsc::channel();
    let child = exec_process::spawn_test_shell_with_events(
        r#"printf '{\"type\":\"ready\",\"port\":43123}\n' >&$1; printf '{\"type\":\"error\",\"msg\":\"boom\"}\n' >&$1; sleep 30"#,
        Arc::new(move |event| {
            let _ = send.send(event_name(event));
        }),
    )
    .unwrap();
    assert_eq!(
        receive.recv_timeout(Duration::from_secs(2)).unwrap(),
        "died: boom"
    );
    assert_child_reaped_eventually(child.pid);
}

#[test]
fn watchdog_unexpected_eof_terminates_and_reaps_child() {
    let (send, receive) = mpsc::channel();
    let child = exec_process::spawn_test_shell_with_events(
        r#"printf '{\"type\":\"ready\",\"port\":43123}\n' >&$1; eval "exec ${1}>&-"; sleep 30"#,
        Arc::new(move |event| {
            let _ = send.send(event_name(event));
        }),
    )
    .unwrap();
    assert_eq!(
        receive.recv_timeout(Duration::from_secs(2)).unwrap(),
        "died: pipe closed unexpectedly"
    );
    assert_child_reaped_eventually(child.pid);
}
