use jni::{
    EnvUnowned,
    objects::JString,
    sys::{JNIEnv as RawJNIEnv, jstring},
};

use crate::loge;

/// Reads the contents of the Java string `s` and invokes `f` with them.
///
/// The string is decoded from JNI's *modified* UTF-8, so characters outside
/// the BMP (emoji, …) — which `GetStringUTFChars` encodes as a surrogate pair
/// that plain `str::from_utf8` rejects — survive the round trip.
///
/// Returns `None` *without calling `f`* if `env` or `s` is null, or if the JNI
/// call fails (`GetStringUTFChars` returns null after throwing
/// `OutOfMemoryError`). Any pending Java exception is cleared before
/// returning, so the caller is free to keep making JNI calls; every caller
/// must supply its own fallback for the `None` case.
///
/// # Safety
/// `env` must be a valid `JNIEnv*` pointer for the current thread and `s`
/// must be a valid `jstring` (or null).
pub fn with_jstring<F, R>(env: *mut RawJNIEnv, s: jstring, f: F) -> Option<R>
where
    F: FnOnce(&str) -> R,
{
    let owned = jstring_to_string(env, s)?;
    Some(f(&owned))
}

/// Copies the contents of the Java string `s` into an owned Rust `String`.
///
/// The copy lets the JNI attachment guard and the pinned `GetStringUTFChars`
/// buffer be released before any caller-supplied work runs.
fn jstring_to_string(env: *mut RawJNIEnv, s: jstring) -> Option<String> {
    if env.is_null() {
        loge!("with_jstring called with a null JNIEnv");
        return None;
    }
    if s.is_null() {
        return None;
    }

    // Safety: the caller guarantees `env` is this thread's JNIEnv pointer.
    let mut unowned = unsafe { EnvUnowned::from_raw(env) };
    let mut owned = None;
    let _ = unowned.with_env_no_catch(|jni_env| {
        // Safety: `s` is a local reference owned by the calling JNI frame;
        // `JString` is a non-owning wrapper, so it will not delete it.
        let string = unsafe { JString::from_raw(jni_env, s) };
        match string.try_to_string(jni_env) {
            Ok(value) => owned = Some(value),
            Err(err) => {
                loge!("failed to read Java string: {err:?}");
                if jni_env.exception_check() {
                    jni_env.exception_clear();
                }
            }
        }
        Ok::<(), jni::errors::Error>(())
    });

    owned
}
