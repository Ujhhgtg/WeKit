//! JNI entry points

#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]

mod audio_utils;
mod crash_handler;
mod crash_triggerer;
mod logging;
#[allow(dead_code)]
mod nuke_client;
#[allow(dead_code)]
mod nuke_crypto;
mod telegram_sticker;
mod utils;

use std::{
    ffi::CString,
    fs::File,
    io::Read,
    panic::{AssertUnwindSafe, catch_unwind},
    time::{SystemTime, UNIX_EPOCH},
};

use crash_handler::{install_crash_handler, uninstall_crash_handler};
use crash_triggerer::trigger_test_crash;

use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, JNIEnv as RawJNIEnv, JavaVM, jboolean, jint, jlong,
    jobject, jstring,
};
use libc::c_void;

use crate::utils::with_jstring;

fn new_jstring(env: *mut RawJNIEnv, value: &str) -> jstring {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    unsafe {
        let fns = *env;
        let value = CString::new(value)
            .unwrap_or_else(|_| CString::new("native string conversion failed").unwrap());
        ((*fns).v1_6.NewStringUTF)(env, value.as_ptr())
    }
}

fn random_uuid_v4() -> Result<String, String> {
    let mut bytes = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut source| source.read_exact(&mut bytes))
        .map_err(|error| format!("nonce generation failed: {error}"))?;
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Ok(format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    ))
}

fn nuke_client_error(message: impl Into<String>) -> String {
    serde_json::json!({
        "success": false,
        "error": message.into(),
    })
    .to_string()
}

fn nuke_request_headers(auth: &nuke_client::ClientAuth) -> serde_json::Value {
    serde_json::json!({
        "Content-Type": "application/json",
        "X-Client-Id": auth.user_id,
        "X-Platform": auth.platform,
        "X-Timestamp": auth.timestamp,
        "X-Nonce": auth.nonce,
        "X-Signature": auth.signature,
    })
}

fn run_nuke_client_transaction() -> String {
    use nuke_client::{
        ClientAuth, DEFAULT_CLIENT_REPORT_URL, DEFAULT_WECHAT_PLATFORM, DEFAULT_WECHAT_USER_ID,
        client_users_endpoint_for_report, current_native_sync_signer_observations,
        fixed_native_wechat_report, is_valid_wechat_user_id, prepare_one_report,
        prepare_registration, send_prepared_registration, send_prepared_report,
        sign_prepared_registration_with_signer_observations,
        sign_prepared_report_with_signer_observations,
    };

    let user_id = DEFAULT_WECHAT_USER_ID;
    if !is_valid_wechat_user_id(user_id) {
        return nuke_client_error(
            "default WeChat id must be wxid_ followed by 14 lowercase alphanumeric characters",
        );
    }

    let result = (|| -> Result<serde_json::Value, String> {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| format!("clock failed: {error}"))?
            .as_secs()
            .to_string();
        let registration_endpoint = client_users_endpoint_for_report(DEFAULT_CLIENT_REPORT_URL)
            .map_err(|error| error.to_string())?;
        let registration_auth = ClientAuth {
            user_id: user_id.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp: timestamp.clone(),
            nonce: random_uuid_v4()?,
            signature: String::new(),
        };
        let report_auth = ClientAuth {
            user_id: user_id.to_owned(),
            platform: DEFAULT_WECHAT_PLATFORM.to_owned(),
            timestamp,
            nonce: random_uuid_v4()?,
            signature: String::new(),
        };
        let observations =
            current_native_sync_signer_observations(0).map_err(|error| error.to_string())?;

        let prepared_registration =
            prepare_registration(&registration_auth).map_err(|error| error.to_string())?;
        let signed_registration = sign_prepared_registration_with_signer_observations(
            &prepared_registration,
            &registration_auth,
            observations.registration,
        )
        .map_err(|error| error.to_string())?;
        let registration = send_prepared_registration(
            &registration_endpoint,
            &prepared_registration,
            &signed_registration,
        )
        .map_err(|error| error.to_string())?;

        let report_json = fixed_native_wechat_report()
            .to_json_bytes()
            .map_err(|error| error.to_string())?;
        let prepared_report =
            prepare_one_report(&report_json, &report_auth).map_err(|error| error.to_string())?;
        let signed_report = sign_prepared_report_with_signer_observations(
            &prepared_report,
            &report_auth,
            observations.report,
        )
        .map_err(|error| error.to_string())?;
        let report =
            send_prepared_report(DEFAULT_CLIENT_REPORT_URL, &prepared_report, &signed_report)
                .map_err(|error| error.to_string())?;

        Ok(serde_json::json!({
            "success": true,
            "userId": user_id,
            "registration": {
                "endpoint": registration_endpoint,
                "httpStatus": registration.http_status,
                "requestHeaders": nuke_request_headers(&signed_registration),
                "requestBody": registration.request_body,
                "canonicalPayload": registration.canonical_payload,
                "responseBody": registration.response_body,
                "code": registration.code,
                "message": registration.message,
            },
            "report": {
                "endpoint": DEFAULT_CLIENT_REPORT_URL,
                "httpStatus": report.http_status,
                "requestHeaders": nuke_request_headers(&signed_report),
                "requestBody": report.request_body,
                "canonicalPayload": report.canonical_payload,
                "responseBody": report.response_body,
                "decryptedJson": report.decrypted_json,
            },
        }))
    })();

    match result {
        Ok(value) => value.to_string(),
        Err(error) => nuke_client_error(error),
    }
}

fn native_error_string(env: *mut RawJNIEnv, result: Result<(), String>) -> jstring {
    if env.is_null() {
        return std::ptr::null_mut();
    }

    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => unsafe {
            let fns = *env;
            let c_str = CString::new(message)
                .unwrap_or_else(|_| CString::new("native conversion failed").unwrap());
            ((*fns).v1_6.NewStringUTF)(env, c_str.as_ptr())
        },
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI exports
// ─────────────────────────────────────────────────────────────────────────────

/// Execute the native-equivalent Nuke registration and encrypted report flow.
///
/// Java signature: `()Ljava/lang/String;`
#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_features_items_debug_Experiments2_submitNukeReportNative(
    env: *mut RawJNIEnv,
    _thiz: jobject,
) -> jstring {
    let result = run_nuke_client_transaction();
    let result = catch_unwind(AssertUnwindSafe(|| result))
        .unwrap_or_else(|_| nuke_client_error("native client panicked"));
    new_jstring(env, &result)
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
