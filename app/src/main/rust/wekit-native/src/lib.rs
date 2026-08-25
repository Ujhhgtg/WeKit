//! JNI entry points

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod audio_utils;
#[cfg(test)]
#[allow(dead_code)]
mod chroot_cleanup;
mod crash_handler;
mod crash_triggerer;
mod logging;
mod owned_process;
mod pty;
mod read_receipts_server;
mod telegram_sticker;
mod utils;

use std::ffi::CString;

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jbyteArray, jint,
    jlong, jlongArray, jobject, jobjectArray, jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_environment_OwnedProcess_00024Native_start(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    argv: jobjectArray,
    environment: jobjectArray,
    cwd: jstring,
) -> jlongArray {
    let result = string_array(env, argv).and_then(|argv| {
        string_array(env, environment).and_then(|environment| {
            with_jstring(env, cwd, |cwd| {
                owned_process::start(argv, environment, cwd.to_owned())
            })
            .unwrap_or_else(|| Err("missing cwd".into()))
        })
    });
    match result {
        Ok(started) => unsafe {
            let pid = started.process.pid();
            let pgid = started.process.pgid();
            let array = ((**env).v1_6.NewLongArray)(env, 6);
            if array.is_null() {
                libc::close(started.stdin);
                libc::close(started.stdout);
                libc::close(started.stderr);
                return std::ptr::null_mut();
            }
            let process = Box::new(started.process);
            let values = [
                (&*process as *const owned_process::OwnedProcess) as jlong,
                pid as jlong,
                pgid as jlong,
                started.stdin as jlong,
                started.stdout as jlong,
                started.stderr as jlong,
            ];
            ((**env).v1_6.SetLongArrayRegion)(env, array, 0, values.len() as jint, values.as_ptr());
            if ((**env).v1_6.ExceptionCheck)(env) != JNI_FALSE {
                libc::close(started.stdin);
                libc::close(started.stdout);
                libc::close(started.stderr);
                return std::ptr::null_mut();
            }
            let _ = Box::into_raw(process);
            array
        },
        Err(error) => {
            loge!("owned process start failed: {error}");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_environment_OwnedProcess_00024Native_pollExit(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return i32::MIN;
    }
    match unsafe { &*(handle as *const owned_process::OwnedProcess) }.poll_exit() {
        Ok(Some(code)) => code,
        Ok(None) => i32::MIN + 1,
        Err(error) => {
            loge!("owned process wait failed: {error}");
            i32::MIN
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_environment_OwnedProcess_00024Native_terminateGroup(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    grace_millis: jlong,
) -> jboolean {
    if handle != 0
        && unsafe { &*(handle as *const owned_process::OwnedProcess) }
            .terminate_group(std::time::Duration::from_millis(grace_millis.max(0) as u64))
            .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_environment_OwnedProcess_00024Native_close(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut owned_process::OwnedProcess));
        }
    }
}

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

fn string_array(env: *mut RawJNIEnv, array: jobjectArray) -> Result<Vec<String>, String> {
    if env.is_null() || array.is_null() {
        return Err("missing string array".into());
    }
    unsafe {
        let fns = *env;
        let len = ((*fns).v1_6.GetArrayLength)(env, array as jobject);
        let mut result = Vec::with_capacity(len as usize);
        for index in 0..len {
            let item = ((*fns).v1_6.GetObjectArrayElement)(env, array, index) as jstring;
            let value = with_jstring(env, item, str::to_owned)
                .ok_or_else(|| "invalid string array item".to_owned())?;
            ((*fns).v1_6.DeleteLocalRef)(env, item as jobject);
            result.push(value);
        }
        Ok(result)
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_start(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    argv: jobjectArray,
    environment: jobjectArray,
    cwd: jstring,
    cols: jint,
    rows: jint,
) -> jlong {
    let result = string_array(env, argv).and_then(|argv| {
        string_array(env, environment).and_then(|environment| {
            with_jstring(env, cwd, |cwd| {
                pty::start(argv, environment, cwd.to_owned(), cols, rows)
            })
            .unwrap_or_else(|| Err("missing cwd".into()))
        })
    });
    match result {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            loge!("pty start failed: {error}");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_write(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    bytes: jbyteArray,
) -> jboolean {
    if handle == 0 || bytes.is_null() {
        return JNI_FALSE;
    }
    unsafe {
        let fns = *env;
        let len = ((*fns).v1_6.GetArrayLength)(env, bytes as jobject);
        let ptr = ((*fns).v1_6.GetByteArrayElements)(env, bytes, std::ptr::null_mut());
        if ptr.is_null() {
            return JNI_FALSE;
        }
        let result = pty::write(
            &*(handle as *const pty::Pty),
            std::slice::from_raw_parts(ptr as *const u8, len as usize),
        );
        ((*fns).v1_6.ReleaseByteArrayElements)(env, bytes, ptr, 0);
        match result {
            Ok(()) => JNI_TRUE,
            Err(error) => {
                loge!("pty write failed: {error}");
                JNI_FALSE
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_read(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    buffer: jbyteArray,
) -> jint {
    if handle == 0 || buffer.is_null() {
        return -2;
    }
    unsafe {
        let fns = *env;
        let len = ((*fns).v1_6.GetArrayLength)(env, buffer);
        if len <= 0 {
            return -2;
        }
        let ptr = ((*fns).v1_6.GetByteArrayElements)(env, buffer, std::ptr::null_mut());
        if ptr.is_null() {
            return -2;
        }
        let result = pty::read(
            &*(handle as *const pty::Pty),
            std::slice::from_raw_parts_mut(ptr as *mut u8, len as usize),
        );
        ((*fns).v1_6.ReleaseByteArrayElements)(env, buffer, ptr, 0);
        match result {
            Ok(pty::ReadResult::Data(count)) => count as jint,
            Ok(pty::ReadResult::Timeout) => 0,
            Ok(pty::ReadResult::Eof) => -1,
            Err(error) => {
                loge!("pty read failed: {error}");
                -2
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_resize(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
    cols: jint,
    rows: jint,
) -> jboolean {
    if handle != 0 && pty::resize(unsafe { &*(handle as *const pty::Pty) }, cols, rows).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_waitForExit(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -2;
    }
    pty::wait(unsafe { &*(handle as *const pty::Pty) }).unwrap_or_else(|error| {
        loge!("pty wait failed: {error}");
        -2
    })
}
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_kill(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) -> jboolean {
    if handle != 0 && pty::kill(unsafe { &*(handle as *const pty::Pty) }).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_agent_terminal_NativeTerminalBackend_00024NativePty_close(
    _env: *mut RawJNIEnv,
    _thiz: jobject,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut pty::Pty));
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Start the loopback-only embedded read-receipts origin.
///
/// Java signature: `(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsNative_startServer(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    database_path: jstring,
    port: jint,
    connector_authenticator: jstring,
) -> jstring {
    let result = if !(0..=u16::MAX as jint).contains(&port) {
        Err("server port must be between 0 and 65535".to_owned())
    } else {
        with_jstring(env, database_path, |database_path| {
            with_jstring(env, connector_authenticator, |connector_authenticator| {
                read_receipts_server::start(database_path, port as u16, connector_authenticator)
                    .map(|_| ())
            })
            .unwrap_or_else(|| Err("missing or unreadable connector authenticator".to_owned()))
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
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_nativeAnyToSilk(
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
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_AudioUtils_pcmToSilk(
    env: *mut RawJNIEnv,
    _thiz: jobject,
    pcm_path: jstring,
    silk_path: jstring,
    sample_rate: jint,
    channel_count: jint,
) -> jboolean {
    if sample_rate <= 0 || channel_count <= 0 {
        loge!(
            "pcm_to_silk: invalid format sample_rate={} channel_count={}",
            sample_rate,
            channel_count
        );
        return JNI_FALSE;
    }

    with_jstring(env, pcm_path, |pcm| {
        with_jstring(env, silk_path, |silk| {
            match audio_utils::pcm_file_to_silk(
                pcm,
                silk,
                sample_rate as u32,
                channel_count as usize,
            ) {
                Ok(()) => JNI_TRUE,
                Err(error) => {
                    loge!("pcm_to_silk failed: {error:?}");
                    JNI_FALSE
                }
            }
        })
    })
    .flatten()
    .unwrap_or_else(|| {
        loge!("pcm_to_silk: missing or unreadable path argument");
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
