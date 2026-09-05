//! Extension-pack packaging: build pack assets plus the remote index.
//!
//! Version format: first 12 hex chars of the SHA-256 over the sorted
//! `name:sha256\n` file lines — content-addressed, no manual version
//! bookkeeping, and a rebuild of identical content keeps the same version (and
//! asset name), so CI never publishes and devices never re-download unchanged
//! content.
//!
//! The index (`manifest.json`, uploaded next to the assets) is the single
//! source of truth for "latest": each entry carries the pack id, version,
//! Release asset file name, and the asset's SHA-256, which the device verifies
//! after download.

use anyhow::{Context, Result};
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use zip::ZipWriter;
use zip::write::SimpleFileOptions;

const PACK_SCRIPT_DEPS: &str = "script-deps";
const PACK_PYTHON_RUNTIME: &str = "python-runtime";
const PACK_CLOUDFLARED: &str = "cloudflared";
const PACK_ARCHLINUX: &str = "archlinux-arm64";
const PACK_LLAMA: &str = "llama-native";
/// Static index entry for the externally hosted Qwen GGUF; no asset is built.
const PACK_QWEN_MODEL: &str = "qwen3.8-4b-distill";
const DIST_DIR: &str = "dist/extensions";
const INDEX_FILE: &str = "manifest.json";
const CLOUDFLARED_LIB: &str = "libwekit_cloudflared.so";
const LLAMA_LIB: &str = "libwekit_llama.so";
const LLAMA_LIB_OPENCL: &str = "libwekit_llama_opencl.so";
const LLAMA_ABI: &str = "arm64-v8a";
const LLAMA_TARGET: &str = "aarch64-linux-android";
const LLAMA_CRATE: &str = "app/src/main/rust/wekit-llama";

#[derive(Args)]
pub struct ExtensionsArgs {
    #[command(subcommand)]
    pub command: ExtensionsCommand,

    /// Only process the given pack id (script-deps | python-runtime |
    /// cloudflared | archlinux-arm64 | llama-native | qwen3.8-4b-distill). Skips writing the index.
    #[arg(long, global = true)]
    pub only: Option<String>,
}

#[derive(Subcommand)]
pub enum ExtensionsCommand {
    /// Build pack assets and the manifest.json index into dist/extensions.
    Pack,
}

/// The remotely published index; mirrored on-device by `PackIndex.kt`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackIndex {
    pub packs: Vec<PackIndexEntry>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PackIndexEntry {
    pub id: String,
    pub version: String,
    /// Release asset file name for this version.
    pub asset: String,
    pub sha256: String,
    /// Download URL for packs fetched from a third-party host instead of the
    /// WeKit release; `asset` is then a placeholder (e.g. `external`).
    #[serde(rename = "externalUrl", skip_serializing_if = "Option::is_none")]
    pub external_url: Option<String>,
    /// Exact download size in bytes for externally hosted packs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bytes: Option<u64>,
    /// Opaque pack-specific metadata (e.g. the model manifest for model packs).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meta: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ArchSources {
    rootfs: ArchRootfsSource,
    proot: ArchProotSource,
    bridge: ArchBridgeSource,
}

#[derive(Debug, Deserialize)]
struct ArchRootfsSource {
    release: String,
    url: String,
    md5: String,
    sha256: String,
    max_extracted_bytes: u64,
    signature_url: String,
    signing_fingerprint: String,
}
#[derive(Debug, Deserialize)]
struct ArchProotSource {
    source: String,
    commit: String,
}
#[derive(Debug, Deserialize)]
struct ArchBridgeSource {
    cargo_package: String,
    target: String,
}

/// SHA-256 over the sorted `name:sha256\n` lines — the pack's content identity.
pub fn content_hash(files: &BTreeMap<String, String>) -> String {
    let mut hasher = Sha256::new();
    for (name, sha) in files {
        hasher.update(format!("{name}:{sha}\n").as_bytes());
    }
    hex(&hasher.finalize())
}

/// First 12 hex chars of the content hash.
pub fn derive_version(content_hash: &str) -> String {
    content_hash[..12].to_string()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

fn md5_file(path: &Path) -> Result<String> {
    let mut file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut context = md5::Context::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        context.consume(&buf[..n]);
    }
    Ok(format!("{:x}", context.finalize()))
}

/// Index entry for a pack: versioned asset name plus the asset's SHA-256.
/// The files map holds exactly one canonical (version-less) name -> sha entry.
fn index_entry(id: &str, version: &str, files: &BTreeMap<String, String>) -> PackIndexEntry {
    let (name, sha) = files
        .iter()
        .next()
        .unwrap_or_else(|| panic!("pack '{id}' has no files"));
    let stem = name.split('.').next().unwrap();
    let ext = name.rsplit('.').next().filter(|e| *e != name);
    let asset = match ext {
        Some(e) => format!("{stem}-{version}.{e}"),
        None => format!("{stem}-{version}"),
    };
    PackIndexEntry {
        id: id.into(),
        version: version.into(),
        asset,
        sha256: sha.clone(),
        external_url: None,
        bytes: None,
        meta: None,
    }
}

pub fn run(root: &Path, args: &ExtensionsArgs) -> Result<()> {
    let selected = |id: &str| args.only.as_deref().map(|only| only == id).unwrap_or(true);

    let dist = root.join(DIST_DIR);
    fs::create_dir_all(&dist)?;

    let mut entries: Vec<PackIndexEntry> = Vec::new();
    if selected(PACK_SCRIPT_DEPS) {
        entries.push(build_script_deps(root, &dist)?);
    }
    if selected(PACK_PYTHON_RUNTIME) {
        entries.push(build_python_runtime(root, &dist)?);
    }
    if selected(PACK_CLOUDFLARED) {
        entries.push(build_cloudflared_zip(root, &dist)?);
    }
    if selected(PACK_ARCHLINUX) {
        entries.push(build_archlinux_zip(root, &dist)?);
    }
    if selected(PACK_LLAMA) {
        entries.push(build_llama_zip(root, &dist)?);
    }
    // Always-present index entry for the externally hosted Qwen GGUF: a full
    // pack run appends it alongside the built assets; `--only qwen3.8-4b-distill`
    // just prints it — no asset is built or downloaded here.
    if selected(PACK_QWEN_MODEL) {
        entries.push(qwen_model_entry());
    }
    entries.sort_by(|a, b| a.id.cmp(&b.id));

    match &args.command {
        ExtensionsCommand::Pack => {
            for entry in &entries {
                println!(
                    "pack: {} {} → {}",
                    entry.id,
                    entry.version,
                    dist.join(&entry.asset).display()
                );
            }
            if args.only.is_some() {
                println!(
                    "note: --only skips writing {INDEX_FILE}; run a full `cargo xtask extensions pack` to refresh the index"
                );
            } else {
                let index_path = dist.join(INDEX_FILE);
                fs::write(
                    &index_path,
                    serde_json::to_string_pretty(&PackIndex { packs: entries })?,
                )
                .with_context(|| format!("write {}", index_path.display()))?;
                println!("index: {}", index_path.display());
            }
        }
    }
    Ok(())
}

fn read_arch_sources(root: &Path) -> Result<ArchSources> {
    let path = root.join("extension-packs/archlinux-arm64-sources.json");
    parse_arch_sources(&fs::read(&path)?)
}

