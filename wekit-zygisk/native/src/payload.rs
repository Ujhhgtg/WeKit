// APK publication across app specialization and InMemoryDexClassLoader construction.

use crate::loge;
use anyhow::{Context, Result, ensure};
use jni::sys::{JNIEnv as RawJNIEnv, jobject};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

/// Copy the already-open module APK after specialization. Content-addressed paths
/// keep resources, DEX, native libraries and child processes on the same version.
pub fn publish_apk(mut source: File, data_dir: &str) -> Result<(PathBuf, Vec<Vec<u8>>)> {
    let directory = Path::new(data_dir).join("files/mmkv");
    fs::create_dir_all(&directory)?;
    let temporary = directory.join(format!(".wekit-bootstrap-{}.tmp", std::process::id()));
    // A prior crash may have left this PID's temporary file. Never follow it.
    match fs::remove_file(&temporary) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    let result = (|| {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&temporary)?;
        // Dynamic code is read-only before its content is written (the open fd
        // still allows writes), and remains read-only when published.
        output.set_permissions(fs::Permissions::from_mode(0o400))?;
        let mut hash = Sha256::new();
        let mut bytes = [0u8; 65536];
        let mut total = 0u64;
        loop {
            let count = source.read(&mut bytes)?;
            if count == 0 {
                break;
            }
            total += count as u64;
            ensure!(
                total <= crate::apk::MAX_APK_BYTES,
                "module APK exceeds size limit"
            );
            hash.update(&bytes[..count]);
            output.write_all(&bytes[..count])?;
        }
        ensure!(total > 0, "empty module APK");
        output.sync_all()?;
        drop(output);
        let digest = hash
            .finalize()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let destination = directory.join(format!(".wekit-bootstrap-{digest}.apk"));
        // Decode the exact copy before publication so APK and DEX cannot diverge.
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)?;
        let dex = crate::apk::read_dex(file).context("invalid module APK DEX")?;
        // rename is atomic, including concurrent copies of the same content. A
        // different APK hash always uses a different path.
        fs::rename(&temporary, &destination)?;
        Ok((destination, dex))
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

// ── InMemoryDexClassLoader ────────────────────────────────────────────────────

/// Build an InMemoryDexClassLoader from byte slices via raw JNI.
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer for the current thread.
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> jobject {
    let fns = *env;
    if ((*fns).v1_6.PushLocalFrame)(env, 16) < 0 {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    let loader = (|| {
        let bb_class = ((*fns).v1_6.FindClass)(env, c"java/nio/ByteBuffer".as_ptr());
        if bb_class.is_null() {
            return std::ptr::null_mut();
        }
        let arr = ((*fns).v1_6.NewObjectArray)(
            env,
            dex_buffers.len() as i32,
            bb_class,
            std::ptr::null_mut(),
        );
        if arr.is_null() {
            return std::ptr::null_mut();
        }
        for (i, buf) in dex_buffers.iter().enumerate() {
            let bb =
                ((*fns).v1_6.NewDirectByteBuffer)(env, buf.as_ptr() as *mut _, buf.len() as i64);
            if bb.is_null() {
                return std::ptr::null_mut();
            }
            ((*fns).v1_6.SetObjectArrayElement)(env, arr, i as i32, bb);
            ((*fns).v1_6.DeleteLocalRef)(env, bb);
            if ((*fns).v1_6.ExceptionCheck)(env) != jni::sys::JNI_FALSE {
                return std::ptr::null_mut();
            }
        }
        let cl_class =
            ((*fns).v1_6.FindClass)(env, c"dalvik/system/InMemoryDexClassLoader".as_ptr());
        if cl_class.is_null() {
            return std::ptr::null_mut();
        }
        let ctor = ((*fns).v1_6.GetMethodID)(
            env,
            cl_class,
            c"<init>".as_ptr(),
            c"([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V".as_ptr(),
        );
        if ctor.is_null() {
            return std::ptr::null_mut();
        }
        let loader = ((*fns).v1_6.NewObject)(env, cl_class, ctor, arr, parent_loader);
        if loader.is_null() {
            return std::ptr::null_mut();
        }
        ((*fns).v1_6.NewGlobalRef)(env, loader)
    })();
    if ((*fns).v1_6.ExceptionCheck)(env) != jni::sys::JNI_FALSE {
        ((*fns).v1_6.ExceptionDescribe)(env);
        ((*fns).v1_6.ExceptionClear)(env);
    }
    ((*fns).v1_6.PopLocalFrame)(env, std::ptr::null_mut());
    if loader.is_null() {
        loge!("Zygisk: InMemoryDexClassLoader allocation failed");
    }
    loader
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use zip::{ZipWriter, write::SimpleFileOptions};

    #[test]
    fn publication_keeps_versions_readonly_and_failure_preserves_previous_apks() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        struct Directory(PathBuf);
        impl Drop for Directory {
            fn drop(&mut self) {
                let _ = fs::remove_dir_all(&self.0);
            }
        }
        let directory = Directory(std::env::temp_dir().join(format!(
            "wekit-apk-publication-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        )));
        fs::create_dir(&directory.0).unwrap();
        let source = directory.0.join("input.apk");
        let host = directory.0.join("host");
        let mut previous = Vec::new();
        for dex in [b"first".as_slice(), b"second".as_slice()] {
            let mut zip = ZipWriter::new(File::create(&source).unwrap());
            zip.start_file("classes.dex", SimpleFileOptions::default())
                .unwrap();
            zip.write_all(dex).unwrap();
            zip.finish().unwrap();
            let original = fs::read(&source).unwrap();
            let (path, buffers) =
                publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).unwrap();
            assert_eq!(buffers, [dex.to_vec()]);
            assert_eq!(fs::read(&path).unwrap(), original);
            assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o222, 0);
            previous.push((path, original));
        }
        assert_ne!(previous[0].0, previous[1].0);
        fs::write(&source, b"corrupted APK").unwrap();
        assert!(publish_apk(File::open(&source).unwrap(), host.to_str().unwrap()).is_err());
        for (path, bytes) in previous {
            assert_eq!(fs::read(path).unwrap(), bytes);
        }
        assert_eq!(fs::read_dir(host.join("files/mmkv")).unwrap().count(), 2);
    }
}
