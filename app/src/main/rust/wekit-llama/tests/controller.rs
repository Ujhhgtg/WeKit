//! Controller validation paths (real starts need a model + fork; those run
//! as the desktop smoke sequence, not as automated tests).

use std::sync::Mutex;

use wekit_llama::controller::{self, Status};

// The controller state is process-global; serialize the tests.
static TEST_LOCK: Mutex<()> = Mutex::new(());

#[test]
fn rejects_relative_model_path() {
    let _guard = TEST_LOCK.lock().unwrap();
    let error = controller::start("relative/model.gguf", 4096, "cpu", 0.6, 0.95, 20)
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
    for n_ctx in [99_u32, 262_145] {
        let error = controller::start("/abs/model.gguf", n_ctx, "cpu", 0.6, 0.95, 20)
            .expect_err("out-of-range n_ctx must be rejected");
        assert_eq!(
            error,
            format!("n_ctx must be within 100..=262144, got {n_ctx}")
        );
    }
}

#[test]
fn rejects_unknown_backend() {
    let _guard = TEST_LOCK.lock().unwrap();
    let error = controller::start("/abs/model.gguf", 4096, "tensor", 0.6, 0.95, 20)
        .expect_err("unknown backend spellings must be rejected");
    assert_eq!(error, "unknown backend: tensor");
}

#[test]
fn stop_resets_a_failed_state_to_stopped() {
    let _guard = TEST_LOCK.lock().unwrap();
    let _ = controller::start("relative/model.gguf", 4096, "cpu", 0.6, 0.95, 20);
    assert!(matches!(controller::status(), Status::Failed { .. }));
    controller::stop();
    assert_eq!(controller::status(), Status::Stopped);
}

#[test]
fn start_failure_via_control_thread_marks_failed() {
    let _guard = TEST_LOCK.lock().unwrap();
    // Absolute + in-range + valid backend → dispatched to the control
    // thread, which forks; the child's model load fails and the error must
    // flow back through the pipe into the Failed status.
    let error =
        controller::start("/nonexistent/model.gguf", 512, "cpu", 0.6, 0.95, 20).unwrap_err();
    assert!(error.contains("loading model"), "unexpected error: {error}");
    assert!(matches!(controller::status(), Status::Failed { .. }));
    assert!(controller::status_json().contains("loading model"));
}