fn parse_arch_sources(bytes: &[u8]) -> Result<ArchSources> {
    let source: ArchSources = serde_json::from_slice(bytes)?;
    anyhow::ensure!(
        source
            .rootfs
            .release
            .chars()
            .all(|c| c.is_ascii_digit() || c == '.'),
        "invalid Arch release"
    );
    anyhow::ensure!(
        source.rootfs.url.starts_with("https://")
            && source.rootfs.signature_url.starts_with("https://"),
        "Arch inputs must use HTTPS"
    );
    anyhow::ensure!(
        source.rootfs.md5.len() == 32 && source.rootfs.md5.chars().all(|c| c.is_ascii_hexdigit()),
        "invalid rootfs MD5"
    );
    anyhow::ensure!(
        source.rootfs.sha256.len() == 64
            && source.rootfs.sha256.chars().all(|c| c.is_ascii_hexdigit()),
        "invalid rootfs SHA-256"
    );
    anyhow::ensure!(
        source.rootfs.max_extracted_bytes >= 1024 * 1024 * 1024,
        "invalid rootfs extracted-size limit"
    );
    anyhow::ensure!(
        source.rootfs.signing_fingerprint.len() == 40
            && source
                .rootfs
                .signing_fingerprint
                .chars()
                .all(|c| c.is_ascii_hexdigit()),
        "invalid rootfs signing fingerprint"
    );
    anyhow::ensure!(
        source.proot.source.starts_with("https://")
            && source.proot.commit.len() == 40
            && source.proot.commit.chars().all(|c| c.is_ascii_hexdigit()),
        "invalid pinned PRoot source"
    );
    anyhow::ensure!(
        source.proot.commit == crate::PROOT_COMMIT,
        "Arch descriptor PRoot commit does not match the packaged PRoot pin",
    );
    anyhow::ensure!(
        source.bridge.cargo_package == "invoke_tool"
            && source.bridge.target == "aarch64-linux-android",
        "invalid bridge identity"
    );
    Ok(source)
}

fn verify_arch_rootfs(path: &Path, source: &ArchRootfsSource) -> Result<()> {
    anyhow::ensure!(
        sha256_file(path)?.eq_ignore_ascii_case(&source.sha256),
        "pinned Arch rootfs SHA-256 mismatch"
    );
    anyhow::ensure!(
        md5_file(path)?.eq_ignore_ascii_case(&source.md5),
        "pinned Arch rootfs MD5 mismatch"
    );
    Ok(())
}

fn arch_proot_input_paths(root: &Path) -> (PathBuf, PathBuf) {
    crate::proot_artifact_paths(root)
}

fn build_archlinux_zip(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    let source = read_arch_sources(root)?;
    let rootfs = std::env::var_os("WEKIT_ARCH_ROOTFS").map(PathBuf::from)
        .context("WEKIT_ARCH_ROOTFS must point to the separately downloaded and signature/checksum-verified rootfs")?;
    let (proot, proot_loader) = arch_proot_input_paths(root);
    let bridge = root.join("app/src/main/jniLibs/arm64-v8a/libinvoke_tool.so");
    anyhow::ensure!(
        rootfs.is_file() && proot.is_file() && proot_loader.is_file() && bridge.is_file(),
        "Arch pack input is missing"
    );
    verify_arch_rootfs(&rootfs, &source.rootfs)?;

    let inputs = [
        ("ArchLinuxARM-aarch64-rootfs.tar.gz", rootfs),
        ("proot", proot),
        ("proot-loader", proot_loader),
        ("invoke_tool", bridge),
    ];
    let inner = inputs
        .iter()
        .map(|(name, path)| Ok((name.to_string(), sha256_file(path)?)))
        .collect::<Result<BTreeMap<_, _>>>()?;
    let inner_manifest = serde_json::to_string_pretty(&serde_json::json!({
        "source": {
            "rootfs_release": source.rootfs.release,
            "rootfs_url": source.rootfs.url,
            "rootfs_md5": source.rootfs.md5,
            "rootfs_sha256": source.rootfs.sha256,
            "rootfs_max_extracted_bytes": source.rootfs.max_extracted_bytes,
            "rootfs_signature_url": source.rootfs.signature_url,
            "rootfs_signing_fingerprint": source.rootfs.signing_fingerprint,
            "proot_source": source.proot.source,
            "proot_commit": source.proot.commit,
        },
        "files": inner,
    }))?;
    let zip_tmp = dist.join("archlinux-arm64-unversioned.zip");
    write_arch_zip(&zip_tmp, &inputs, &inner_manifest)?;
    let mut files = BTreeMap::new();
    files.insert("archlinux-arm64.zip".to_string(), sha256_file(&zip_tmp)?);
    let version = derive_version(&content_hash(&files));
    let entry = index_entry(PACK_ARCHLINUX, &version, &files);
    let asset = dist.join(&entry.asset);
    fs::rename(&zip_tmp, &asset)?;
    clean_stale(dist, "archlinux-arm64-", &asset)?;
    Ok(entry)
}

fn write_arch_zip(path: &Path, inputs: &[(&str, PathBuf)], inner_manifest: &str) -> Result<()> {
    let mut zip = ZipWriter::new(File::create(path)?);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, path) in inputs {
        zip.start_file(name, options)?;
        let mut input = File::open(path)?;
        std::io::copy(&mut input, &mut zip)?;
    }
    zip.start_file("manifest.json", options)?;
    zip.write_all(inner_manifest.as_bytes())?;
    zip.finish()?;
    Ok(())
}

fn build_script_deps(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    let gradlew = if cfg!(windows) {
        "gradlew.bat"
    } else {
        "./gradlew"
    };
    let status = Command::new(gradlew)
        .args([":app:generateScriptDepsDex", "--quiet"])
        .current_dir(root)
        .status()
        .context("failed to spawn gradlew")?;
    if !status.success() {
        anyhow::bail!(":app:generateScriptDepsDex failed");
    }

    let dex = root.join("app/build/outputs/script-deps/classes.dex");
    let mut files = BTreeMap::new();
    files.insert("script-deps.dex".to_string(), sha256_file(&dex)?);
    let version = derive_version(&content_hash(&files));
    let entry = index_entry(PACK_SCRIPT_DEPS, &version, &files);

    let asset = dist.join(&entry.asset);
    fs::copy(&dex, &asset).context("copy script-deps DEX into dist")?;
    clean_stale(dist, "script-deps-", &asset)?;

    println!("script-deps: {version}");
    Ok(entry)
}

