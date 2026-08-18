//! Extension-pack packaging and lock management.
//!
//! Spec: ~/coding/wekit_dev/superpowers/docs/superpowers/specs/2026-08-18-extension-packs-design.md
//!
//! Version format: `YYYYMMDD-<12 hex chars>` where the hash is SHA-256 over the
//! lock's sorted `name:sha256\n` file lines — content-addressed, no manual
//! version bookkeeping.

use anyhow::{bail, Context, Result};
use clap::{Args, Subcommand};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use time::OffsetDateTime;
use zip::write::SimpleFileOptions;
use zip::ZipWriter;

const PACK_SCRIPT_DEPS: &str = "script-deps";
const PACK_CLOUDFLARED: &str = "cloudflared";
const DIST_DIR: &str = "dist/extensions";
const LOCK_FILE: &str = "extensions.lock";
const CLOUDFLARED_LIB: &str = "libwekit_cloudflared.so";

#[derive(Args)]
pub struct ExtensionsArgs {
    #[command(subcommand)]
    pub command: ExtensionsCommand,

    /// Only process the given pack id (script-deps | cloudflared). Not allowed with `lock`.
    #[arg(long, global = true)]
    pub only: Option<String>,
}

#[derive(Subcommand)]
pub enum ExtensionsCommand {
    /// Build pack assets into dist/extensions (versioned names).
    Pack,
    /// Build all packs and write (or print) extensions.lock.
    Lock {
        /// Write extensions.lock at the repo root; without it the lock is printed.
        #[arg(long)]
        write: bool,
    },
    /// Rebuild assets and fail if they do not match the committed extensions.lock.
    Verify,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtensionsLock {
    pub packs: Vec<PackLock>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackLock {
    pub id: String,
    pub version: String,
    /// Canonical (version-less) file name -> SHA-256 hex.
    pub files: BTreeMap<String, String>,
}

impl PackLock {
    /// The Release asset file name: canonical stem + version + extension.
    pub fn asset_name(&self) -> String {
        let (name, _) = self.files.iter().next()
            .unwrap_or_else(|| panic!("pack '{}' has no files", self.id));
        let stem = name.split('.').next().unwrap();
        let ext = name.rsplit('.').next().filter(|e| *e != name);
        match ext {
            Some(e) => format!("{stem}-{}.{e}", self.version),
            None => format!("{stem}-{}", self.version),
        }
    }
}

/// SHA-256 over the sorted `name:sha256\n` lines — the pack's content identity.
pub fn content_hash(files: &BTreeMap<String, String>) -> String {
    let mut hasher = Sha256::new();
    for (name, sha) in files {
        hasher.update(format!("{name}:{sha}\n").as_bytes());
    }
    hex(&hasher.finalize())
}

/// `YYYYMMDD-<12 hex>` from a UTC date and the pack content hash.
pub fn derive_version(date: &str, content_hash: &str) -> String {
    format!("{date}-{}", &content_hash[..12])
}

fn date_part(version: &str) -> &str {
    version.split('-').next().unwrap_or(version)
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
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

/// Pure comparison used by `verify` (unit-testable without touching the fs).
///
/// Compares content hashes only: a same-content rebuild on a later date carries
/// a different date-bearing version, which is not drift. The lock itself must
/// be internally consistent (version matches its own file hashes).
pub fn compare_pack(expected: &PackLock, actual: &PackLock) -> Result<()> {
    if expected.id != actual.id {
        bail!("pack id mismatch: lock '{}' vs rebuilt '{}'", expected.id, actual.id);
    }
    let expected_version = derive_version(date_part(&expected.version), &content_hash(&expected.files));
    if expected.version != expected_version {
        bail!(
            "extensions.lock is internally inconsistent for pack '{}' (hand-edited version or hash)\n  lock:    {}\n  derived: {}\n  regenerate with `cargo xtask extensions lock --write`",
            expected.id, expected.version, expected_version
        );
    }
    if expected.files != actual.files {
        bail!(
            "pack '{}' does not match extensions.lock\n  lock:    {} {:#?}\n  rebuilt: {} {:#?}",
            actual.id, expected.version, expected.files, actual.version, actual.files
        );
    }
    Ok(())
}

/// Pure alignment used after a rebuild (unit-testable without touching the fs).
///
/// If the lock has an entry with the same id whose `files` map equals the
/// rebuilt pack's (identical content), adopt the lock's version string so the
/// asset name stays stable across rebuild dates — otherwise an overnight
/// rerun would publish a new-date asset the module never requests. Returns
/// true when aligned; new or changed content is left untouched (false).
pub fn align_pack_to_lock(lock: &ExtensionsLock, pack: &mut PackLock) -> bool {
    if let Some(entry) = lock.packs.iter().find(|p| p.id == pack.id) {
        if entry.files == pack.files {
            pack.version = entry.version.clone();
            return true;
        }
    }
    false
}

pub fn run(root: &Path, args: &ExtensionsArgs) -> Result<()> {
    let selected = |id: &str| args.only.as_deref().map(|only| only == id).unwrap_or(true);

    if let ExtensionsCommand::Lock { .. } = args.command {
        if args.only.is_some() {
            bail!("`lock` always covers every pack; drop --only");
        }
    }

    let dist = root.join(DIST_DIR);
    fs::create_dir_all(&dist)?;

    let mut packs: Vec<PackLock> = Vec::new();
    if selected(PACK_SCRIPT_DEPS) {
        packs.push(build_script_deps(root, &dist)?);
    }
    if selected(PACK_CLOUDFLARED) {
        packs.push(build_cloudflared_zip(root, &dist)?);
    }
    packs.sort_by(|a, b| a.id.cmp(&b.id));

    // Align rebuilt packs with the committed lock: identical content keeps the
    // lock's version (and thus its asset name) so CI publishes exactly the
    // filename the module pins even when the rebuild runs on a later date.
    // Also makes `lock --write` stable across rebuilds of unchanged content.
    if root.join(LOCK_FILE).is_file() {
        let lock_text = fs::read_to_string(root.join(LOCK_FILE)).context("read extensions.lock")?;
        let lock: ExtensionsLock = serde_json::from_str(&lock_text).context("parse extensions.lock")?;
        for pack in &mut packs {
            let rebuilt_name = pack.asset_name();
            if align_pack_to_lock(&lock, pack) {
                let locked_name = pack.asset_name();
                if rebuilt_name != locked_name {
                    fs::rename(dist.join(&rebuilt_name), dist.join(&locked_name))
                        .with_context(|| format!("rename dist asset {rebuilt_name} → {locked_name}"))?;
                }
            }
        }
    }

    match &args.command {
        ExtensionsCommand::Pack => {
            for pack in &packs {
                println!("pack: {} {} → {}", pack.id, pack.version, dist.join(pack.asset_name()).display());
            }
        }
        ExtensionsCommand::Lock { write } => {
            let lock = ExtensionsLock { packs };
            let json = serde_json::to_string_pretty(&lock)?;
            if *write {
                fs::write(root.join(LOCK_FILE), &json)?;
                println!("wrote {LOCK_FILE}");
            } else {
                println!("{json}");
            }
        }
        ExtensionsCommand::Verify => {
            let lock_text = fs::read_to_string(root.join(LOCK_FILE))
                .with_context(|| format!("{LOCK_FILE} not found; run `cargo xtask extensions lock --write` first"))?;
            let lock: ExtensionsLock = serde_json::from_str(&lock_text).context("parse extensions.lock")?;
            if lock.packs.len() != packs.len() {
                bail!(
                    "extensions.lock has {} pack(s) but {} were rebuilt",
                    lock.packs.len(), packs.len()
                );
            }
            for actual in &packs {
                let expected = lock
                    .packs
                    .iter()
                    .find(|p| p.id == actual.id)
                    .with_context(|| format!("extensions.lock has no entry for pack '{}'", actual.id))?;
                compare_pack(expected, actual)?;
            }
            println!("{LOCK_FILE} verified");
        }
    }
    Ok(())
}

fn build_script_deps(root: &Path, dist: &Path) -> Result<PackLock> {
    let gradlew = if cfg!(windows) { "gradlew.bat" } else { "./gradlew" };
    let status = Command::new(gradlew)
        .args([":app:generateScriptDepsDex", "--quiet"])
        .current_dir(root)
        .status()
        .context("failed to spawn gradlew")?;
    if !status.success() {
        bail!(":app:generateScriptDepsDex failed");
    }

    let dex = root.join("app/build/outputs/script-deps/classes.dex");
    let sha = sha256_file(&dex)?;
    let mut files = BTreeMap::new();
    files.insert("script-deps.dex".to_string(), sha);
    let version = derive_version(&today()?, &content_hash(&files));

    let asset = dist.join(format!("script-deps-{version}.dex"));
    fs::copy(&dex, &asset).context("copy script-deps DEX into dist")?;
    clean_stale(dist, "script-deps-", &asset)?;

    println!("script-deps: {version}");
    Ok(PackLock { id: PACK_SCRIPT_DEPS.into(), version, files })
}

fn build_cloudflared_zip(root: &Path, dist: &Path) -> Result<PackLock> {
    let abis = ["arm64-v8a", "armeabi-v7a"];
    crate::task_build_cloudflared(&abis.iter().map(|s| s.to_string()).collect::<Vec<_>>())?;

    let mut inner: BTreeMap<String, String> = BTreeMap::new();
    let mut so_paths: Vec<(String, PathBuf)> = Vec::new();
    for abi in abis {
        let so = root.join("target/cloudflared").join(abi).join(CLOUDFLARED_LIB);
        inner.insert(format!("{abi}/{CLOUDFLARED_LIB}"), sha256_file(&so)?);
        so_paths.push((abi.to_string(), so));
    }
    let inner_manifest = serde_json::to_string_pretty(&serde_json::json!({ "files": inner }))?;

    let zip_tmp = dist.join("cloudflared-unversioned.zip");
    {
        let file = File::create(&zip_tmp)?;
        let mut zip = ZipWriter::new(file);
        let options = SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
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
    let version = derive_version(&today()?, &content_hash(&files));

    let asset = dist.join(format!("cloudflared-{version}.zip"));
    fs::rename(&zip_tmp, &asset)?;
    clean_stale(dist, "cloudflared-", &asset)?;

    println!("cloudflared: {version}");
    Ok(PackLock { id: PACK_CLOUDFLARED.into(), version, files })
}

/// Remove older versioned assets of the same pack so dist always holds exactly one.
fn clean_stale(dist: &Path, prefix: &str, keep: &Path) -> Result<()> {
    for entry in fs::read_dir(dist)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file()
            && path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with(prefix))
            && path != keep
        {
            fs::remove_file(&path).with_context(|| format!("remove stale {}", path.display()))?;
        }
    }
    Ok(())
}

fn today() -> Result<String> {
    let format = time::macros::format_description!("[year][month][day]");
    OffsetDateTime::now_utc()
        .format(&format)
        .context("format UTC date")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn files(entries: &[(&str, &str)]) -> BTreeMap<String, String> {
        entries.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
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
    fn version_is_date_plus_twelve_hex_chars() {
        let v = derive_version("20260818", &"0123456789abcdef".repeat(4));
        assert_eq!(v, "20260818-0123456789ab");
    }

    #[test]
    fn asset_name_inserts_version_before_extension() {
        let pack = PackLock {
            id: "script-deps".into(),
            version: "20260818-abc123def456".into(),
            files: files(&[("script-deps.dex", "00")]),
        };
        assert_eq!(pack.asset_name(), "script-deps-20260818-abc123def456.dex");
        let pack = PackLock {
            id: "cloudflared".into(),
            version: "20260818-abc123def456".into(),
            files: files(&[("cloudflared.zip", "00")]),
        };
        assert_eq!(pack.asset_name(), "cloudflared-20260818-abc123def456.zip");
    }

    #[test]
    fn compare_pack_accepts_equivalent_lock_and_rejects_drift() {
        let lock_files = files(&[("script-deps.dex", "aa")]);
        let lock = PackLock {
            id: "script-deps".into(),
            version: derive_version("20260818", &content_hash(&lock_files)),
            files: lock_files.clone(),
        };
        let rebuilt = lock.clone();
        assert!(compare_pack(&lock, &rebuilt).is_ok());

        let drifted = PackLock {
            files: files(&[("script-deps.dex", "bb")]),
            ..lock.clone()
        };
        assert!(compare_pack(&lock, &drifted).is_err());

        // A hand-edited or corrupted lock (version does not match its own hashes)
        // must fail loudly instead of being compared against rebuilt content.
        let inconsistent = PackLock {
            version: derive_version("20260818", &content_hash(&files(&[("script-deps.dex", "bb")]))),
            ..lock.clone()
        };
        assert!(compare_pack(&inconsistent, &rebuilt).is_err());
    }

    #[test]
    fn compare_pack_accepts_same_content_rebuilt_on_a_different_date() {
        let lock_files = files(&[("script-deps.dex", "aa")]);
        let hash = content_hash(&lock_files);
        let lock = PackLock {
            id: "script-deps".into(),
            version: derive_version("20260818", &hash),
            files: lock_files.clone(),
        };
        // Calendar-day rollover: identical content, new date prefix in the version.
        let rebuilt_next_day = PackLock {
            version: derive_version("20260819", &hash),
            files: lock_files,
            ..lock.clone()
        };
        assert!(compare_pack(&lock, &rebuilt_next_day).is_ok());
    }

    #[test]
    fn align_pack_to_lock_adopts_lock_version_for_same_content() {
        let lock_files = files(&[("script-deps.dex", "aa")]);
        let hash = content_hash(&lock_files);
        let entry = PackLock {
            id: "script-deps".into(),
            version: derive_version("20260817", &hash),
            files: lock_files.clone(),
        };
        let lock = ExtensionsLock { packs: vec![entry.clone()] };

        // Same content rebuilt on a later date: aligned to the lock's version.
        let mut rebuilt = PackLock {
            version: derive_version("20260818", &hash),
            files: lock_files,
            ..entry.clone()
        };
        assert!(align_pack_to_lock(&lock, &mut rebuilt));
        assert_eq!(rebuilt.version, entry.version);
        assert_eq!(rebuilt.asset_name(), entry.asset_name());
    }

    #[test]
    fn align_pack_to_lock_leaves_changed_or_unknown_content_alone() {
        let lock_files = files(&[("script-deps.dex", "aa")]);
        let entry = PackLock {
            id: "script-deps".into(),
            version: derive_version("20260817", &content_hash(&lock_files)),
            files: lock_files,
        };
        let lock = ExtensionsLock { packs: vec![entry] };

        // Changed content: not aligned, rebuilt version kept.
        let hash = content_hash(&files(&[("script-deps.dex", "bb")]));
        let rebuilt_version = derive_version("20260818", &hash);
        let mut rebuilt = PackLock {
            version: rebuilt_version.clone(),
            files: files(&[("script-deps.dex", "bb")]),
            ..lock.packs[0].clone()
        };
        assert!(!align_pack_to_lock(&lock, &mut rebuilt));
        assert_eq!(rebuilt.version, rebuilt_version);

        // No lock entry for the id: not aligned.
        let mut unknown = PackLock {
            id: "cloudflared".into(),
            ..lock.packs[0].clone()
        };
        assert!(!align_pack_to_lock(&lock, &mut unknown));
    }

    #[test]
    fn lock_json_roundtrip() {
        let lock = ExtensionsLock {
            packs: vec![PackLock {
                id: "script-deps".into(),
                version: "20260818-0123456789ab".into(),
                files: files(&[("script-deps.dex", "00")]),
            }],
        };
        let json = serde_json::to_string_pretty(&lock).unwrap();
        let back: ExtensionsLock = serde_json::from_str(&json).unwrap();
        assert_eq!(back.packs[0].version, "20260818-0123456789ab");
    }
}
