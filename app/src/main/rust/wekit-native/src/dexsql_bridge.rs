//! JNI bridge for the embedded DexSQL session API.
//!
//! The bridge deliberately keeps the native session independent of Kotlin
//! objects.  Paths and SQL are copied out of JNI strings before they reach
//! DexSQL, while query and resolve responses are returned as small JSON
//! envelopes that can be consumed by either the feature code or desktop
//! tooling.  Session handles are IDs in a process-wide registry rather than
//! raw pointers: an in-flight query keeps its `Arc<Engine>` alive when another
//! thread closes the handle, and a stale handle is reported as a structured
//! error instead of becoming a use-after-free.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use dexsql::{Engine, EngineOptions, Error as DexSqlError, IndexNeeds, ResolveOptions};
use jni::sys::{
    JNI_FALSE, JNIEnv as RawJNIEnv, jboolean, jint, jlong, jobject, jobjectArray, jstring,
};
use serde_json::{Value, json};

use crate::utils::with_jstring;
use crate::{loge, native_string, string_array};

type Session = Arc<Engine>;

static SESSIONS: OnceLock<Mutex<HashMap<jlong, Session>>> = OnceLock::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn sessions() -> &'static Mutex<HashMap<jlong, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn json_string(value: Value) -> String {
    // Every value constructed by this module is JSON-serializable.  Keep the
    // fallback explicit so a future response field cannot make JNI return a
    // null string and hide the original operation failure.
    serde_json::to_string(&value).unwrap_or_else(|error| {
        serde_json::to_string(&json!({
            "ok": false,
            "error": {
                "kind": "serialization",
                "message": error.to_string(),
            },
        }))
        .expect("static JSON serialization cannot fail")
    })
}

fn success(value: Value) -> String {
    let mut object = match value {
        Value::Object(object) => object,
        other => {
            let mut object = serde_json::Map::new();
            object.insert("value".to_owned(), other);
            object
        }
    };
    object.insert("ok".to_owned(), Value::Bool(true));
    json_string(Value::Object(object))
}

fn failure(kind: &str, message: impl Into<String>) -> String {
    json_string(json!({
        "ok": false,
        "error": {
            "kind": kind,
            "message": message.into(),
        },
    }))
}

fn engine_error(error: DexSqlError) -> String {
    match error {
        DexSqlError::Load(message) => failure("load", message),
        DexSqlError::Query(message) => failure("query", message),
        DexSqlError::EmptyResult => failure("empty_result", "query returned no rows"),
        DexSqlError::MultipleResults { count } => failure(
            "multiple_results",
            format!("query returned {count} rows; expected one"),
        ),
        DexSqlError::ResultIndexOutOfRange { index, count } => failure(
            "result_index_out_of_range",
            format!("result index {index} is out of range for {count} rows"),
        ),
    }
}

fn session_for(handle: jlong) -> Result<Session, String> {
    if handle <= 0 {
        return Err("session handle must be positive".to_owned());
    }
    sessions()
        .lock()
        .expect("DexSQL session registry mutex poisoned")
        .get(&handle)
        .cloned()
        .ok_or_else(|| format!("unknown DexSQL session handle {handle}"))
}

fn insert_session(engine: Engine) -> jlong {
    let mut registry = sessions()
        .lock()
        .expect("DexSQL session registry mutex poisoned");
    loop {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle > 0 && !registry.contains_key(&handle) {
            registry.insert(handle, Arc::new(engine));
            return handle;
        }
    }
}

fn response_string(env: *mut RawJNIEnv, response: String) -> jstring {
    native_string(env, &response)
}

/// Open one or more DEX/APK/JAR/ZIP inputs.
///
/// Java signature: `([Ljava/lang/String;)J`
///
/// The public Engine API currently requires index options to be selected when
/// the immutable dataset is opened.  The JNI session opts into every relation
/// and retains full code items so one handle can back all `dexMethod`-style
/// SQL delegates.  A later optimization can pre-scan a batch of delegates
/// before calling this function without changing the handle protocol.
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_dexkit_DexSqlNative_open(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    paths: jobjectArray,
) -> jlong {
    let paths = match string_array(env, paths) {
        Ok(paths) if !paths.is_empty() => paths,
        Ok(_) => {
            loge!("DexSQL open failed: no input paths");
            return 0;
        }
        Err(error) => {
            loge!("DexSQL open failed: {error}");
            return 0;
        }
    };

    let options = EngineOptions {
        index_needs: IndexNeeds::all(),
        eager_code_items: true,
        full_code_items: true,
        retain_classes: true,
        threads: Some(1),
    };
    match Engine::open_with_options(paths, options) {
        Ok(engine) => insert_session(engine),
        Err(error) => {
            // `open` returns a primitive handle for the Kotlin API.  Keep the
            // same structured error vocabulary as query/resolve in native
            // logs; callers receive the unambiguous zero handle.
            loge!("DexSQL open failed: {}", engine_error(error));
            0
        }
    }
}

/// Execute a read-only SQL statement against an open session.
///
/// Java signature: `(JLjava/lang/String;)Ljava/lang/String;`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_dexkit_DexSqlNative_query(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    sql: jstring,
) -> jstring {
    let session = match session_for(handle) {
        Ok(session) => session,
        Err(error) => return response_string(env, failure("invalid_handle", error)),
    };
    let response = with_jstring(env, sql, |sql| match session.query(sql) {
        Ok(result) => success(json!({
            "columns": result.columns,
            "rows": result.rows,
        })),
        Err(error) => engine_error(error),
    })
    .unwrap_or_else(|| failure("invalid_input", "SQL string is null or unreadable"));
    response_string(env, response)
}

/// Resolve one descriptor-like value from the first result column.
///
/// Java signature: `(JLjava/lang/String;IZZ)Ljava/lang/String;` where the
/// integer is the selected row and the booleans are `allowMultiple` and
/// `allowFailure`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_dexkit_DexSqlNative_resolve(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    sql: jstring,
    result_index: jint,
    allow_multiple: jboolean,
    allow_failure: jboolean,
) -> jstring {
    let session = match session_for(handle) {
        Ok(session) => session,
        Err(error) => return response_string(env, failure("invalid_handle", error)),
    };
    if result_index < 0 {
        return response_string(
            env,
            failure("invalid_input", "result index must be non-negative"),
        );
    }
    let options = ResolveOptions {
        result_index: result_index as usize,
        allow_multiple: allow_multiple != JNI_FALSE,
        allow_failure: allow_failure != JNI_FALSE,
    };
    let response = with_jstring(env, sql, |sql| match session.resolve_one(sql, options) {
        Ok(value) => success(json!({ "value": value })),
        Err(error) => engine_error(error),
    })
    .unwrap_or_else(|| failure("invalid_input", "SQL string is null or unreadable"));
    response_string(env, response)
}

/// Close a session.  The Kotlin surface currently uses `Unit`; failures are
/// still emitted using the same structured JSON envelope as query/resolve so
/// native diagnostics retain an actionable error kind and message.
///
/// Java signature: `(J)V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_dexkit_DexSqlNative_close(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) {
    let (closed, response) = if handle <= 0 {
        (
            false,
            failure("invalid_handle", "session handle must be positive"),
        )
    } else if sessions()
        .lock()
        .expect("DexSQL session registry mutex poisoned")
        .remove(&handle)
        .is_some()
    {
        (true, success(json!({ "closed": true })))
    } else {
        (
            false,
            failure(
                "invalid_handle",
                format!("unknown DexSQL session handle {handle}"),
            ),
        )
    };
    if !closed {
        loge!("DexSQL close failed: {response}");
    }
}