fn build_python_runtime(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    let gradlew = if cfg!(windows) {
        "gradlew.bat"
    } else {
        "./gradlew"
    };
    let catalog: toml::Value =
        toml::from_str(&fs::read_to_string(root.join("gradle/libs.versions.toml"))?)?;
    let api_version = catalog["versions"]["pythonRuntimeApiVersion"]
        .as_str()
        .context("missing pythonRuntimeApiVersion in version catalog")?;
    let python_version = catalog["versions"]["pythonRuntimePython"]
        .as_str()
        .context("missing pythonRuntimePython in version catalog")?;
    let build_python = find_or_install_uv_python(python_version)?;
    let api_repo = root.join("target/python-runtime-api-maven");
    if api_repo.exists() {
        fs::remove_dir_all(&api_repo)?;
    }
    fs::create_dir_all(&api_repo)?;
    let repo_property = format!("-PwekitPythonApiRepo={}", api_repo.display());
    let version_property = format!("-PwekitPythonApiVersion={api_version}");
    let python_property = format!("-PwekitPythonBuildExecutable={}", build_python.display());

    let status = Command::new(gradlew)
        .args([":libs:python-runtime-api:bundleReleaseAar", "--quiet"])
        .current_dir(root)
        .status()
        .context("failed to build Python runtime API")?;
    anyhow::ensure!(status.success(), "Python runtime API build failed");
    let api_dir = api_repo
        .join("dev/ujhhgtg/wekit/python-runtime-api")
        .join(api_version);
    fs::create_dir_all(&api_dir)?;
    let api_name = format!("python-runtime-api-{api_version}");
    fs::copy(
        root.join("libs/python-runtime-api/build/outputs/aar/python-runtime-api-release.aar"),
        api_dir.join(format!("{api_name}.aar")),
    )?;
    fs::write(
        api_dir.join(format!("{api_name}.pom")),
        format!(
            "<project><modelVersion>4.0.0</modelVersion><groupId>dev.ujhhgtg.wekit</groupId><artifactId>python-runtime-api</artifactId><version>{api_version}</version><packaging>aar</packaging></project>"
        ),
    )?;

    let status = Command::new(gradlew)
        .args([
            "-p",
            "extension-packs/python-runtime",
            ":runtime:extractChaquopyTarget",
            "--quiet",
            repo_property.as_str(),
            version_property.as_str(),
            python_property.as_str(),
        ])
        .current_dir(root)
        .status()
        .context("failed to resolve the pinned Chaquopy target")?;
    anyhow::ensure!(status.success(), "Chaquopy target extraction failed");

    let patched_bridge = build_patched_chaquopy_bridge(root, &catalog)?;
    let native_wheels = build_python_native_wheels(root, &catalog, &build_python)?;
    let second_dex = build_runtime_multidex_probe(root, &catalog)?;
    let bridge_property = format!("-PwekitPatchedChaquopyBridge={}", patched_bridge.display());
    let wheel_property = format!("-PwekitPythonWheelDirectory={}", native_wheels.display());

    let status = Command::new(gradlew)
        .args([
            "-p",
            "extension-packs/python-runtime",
            ":runtime:assembleRelease",
            "--quiet",
            repo_property.as_str(),
            version_property.as_str(),
            bridge_property.as_str(),
            python_property.as_str(),
            wheel_property.as_str(),
        ])
        .current_dir(root)
        .status()
        .context("failed to build Python runtime")?;
    anyhow::ensure!(status.success(), "Python runtime build failed");

    let runtime_apk = root.join(
        "extension-packs/python-runtime/runtime/build/outputs/apk/release/runtime-release-unsigned.apk",
    );
    anyhow::ensure!(runtime_apk.is_file(), "Python runtime APK was not produced");
    let runtime_container = dist.join("python-runtime-unversioned.apk");
    build_runtime_container(&runtime_apk, &second_dex, &runtime_container)?;
    let metadata = inspect_runtime_container(&runtime_container)?;
    let sha256 = sha256_file(&runtime_container)?;
    let version = derive_version(&sha256);
    let asset_name = format!("python-runtime-{version}.apk");
    let asset = dist.join(&asset_name);
    fs::rename(&runtime_container, &asset).context("publish Python runtime APK into dist")?;
    clean_stale(dist, "python-runtime-", &asset)?;
    build_python_sdk_artifact(root, dist)?;

    println!("python-runtime: {version}");
    Ok(PackIndexEntry {
        id: PACK_PYTHON_RUNTIME.into(),
        version,
        asset: asset_name,
        sha256,
        external_url: None,
        bytes: None,
        meta: Some(metadata),
    })
}

fn find_or_install_uv_python(version: &str) -> Result<PathBuf> {
    let find = || {
        Command::new("uv")
            .args(["python", "find", version])
            .output()
            .with_context(|| {
                format!("uv is required to provide Python {version} for the runtime build")
            })
    };
    let mut output = find()?;
    if !output.status.success() {
        run_extension_command(
            Command::new("uv").args(["python", "install", version]),
            &format!("install Python {version} for the runtime build"),
        )?;
        output = find()?;
    }
    anyhow::ensure!(
        output.status.success(),
        "uv could not locate Python {version} for the runtime build"
    );
    let executable = PathBuf::from(String::from_utf8(output.stdout)?.trim());
    anyhow::ensure!(
        executable.is_file(),
        "uv returned a missing Python executable: {}",
        executable.display()
    );
    Ok(executable)
}

fn build_python_native_wheels(
    root: &Path,
    catalog: &toml::Value,
    build_python: &Path,
) -> Result<PathBuf> {
    let versions = &catalog["versions"];
    let revision = versions["pythonRuntimeChaquopyRevision"]
        .as_str()
        .context("missing pythonRuntimeChaquopyRevision in version catalog")?;
    let python_version = versions["pythonRuntimePython"]
        .as_str()
        .context("missing pythonRuntimePython in version catalog")?;
    let ndk_version = versions["pythonRuntimeNdk"]
        .as_str()
        .context("missing pythonRuntimeNdk in version catalog")?;
    let target_version = versions["pythonRuntimeChaquopyTarget"]
        .as_str()
        .context("missing pythonRuntimeChaquopyTarget in version catalog")?;
    let patch = root.join("patches/chaquopy/runtime-native-wheels.patch");
    let build_root = root.join("target/python-runtime-native-wheels");
    let artifacts = build_root.join("artifacts");
    let cache_key_path = build_root.join("cache-key");
    let wheels = [
        (
            "chaquopy-freetype",
            "chaquopy_freetype-2.9.1-3-py3-none-android_24_arm64_v8a.whl",
        ),
        (
            "chaquopy-libxml2",
            "chaquopy_libxml2-2.9.8-3-py3-none-android_24_arm64_v8a.whl",
        ),
        (
            "chaquopy-libxslt",
            "chaquopy_libxslt-1.1.32-3-py3-none-android_24_arm64_v8a.whl",
        ),
        (
            "backports-zstd",
            "backports_zstd-1.7.0-0-cp313-cp313-android_24_arm64_v8a.whl",
        ),
        (
            "brotli",
            "brotli-1.2.0-0-cp313-cp313-android_24_arm64_v8a.whl",
        ),
    ];
    let mut cache_hasher = Sha256::new();
    cache_hasher.update(b"wekit-python-native-wheels-v1\0");
    cache_hasher.update(revision.as_bytes());
    cache_hasher.update(python_version.as_bytes());
    cache_hasher.update(ndk_version.as_bytes());
    cache_hasher.update(target_version.as_bytes());
    cache_hasher.update(fs::read(&patch)?);
    let cache_key = hex(&cache_hasher.finalize());
    if wheels
        .iter()
        .all(|(_, filename)| artifacts.join(filename).is_file())
        && fs::read_to_string(&cache_key_path)
            .map(|cached| cached.trim() == cache_key)
            .unwrap_or(false)
    {
        return Ok(artifacts);
    }

    if build_root.exists() {
        fs::remove_dir_all(&build_root)?;
    }
    fs::create_dir_all(&artifacts)?;
    let source = root.join("target/python-runtime-chaquopy/source");
    let pypi = source.join("server/pypi");
    anyhow::ensure!(
        pypi.join("build-wheel.py").is_file(),
        "patched Chaquopy package builder is missing"
    );
    for (package, _) in &wheels {
        let output = pypi.join("dist").join(package);
        if output.exists() {
            fs::remove_dir_all(output)?;
        }
    }

    let target_dir = source
        .join("maven/com/chaquo/python/target")
        .join(target_version);
    fs::create_dir_all(&target_dir)?;
    let target_name = format!("target-{target_version}-arm64-v8a.zip");
    let target_zip = target_dir.join(&target_name);
    run_extension_command(
        Command::new("wget")
            .args(["--quiet", "-O"])
            .arg(&target_zip)
            .arg(format!(
                "https://repo.maven.apache.org/maven2/com/chaquo/python/target/\
                 {target_version}/{target_name}"
            )),
        "download Chaquopy arm64 target for Python native wheels",
    )?;

    let venv = build_root.join("venv");
    run_extension_command(
        Command::new("uv")
            .args(["venv", "--python"])
            .arg(build_python)
            .arg(&venv),
        "create Python native-wheel build environment",
    )?;
    let venv_bin = if cfg!(windows) {
        venv.join("Scripts")
    } else {
        venv.join("bin")
    };
    let venv_python = if cfg!(windows) {
        venv_bin.join("python.exe")
    } else {
        venv_bin.join("python")
    };
    run_extension_command(
        Command::new("uv")
            .args(["pip", "install", "--python"])
            .arg(&venv_python)
            .arg("-r")
            .arg(pypi.join("requirements.txt"))
            .arg("patchelf"),
        "install Python native-wheel build tools",
    )?;

    let mut path_entries = vec![venv_bin];
    if let Some(parent) = build_python.parent() {
        path_entries.push(parent.to_path_buf());
    }
    path_entries.extend(std::env::split_paths(
        &std::env::var_os("PATH").unwrap_or_default(),
    ));
    let build_path = std::env::join_paths(path_entries)?;
    let android_home = crate::find_android_home(root)?;
    for (package, filename) in &wheels {
        run_extension_command(
            Command::new(pypi.join("build-wheel.py"))
                .args(["--python", python_version, "--abi", "arm64-v8a"])
                .arg(package)
                .env("PATH", &build_path)
                .env("ANDROID_HOME", &android_home)
                .env("ANDROID_SDK_ROOT", &android_home)
                .env("ndk_version", ndk_version),
            &format!("build 16 KB-aligned {package} wheel"),
        )?;
        let wheel = pypi.join("dist").join(package).join(filename);
        anyhow::ensure!(
            wheel.is_file(),
            "Python native wheel was not produced: {filename}"
        );
        fs::copy(wheel, artifacts.join(filename))?;
    }
    fs::write(cache_key_path, cache_key)?;
    Ok(artifacts)
}

