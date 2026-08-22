//! Fork machinery test that needs no model: the child's server fails to
//! load a nonexistent model, and the parent must surface the child's pipe
//! error line as the `fork_server` error.

use std::sync::Arc;

use wekit_llama::fork::{self, ChildEvent};
use wekit_llama::llama::{Backend, EngineConfig};
use wekit_llama::server::HttpServerConfig;

fn test_config() -> HttpServerConfig {
    HttpServerConfig {
        engine: EngineConfig {
            model_path: "/nonexistent/wekit-llama-test.gguf".to_owned(),
            n_ctx: 512,
            threads: 1,
            backend: Backend::Cpu,
            temp: 0.6,
            top_p: 0.95,
            top_k: 20,
            idle_timeout_secs: 600,
        },
        bind_port: 0,
    }
}

#[test]
fn fork_child_model_load_failure_surfaces_as_error() {
    let events = Arc::new(std::sync::Mutex::new(Vec::new()));
    let recorded = events.clone();
    let error = fork::fork_server(
        test_config(),
        Arc::new(move |event| {
            recorded.lock().unwrap().push(match event {
                ChildEvent::Ready { port } => format!("ready port={port}"),
                ChildEvent::Exiting { reason } => format!("exiting: {reason}"),
                ChildEvent::Died { reason } => format!("died: {reason}"),
            });
        }),
    )
    .expect_err("loading a nonexistent model must fail");
    assert!(error.contains("loading model"), "unexpected error: {error}");
    // The child died before ready, so no watchdog event may have fired.
    assert!(events.lock().unwrap().is_empty());
}

#[test]
fn stop_child_tolerates_non_children() {
    // pid 0/negative: no signal is ever sent; -1 must not broadcast.
    fork::stop_child(0);
    fork::stop_child(-1);
}
