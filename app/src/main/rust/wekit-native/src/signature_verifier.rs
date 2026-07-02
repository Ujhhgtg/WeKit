//! Native signature verification.
//!
//! The security-critical parts — SHA-256 hashing, the expected-hash constant,
//! and the pass/fail decision — live here in compiled code. The Android APIs
//! required to read the signing certificate have no native equivalent, so they
//! are called back through JNI.

use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::sys::jint;
use jni::{Env, JValue};
use sha2::{Digest, Sha256};

use crate::{loge, logi, logw};

/// SHA-256 of the expected signing certificate (DER encoded).
const EXPECTED_CERT_SHA256: &str = "6bc3be50d46327b8c66b564ff946a9c51bbb72a198fc7cd651e9726901584315";

/// `PackageManager.GET_SIGNING_CERTIFICATES`
const GET_SIGNING_CERTIFICATES: jint = 0x0800_0000;

/// Verify the module's signing certificate against [`EXPECTED_CERT_SHA256`].
///
/// Returns `true` only if a certificate was recovered and its hash matches.
/// Any error is treated as a verification failure (returns `false`); this
/// function never panics across the FFI boundary.
pub fn verify(env: &mut Env, context: &JObject, package_name: &str) -> bool {
    match verify_via_package_manager(env, context, package_name) {
        Ok(true) => {
            logi!("PackageManager verification succeeded");
            return true;
        }
        Ok(false) => {
            logw!("PackageManager verification mismatch, trying APK path fallback");
        }
        Err(e) => {
            logw!(
                "PackageManager verification failed ({e:?}), trying APK path fallback"
            );
            // A failed JNI call may leave a pending Java exception (e.g.
            // NameNotFoundException). Clear it so the fallback's own JNI
            // calls are not disturbed.
            if env.exception_check() {
                env.exception_clear();
            }
        }
    }

    match verify_via_apk_path(env, context, package_name) {
        Ok(true) => {
            logi!("APK verification succeeded");
            true
        }
        Ok(false) => {
            loge!("APK verification mismatch");
            false
        }
        Err(e) => {
            loge!("APK verification failed: {e:?}");
            if env.exception_check() {
                env.exception_clear();
            }
            false
        }
    }
}

/// Hash `cert_der` and compare (case-insensitively) against the expected hash.
fn cert_hash_matches(cert_der: &[u8]) -> bool {
    let hash = Sha256::digest(cert_der);
    let mut hash_hex = String::with_capacity(hash.len() * 2);
    for b in hash.iter() {
        hash_hex.push_str(&format!("{b:02x}"));
    }

    if hash_hex == EXPECTED_CERT_SHA256 {
        true
    } else {
        loge!("signature mismatch! expected={EXPECTED_CERT_SHA256}, actual={hash_hex}");
        false
    }
}

/// Primary path: recover the DER cert via `PackageManager` signing info.
fn verify_via_package_manager(
    env: &mut Env,
    context: &JObject,
    package_name: &str,
) -> jni::errors::Result<bool> {
    // context.getPackageManager()
    let pm = env
        .call_method(
            context,
            jni::jni_str!("getPackageManager"),
            jni::jni_sig!("()Landroid/content/pm/PackageManager;"),
            &[],
        )?
        .l()?;

    // pm.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)
    let name_jstr = env.new_string(package_name)?;
    let package_info = env
        .call_method(
            &pm,
            jni::jni_str!("getPackageInfo"),
            jni::jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"),
            &[
                JValue::Object(&name_jstr),
                JValue::Int(GET_SIGNING_CERTIFICATES),
            ],
        )?
        .l()?;

    // packageInfo.signingInfo (field)
    let signing_info = env
        .get_field(
            &package_info,
            jni::jni_str!("signingInfo"),
            jni::jni_sig!("Landroid/content/pm/SigningInfo;"),
        )?
        .l()?;
    if signing_info.is_null() {
        logw!("no signing info found");
        return Ok(false);
    }

    // signingInfo.getSigningCertificateHistory() -> Signature[]
    let history = env
        .call_method(
            &signing_info,
            jni::jni_str!("getSigningCertificateHistory"),
            jni::jni_sig!("()[Landroid/content/pm/Signature;"),
            &[],
        )?
        .l()?;
    if history.is_null() {
        logw!("no signing certificate history found");
        return Ok(false);
    }

    let history: JObjectArray<JObject> =
        unsafe { JObjectArray::<JObject>::from_raw(env, history.as_raw()) };
    if history.len(env)? == 0 {
        logw!("signing certificate history is empty");
        return Ok(false);
    }

    // history[0].toByteArray() -> byte[]  (already DER encoded)
    let signature = history.get_element(env, 0)?;
    let cert_bytes = env
        .call_method(
            &signature,
            jni::jni_str!("toByteArray"),
            jni::jni_sig!("()[B"),
            &[],
        )?
        .l()?;
    let cert_bytes: JByteArray = unsafe { JByteArray::from_raw(env, cert_bytes.as_raw()) };
    let cert_der = env.convert_byte_array(&cert_bytes)?;

    Ok(cert_hash_matches(&cert_der))
}