fn build_patched_chaquopy_bridge(root: &Path, catalog: &toml::Value) -> Result<PathBuf> {
    let versions = &catalog["versions"];
    let revision = versions["pythonRuntimeChaquopyRevision"]
        .as_str()
        .context("missing pythonRuntimeChaquopyRevision in version catalog")?;
    let cython_version = versions["pythonRuntimeCython"]
        .as_str()
        .context("missing pythonRuntimeCython in version catalog")?;
    let python_version = versions["pythonRuntimePython"]
        .as_str()
        .context("missing pythonRuntimePython in version catalog")?;
    let target_version = versions["pythonRuntimeChaquopyTarget"]
        .as_str()
        .context("missing pythonRuntimeChaquopyTarget in version catalog")?;
    let ndk_version = versions["pythonRuntimeNdk"]
        .as_str()
        .context("missing pythonRuntimeNdk in version catalog")?;
    let min_sdk = versions["minSdk"]
        .as_str()
        .context("missing minSdk in version catalog")?;
    let patches = [
        root.join("patches/chaquopy/runtime-classloader.patch"),
        root.join("patches/chaquopy/runtime-native-wheels.patch"),
    ];
    for patch in &patches {
        anyhow::ensure!(
            patch.is_file(),
            "Chaquopy patch is missing: {}",
            patch.display()
        );
    }

    let build_root = root.join("target/python-runtime-chaquopy");
    let source = build_root.join("source");
    let artifact = build_root.join("artifacts/chaquopy.so");
    let cache_key_path = build_root.join("cache-key");
    let mut cache_hasher = Sha256::new();
    cache_hasher.update(b"wekit-chaquopy-bridge-v1\0");
    cache_hasher.update(revision.as_bytes());
    cache_hasher.update(cython_version.as_bytes());
    cache_hasher.update(python_version.as_bytes());
    cache_hasher.update(target_version.as_bytes());
    cache_hasher.update(ndk_version.as_bytes());
    cache_hasher.update(min_sdk.as_bytes());
    for patch in &patches {
        cache_hasher.update(fs::read(patch)?);
    }
    let cache_key = hex(&cache_hasher.finalize());
    if artifact.is_file()
        && source.join("server/pypi/build-wheel.py").is_file()
        && fs::read_to_string(&cache_key_path)
            .map(|cached| cached.trim() == cache_key)
            .unwrap_or(false)
    {
        return Ok(artifact);
    }
    fs::create_dir_all(&build_root)?;

    let upstream = build_root.join("upstream");
    if !upstream.join(".git").exists() {
        run_extension_command(
            Command::new("git")
                .args(["clone", "--no-checkout"])
                .arg("https://github.com/chaquo/chaquopy.git")
                .arg(&upstream),
            "clone pinned Chaquopy source",
        )?;
    }
    let has_revision = Command::new("git")
        .args(["-C"])
        .arg(&upstream)
        .args(["cat-file", "-e", &format!("{revision}^{{commit}}")])
        .status()
        .context("failed to inspect the Chaquopy source cache")?
        .success();
    if !has_revision {
        // GitHub's git backend intermittently answers "not our ref" to
        // shallow fetches by SHA; retry a few times, then fall back to a
        // full ref fetch, which carries every commit reachable from the
        // branches (the pinned revision lives on master).
        let mut fetched = false;
        for _ in 0..3 {
            if Command::new("git")
                .args(["-C"])
                .arg(&upstream)
                .args(["fetch", "--depth=1", "origin", revision])
                .status()
                .context("failed to fetch the pinned Chaquopy revision")?
                .success()
            {
                fetched = true;
                break;
            }
        }
        if !fetched {
            run_extension_command(
                Command::new("git").args(["-C"]).arg(&upstream).args([
                    "fetch",
                    "origin",
                    "+refs/heads/*:refs/remotes/origin/*",
                ]),
                "fetch full Chaquopy history",
            )?;
        }
    }

    if source.exists() {
        fs::remove_dir_all(&source)?;
    }
    run_extension_command(
        Command::new("git")
            .args(["clone", "--shared", "--no-checkout"])
            .arg(&upstream)
            .arg(&source),
        "create Chaquopy build checkout",
    )?;
    run_extension_command(
        Command::new("git")
            .args(["-C"])
            .arg(&source)
            .args(["checkout", "--detach", revision]),
        "checkout pinned Chaquopy revision",
    )?;
    for patch in &patches {
        run_extension_command(
            Command::new("git")
                .args(["-C"])
                .arg(&source)
                .args(["apply", "--check"])
                .arg(patch),
            "validate Chaquopy patch",
        )?;
        run_extension_command(
            Command::new("git")
                .args(["-C"])
                .arg(&source)
                .arg("apply")
                .arg(patch),
            "apply Chaquopy patch",
        )?;
    }

    let venv = build_root.join("venv");
    let venv_python = if cfg!(windows) {
        venv.join("Scripts/python.exe")
    } else {
        venv.join("bin/python")
    };
    let venv_cython = if cfg!(windows) {
        venv.join("Scripts/cython.exe")
    } else {
        venv.join("bin/cython")
    };
    if !venv_cython.is_file() {
        run_extension_command(
            Command::new("python3").args(["-m", "venv"]).arg(&venv),
            "create Chaquopy Cython environment",
        )?;
        run_extension_command(
            Command::new(&venv_python).args([
                "-m",
                "pip",
                "install",
                "--disable-pip-version-check",
                "--quiet",
                &format!("Cython=={cython_version}"),
            ]),
            "install pinned Cython",
        )?;
    }

    let generated = build_root.join("generated");
    if generated.exists() {
        fs::remove_dir_all(&generated)?;
    }
    fs::create_dir_all(&generated)?;
    let java_source = source.join("product/runtime/src/main/python/java");
    run_extension_command(
        Command::new(&venv_cython)
            .args(["-Wextra", "-Werror", "chaquopy.pyx", "-I"])
            .arg(&generated)
            .arg("-o")
            .arg(generated.join("chaquopy.c"))
            .current_dir(&java_source),
        "compile patched Chaquopy bridge source",
    )?;

    let target = root.join("extension-packs/python-runtime/runtime/build/chaquopyTarget");
    let include_python = target
        .join("include")
        .join(format!("python{python_version}"));
    let target_libs = target.join("jniLibs/arm64-v8a");
    anyhow::ensure!(
        include_python.join("Python.h").is_file()
            && target_libs
                .join(format!("libpython{python_version}.so"))
                .is_file(),
        "extracted Chaquopy target is incomplete",
    );
    fs::create_dir_all(artifact.parent().unwrap())?;
    let compiler = crate::pinned_ndk_dir(root, Some(ndk_version))?
        .join("toolchains/llvm/prebuilt")
        .join(crate::host_prebuilt_tag()?)
        .join("bin")
        .join(format!("aarch64-linux-android{min_sdk}-clang"));
    run_extension_command(
        Command::new(compiler)
            .args(["-shared", "-fPIC", "-O2", "-DNDEBUG"])
            .arg(format!(
                "-I{}",
                source.join("product/runtime/src/main/c").display()
            ))
            .arg(format!("-I{}", include_python.display()))
            .arg(generated.join("chaquopy.c"))
            .arg(format!("-L{}", target_libs.display()))
            .arg(format!("-lpython{python_version}"))
            .args([
                "-landroid",
                "-ldl",
                "-lm",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-soname,chaquopy.so",
                "-Wl,-s",
                "-o",
            ])
            .arg(&artifact),
        "link patched Chaquopy bridge",
    )?;
    fs::write(cache_key_path, &cache_key)?;
    Ok(artifact)
}

