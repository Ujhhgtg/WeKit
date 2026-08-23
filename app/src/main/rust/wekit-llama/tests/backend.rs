//! Real llama.cpp load-policy regression tests.

use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

use llama_cpp_2::llama_backend::LlamaBackend;
use wekit_llama::llama::{Backend, Engine, EngineConfig};

struct TempModel(std::path::PathBuf);

impl TempModel {
    fn invalid_gguf() -> Self {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "wekit-llama-invalid-{}-{nonce}.gguf",
            std::process::id(),
        ));
        fs::write(&path, b"not a GGUF model").unwrap();
        Self(path)
    }
}

impl Drop for TempModel {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

#[test]
fn auto_fit_failure_attempts_real_cpu_fallback() {
    let model = TempModel::invalid_gguf();
    let backend = LlamaBackend::init().unwrap();
    let cfg = EngineConfig {
        model_path: model.0.to_string_lossy().into_owned(),
        n_ctx: 4096,
        threads: 1,
        backend: Backend::Auto,
        temp: 0.6,
        top_p: 0.95,
        top_k: 20,
        idle_timeout_secs: 600,
    };

    let error = Engine::load(&backend, &cfg).unwrap_err();
    assert!(
        error.contains("automatic placement failed"),
        "automatic fitting failure was not preserved: {error}",
    );
    assert!(
        error.contains("CPU fallback failed"),
        "a real CPU retry was not attempted: {error}",
    );
}