/// Fallback path: read the base APK, extract the X.509 cert from its PKCS#7
/// signature block, all via JNI.
fn verify_via_apk_path(
    env: &mut Env,
    context: &JObject,
    package_name: &str,
) -> jni::errors::Result<bool> {
    let Some(apk_path) = resolve_base_apk_path(env, context, package_name)? else {
        loge!("failed to resolve base APK path");
        return Ok(false);
    };
    logi!("resolved base APK path: {apk_path}");

    // ZipFile zip = new ZipFile(apkPath)
    let apk_path_jstr = env.new_string(&apk_path)?;
    let zip_file = env.new_object(
        jni::jni_str!("java/util/zip/ZipFile"),
        jni::jni_sig!("(Ljava/lang/String;)V"),
        &[JValue::Object(&apk_path_jstr)],
    )?;

    let result = extract_and_verify_from_zip(env, &zip_file);

    // zip.close() regardless of outcome
    let _ = env.call_method(
        &zip_file,
        jni::jni_str!("close"),
        jni::jni_sig!("()V"),
        &[],
    );

    result
}

/// Iterate the ZIP entries, find the signature block, parse the cert and verify.
fn extract_and_verify_from_zip(
    env: &mut Env,
    zip_file: &JObject,
) -> jni::errors::Result<bool> {
    // Enumeration<? extends ZipEntry> entries = zip.entries()
    let entries = env
        .call_method(
            zip_file,
            jni::jni_str!("entries"),
            jni::jni_sig!("()Ljava/util/Enumeration;"),
            &[],
        )?
        .l()?;

    loop {
        let has_more = env
            .call_method(
                &entries,
                jni::jni_str!("hasMoreElements"),
                jni::jni_sig!("()Z"),
                &[],
            )?
            .z()?;
        if !has_more {
            break;
        }

        let entry = env
            .call_method(
                &entries,
                jni::jni_str!("nextElement"),
                jni::jni_sig!("()Ljava/lang/Object;"),
                &[],
            )?
            .l()?;

        let name_obj = env
            .call_method(
                &entry,
                jni::jni_str!("getName"),
                jni::jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let name_jstr: JString = unsafe { JString::from_raw(env, name_obj.as_raw()) };
        let name = name_jstr.try_to_string(env)?;

        if !is_signature_entry(&name) {
            continue;
        }
        logi!("found signature entry: {name}");

        // InputStream is = zip.getInputStream(entry)
        let input_stream = env
            .call_method(
                zip_file,
                jni::jni_str!("getInputStream"),
                jni::jni_sig!("(Ljava/util/zip/ZipEntry;)Ljava/io/InputStream;"),
                &[JValue::Object(&entry)],
            )?
            .l()?;

        // CertificateFactory cf = CertificateFactory.getInstance("X.509")
        let x509 = env.new_string("X.509")?;
        let cert_factory = env
            .call_static_method(
                jni::jni_str!("java/security/cert/CertificateFactory"),
                jni::jni_str!("getInstance"),
                jni::jni_sig!(
                    "(Ljava/lang/String;)Ljava/security/cert/CertificateFactory;"
                ),
                &[JValue::Object(&x509)],
            )?
            .l()?;

        // Certificate cert = cf.generateCertificate(is)
        let cert = env
            .call_method(
                &cert_factory,
                jni::jni_str!("generateCertificate"),
                jni::jni_sig!(
                    "(Ljava/io/InputStream;)Ljava/security/cert/Certificate;"
                ),
                &[JValue::Object(&input_stream)],
            )?
            .l()?;
        if cert.is_null() {
            loge!("no certificate found in APK signature");
            return Ok(false);
        }

        // byte[] der = cert.getEncoded()
        let encoded = env
            .call_method(
                &cert,
                jni::jni_str!("getEncoded"),
                jni::jni_sig!("()[B"),
                &[],
            )?
            .l()?;
        let encoded: JByteArray = unsafe { JByteArray::from_raw(env, encoded.as_raw()) };
        let cert_der = env.convert_byte_array(&encoded)?;

        return Ok(cert_hash_matches(&cert_der));
    }

    loge!("no signature entry found in APK");
    Ok(false)
}

/// A `META-INF/*.{RSA,DSA,EC}` PKCS#7 signature block.
fn is_signature_entry(name: &str) -> bool {
    name.starts_with("META-INF/")
        && (name.ends_with(".RSA") || name.ends_with(".DSA") || name.ends_with(".EC"))
}

/// Resolve the base APK path via `PackageManager.getApplicationInfo(...).sourceDir`.
fn resolve_base_apk_path(
    env: &mut Env,
    context: &JObject,
    package_name: &str,
) -> jni::errors::Result<Option<String>> {
    let pm = env
        .call_method(
            context,
            jni::jni_str!("getPackageManager"),
            jni::jni_sig!("()Landroid/content/pm/PackageManager;"),
            &[],
        )?
        .l()?;

    let name_jstr = env.new_string(package_name)?;
    let app_info = env
        .call_method(
            &pm,
            jni::jni_str!("getApplicationInfo"),
            jni::jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;"),
            &[JValue::Object(&name_jstr), JValue::Int(0)],
        )?
        .l()?;

    let source_dir = env
        .get_field(
            &app_info,
            jni::jni_str!("sourceDir"),
            jni::jni_sig!("Ljava/lang/String;"),
        )?
        .l()?;
    if source_dir.is_null() {
        return Ok(None);
    }

    let source_dir: JString = unsafe { JString::from_raw(env, source_dir.as_raw()) };
    Ok(Some(source_dir.try_to_string(env)?))
}