fn build_runtime_multidex_probe(root: &Path, catalog: &toml::Value) -> Result<PathBuf> {
    let min_sdk = catalog["versions"]["minSdk"]
        .as_str()
        .context("missing minSdk in version catalog")?;
    let build_root = root.join("target/python-runtime-multidex");
    if build_root.exists() {
        fs::remove_dir_all(&build_root)?;
    }
    let classes = build_root.join("classes");
    let dex = build_root.join("dex");
    fs::create_dir_all(&classes)?;
    fs::create_dir_all(&dex)?;
    run_extension_command(
        Command::new("javac")
            .args(["--release", "8", "-Xlint:-options", "-d"])
            .arg(&classes)
            .arg(root.join("extension-packs/python-runtime/multidex/RuntimeMultidexProbe.java")),
        "compile runtime multi-DEX probe",
    )?;

    let build_tools = PathBuf::from(crate::find_android_home(root)?).join("build-tools");
    let d8_name = if cfg!(windows) { "d8.bat" } else { "d8" };
    let mut d8_candidates = fs::read_dir(&build_tools)?
        .filter_map(|entry| entry.ok().map(|entry| entry.path().join(d8_name)))
        .filter(|path| path.is_file())
        .collect::<Vec<_>>();
    d8_candidates.sort();
    let d8 = d8_candidates
        .into_iter()
        .rev()
        .find(|path| !path.to_string_lossy().contains("-rc"))
        .context("Android d8 build tool is not installed")?;
    run_extension_command(
        Command::new(d8)
            .args(["--min-api", min_sdk, "--output"])
            .arg(&dex)
            .arg(classes.join("dev/ujhhgtg/wekit/python/runtime/RuntimeMultidexProbe.class")),
        "build runtime classes2.dex probe",
    )?;
    let output = dex.join("classes.dex");
    anyhow::ensure!(
        output.is_file(),
        "runtime classes2.dex probe was not produced"
    );
    Ok(output)
}

fn run_extension_command(command: &mut Command, action: &str) -> Result<()> {
    let status = command
        .status()
        .with_context(|| format!("failed to start {action}"))?;
    anyhow::ensure!(status.success(), "{action} failed with {status}");
    Ok(())
}

fn build_runtime_container(input: &Path, second_dex: &Path, output: &Path) -> Result<()> {
    anyhow::ensure!(
        second_dex.is_file(),
        "Python runtime classes2.dex probe is missing"
    );
    let mut archive = zip::ZipArchive::new(File::open(input)?)?;
    anyhow::ensure!(
        archive.by_name("classes2.dex").is_err(),
        "standalone runtime already has classes2.dex"
    );
    let mut writer = ZipWriter::new(File::create(output)?);
    for index in 0..archive.len() {
        writer.raw_copy_file(archive.by_index(index)?)?;
    }
    writer.start_file(
        "classes2.dex",
        SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
    )?;
    std::io::copy(&mut File::open(second_dex)?, &mut writer)?;
    writer.finish()?;
    Ok(())
}

fn inspect_runtime_container(path: &Path) -> Result<String> {
    let mut archive = zip::ZipArchive::new(File::open(path)?)?;
    let mut names = BTreeSet::new();
    let mut dex_numbers = Vec::new();
    for index in 0..archive.len() {
        let name = archive.by_index(index)?.name().to_string();
        anyhow::ensure!(
            names.insert(name.clone()),
            "duplicate runtime APK entry: {name}"
        );
        anyhow::ensure!(
            !name.starts_with('/')
                && !name.contains('\\')
                && !name.split('/').any(|part| part == ".."),
            "unsafe runtime APK entry: {name}",
        );
        if name == "classes.dex" {
            dex_numbers.push(1);
        } else if let Some(number) = name
            .strip_prefix("classes")
            .and_then(|name| name.strip_suffix(".dex"))
        {
            dex_numbers.push(
                number
                    .parse::<usize>()
                    .context("invalid runtime DEX name")?,
            );
        }
        anyhow::ensure!(
            !name.starts_with("lib/") || name.starts_with("lib/arm64-v8a/"),
            "runtime APK contains an unsupported ABI: {name}",
        );
    }
    dex_numbers.sort_unstable();
    anyhow::ensure!(
        dex_numbers == (1..=dex_numbers.len()).collect::<Vec<_>>(),
        "runtime DEX sequence has a gap"
    );
    anyhow::ensure!(
        dex_numbers.len() >= 2,
        "runtime APK must exercise multi-DEX loading"
    );
    anyhow::ensure!(
        names.contains("assets/chaquopy/build.json"),
        "runtime APK has no Chaquopy build.json"
    );
    anyhow::ensure!(
        names
            .iter()
            .any(|name| name.starts_with("lib/arm64-v8a/") && name.ends_with(".so")),
        "runtime APK has no arm64 native libraries"
    );

    let mut entry = archive
        .by_name("assets/runtime-manifest.json")
        .context("Python runtime APK has no runtime-manifest.json")?;
    let mut text = String::new();
    entry.read_to_string(&mut text)?;
    Ok(serde_json::to_string(&serde_json::from_str::<
        serde_json::Value,
    >(&text)?)?)
}

