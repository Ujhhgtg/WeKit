use super::{Reader, Result, jpeg, mp4};
use jni::sys::{JNI_FALSE, JNI_TRUE, JNIEnv, jboolean, jbyteArray, jlong, jobject};
use serde::Serialize;
use std::{ffi::CString, ptr};

fn reader(env: *mut JNIEnv, channel: jobject, length: jlong, headers_only: bool) -> Result<Reader> {
    if length < 0 || channel.is_null() {
        return Err("Invalid file channel".into());
    }
    unsafe {
        let api = &(**env).v1_6;
        let class = (api.GetObjectClass)(env, channel);
        let field = (api.GetFieldID)(
            env,
            class,
            c"fd".as_ptr(),
            c"Ljava/io/FileDescriptor;".as_ptr(),
        );
        (api.DeleteLocalRef)(env, class);
        if field.is_null() {
            return Err("FileChannel has no file descriptor".into());
        }
        let descriptor = (api.GetObjectField)(env, channel, field);
        if descriptor.is_null() {
            return Err("FileChannel is closed".into());
        }
        let class = (api.GetObjectClass)(env, descriptor);
        #[cfg(target_os = "android")]
        let name = c"descriptor";
        #[cfg(not(target_os = "android"))]
        let name = c"fd";
        let field = (api.GetFieldID)(env, class, name.as_ptr(), c"I".as_ptr());
        (api.DeleteLocalRef)(env, class);
        if field.is_null() {
            (api.DeleteLocalRef)(env, descriptor);
            return Err("Invalid file descriptor".into());
        }
        let fd = (api.GetIntField)(env, descriptor, field);
        (api.DeleteLocalRef)(env, descriptor);
        if fd < 0 {
            return Err("FileChannel is closed".into());
        }
        Ok(if headers_only {
            Reader::with_header_cache(fd, length as u64)
        } else {
            Reader::new(fd, length as u64)
        })
    }
}

fn throw(env: *mut JNIEnv, message: String) {
    unsafe {
        let api = &(**env).v1_6;
        if (api.ExceptionCheck)(env) != JNI_FALSE {
            return;
        }
        let class = (api.FindClass)(env, c"java/io/IOException".as_ptr());
        if class.is_null() {
            return;
        }
        let message = CString::new(message.replace('\0', " ")).expect("NUL removed");
        (api.ThrowNew)(env, class, message.as_ptr());
        (api.DeleteLocalRef)(env, class);
    }
}

fn json<T: Serialize>(env: *mut JNIEnv, result: Result<T>) -> jbyteArray {
    let bytes = match result
        .and_then(|value| serde_json::to_vec(&value).map_err(|error| error.to_string()))
    {
        Ok(bytes) => bytes,
        Err(error) => {
            throw(env, error);
            return ptr::null_mut();
        }
    };
    unsafe {
        let api = &(**env).v1_6;
        let array = (api.NewByteArray)(env, bytes.len() as i32);
        if !array.is_null() {
            (api.SetByteArrayRegion)(env, array, 0, bytes.len() as i32, bytes.as_ptr().cast());
        }
        array
    }
}

fn bounds(start: jlong, length: jlong) -> Result<(u64, u64)> {
    if start < 0 || length < 0 {
        return Err("Invalid parser bounds".into());
    }
    Ok((start as u64, length as u64))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_hasMetadata(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
) -> jboolean {
    match reader(env, channel, size, true).and_then(|input| jpeg::has_motion_metadata(&input)) {
        Ok(true) => JNI_TRUE,
        Ok(false) => JNI_FALSE,
        Err(error) => {
            throw(env, error);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_inspectPhoto(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
) -> jbyteArray {
    json(
        env,
        reader(env, channel, size, false).and_then(|input| jpeg::inspect(&input)),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_parseJpeg(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
    headers: jboolean,
    discard: jboolean,
) -> jbyteArray {
    json(
        env,
        reader(env, channel, size, false)
            .and_then(|input| jpeg::parse(&input, headers != JNI_FALSE, discard != JNI_FALSE)),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_inspectMetadata(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
    start: jlong,
    length: jlong,
) -> jbyteArray {
    json(
        env,
        bounds(start, length).and_then(|(start, length)| {
            reader(env, channel, size, false).and_then(|input| mp4::inspect(&input, start, length))
        }),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_analyzeVideo(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
    start: jlong,
    length: jlong,
) -> jbyteArray {
    json(
        env,
        bounds(start, length).and_then(|(start, length)| {
            reader(env, channel, size, false)
                .and_then(|input| mp4::analyze_layout(&input, start, length))
        }),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_livephoto_MotionPhotoNative_readBoxes(
    env: *mut JNIEnv,
    _: jobject,
    channel: jobject,
    size: jlong,
    start: jlong,
    end: jlong,
) -> jbyteArray {
    json(
        env,
        bounds(start, end).and_then(|(start, end)| {
            reader(env, channel, size, false).and_then(|input| mp4::boxes(&input, start, end))
        }),
    )
}
