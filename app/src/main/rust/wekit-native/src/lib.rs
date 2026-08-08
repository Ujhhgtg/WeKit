//! JNI entry points

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod audio_utils;
mod crash_handler;
mod crash_triggerer;
mod logging;
mod read_receipts_server;
mod telegram_sticker;
mod utils;

use std::ffi::CString;

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jlong,
    jobject, jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

fn native_string(env: *mut RawJNIEnv, value: &str) -> jstring {
    if env.is_null() {
        return std::ptr::null_mut();
    }

    unsafe {
        let fns = *env;
        let c_str = CString::new(value)
            .unwrap_or_else(|_| CString::new("native conversion failed").unwrap());
        ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
    }
}

fn native_error_string(env: *mut RawJNIEnv, result: Result<(), String>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => native_string(env, &message),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Start the loopback-only embedded read-receipts origin.
///
/// Java signature: `(Ljava/lang/String;I)Ljava/lang/String;`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsNative_startServer(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    database_path: jstring,
    port: jint,
) -> jstring {
    let result = if !(0..=u16::MAX as jint).contains(&port) {
        Err("server port must be between 0 and 65535".to_owned())
    } else {
        with_jstring(env, database_path, |database_path| {
            read_receipts_server::start(database_path, port as u16).map(|_| ())
        })
        .unwrap_or_else(|| Err("missing or unreadable database path".to_owned()))
    };
    if let Err(error) = &result {
        loge!("failed to start read receipts server: {error}");
    }
    native_error_string(env, result)
}

/// Request asynchronous shutdown of the embedded read-receipts origin.
///
/// Java signature: `()V`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsNative_stopServer(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
) {
    read_receipts_server::stop();
}

/// Return a bounded JSON status object with `state`, `port`, and `error` fields.
///
/// Java signature: `()Ljava/lang/String;`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsNative_serverStatus(
    env: *mut RawJNIEnv,
    _thiz: jobject,
) -> jstring {
    native_string(env, &read_receipts_server::status().to_json())
}

/// Install the native crash handler.
///
/// Java signature: `(Ljava/lang/String;Ljava/lang/String;)Z`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_crash_NativeCrashHandler_installNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    crash_log_dir: jstring,
    crash_log_file_name_prefix: jstring,
) -> jboolean {
    with_jstring(env, crash_log_dir, |dir| {
        with_jstring(env, crash_log_file_name_prefix, |prefix| {
            if install_crash_handler(dir, prefix) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        })
    })
    .flatten()
    .unwrap_or_else(|| {
        loge!("install_crash_handler: missing or unreadable path argument");
        JNI_FALSE
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
        Some(Ok(html)) => unsafe {
            let fns = *env;
            let c_str = CString::new(html).unwrap_or_default();
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
        // A null return makes the Kotlin side fall back to WeChat's own renderer.
        Some(Err(_)) | None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_anyToSilk(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    any_path: jstring,
    silk_path: jstring,
) -> jboolean {
    logi!("converting any to silk...");
    with_jstring(env, any_path, |any| {
        with_jstring(env, silk_path, |silk| {
            logi!("converting {} to {}", any, silk);
            match audio_utils::any_to_silk(any, silk) {
                Ok(_) => {
                    logi!("any_to_silk succeeded");
                    JNI_TRUE
                }
                Err(err) => {
                    logi!("any_to_silk failed: {:?}", err);
                    JNI_FALSE
                }
            }
        })
    })
    .flatten()
    .unwrap_or_else(|| {
        loge!("any_to_silk: missing or unreadable path argument");
        JNI_FALSE
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
    .flatten()
    .unwrap_or_else(|| {
        loge!("silk_to_pcm: missing or unreadable path argument");
        JNI_FALSE
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
    .flatten()
    .unwrap_or_else(|| {
        loge!("pcm_to_mp3: missing or unreadable path argument");
        JNI_FALSE
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
    .unwrap_or_else(|| {
        loge!("get_audio_duration_ms: missing or unreadable path argument");
        0
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_TelegramStickerConverter_tgsToGifNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    output_path: jstring,
    frame_rate: jint,
) -> jstring {
    let result = with_jstring(env, input_path, |input| {
        with_jstring(env, output_path, |output| {
            telegram_sticker::tgs_to_gif(input, output, frame_rate as f32)
        })
    })
    .flatten()
    .unwrap_or_else(|| Err("missing or unreadable path argument".to_string()));
    native_error_string(env, result)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_TelegramStickerConverter_webmToGifNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    input_path: jstring,
    output_path: jstring,
    remove_rounded_canvas_mask: jboolean,
) -> jstring {
    let result = with_jstring(env, input_path, |input| {
        with_jstring(env, output_path, |output| {
            telegram_sticker::webm_to_gif(input, output, remove_rounded_canvas_mask != JNI_FALSE)
        })
    })
    .flatten()
    .unwrap_or_else(|| Err("missing or unreadable path argument".to_string()));
    native_error_string(env, result)
}

/// Required JNI library entry point — returns the JNI version we target.
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_6
}