fn build_python_sdk_artifact(root: &Path, dist: &Path) -> Result<()> {
    let output = dist.join("wekit-python-sdk.zip");
    let mut zip = ZipWriter::new(File::create(&output)?);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (prefix, directory) in [
        ("stubs", root.join("extension-packs/python-runtime/stubs")),
        (
            "stubs",
            root.join("extension-packs/python-runtime/runtime/build/generated/dexkitBindings/stubs"),
        ),
        ("examples", root.join("extension-packs/python-runtime/examples")),
    ] {
        for entry in walkdir::WalkDir::new(&directory) {
            let entry = entry?;
            if !entry.file_type().is_file() {
                continue;
            }
            let relative = entry.path().strip_prefix(&directory)?;
            let name = format!("{prefix}/{}", relative.to_string_lossy().replace('\\', "/"));
            zip.start_file(name, options)?;
            let mut input = File::open(entry.path())?;
            std::io::copy(&mut input, &mut zip)?;
        }
    }
    zip.finish()?;
    Ok(())
}

fn build_cloudflared_zip(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    let abis = ["arm64-v8a"];
    crate::task_build_cloudflared(&abis.iter().map(|s| s.to_string()).collect::<Vec<_>>())?;

    let mut inner: BTreeMap<String, String> = BTreeMap::new();
    let mut so_paths: Vec<(String, PathBuf)> = Vec::new();
    for abi in abis {
        let so = root
            .join("target/cloudflared")
            .join(abi)
            .join(CLOUDFLARED_LIB);
        inner.insert(format!("{abi}/{CLOUDFLARED_LIB}"), sha256_file(&so)?);
        so_paths.push((abi.to_string(), so));
    }
    let inner_manifest = serde_json::to_string_pretty(&serde_json::json!({ "files": inner }))?;

    let zip_tmp = dist.join("cloudflared-unversioned.zip");
    {
        let file = File::create(&zip_tmp)?;
        let mut zip = ZipWriter::new(file);
        let options =
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (abi, so) in &so_paths {
            zip.start_file(format!("{abi}/{CLOUDFLARED_LIB}"), options)?;
            let mut bytes = Vec::new();
            File::open(so)?.read_to_end(&mut bytes)?;
            zip.write_all(&bytes)?;
        }
        zip.start_file("manifest.json", options)?;
        zip.write_all(inner_manifest.as_bytes())?;
        zip.finish()?;
    }

    let mut files = BTreeMap::new();
    files.insert("cloudflared.zip".to_string(), sha256_file(&zip_tmp)?);
    let version = derive_version(&content_hash(&files));
    let entry = index_entry(PACK_CLOUDFLARED, &version, &files);

    let asset = dist.join(&entry.asset);
    fs::rename(&zip_tmp, &asset)?;
    clean_stale(dist, "cloudflared-", &asset)?;

    println!("cloudflared: {version}");
    Ok(entry)
}

// ── llama-native pack ──────────────────────────────────────────────────────────

/// Build both android variants of the llama native server and zip them.
///
/// Variant 1 (`libwekit_llama.so`) is the crate's default feature set
/// (CPU + Vulkan); variant 2 (`libwekit_llama_opencl.so`) adds `opencl` on top.
/// Cross-compiling the GPU backends needs Khronos headers the NDK sysroot
/// cannot provide, so the vendored submodules under `third_party/` are staged
/// first (see `ensure_vulkan_include` / `stage_spirv_headers`). Both cargo runs
/// must execute with cwd inside the wekit-llama crate for its generated
/// `.cargo/config.toml` (NDK linker + CC) to apply.
fn build_llama_zip(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    crate::task_configure()?;

    let pack_dir = root.join("target/llama-pack");
    fs::create_dir_all(&pack_dir)?;
    let llama_dir = root.join(LLAMA_CRATE);

    let vk_include = ensure_vulkan_include(root)?;
    let spirv_prefix = stage_spirv_headers(root, &pack_dir)?;
    let glslc = resolve_glslc()?;
    let ndk = crate::pinned_ndk_dir(root, None)?;
    let base_env: Vec<(&str, String)> = vec![
        ("ANDROID_NDK", ndk.display().to_string()),
        ("VULKAN_INCLUDE_DIR", vk_include.display().to_string()),
        (
            "SPIRV_HEADERS_DIR",
            spirv_prefix
                .join("share/cmake/SPIRV-Headers")
                .display()
                .to_string(),
        ),
        (
            "SPIRV_HEADERS_INCLUDE_DIR",
            spirv_prefix.join("include").display().to_string(),
        ),
        ("VULKAN_GLSLC", glslc),
    ];

    // Variant 1: default features (CPU + Vulkan).
    println!("llama-native: variant 1/2 CPU + Vulkan ({LLAMA_TARGET})");
    run_cargo(
        &[
            "build",
            "--release",
            "--target",
            LLAMA_TARGET,
            "-p",
            "wekit-llama",
            "--lib",
        ],
        &llama_dir,
        &base_env,
    )?;
    let so = root
        .join("target")
        .join(LLAMA_TARGET)
        .join("release")
        .join(LLAMA_LIB);
    let staged_vulkan = pack_dir.join(LLAMA_LIB);
    fs::copy(&so, &staged_vulkan)
        .with_context(|| format!("copy {} → {}", so.display(), staged_vulkan.display()))?;

    // Variant 2: OpenCL on top of the default (Vulkan) features. Both variants
    // write the same cargo output path, so variant 1 was staged aside above.
    println!("llama-native: variant 2/2 CPU + Vulkan + OpenCL ({LLAMA_TARGET})");
    let stub = pack_dir.join("libOpenCL.so");
    make_opencl_stub(root, &stub)?;
    let mut opencl_env = base_env.clone();
    opencl_env.push((
        "OPENCL_INCLUDE_DIR",
        root.join("third_party/OpenCL-Headers")
            .display()
            .to_string(),
    ));
    opencl_env.push(("OPENCL_LIBRARY", stub.display().to_string()));
    run_cargo(
        &[
            "build",
            "--release",
            "--target",
            LLAMA_TARGET,
            "-p",
            "wekit-llama",
            "--lib",
            "--features",
            "opencl",
        ],
        &llama_dir,
        &opencl_env,
    )?;
    let so = root
        .join("target")
        .join(LLAMA_TARGET)
        .join("release")
        .join(LLAMA_LIB);
    let staged_opencl = pack_dir.join(LLAMA_LIB_OPENCL);
    fs::copy(&so, &staged_opencl)
        .with_context(|| format!("copy {} → {}", so.display(), staged_opencl.display()))?;

    let inputs = [
        (format!("{LLAMA_ABI}/{LLAMA_LIB}"), staged_vulkan),
        (format!("{LLAMA_ABI}/{LLAMA_LIB_OPENCL}"), staged_opencl),
    ];
    let inner = inputs
        .iter()
        .map(|(name, path)| Ok((name.clone(), sha256_file(path)?)))
        .collect::<Result<BTreeMap<_, _>>>()?;
    let inner_manifest = serde_json::to_string_pretty(&serde_json::json!({ "files": inner }))?;

    let zip_tmp = dist.join("llama-native-unversioned.zip");
    {
        let file = File::create(&zip_tmp)?;
        let mut zip = ZipWriter::new(file);
        let options =
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (name, path) in &inputs {
            zip.start_file(name, options)?;
            let mut bytes = Vec::new();
            File::open(path)?.read_to_end(&mut bytes)?;
            zip.write_all(&bytes)?;
        }
        zip.start_file("manifest.json", options)?;
        zip.write_all(inner_manifest.as_bytes())?;
        zip.finish()?;
    }

    let mut files = BTreeMap::new();
    files.insert("llama-native.zip".to_string(), sha256_file(&zip_tmp)?);
    let version = derive_version(&content_hash(&files));
    let entry = index_entry(PACK_LLAMA, &version, &files);

    let asset = dist.join(&entry.asset);
    fs::rename(&zip_tmp, &asset)?;
    clean_stale(dist, "llama-native-", &asset)?;

    println!("llama-native: {version}");
    Ok(entry)
}

