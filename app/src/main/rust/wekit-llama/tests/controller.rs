//! Controller validation paths (real starts need Android app_process; those run
//! as the desktop smoke sequence, not as automated tests).

use std::sync::Mutex;

use wekit_llama::controller::{self, Status};

// The controller state is process-global; serialize the tests.
static TEST_LOCK: Mutex<()> = Mutex::new(());

fn start_with_launch_paths(bootstrap_apk: &str, native_library: &str) -> Result<(), String> {
    controller::start(
        bootstrap_apk,
        native_library,
        "/abs/model.gguf",
        4096,
        "cpu",
        0.6,
        0.95,
        20,
    )
}

#[test]
fn rejects_relative_bootstrap_and_native_library_paths() {
    let _guard = TEST_LOCK.lock().unwrap();
    assert_eq!(
        start_with_launch_paths("relative.apk", "/abs/lib.so").unwrap_err(),
        "bootstrap APK path must be absolute",
    );
    assert_eq!(
        start_with_launch_paths("/abs/base.apk", "relative.so").unwrap_err(),
        "native library path must be absolute",
    );
}

#[test]
fn rejects_relative_model_path() {
    let _guard = TEST_LOCK.lock().unwrap();
    let error = controller::start(
        "/abs/base.apk",
        "/abs/libwekit_llama.so",
        "relative/model.gguf",
        4096,
        "cpu",
        0.6,
        0.95,
        20,
    )
    .expect_err("relative paths must be rejected");
    assert_eq!(error, "model path must be absolute");
    assert!(matches!(controller::status(), Status::Failed { .. }));
    assert_eq!(
        controller::status_json(),
        r#"{"error":"model path must be absolute","pid":null,"port":null,"state":"failed"}"#
    );
}

#[test]
fn rejects_out_of_range_n_ctx() {
    let _guard = TEST_LOCK.lock().unwrap();
    for n_ctx in [4095_u32, 262_145] {
        let error = controller::start(
            "/abs/base.apk",
            "/abs/libwekit_llama.so",
            "/abs/model.gguf",
            n_ctx,
            "cpu",
            0.6,
            0.95,
            20,
        )
        .expect_err("out-of-range n_ctx must be rejected");
        assert_eq!(
            error,
            format!("n_ctx must be within 4096..=262144, got {n_ctx}")
        );
    }
}

#[test]
fn rejects_unknown_backend() {
    let _guard = TEST_LOCK.lock().unwrap();
    let error = controller::start(
        "/abs/base.apk",
        "/abs/libwekit_llama.so",
        "/abs/model.gguf",
        4096,
        "tensor",
        0.6,
        0.95,
        20,
    )
    .expect_err("unknown backend spellings must be rejected");
    assert_eq!(error, "unknown backend: tensor");
}

#[test]
fn stop_resets_a_failed_state_to_stopped() {
    let _guard = TEST_LOCK.lock().unwrap();
    let _ = controller::start(
        "/abs/base.apk",
        "/abs/libwekit_llama.so",
        "relative/model.gguf",
        4096,
        "cpu",
        0.6,
        0.95,
        20,
    );
    assert!(matches!(controller::status(), Status::Failed { .. }));
    controller::stop();
    assert_eq!(controller::status(), Status::Stopped);
}

#[test]
fn exec_failure_via_control_thread_marks_failed() {
    let _guard = TEST_LOCK.lock().unwrap();
    // Absolute + in-range + valid backend → dispatched to the control
    // thread, which launches app_process; desktop exec fails and the error must
    // flow back through the pipe into the Failed status.
    let error = controller::start(
        "/abs/base.apk",
        "/abs/libwekit_llama.so",
        "/nonexistent/model.gguf",
        4096,
        "cpu",
        0.6,
        0.95,
        20,
    )
    .unwrap_err();
    assert!(
        error.contains("execve app_process64 failed"),
        "unexpected error: {error}"
    );
    assert!(matches!(controller::status(), Status::Failed { .. }));
    assert!(controller::status_json().contains("execve app_process64 failed"));
}
