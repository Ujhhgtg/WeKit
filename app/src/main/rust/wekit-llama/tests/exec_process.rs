use std::io;

use wekit_llama::exec_process::{self, SpawnedServer, stop_child};

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