/// The complete Vulkan header set — the C headers plus the `vulkan.hpp` C++
/// bindings the NDK lacks — lives in Vulkan-Hpp's nested `Vulkan-Headers`
/// submodule at the gitlink pinned by the outer submodule; no Vulkan-Hpp tag
/// bundles the C headers in its own tree. Checkouts made with
/// `submodules: recursive` already have it; fetch just this nested path
/// otherwise.
fn ensure_vulkan_include(root: &Path) -> Result<PathBuf> {
    let hpp = root.join("third_party/Vulkan-Hpp");
    anyhow::ensure!(
        hpp.join("vulkan/vulkan.hpp").is_file(),
        "Vulkan-Hpp source is not initialized at {}; run `git submodule update --init --recursive`",
        hpp.display()
    );
    let include = hpp.join("Vulkan-Headers/include");
    if !include.join("vulkan/vulkan.hpp").is_file() {
        crate::run_cmd(
            "git",
            &[
                "-C",
                hpp.to_str().unwrap(),
                "submodule",
                "update",
                "--init",
                "Vulkan-Headers",
            ],
            root,
        )?;
    }
    anyhow::ensure!(
        include.join("vulkan/vulkan.hpp").is_file()
            && include.join("vulkan/vulkan_core.h").is_file(),
        "incomplete Vulkan headers at {}; run `git submodule update --init --recursive`",
        include.display()
    );
    Ok(include)
}

/// The SPIRV-Headers repository only produces its CMake `Config` package
/// through `install()`, so install the header-only project into a staging
/// prefix under `target/llama-pack` and point `SPIRV-Headers_DIR` at it —
/// ggml-vulkan does `find_package(SPIRV-Headers CONFIG REQUIRED)` and the NDK
/// toolchain cannot discover host packages.
fn stage_spirv_headers(root: &Path, pack_dir: &Path) -> Result<PathBuf> {
    let prefix = pack_dir.join("spirv-headers");
    let config = prefix.join("share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake");
    if config.is_file() {
        return Ok(prefix);
    }
    let source = root.join("third_party/SPIRV-Headers");
    anyhow::ensure!(
        source.join("CMakeLists.txt").is_file(),
        "SPIRV-Headers source is not initialized at {}; run `git submodule update --init --recursive`",
        source.display()
    );
    let build = pack_dir.join("spirv-headers-build");
    let _ = fs::remove_dir_all(&build);
    crate::run_cmd_owned(
        "cmake",
        &[
            "-S".into(),
            source.display().to_string(),
            "-B".into(),
            build.display().to_string(),
            "-DSPIRV_HEADERS_ENABLE_TESTS=OFF".into(),
            format!("-DCMAKE_INSTALL_PREFIX={}", prefix.display()),
        ],
        root,
    )?;
    crate::run_cmd_owned(
        "cmake",
        &["--install".into(), build.display().to_string()],
        root,
    )?;
    anyhow::ensure!(
        config.is_file(),
        "SPIRV-Headers install did not produce {}",
        config.display()
    );
    Ok(prefix)
}

/// `glslc` host shader compiler required by the Vulkan backend's
/// vulkan-shaders-gen build tool (same PATH lookup the sys build script does).
fn resolve_glslc() -> Result<String> {
    std::env::var_os("PATH")
        .and_then(|paths| {
            std::env::split_paths(&paths)
                .map(|p| p.join("glslc"))
                .find(|p| p.is_file())
        })
        .map(|p| p.to_string_lossy().into_owned())
        .with_context(
            || "`glslc` (shaderc) not found on PATH; required to cross-compile the Vulkan backend",
        )
}

/// Compile a definition-free `libOpenCL.so` with the NDK clang. cmake's
/// FindOpenCL needs `OPENCL_LIBRARY` to name an existing file even though the
/// final link resolves against the stub the crate's own build.rs generates in
/// its OUT_DIR; the device's vendor libOpenCL.so provides the real symbols.
fn make_opencl_stub(root: &Path, stub: &Path) -> Result<()> {
    let bin = crate::find_ndk_bin_dir(root)?;
    let cc = format!("{bin}/aarch64-linux-android{}-clang", crate::MIN_SDK);
    let status = Command::new(&cc)
        .args(["-shared", "-fPIC", "-o"])
        .arg(stub)
        .arg("/dev/null")
        .status()
        .with_context(|| format!("failed to spawn NDK clang ({cc}) for the OpenCL stub"))?;
    anyhow::ensure!(status.success(), "OpenCL stub build failed with {status}");
    Ok(())
}

/// cargo runner for the llama pack: prefers the `cargo` that invoked xtask and
/// must run with cwd inside the wekit-llama crate — cargo only reads
/// `.cargo/config.toml` from cwd upward, so a workspace-root invocation would
/// silently lose the NDK linker/CC configuration.
fn run_cargo(args: &[&str], cwd: &Path, envs: &[(&str, String)]) -> Result<()> {
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let mut command = Command::new(cargo);
    command.args(args).current_dir(cwd);
    for (key, value) in envs {
        command.env(key, value);
    }
    let status = command
        .status()
        .with_context(|| format!("failed to spawn `cargo {}`", args.join(" ")))?;
    anyhow::ensure!(
        status.success(),
        "`cargo {}` exited with {status}",
        args.join(" ")
    );
    Ok(())
}

// ── static model-pack entry ────────────────────────────────────────────────────

const QWEN_MODEL_META: &str = r#"{
  "schemaVersion": 1,
  "models": [{
    "id": "qwen3.8-4b-distill-q4km",
    "displayName": "Qwen3.8-4B Distill",
    "file": "model.gguf",
    "quant": "Q4_K_M",
    "defaultContextWindow": 32768,
    "maxContextWindow": 262144,
    "maxTokens": 8192,
    "defaultReasoningEffort": "medium",
    "supportsThinking": true,
    "sampling": { "temperature": 0.6, "topP": 0.95, "topK": 20 }
  }]
}"#;

/// Index entry for the Qwen3.8-4B distill GGUF hosted on Hugging Face. The
/// installer downloads it straight from the pinned revision URL and verifies
/// the pinned SHA-256/size; the pack build produces no asset for it.
fn qwen_model_entry() -> PackIndexEntry {
    PackIndexEntry {
        id: PACK_QWEN_MODEL.into(),
        version: "1".into(),
        asset: "external".into(),
        sha256: "dec96e8cf2e11b613bb46513dec485377f9ca5a351e71712ee0e244f287c6790".into(),
        external_url: Some("https://huggingface.co/empero-ai/Qwen3.8-4B-Distill-GGUF/resolve/391fc7d103e3942a408def3e4f51c2f85d464417/Qwen3.8-4B-Q4_K_M.gguf".into()),
        bytes: Some(2_783_446_304),
        meta: Some(QWEN_MODEL_META.into()),
    }
}

