pub mod elf;
pub mod matcher;
mod patch;
pub mod snapshot;
mod stackmap;

use std::collections::HashMap;
use std::ffi::CString;
use std::sync::{Mutex, OnceLock};

use jni::sys::{JNIEnv, jboolean, jint, jintArray, jobject, jstring};

static IMAGES: OnceLock<Mutex<HashMap<usize, String>>> = OnceLock::new();

fn install() -> anyhow::Result<Option<String>> {
    let images = elf::loaded()?;
    if images.is_empty() {
        return Ok(None);
    }
    let mut installed = IMAGES.get_or_init(Default::default).lock().unwrap();
    let mut reports = Vec::new();
    for image in images {
        if let Some(report) = installed.get(&image.code_base) {
            reports.push(report.clone());
            continue;
        }
        let started = std::time::Instant::now();
        let result = snapshot::parse(image.data, image.instructions.len()).and_then(|snapshot| {
            let resolution = matcher::resolve(image.instructions, image.data, &snapshot)?;
            let count = patch::install(image.code_base, &resolution.targets, &resolution.factory)?;
            let mut roles: Vec<_> = resolution
                .targets
                .iter()
                .map(|target| target.role.name())
                .collect();
            roles.sort_unstable();
            roles.dedup();
            Ok(format!(
                "installed {count} semantic Flutter hooks in {} ms ({} snapshot objects): {}",
                started.elapsed().as_millis(),
                snapshot.object_count,
                roles.join(", ")
            ))
        });
        let report = match result {
            Ok(report) => report,
            Err(error) => format!("Flutter semantic hooks unavailable: {error:#}"),
        };
        crate::logi!("{report}");
        installed.insert(image.code_base, report.clone());
        reports.push(report);
    }
    Ok(Some(reports.join("; ")))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_monet_FlutterMonet_00024Native_install(
    env: *mut JNIEnv,
    _object: jobject,
) -> jstring {
    let report = match install() {
        Ok(Some(report)) => report,
        Ok(None) => return std::ptr::null_mut(),
        Err(error) => format!("Flutter semantic hooks failed: {error:#}"),
    };
    let report = CString::new(report).unwrap();
    unsafe { ((**env).v1_6.NewStringUTF)(env, report.as_ptr()) }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_monet_FlutterMonet_00024Native_updatePalette(
    env: *mut JNIEnv,
    _object: jobject,
    values: jintArray,
    dark: jboolean,
) {
    let length = unsafe { ((**env).v1_6.GetArrayLength)(env, values) };
    if length != 26 {
        crate::loge!("Flutter Monet palette has {length} entries, expected 26");
        return;
    }
    let mut colors = [0 as jint; 26];
    unsafe { ((**env).v1_6.GetIntArrayRegion)(env, values, 0, length, colors.as_mut_ptr()) };
    if let Err(error) = patch::update_palette(&colors, dark) {
        crate::loge!("Flutter Monet palette failed: {error:#}");
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_dev_ujhhgtg_wekit_utils_monet_FlutterMonet_00024Native_setEnabled(
    _env: *mut JNIEnv,
    _object: jobject,
    enabled: jboolean,
) {
    patch::set_enabled(enabled);
}
