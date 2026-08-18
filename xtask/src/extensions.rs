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
use std::collections::BTreeMap;
use std::fs;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use zip::write::SimpleFileOptions;
use zip::ZipWriter;

const PACK_SCRIPT_DEPS: &str = "script-deps";
const PACK_CLOUDFLARED: &str = "cloudflared";
const DIST_DIR: &str = "dist/extensions";
const INDEX_FILE: &str = "manifest.json";
const CLOUDFLARED_LIB: &str = "libwekit_cloudflared.so";

#[derive(Args)]
pub struct ExtensionsArgs {
    #[command(subcommand)]
    pub command: ExtensionsCommand,

    /// Only process the given pack id (script-deps | cloudflared). Skips writing the index.
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackIndexEntry {
    pub id: String,
    pub version: String,
    /// Release asset file name for this version.
    pub asset: String,
    pub sha256: String,
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
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

/// Index entry for a pack: versioned asset name plus the asset's SHA-256.
/// The files map holds exactly one canonical (version-less) name -> sha entry.
fn index_entry(id: &str, version: &str, files: &BTreeMap<String, String>) -> PackIndexEntry {
    let (name, sha) = files.iter().next()
        .unwrap_or_else(|| panic!("pack '{id}' has no files"));
    let stem = name.split('.').next().unwrap();
    let ext = name.rsplit('.').next().filter(|e| *e != name);
    let asset = match ext {
        Some(e) => format!("{stem}-{version}.{e}"),
        None => format!("{stem}-{version}"),
    };
    PackIndexEntry { id: id.into(), version: version.into(), asset, sha256: sha.clone() }
}

pub fn run(root: &Path, args: &ExtensionsArgs) -> Result<()> {
    let selected = |id: &str| args.only.as_deref().map(|only| only == id).unwrap_or(true);

    let dist = root.join(DIST_DIR);
    fs::create_dir_all(&dist)?;

    let mut entries: Vec<PackIndexEntry> = Vec::new();
    if selected(PACK_SCRIPT_DEPS) {
        entries.push(build_script_deps(root, &dist)?);
    }
    if selected(PACK_CLOUDFLARED) {
        entries.push(build_cloudflared_zip(root, &dist)?);
    }
    entries.sort_by(|a, b| a.id.cmp(&b.id));

    match &args.command {
        ExtensionsCommand::Pack => {
            for entry in &entries {
                println!("pack: {} {} → {}", entry.id, entry.version, dist.join(&entry.asset).display());
            }
            if args.only.is_some() {
                println!("note: --only skips writing {INDEX_FILE}; run a full `cargo xtask extensions pack` to refresh the index");
            } else {
                let index_path = dist.join(INDEX_FILE);
                fs::write(&index_path, serde_json::to_string_pretty(&PackIndex { packs: entries })?)
                    .with_context(|| format!("write {}", index_path.display()))?;
                println!("index: {}", index_path.display());
            }
        }
    }
    Ok(())
}

fn build_script_deps(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
    let gradlew = if cfg!(windows) { "gradlew.bat" } else { "./gradlew" };
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

fn build_cloudflared_zip(root: &Path, dist: &Path) -> Result<PackIndexEntry> {
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
    let version = derive_version(&content_hash(&files));
    let entry = index_entry(PACK_CLOUDFLARED, &version, &files);

    let asset = dist.join(&entry.asset);
    fs::rename(&zip_tmp, &asset)?;
    clean_stale(dist, "cloudflared-", &asset)?;

    println!("cloudflared: {version}");
    Ok(entry)
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
    fn version_is_first_twelve_hex_chars_of_content_hash() {
        let hash = content_hash(&files(&[("script-deps.dex", "aa")]));
        assert_eq!(derive_version(&hash), hash[..12]);
    }

    #[test]
    fn index_entry_inserts_version_before_extension() {
        let entry = index_entry("script-deps", "0123456789ab", &files(&[("script-deps.dex", "00")]));
        assert_eq!(entry.asset, "script-deps-0123456789ab.dex");
        assert_eq!(entry.sha256, "00");

        let entry = index_entry("cloudflared", "0123456789ab", &files(&[("cloudflared.zip", "11")]));
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
            }],
        };
        let json = serde_json::to_string_pretty(&index).unwrap();
        let back: PackIndex = serde_json::from_str(&json).unwrap();
        assert_eq!(back.packs[0].version, "0123456789ab");
        assert_eq!(back.packs[0].asset, "script-deps-0123456789ab.dex");
    }
}