/// Remove older versioned assets of the same pack so dist always holds exactly one.
fn clean_stale(dist: &Path, prefix: &str, keep: &Path) -> Result<()> {
    for entry in fs::read_dir(dist)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file()
            && path
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with(prefix))
            && path != keep
        {
            fs::remove_file(&path).with_context(|| format!("remove stale {}", path.display()))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn files(entries: &[(&str, &str)]) -> BTreeMap<String, String> {
        entries
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn content_hash_is_order_independent_and_stable() {
        let a = files(&[("script-deps.dex", "aa"), ("other", "bb")]);
        let mut b = a.clone();
        // BTreeMap keeps entries sorted regardless of insertion order.
        b.insert("other".into(), "bb".into());
        b.insert("script-deps.dex".into(), "aa".into());
        assert_eq!(content_hash(&a), content_hash(&b));
        assert_eq!(content_hash(&a).len(), 64);
    }

    #[test]
    fn version_is_first_twelve_hex_chars_of_content_hash() {
        let hash = content_hash(&files(&[("script-deps.dex", "aa")]));
        assert_eq!(derive_version(&hash), hash[..12]);
    }

    #[test]
    fn index_entry_inserts_version_before_extension() {
        let entry = index_entry(
            "script-deps",
            "0123456789ab",
            &files(&[("script-deps.dex", "00")]),
        );
        assert_eq!(entry.asset, "script-deps-0123456789ab.dex");
        assert_eq!(entry.sha256, "00");

        let entry = index_entry(
            "cloudflared",
            "0123456789ab",
            &files(&[("cloudflared.zip", "11")]),
        );
        assert_eq!(entry.asset, "cloudflared-0123456789ab.zip");
        assert_eq!(entry.sha256, "11");
    }

    #[test]
    fn index_json_roundtrip() {
        let index = PackIndex {
            packs: vec![PackIndexEntry {
                id: "script-deps".into(),
                version: "0123456789ab".into(),
                asset: "script-deps-0123456789ab.dex".into(),
                sha256: "00".into(),
                external_url: None,
                bytes: None,
                meta: None,
            }],
        };
        let json = serde_json::to_string_pretty(&index).unwrap();
        let back: PackIndex = serde_json::from_str(&json).unwrap();
        assert_eq!(back.packs[0].version, "0123456789ab");
        assert_eq!(back.packs[0].asset, "script-deps-0123456789ab.dex");
    }

    #[test]
    fn external_entry_serializes_optional_fields_and_plain_entries_omit_them() {
        let entry = qwen_model_entry();
        assert_eq!(entry.id, "qwen3.8-4b-distill");
        assert_eq!(entry.bytes, Some(2_783_446_304));
        let json = serde_json::to_value(&entry).unwrap();
        assert!(
            json["externalUrl"]
                .as_str()
                .unwrap()
                .contains("/391fc7d103e3942a408def3e4f51c2f85d464417/")
        );
        assert!(json.get("external_url").is_none());
        assert!(
            json["meta"]
                .as_str()
                .unwrap()
                .contains("qwen3.8-4b-distill-q4km")
        );
        let back: PackIndexEntry = serde_json::from_value(json).unwrap();
        assert_eq!(back, entry);

        // Built packs must keep the exact pre-existing wire shape.
        let plain = serde_json::to_value(index_entry(
            "llama-native",
            "0123456789ab",
            &files(&[("llama-native.zip", "00")]),
        ))
        .unwrap();
        assert!(plain.get("externalUrl").is_none());
        assert!(plain.get("bytes").is_none());
        assert!(plain.get("meta").is_none());
    }

    #[test]
    fn arch_source_descriptor_pins_immutable_identities() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let source = read_arch_sources(root).unwrap();
        assert_eq!(source.rootfs.release, "2026.08");
        assert_eq!(source.rootfs.md5, "23eec86365b24f7913c403e8f4e8719b");
        assert_eq!(
            source.rootfs.sha256,
            "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319"
        );
        assert_eq!(
            source.rootfs.signing_fingerprint,
            "68B3537F39A313B3E574D06777193F152BDBE6A6"
        );
        assert_eq!(source.proot.commit, crate::PROOT_COMMIT);
        assert_eq!(source.bridge.target, "aarch64-linux-android");
    }

    #[test]
    fn arch_pack_uses_shared_proot_build_outputs() {
        let root = Path::new("/workspace");
        let (proot, loader) = arch_proot_input_paths(root);
        assert_eq!(proot, root.join("target/proot-static/artifacts/proot"));
        assert_eq!(loader, root.join("target/proot-static/artifacts/loader"));
    }

    #[test]
    fn arch_source_descriptor_requires_valid_sha256() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let path = root.join("extension-packs/archlinux-arm64-sources.json");
        let mut json: serde_json::Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
        json["rootfs"]["sha256"] = serde_json::Value::String("not-a-sha256".into());
        let error = parse_arch_sources(&serde_json::to_vec(&json).unwrap()).unwrap_err();
        assert!(error.to_string().contains("invalid rootfs SHA-256"));

        json["rootfs"].as_object_mut().unwrap().remove("sha256");
        assert!(parse_arch_sources(&serde_json::to_vec(&json).unwrap()).is_err());
    }

    #[test]
    fn arch_rootfs_verification_rejects_sha256_mismatch() {
        let path =
            std::env::temp_dir().join(format!("wekit-rootfs-checksum-test-{}", std::process::id()));
        fs::write(&path, b"rootfs").unwrap();
        let source = ArchRootfsSource {
            release: "2026.08".into(),
            url: "https://example.invalid/rootfs".into(),
            md5: "307cfa551ed600e2db40b7885ce3ceda".into(),
            sha256: "3c47ef972d531d524daa15fa33dd885dd23de6221bbd10a29eb42ecfcf2ef422".into(),
            max_extracted_bytes: 1024 * 1024 * 1024,
            signature_url: "https://example.invalid/rootfs.sig".into(),
            signing_fingerprint: "68B3537F39A313B3E574D06777193F152BDBE6A6".into(),
        };
        verify_arch_rootfs(&path, &source).unwrap();

        let mismatched = ArchRootfsSource {
            sha256: "0".repeat(64),
            ..source
        };
        let error = verify_arch_rootfs(&path, &mismatched).unwrap_err();
        assert!(error.to_string().contains("SHA-256 mismatch"));
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn arch_pack_index_name_is_content_addressed() {
        let files = files(&[("archlinux-arm64.zip", "abcd")]);
        let version = derive_version(&content_hash(&files));
        let entry = index_entry(PACK_ARCHLINUX, &version, &files);
        assert_eq!(entry.asset, format!("archlinux-arm64-{version}.zip"));
    }

    #[test]
    fn arch_pack_contains_rootfs_launcher_loader_bridge_and_manifest() {
        let base =
            std::env::temp_dir().join(format!("wekit-arch-pack-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        fs::create_dir_all(&base).unwrap();
        let names = [
            "ArchLinuxARM-aarch64-rootfs.tar.gz",
            "proot",
            "proot-loader",
            "invoke_tool",
        ];
        let inputs = names
            .iter()
            .map(|name| {
                let path = base.join(name);
                fs::write(&path, name.as_bytes()).unwrap();
                (*name, path)
            })
            .collect::<Vec<_>>();
        let output = base.join("pack.zip");
        write_arch_zip(&output, &inputs, r#"{"files":{}}"#).unwrap();
        let mut archive = zip::ZipArchive::new(File::open(output).unwrap()).unwrap();
        for name in names {
            assert!(archive.by_name(name).is_ok(), "missing {name}");
        }
        assert!(archive.by_name("manifest.json").is_ok());
        fs::remove_dir_all(base).unwrap();
    }
}
