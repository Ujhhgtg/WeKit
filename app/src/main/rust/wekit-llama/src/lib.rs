//! wekit-llama: local OpenAI-compatible llama.cpp server (pack library).
//!
//! This crate is dlopened into the WeChat process as `libwekit_llama.so`
//! (Android, CPU+Vulkan) / `libwekit_llama_opencl.so` (OpenCL variant) and
//! also builds the desktop `llama_server` CLI. Library-level rule: **zero
//! global initialization at dlopen time** — every static in the crate is
//! const-initialized; the backend, model, and tokio runtime only ever exist
//! inside [`server::serve`] in the forked child (see [`fork`]'s invariants).
//!
//! JNI surface (Android only, hand-written exports mirroring
//! `wekit-native/src/lib.rs`): `startServer`/`stopServer`/`serverStatus` on
//! `dev.ujhhgtg.wekit.agent.model.local.LlamaServerNative`. The exports
//! never panic: every failure maps to the JSON status string
//! `{"state":"stopped|starting|running|failed","port":N,"pid":N,"error":"…"}`.

pub mod controller;
pub mod fork;
pub mod llama;
pub mod parse;
pub mod server;
pub mod template;
pub mod truncate;
pub mod wire;

#[cfg(target_os = "android")]
#[allow(non_snake_case)]
mod jni_surface {
    use std::ffi::CString;

    use jni::EnvUnowned;
    use jni::objects::JString;
    use jni::sys::{JNIEnv as RawJNIEnv, jint, jobject, jstring};

    use crate::controller;

    // ─────────────────────────────────────────────────────────────────────────
    // JNI helpers (the wekit-native/src/utils.rs pattern)
    // ─────────────────────────────────────────────────────────────────────────

    /// Reads the Java string `s` and invokes `f` with its contents.
    ///
    /// Decodes JNI's *modified* UTF-8 so characters outside the BMP survive
    /// the round trip. Returns `None` without calling `f` for null `env`/`s`
    /// or a failed JNI call (a pending exception is cleared).
    ///
    /// # Safety
    /// `env` must be a valid `JNIEnv*` for the current thread; `s` must be a
    /// valid `jstring` (or null).
    fn with_jstring<F, R>(env: *mut RawJNIEnv, s: jstring, f: F) -> Option<R>
    where
        F: FnOnce(&str) -> R,
    {
        if env.is_null() || s.is_null() {
            return None;
        }
        // Safety: the caller guarantees `env` is this thread's JNIEnv pointer.
        let mut unowned = unsafe { EnvUnowned::from_raw(env) };
        let mut owned = None;
        let _ = unowned.with_env_no_catch(|jni_env| {
            // Safety: `s` is a local reference owned by the calling JNI frame;
            // `JString` is a non-owning wrapper and will not delete it.
            let string = unsafe { JString::from_raw(jni_env, s) };
            match string.try_to_string(jni_env) {
                Ok(value) => owned = Some(value),
                Err(_) => {
                    if jni_env.exception_check() {
                        jni_env.exception_clear();
                    }
                }
            }
            Ok::<(), jni::errors::Error>(())
        });
        owned.map(|value| f(&value))
    }

    /// Create a Java string via the raw `NewStringUTF` function table.
    fn native_string(env: *mut RawJNIEnv, value: &str) -> jstring {
        if env.is_null() {
            return std::ptr::null_mut();
        }
        let c_str = CString::new(value)
            .unwrap_or_else(|_| CString::new("native conversion failed").unwrap());
        unsafe { ((*(*env)).v1_6.NewStringUTF)(env, c_str.as_ptr()) }
    }

    /// Model-manifest sampling block: `{"sampling":{"temperature":0.6,
    /// "topP":0.95,"topK":20}}` — the same keys directly at the top level
    /// are accepted too. Missing or unparsable input keeps the spec preset
    /// (temp 0.6 / top_p 0.95 / top_k 20).
    fn parse_sampling(config_json: &str) -> (f32, f32, i32) {
        let value: serde_json::Value =
            serde_json::from_str(config_json).unwrap_or(serde_json::Value::Null);
        let block = value.get("sampling").unwrap_or(&value);
        let temp = block
            .get("temperature")
            .and_then(serde_json::Value::as_f64)
            .unwrap_or(0.6) as f32;
        let top_p = block
            .get("topP")
            .and_then(serde_json::Value::as_f64)
            .unwrap_or(0.95) as f32;
        let top_k = block
            .get("topK")
            .and_then(serde_json::Value::as_i64)
            .unwrap_or(20) as i32;
        (temp, top_p, top_k)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JNI exports
    // ─────────────────────────────────────────────────────────────────────────

    /// Start (or reuse) the forked inference child; blocks until ready or
    /// failed. Every failure is reflected in the returned status JSON — the
    /// export itself cannot panic or return null.
    ///
    /// Java signature: `(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;`
    #[unsafe(no_mangle)]
    pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_model_local_LlamaServerNative_startServer(
        env: *mut RawJNIEnv,
        _thiz: jobject,
        model_path: jstring,
        n_ctx: jint,
        backend: jstring,
        config_json: jstring,
    ) -> jstring {
        let result = (|| {
            let model_path =
                with_jstring(env, model_path, str::to_owned).ok_or("missing model path")?;
            let backend = with_jstring(env, backend, str::to_owned).ok_or("missing backend")?;
            let config_json = with_jstring(env, config_json, str::to_owned).unwrap_or_default();
            let n_ctx = u32::try_from(n_ctx).map_err(|_| format!("n_ctx out of range: {n_ctx}"))?;
            let (temp, top_p, top_k) = parse_sampling(&config_json);
            controller::start(&model_path, n_ctx, &backend, temp, top_p, top_k)
        })();
        // JNI-local failures (null strings, jint overflow) must land in the
        // status too; controller::start's own failures are already recorded
        // (recording again is idempotent).
        if let Err(error) = &result {
            controller::mark_failed(error);
        }
        native_string(env, &controller::status_json())
    }

    /// Stop the child (SIGTERM → 3s → SIGKILL) and return the new status.
    ///
    /// Java signature: `()Ljava/lang/String;`
    #[unsafe(no_mangle)]
    pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_model_local_LlamaServerNative_stopServer(
        env: *mut RawJNIEnv,
        _thiz: jobject,
    ) -> jstring {
        controller::stop();
        native_string(env, &controller::status_json())
    }

    /// Return the lifecycle status JSON.
    ///
    /// Java signature: `()Ljava/lang/String;`
    #[unsafe(no_mangle)]
    pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_model_local_LlamaServerNative_serverStatus(
        env: *mut RawJNIEnv,
        _thiz: jobject,
    ) -> jstring {
        native_string(env, &controller::status_json())
    }
}
