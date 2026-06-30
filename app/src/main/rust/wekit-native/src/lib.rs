//! JNI entry points

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod audio_utils;
mod crash_handler;
mod crash_triggerer;
mod logging;
mod native_hook;
mod preferences_db;
mod utils;

use std::ffi::CString;

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jbyteArray,
    jfloat, jint, jlong, jobject, jobjectArray, jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Install the native crash handler.
///
/// Java signature: `(Ljava/lang/String;)Z`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_crash_NativeCrashHandler_installNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_log_dir: jstring,
) -> jboolean {
    with_jstring(env, crash_log_dir, |dir| {
        if install_crash_handler(dir) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Uninstall the native crash handler.
///
/// Java signature: `()V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_crash_NativeCrashHandler_uninstallNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    uninstall_crash_handler();
}

/// Trigger a deliberate test crash.
///
/// Java signature: `(I)V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_crash_NativeCrashHandler_triggerTestCrashNative(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_type: jint,
) {
    trigger_test_crash(crash_type);
}

/// Convert a Markdown string to HTML.
///
/// Java signature: `(Ljava/lang/String;)Ljava/lang/String;`
#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_ujhhgtg_wekit_features_items_chat_MarkdownRendering_convertMarkdownToHtmlNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    markdown_string: jstring,
) -> jstring {
    let result = with_jstring(env, markdown_string, |md_text| {
        markdown::to_html_with_options(md_text, &markdown::Options::gfm())
    });

    match result {
        Ok(html) => unsafe {
            let fns = *env;
            let c_str = CString::new(html).unwrap_or_default();
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_mp3ToSilk(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    mp3_path: jstring,
    silk_path: jstring,
) -> jboolean {
    logi!("converting mp3 to silk...");
    with_jstring(env, mp3_path, |mp3| {
        with_jstring(env, silk_path, |silk| {
            logi!("converting {} to {}", mp3, silk);
            match audio_utils::mp3_to_silk(mp3, silk) {
                Ok(_) => {
                    logi!("mp3_to_silk succeeded");
                    JNI_TRUE
                }
                Err(err) => {
                    logi!("mp3_to_silk failed: {:?}", err);
                    JNI_FALSE
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_silkToPcm(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    silk_path: jstring,
    pcm_path: jstring,
) -> jboolean {
    logi!("converting silk to pcm...");
    with_jstring(env, silk_path, |silk| {
        with_jstring(env, pcm_path, |pcm| {
            logi!("converting {} to {}", silk, pcm);
            match audio_utils::silk_to_pcm(silk, pcm, 24000) {
                Ok(_) => {
                    logi!("silk_to_pcm succeeded");
                    JNI_TRUE
                }
                Err(err) => {
                    logi!("silk_to_pcm failed: {:?}", err);
                    JNI_FALSE
                }
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_pcmToMp3(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    pcm_path: jstring,
    mp3_path: jstring,
) -> jboolean {
    logi!("converting pcm to mp3...");
    with_jstring(env, pcm_path, |pcm| {
        with_jstring(env, mp3_path, |mp3| {
            logi!("converting {} to {}", pcm, mp3);
            if audio_utils::pcm_to_mp3(pcm, mp3, 24000, 128) {
                logi!("pcm_to_mp3 succeeded");
                JNI_TRUE
            } else {
                logi!("pcm_to_mp3 failed");
                JNI_FALSE
            }
        })
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_getDurationMs(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    path: jstring,
) -> jlong {
    logi!("reading audio duration...");
    with_jstring(env, path, |p| match audio_utils::get_audio_duration_ms(p) {
        Ok(val) => {
            logi!("get_audio_duration_ms succeeded: {val}");
            val
        }
        Err(err) => {
            loge!("get_audio_duration_ms failed: {:?}", err);
            0
        }
    })
}

// JNI exports for TursoPrefsImpl

use jni::objects::{JByteArray, JObject, JObjectArray, JString};

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeInit(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    db_path: jstring,
) {
    with_jstring(env, db_path, |path| {
        preferences_db::init_db(path);
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetString(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    def_value: jstring,
) -> jstring {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && (row.val_type == preferences_db::TYPE_STRING
                || row.val_type == preferences_db::TYPE_STRING_SET)
            && let Some(ref s) = row.val_string
        {
            unsafe {
                let fns = *env;
                let c_str = CString::new(s.as_str()).unwrap_or_default();
                return ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr());
            }
        }
        def_value
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutString(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jstring,
) {
    with_jstring(env, key, |key_str| {
        if value.is_null() {
            preferences_db::remove_key(key_str);
        } else {
            with_jstring(env, value, |val_str| {
                preferences_db::put_value(
                    key_str,
                    preferences_db::TYPE_STRING,
                    None,
                    None,
                    None,
                    None,
                    Some(val_str),
                    None,
                );
            });
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetStringSet(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
) -> jobjectArray {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && row.val_type == preferences_db::TYPE_STRING_SET
            && let Some(ref s) = row.val_string
        {
            let parts: Vec<&str> = s.split('\u{001f}').collect();
            let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
            let mut result = std::ptr::null_mut();
            let _ = unowned.with_env(|jni_env| {
                let class = jni_env
                    .find_class(jni::jni_str!("java/lang/String"))
                    .unwrap();
                let array = jni_env
                    .new_object_array(parts.len() as jni::sys::jsize, &class, JObject::null())
                    .unwrap();
                for (i, part) in parts.iter().enumerate() {
                    let j_str = jni_env.new_string(part).unwrap();
                    array.set_element(jni_env, i, &j_str).unwrap();
                }
                result = array.as_raw();
                Ok::<(), jni::errors::Error>(())
            });
            return result;
        }
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutStringSet(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    values: jobjectArray,
) {
    with_jstring(env, key, |key_str| {
        if values.is_null() {
            preferences_db::remove_key(key_str);
        } else {
            let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
            let _ = unowned.with_env(|jni_env| {
                let arr_obj = unsafe { JObjectArray::<JObject>::from_raw(jni_env, values) };
                let len = arr_obj.len(jni_env).unwrap();
                let mut vec = Vec::new();
                for i in 0..len {
                    let elem: JObject = arr_obj.get_element(jni_env, i).unwrap();
                    if !elem.is_null() {
                        let j_str = unsafe { JString::from_raw(jni_env, elem.as_raw() as jstring) };
                        let s: String = j_str.try_to_string(jni_env).unwrap();
                        vec.push(s);
                    }
                }
                let joined = vec.join("\u{001f}");
                preferences_db::put_value(
                    key_str,
                    preferences_db::TYPE_STRING_SET,
                    None,
                    None,
                    None,
                    None,
                    Some(&joined),
                    None,
                );
                Ok::<(), jni::errors::Error>(())
            });
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetInt(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    def_value: jint,
) -> jint {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && row.val_type == preferences_db::TYPE_INT
            && let Some(val) = row.val_int
        {
            return val as jint;
        }
        def_value
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutInt(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jint,
) {
    with_jstring(env, key, |key_str| {
        preferences_db::put_value(
            key_str,
            preferences_db::TYPE_INT,
            None,
            Some(value),
            None,
            None,
            None,
            None,
        );
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetLong(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    def_value: jlong,
) -> jlong {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && row.val_type == preferences_db::TYPE_LONG
            && let Some(val) = row.val_long
        {
            return val as jlong;
        }
        def_value
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutLong(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jlong,
) {
    with_jstring(env, key, |key_str| {
        preferences_db::put_value(
            key_str,
            preferences_db::TYPE_LONG,
            None,
            None,
            Some(value),
            None,
            None,
            None,
        );
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetFloat(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    def_value: jfloat,
) -> jfloat {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && row.val_type == preferences_db::TYPE_FLOAT
            && let Some(val) = row.val_float
        {
            return val as jfloat;
        }
        def_value
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutFloat(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jfloat,
) {
    with_jstring(env, key, |key_str| {
        preferences_db::put_value(
            key_str,
            preferences_db::TYPE_FLOAT,
            None,
            None,
            None,
            Some(value),
            None,
            None,
        );
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetBoolean(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    def_value: jboolean,
) -> jboolean {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && row.val_type == preferences_db::TYPE_BOOL
            && let Some(val) = row.val_bool
        {
            return if val {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            };
        }
        def_value
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutBoolean(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jboolean,
) {
    with_jstring(env, key, |key_str| {
        preferences_db::put_value(
            key_str,
            preferences_db::TYPE_BOOL,
            Some(value != jni::sys::JNI_FALSE),
            None,
            None,
            None,
            None,
            None,
        );
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetBytes(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
) -> jbyteArray {
    with_jstring(env, key, |key_str| {
        if let Some(row) = preferences_db::get_value(key_str)
            && (row.val_type == preferences_db::TYPE_BYTES
                || row.val_type == preferences_db::TYPE_SERIALIZABLE)
            && let Some(ref bytes) = row.val_bytes
        {
            let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
            let mut result = std::ptr::null_mut();
            let _ = unowned.with_env(|jni_env| {
                let byte_array = jni_env.byte_array_from_slice(bytes).unwrap();
                result = byte_array.as_raw();
                Ok::<(), jni::errors::Error>(())
            });
            return result;
        }
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativePutBytesWithType(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
    value: jbyteArray,
    val_type: jint,
) {
    with_jstring(env, key, |key_str| {
        if value.is_null() {
            preferences_db::remove_key(key_str);
        } else {
            let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
            let _ = unowned.with_env(|jni_env| {
                let arr_obj = unsafe { JByteArray::from_raw(jni_env, value) };
                let bytes = jni_env.convert_byte_array(&arr_obj).unwrap();
                preferences_db::put_value(
                    key_str,
                    val_type,
                    None,
                    None,
                    None,
                    None,
                    None,
                    Some(&bytes),
                );
                Ok::<(), jni::errors::Error>(())
            });
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeContains(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
) -> jboolean {
    with_jstring(env, key, |key_str| {
        if preferences_db::contains_key(key_str) {
            jni::sys::JNI_TRUE
        } else {
            jni::sys::JNI_FALSE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeRemove(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
) {
    with_jstring(env, key, |key_str| {
        preferences_db::remove_key(key_str);
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeClear(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    preferences_db::clear_db();
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetAllKeys(
    env: *mut RawJNIEnv,
    _thiz: jobject,
) -> jobjectArray {
    let keys = preferences_db::get_all_keys();
    let mut unowned = unsafe { jni::EnvUnowned::from_raw(env) };
    let mut result = std::ptr::null_mut();
    let _ = unowned.with_env(|jni_env| {
        let class = jni_env
            .find_class(jni::jni_str!("java/lang/String"))
            .unwrap();
        let array = jni_env
            .new_object_array(keys.len() as jni::sys::jsize, &class, JObject::null())
            .unwrap();
        for (i, key) in keys.iter().enumerate() {
            let j_str = jni_env.new_string(key).unwrap();
            array.set_element(jni_env, i, &j_str).unwrap();
        }
        result = array.as_raw();
        Ok::<(), jni::errors::Error>(())
    });
    result
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_preferences_TursoPrefsImpl_nativeGetType(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    key: jstring,
) -> jint {
    with_jstring(env, key, |key_str| {
        preferences_db::get_type(key_str) as jint
    })
}

/// Required JNI library entry point — returns the JNI version we target.
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
