//! WeKit xtask — build automation for the WeKit Android project.
//!
//! Usage: cargo xtask <COMMAND>
//!
//!   configure            Regenerate wekit-native/.cargo/config.toml from the local NDK.
//!   build [OPTIONS]      Build the project (default: full Android debug build via Gradle).
//!   zygisk <COMMAND>     Build, package, and install the Zygisk module.
//!   check [OPTIONS]      Run `cargo check` on the native library.
//!   clippy [OPTIONS]     Run `cargo clippy` on the native library.
//!
//! Run `cargo xtask <COMMAND> --help` for per-command options.

use anyhow::{Context, Result, bail};
use clap::{Args, Parser, Subcommand, ValueEnum};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    env, fs,
    io::{BufWriter, Read, Write},
    path::{Path, PathBuf},
    process::Command,
};
use walkdir::WalkDir;
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

// ── Project constants (mirror app/build.gradle.kts / libs.versions.toml) ──────

/// Matches `minSdk` in libs.versions.toml.
const MIN_SDK: u32 = 28;

/// Minimum NDK major version accepted by `configure`.  Mirrors the check in
/// ConfigureCargoTask.kt (`minNdk = 29`).
const MIN_NDK_MAJOR: u32 = 29;

// ── ABI table ─────────────────────────────────────────────────────────────────

struct AbiSpec {
    /// Directory name in `jniLibs/` and Android ABI split filter.
    android_name: &'static str,
    /// Cargo target triple passed to `--target`.
    cargo_triple: &'static str,
    /// Clang binary prefix inside the NDK `bin/` dir (the part before
    /// `{MIN_SDK}-clang`).  Note: armv7 uses `armv7a-` not `armv7-`.
    clang_prefix: &'static str,
    /// Prefix used for `CC_`, `CXX_`, `AR_` keys in `.cargo/config.toml`.
    /// Matches the hardcoded strings in `ConfigureCargoTask.kt`.
    env_key: &'static str,
}

// Order matches the template in ConfigureCargoTask.kt so that
// `cargo xtask configure` and the Gradle task produce identical output.
static ABI_TABLE: &[AbiSpec] = &[
    AbiSpec {
        android_name: "arm64-v8a",
        cargo_triple: "aarch64-linux-android",
        clang_prefix: "aarch64-linux-android",
        env_key: "aarch64_linux_android",
    },
    AbiSpec {
        android_name: "x86_64",
        cargo_triple: "x86_64-linux-android",
        clang_prefix: "x86_64-linux-android",
        env_key: "x86_64_linux_android",
    },
    AbiSpec {
        android_name: "armeabi-v7a",
        cargo_triple: "armv7-linux-androideabi",
        clang_prefix: "armv7a-linux-androideabi",
        // Kept with hyphens to match ConfigureCargoTask.kt's template verbatim.
        env_key: "armv7-linux-androideabi",
    },
    AbiSpec {
        android_name: "x86",
        cargo_triple: "i686-linux-android",
        clang_prefix: "i686-linux-android",
        // Kept with hyphens to match ConfigureCargoTask.kt's template verbatim.
        env_key: "i686-linux-android",
    },
];

/// ABIs included in Gradle's ABI splits (the default build targets).
static RELEASE_ABIS: &[&str] = &["arm64-v8a", "armeabi-v7a"];

const ZYGISK_MODULE_ID: &str = "wekit";
const ZYGISK_MODULE_NAME: &str = "WeKit";
const ZYGISK_VERSION_NAME: &str = "1";

struct ZygiskAbiSpec {
    android_name: &'static str,
    magisk_name: &'static str,
    aliases: &'static [&'static str],
}

static ZYGISK_ABIS: &[ZygiskAbiSpec] = &[
    ZygiskAbiSpec {
        android_name: "arm64-v8a",
        magisk_name: "arm64",
        aliases: &["arm64", "a64", "aarch64", "arm64_v8a"],
    },
    ZygiskAbiSpec {
        android_name: "armeabi-v7a",
        magisk_name: "arm",
        aliases: &["armeabi", "arm", "arm32", "a32", "armeabi_v7a"],
    },
];

// ── CLI ────────────────────────────────────────────────────────────────────────

#[derive(Parser)]
#[command(
    name = "cargo xtask",
    about = "WeKit build automation",
    long_about = None,
    disable_help_subcommand = true,
)]
struct Cli {
    #[command(subcommand)]
    command: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Regenerate wekit-native/.cargo/config.toml from the local NDK.
    Configure,

    /// Build the project.
    ///
    /// Default: runs `./gradlew assembleDebug` (full Android + Rust via Gradle).
    /// Pass --native-only to compile only the Rust .so and copy it to jniLibs/.
    Build(BuildArgs),

    /// Install and launch the app on a connected device or emulator.
    ///
    /// Runs `./gradlew install<Flavor><Type>` (default: `installDebug`).
    Run(RunArgs),

    /// Build, package, and install the Zygisk module.
    Zygisk(ZygiskArgs),

    /// Run `cargo check` on the native library for each target ABI.
    Check(NativeArgs),

    /// Run `cargo clippy` on the native library for each target ABI.
    Clippy(NativeArgs),
}

#[derive(Args)]
struct BuildArgs {
    /// Build only the Rust native library (.so) and copy it to jniLibs/.
    /// Skips the Gradle Android build entirely.
    #[arg(long)]
    native_only: bool,

    /// Build a specific app flavor (standard or legacy).
    /// Defaults to both (`assembleDebug` / `assembleRelease`).
    /// Ignored with --native-only.
    #[arg(short, long, value_enum)]
    flavor: Option<Flavor>,

    /// Build a release build instead of debug.
    /// Ignored with --native-only.
    #[arg(long)]
    release: bool,

    #[command(flatten)]
    native: NativeArgs,
}

/// Arguments for `run` (install + launch via Gradle).
#[derive(Args)]
struct RunArgs {
    /// App flavor to install (standard or legacy).
    /// Defaults to standard — both flavors cannot be installed side-by-side.
    #[arg(short, long, value_enum, default_value = "standard")]
    flavor: Flavor,

    /// Install the release build instead of debug.
    #[arg(long)]
    release: bool,
}

#[derive(Args)]
struct ZygiskArgs {
    #[command(subcommand)]
    command: ZygiskCmd,
}

#[derive(Subcommand)]
enum ZygiskCmd {
    /// Build the installable Zygisk ZIP. Builds standard APK splits by default.
    Build(ZygiskBuildArgs),

    /// Build or reuse a Zygisk ZIP, then install it through a connected device's root manager.
    Flash(ZygiskFlashArgs),

    /// Build only the Zygisk native loader(s), without an APK or module ZIP.
    Native(ZygiskNativeArgs),

    /// Generate CMake build files and compile_commands.json for the Zygisk loader.
    Config(ZygiskConfigArgs),

    /// Remove Zygisk CMake build trees and native output directories.
    Clean(ZygiskCleanArgs),
}

#[derive(Args)]
struct ZygiskConfigArgs {
    /// Target ABI(s). May be repeated. Defaults to arm64-v8a and armeabi-v7a.
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,

    /// Build with RelWithDebInfo rather than Debug CMake settings.
    #[arg(long)]
    release: bool,

    /// Android NDK version under ANDROID_HOME/ndk/. Defaults to gradle/libs.versions.toml.
    #[arg(long, value_name = "VERSION")]
    ndk: Option<String>,
}

#[derive(Args)]
struct ZygiskNativeArgs {
    #[command(flatten)]
    config: ZygiskConfigArgs,

    /// Delete each selected ABI's CMake and output directories before building.
    #[arg(long)]
    force: bool,
}

#[derive(Args)]
struct ZygiskBuildArgs {
    /// Package release artifacts instead of debug artifacts.
    #[arg(long)]
    release: bool,

    /// Delete Zygisk CMake and output directories before building native loaders.
    #[arg(long)]
    force: bool,

    /// Android NDK version under ANDROID_HOME/ndk/. Defaults to gradle/libs.versions.toml.
    #[arg(long, value_name = "VERSION")]
    ndk: Option<String>,

    /// APK to embed. Repeat once per ABI to override automatic split-APK discovery.
    #[arg(long = "apk", value_name = "APK")]
    apks: Vec<PathBuf>,

    /// Reuse existing APK outputs instead of running assembleStandard<BuildType>.
    #[arg(long)]
    skip_apk_build: bool,

    /// Also write an unstripped native-symbol ZIP under wekit-zygisk/symbols/.
    #[arg(long)]
    save_symbols: bool,
}

#[derive(Args)]
struct ZygiskFlashArgs {
    #[command(flatten)]
    build: ZygiskBuildArgs,

    /// adb device serial. Uses adb's default device when omitted.
    #[arg(short, long)]
    device: Option<String>,

    /// Root manager command passed to install_module.sh (magisk, ksu, or ap).
    #[arg(long, value_name = "ROOT")]
    root: Option<String>,

    /// Reboot after a successful module installation.
    #[arg(short, long)]
    reboot: bool,

    /// Install the latest ZIP for the selected profile instead of building one.
    #[arg(long)]
    skip_build: bool,
}

#[derive(Args)]
struct ZygiskCleanArgs {
    /// Clean debug, release, or both profiles (default: both).
    #[arg(long, value_enum, default_value_t = ZygiskCleanProfile::All)]
    profile: ZygiskCleanProfile,

    /// Limit cleaning to ABI(s). Defaults to both supported Zygisk ABIs.
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,
}

#[derive(Clone, ValueEnum)]
enum ZygiskCleanProfile {
    Debug,
    Release,
    All,
}

/// Arguments shared by --native-only builds, `check`, and `clippy`.
#[derive(Args)]
struct NativeArgs {
    /// Target ABI(s) to build.  May be repeated.  Defaults to arm64-v8a and armeabi-v7a.
    ///
    /// Valid values: arm64-v8a, armeabi-v7a, x86_64, x86
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,
}

#[derive(ValueEnum, Clone, Debug)]
enum Flavor {
    Standard,
    Legacy,
}

// ── Entry point ────────────────────────────────────────────────────────────────

fn print_banner() {
    println!(
        r#"
     _       __     __ __ _ __
    | |     / /__  / //_/(_) /_
    | | /| / / _ \/ ,<  / / __/
    | |/ |/ /  __/ /| |/ / /_
    |__/|__/\___/_/ |_/_/\__/

[WeKit] WeChat, now with superpowers
"#
    );
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    print_banner();
    match cli.command {
        Cmd::Configure => task_configure()?,
        Cmd::Build(args) => task_build(args)?,
        Cmd::Run(args) => task_run(args)?,
        Cmd::Zygisk(args) => task_zygisk(args)?,
        Cmd::Check(args) => task_cargo_cmd("check", &args.abis, &[])?,
        Cmd::Clippy(args) => task_cargo_cmd("clippy", &args.abis, &["--", "-D", "warnings"])?,
    }
    Ok(())
}

// ── Workspace / path helpers ───────────────────────────────────────────────────

/// Walk up from `cwd` until we find a `Cargo.toml` that declares `[workspace]`.
fn workspace_root() -> PathBuf {
    let mut dir = env::current_dir().expect("could not read cwd");
    loop {
        let toml = dir.join("Cargo.toml");
        if toml.exists() {
            let text = fs::read_to_string(&toml).unwrap_or_default();
            if text.contains("[workspace]") {
                return dir;
            }
        }
        dir = dir
            .parent()
            .unwrap_or_else(|| panic!("workspace root not found; run from inside the WeKit repo"))
            .to_owned();
    }
}

fn native_crate_dir(root: &Path) -> PathBuf {
    root.join("app/src/main/rust/wekit-native")
}

fn jni_libs_dir(root: &Path) -> PathBuf {
    root.join("app/src/main/jniLibs")
}

fn zygisk_dir(root: &Path) -> PathBuf {
    root.join("wekit-zygisk")
}

// ── ABI resolution ─────────────────────────────────────────────────────────────

fn resolve_abis<'a>(names: &[String]) -> Result<Vec<&'a AbiSpec>> {
    let names_to_use: Vec<&str> = if names.is_empty() {
        RELEASE_ABIS.to_vec()
    } else {
        names.iter().map(String::as_str).collect()
    };

    names_to_use
        .iter()
        .map(|name| {
            ABI_TABLE
                .iter()
                .find(|a| a.android_name == *name)
                .with_context(|| {
                    format!(
                        "unknown ABI `{name}`; valid values: {}",
                        ABI_TABLE
                            .iter()
                            .map(|a| a.android_name)
                            .collect::<Vec<_>>()
                            .join(", ")
                    )
                })
        })
        .collect()
}

fn resolve_zygisk_abis<'a>(names: &[String]) -> Result<Vec<&'a ZygiskAbiSpec>> {
    let names_to_use: Vec<&str> = if names.is_empty() {
        ZYGISK_ABIS.iter().map(|abi| abi.android_name).collect()
    } else {
        names.iter().map(String::as_str).collect()
    };

    let mut resolved = Vec::with_capacity(names_to_use.len());
    for name in names_to_use {
        let abi = ZYGISK_ABIS
            .iter()
            .find(|abi| abi.android_name == name || abi.aliases.contains(&name))
            .with_context(|| {
                format!(
                    "unknown Zygisk ABI `{name}`; valid values: {}",
                    ZYGISK_ABIS
                        .iter()
                        .map(|abi| abi.android_name)
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            })?;
        if !resolved
            .iter()
            .any(|existing: &&ZygiskAbiSpec| existing.android_name == abi.android_name)
        {
            resolved.push(abi);
        }
    }
    Ok(resolved)
}

// ── Android SDK / NDK discovery ────────────────────────────────────────────────

/// Return `ANDROID_HOME`, falling back to `sdk.dir` in `local.properties`.
fn find_android_home(workspace_root: &Path) -> Result<String> {
    if let Ok(home) = env::var("ANDROID_HOME") {
        if !home.is_empty() {
            return Ok(home);
        }
    }

    if let Ok(home) = env::var("ANDROID_SDK_ROOT") {
        if !home.is_empty() {
            return Ok(home);
        }
    }

    let props_path = workspace_root.join("local.properties");
    let props = fs::read_to_string(&props_path).with_context(|| {
        format!(
            "ANDROID_HOME not set and could not read {}",
            props_path.display()
        )
    })?;

    for line in props.lines() {
        if let Some(rest) = line.strip_prefix("sdk.dir=") {
            let dir = rest.trim().replace("\\:", ":"); // unescape Windows paths
            if !dir.is_empty() {
                return Ok(dir);
            }
        }
    }

    bail!("ANDROID_HOME env var not set and sdk.dir not found in local.properties");
}

/// Return the `bin/` path inside the highest qualifying NDK's prebuilt llvm dir.
///
/// Mirrors `findNdkClang` in `buildSrc/src/main/kotlin/ConfigureCargoTask.kt`.
fn find_ndk_bin_dir(android_home: &str) -> Result<String> {
    let ndk_root = PathBuf::from(android_home).join("ndk");
    if !ndk_root.exists() {
        bail!("NDK directory not found: {}", ndk_root.display());
    }

    // Collect NDK dirs whose major version >= MIN_NDK_MAJOR.
    let mut candidates: Vec<(Vec<u32>, PathBuf)> = fs::read_dir(&ndk_root)
        .with_context(|| format!("could not list {}", ndk_root.display()))?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let name = e.file_name();
            let parts: Vec<u32> = name
                .to_string_lossy()
                .split('.')
                .filter_map(|p| p.parse::<u32>().ok())
                .collect();
            if parts.first().copied().unwrap_or(0) >= MIN_NDK_MAJOR {
                Some((parts, e.path()))
            } else {
                None
            }
        })
        .collect();

    if candidates.is_empty() {
        bail!(
            "no NDK >= {MIN_NDK_MAJOR} found under {}",
            ndk_root.display()
        );
    }

    // Pick the highest version (lexicographic on version part tuples).
    candidates.sort_by(|a, b| a.0.cmp(&b.0));
    let (_, ndk_dir) = candidates.pop().unwrap();

    let host = host_prebuilt_tag()?;
    let bin_dir = ndk_dir
        .join("toolchains/llvm/prebuilt")
        .join(host)
        .join("bin");

    if !bin_dir.exists() {
        bail!("expected NDK bin dir not found: {}", bin_dir.display());
    }

    Ok(bin_dir.to_string_lossy().replace('\\', "/"))
}

/// Return the prebuilt host tag used by the NDK (e.g. `linux-x86_64`).
fn host_prebuilt_tag() -> Result<&'static str> {
    match (env::consts::OS, env::consts::ARCH) {
        ("linux", "x86_64") => Ok("linux-x86_64"),
        ("linux", "aarch64") => Ok("linux-aarch64"),
        ("macos", "x86_64") => Ok("darwin-x86_64"),
        ("macos", "aarch64") => Ok("darwin-arm64"),
        ("windows", "x86_64") => Ok("windows-x86_64"),
        (os, arch) => bail!("unsupported host OS/arch: {os}/{arch}"),
    }
}

// ── Task: configure ────────────────────────────────────────────────────────────

fn task_configure() -> Result<()> {
    let root = workspace_root();
    let android_home = find_android_home(&root)?;
    let ndk_bin_dir = find_ndk_bin_dir(&android_home)?;

    // On Windows the NDK ships `.cmd` wrappers for the clang binaries.
    let ext = if cfg!(target_os = "windows") {
        ".cmd"
    } else {
        ""
    };
    let ar = format!("{ndk_bin_dir}/llvm-ar");

    let mut out = String::new();

    // [target.*] sections — one per ABI.
    for spec in ABI_TABLE {
        let linker = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        out.push_str(&format!(
            "[target.{}]\nar = \"{ar}\"\nlinker = \"{linker}\"\n\n",
            spec.cargo_triple
        ));
    }

    // [env] section — CC/CXX/AR vars consumed by `cc-rs` and `bindgen`.
    out.push_str("[env]\n");
    for spec in ABI_TABLE {
        let cc = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        let cxx = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang++{ext}", spec.clang_prefix);
        out.push_str(&format!("CC_{k} = \"{cc}\"\n", k = spec.env_key));
        out.push_str(&format!("CXX_{k} = \"{cxx}\"\n", k = spec.env_key));
        out.push_str(&format!("AR_{k} = \"{ar}\"\n\n", k = spec.env_key));
    }

    let out = out.trim_end_matches('\n').to_owned() + "\n";

    let config_path = native_crate_dir(&root).join(".cargo/config.toml");
    fs::create_dir_all(config_path.parent().unwrap())?;
    fs::write(&config_path, &out)
        .with_context(|| format!("failed to write {}", config_path.display()))?;

    println!("configure: wrote {}", config_path.display());
    Ok(())
}

// ── Task: build ────────────────────────────────────────────────────────────────

fn task_build(args: BuildArgs) -> Result<()> {
    if args.native_only {
        task_build_native(&args.native.abis)
    } else {
        task_build_android(&args)
    }
}

/// Compose a Gradle task name from a verb, optional flavor, and profile.
///
/// Examples: `assemble` + `Standard` + `Release` → `assembleStandardRelease`
fn gradle_variant_task(verb: &str, flavor: Option<&Flavor>, release: bool) -> String {
    let profile = if release { "Release" } else { "Debug" };
    match flavor {
        None => format!("{verb}{profile}"),
        Some(Flavor::Standard) => format!("{verb}Standard{profile}"),
        Some(Flavor::Legacy) => format!("{verb}Legacy{profile}"),
    }
}

/// Full Android build via the Gradle wrapper (native lib compiled first).
fn task_build_android(args: &BuildArgs) -> Result<()> {
    task_configure()?;
    task_build_native(&args.native.abis)?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("assemble", args.flavor.as_ref(), args.release);
    println!("build: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Install the app on a connected device or emulator via the Gradle wrapper (native lib compiled first).
fn task_run(args: RunArgs) -> Result<()> {
    task_configure()?;
    task_build_native(&[])?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("install", Some(&args.flavor), args.release);
    println!("run: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Native-only build: cargo build + copy .so to jniLibs/.
fn task_build_native(abi_args: &[String]) -> Result<()> {
    let root = workspace_root();
    let native_dir = native_crate_dir(&root);
    let abis = resolve_abis(abi_args)?;

    for spec in &abis {
        println!(
            "build(native): {} ({})",
            spec.android_name, spec.cargo_triple
        );

        run_cargo(
            &["build", "--release", "--target", spec.cargo_triple],
            &native_dir,
        )?;

        let so_src = root
            .join("target")
            .join(spec.cargo_triple)
            .join("release/libwekit_native.so");
        let so_dst_dir = jni_libs_dir(&root).join(spec.android_name);
        let so_dst = so_dst_dir.join("libwekit_native.so");

        fs::create_dir_all(&so_dst_dir)
            .with_context(|| format!("could not create {}", so_dst_dir.display()))?;
        fs::copy(&so_src, &so_dst).with_context(|| {
            format!("could not copy {} → {}", so_src.display(), so_dst.display())
        })?;

        println!(
            "build(native):  {} → {}",
            so_src.display(),
            so_dst.display()
        );
    }

    Ok(())
}

// ── Task: zygisk ──────────────────────────────────────────────────────────────

#[derive(Clone, Copy)]
enum ZygiskBuildProfile {
    Debug,
    Release,
}

impl ZygiskBuildProfile {
    fn from_release(release: bool) -> Self {
        if release { Self::Release } else { Self::Debug }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Debug => "debug",
            Self::Release => "release",
        }
    }

    fn cmake_type(self) -> &'static str {
        match self {
            Self::Debug => "Debug",
            Self::Release => "RelWithDebInfo",
        }
    }
}

#[derive(Deserialize)]
struct GradleVersionCatalog {
    versions: GradleVersions,
}

#[derive(Deserialize)]
struct GradleVersions {
    ndk: String,
    #[serde(rename = "minSdk")]
    min_sdk: String,
}

#[derive(Debug)]
struct ZygiskBuildConfig {
    ndk_version: String,
    platform: String,
}

fn task_zygisk(args: ZygiskArgs) -> Result<()> {
    match args.command {
        ZygiskCmd::Build(args) => {
            task_zygisk_build(&args)?;
        }
        ZygiskCmd::Flash(args) => task_zygisk_flash(&args)?,
        ZygiskCmd::Native(args) => task_zygisk_native(&args)?,
        ZygiskCmd::Config(args) => task_zygisk_config(&args)?,
        ZygiskCmd::Clean(args) => task_zygisk_clean(&args)?,
    }
    Ok(())
}

fn parse_zygisk_build_config(text: &str, path: &Path) -> Result<ZygiskBuildConfig> {
    let catalog: GradleVersionCatalog =
        toml::from_str(text).with_context(|| format!("could not parse {}", path.display()))?;
    let ndk_version = catalog.versions.ndk.trim();
    if ndk_version.is_empty() {
        bail!("[versions].ndk in {} must not be empty", path.display());
    }
    let min_sdk_text = catalog.versions.min_sdk.trim();
    let min_sdk = min_sdk_text.parse::<u32>().with_context(|| {
        format!(
            "[versions].minSdk in {} must be a positive integer, got {min_sdk_text:?}",
            path.display()
        )
    })?;
    if min_sdk == 0 {
        bail!(
            "[versions].minSdk in {} must be a positive integer, got {min_sdk_text:?}",
            path.display()
        );
    }
    Ok(ZygiskBuildConfig {
        ndk_version: ndk_version.to_owned(),
        platform: format!("android-{min_sdk}"),
    })
}

fn zygisk_build_config(root: &Path) -> Result<ZygiskBuildConfig> {
    let path = root.join("gradle/libs.versions.toml");
    let text =
        fs::read_to_string(&path).with_context(|| format!("could not read {}", path.display()))?;
    parse_zygisk_build_config(&text, &path)
}

fn zygisk_ndk_dir(root: &Path, requested_version: Option<&str>) -> Result<(PathBuf, String)> {
    let config = zygisk_build_config(root)?;
    let ndk_version = requested_version.unwrap_or(&config.ndk_version);
    if ndk_version.is_empty() {
        bail!("Zygisk NDK version must not be empty");
    }
    let android_home = find_android_home(root)?;
    let ndk_dir = PathBuf::from(android_home).join("ndk").join(ndk_version);
    if !ndk_dir.is_dir() {
        bail!("Zygisk NDK {ndk_version} not found: {}", ndk_dir.display());
    }
    let toolchain = ndk_dir.join("build/cmake/android.toolchain.cmake");
    if !toolchain.is_file() {
        bail!(
            "Zygisk NDK toolchain file not found: {}",
            toolchain.display()
        );
    }
    Ok((ndk_dir, config.platform))
}

fn zygisk_build_dir(root: &Path, profile: ZygiskBuildProfile, abi: &ZygiskAbiSpec) -> PathBuf {
    zygisk_dir(root)
        .join("my_build")
        .join(profile.name())
        .join(abi.android_name)
}

fn zygisk_native_output_dir(
    root: &Path,
    profile: ZygiskBuildProfile,
    abi: &ZygiskAbiSpec,
) -> PathBuf {
    zygisk_dir(root)
        .join("output/native")
        .join(profile.name())
        .join("lib")
        .join(abi.android_name)
}

fn zygisk_symbols_dir(root: &Path, profile: ZygiskBuildProfile, abi: &ZygiskAbiSpec) -> PathBuf {
    zygisk_dir(root)
        .join("output/unstripped")
        .join(profile.name())
        .join(abi.android_name)
}

fn configure_zygisk_abi(
    root: &Path,
    profile: ZygiskBuildProfile,
    abi: &ZygiskAbiSpec,
    ndk_dir: &Path,
    android_platform: &str,
) -> Result<()> {
    let module_dir = zygisk_dir(root);
    let source_dir = module_dir.join("native");
    let build_dir = zygisk_build_dir(root, profile, abi);
    let library_dir = zygisk_native_output_dir(root, profile, abi);
    let binary_dir = module_dir
        .join("output/native")
        .join(profile.name())
        .join("bin")
        .join(abi.android_name);
    let symbols_dir = zygisk_symbols_dir(root, profile, abi);
    let toolchain = ndk_dir.join("build/cmake/android.toolchain.cmake");

    let args = vec![
        "-S".to_owned(),
        source_dir.display().to_string(),
        "-B".to_owned(),
        build_dir.display().to_string(),
        format!("-DANDROID_ABI={}", abi.android_name),
        format!("-DANDROID_PLATFORM={android_platform}"),
        format!("-DANDROID_NDK={}", ndk_dir.display()),
        "-DANDROID_STL=c++_static".to_owned(),
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON".to_owned(),
        format!("-DCMAKE_TOOLCHAIN_FILE={}", toolchain.display()),
        format!("-DCMAKE_RUNTIME_OUTPUT_DIRECTORY={}", binary_dir.display()),
        format!("-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={}", library_dir.display()),
        format!("-DDEBUG_SYMBOLS_PATH={}", symbols_dir.display()),
        format!("-DCMAKE_BUILD_TYPE={}", profile.cmake_type()),
        format!("-DMODULE_NAME={ZYGISK_MODULE_ID}"),
        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON".to_owned(),
        "-G".to_owned(),
        "Ninja".to_owned(),
    ];
    println!("zygisk(config): {} ({})", abi.android_name, profile.name());
    run_cmd_owned("cmake", &args, root)
}

fn build_zygisk_native(
    root: &Path,
    profile: ZygiskBuildProfile,
    requested_ndk: Option<&str>,
    abi_names: &[String],
    force: bool,
) -> Result<()> {
    let abis = resolve_zygisk_abis(abi_names)?;
    let (ndk_dir, android_platform) = zygisk_ndk_dir(root, requested_ndk)?;

    for abi in abis {
        let build_dir = zygisk_build_dir(root, profile, abi);
        let output_dir = zygisk_native_output_dir(root, profile, abi);
        let symbols_dir = zygisk_symbols_dir(root, profile, abi);
        if force {
            remove_dir_if_exists(&build_dir)?;
            remove_dir_if_exists(&output_dir)?;
            remove_dir_if_exists(&symbols_dir)?;
        }

        configure_zygisk_abi(root, profile, abi, &ndk_dir, &android_platform)?;
        println!("zygisk(native): {} ({})", abi.android_name, profile.name());
        run_cmd(
            "cmake",
            &["--build", &build_dir.display().to_string(), "--parallel"],
            root,
        )?;

        let library = output_dir.join(format!("lib{ZYGISK_MODULE_ID}.so"));
        if !library.is_file() {
            bail!("Zygisk native build did not produce {}", library.display());
        }
    }
    Ok(())
}

fn task_zygisk_config(args: &ZygiskConfigArgs) -> Result<()> {
    let root = workspace_root();
    let profile = ZygiskBuildProfile::from_release(args.release);
    let abis = resolve_zygisk_abis(&args.abis)?;
    let (ndk_dir, android_platform) = zygisk_ndk_dir(&root, args.ndk.as_deref())?;
    for abi in abis {
        configure_zygisk_abi(&root, profile, abi, &ndk_dir, &android_platform)?;
    }
    Ok(())
}

fn task_zygisk_native(args: &ZygiskNativeArgs) -> Result<()> {
    let root = workspace_root();
    build_zygisk_native(
        &root,
        ZygiskBuildProfile::from_release(args.config.release),
        args.config.ndk.as_deref(),
        &args.config.abis,
        args.force,
    )
}

fn task_zygisk_build(args: &ZygiskBuildArgs) -> Result<PathBuf> {
    let root = workspace_root();
    let profile = ZygiskBuildProfile::from_release(args.release);
    if !args.skip_apk_build {
        let gradle_task = gradle_variant_task("assemble", Some(&Flavor::Standard), args.release);
        println!("zygisk(apk): ./gradlew {gradle_task}");
        run_gradlew(&[&gradle_task], &root)?;
    }

    build_zygisk_native(&root, profile, args.ndk.as_deref(), &[], args.force)?;
    package_zygisk_module(&root, profile, &args.apks, args.save_symbols)
}

fn apk_abis(path: &Path) -> Result<Vec<&'static str>> {
    let file =
        fs::File::open(path).with_context(|| format!("could not open {}", path.display()))?;
    let mut archive = ZipArchive::new(file)
        .with_context(|| format!("could not inspect APK {}", path.display()))?;
    Ok(ZYGISK_ABIS
        .iter()
        .filter_map(|abi| {
            archive
                .by_name(&format!("lib/{}/libwekit_native.so", abi.android_name))
                .ok()
                .map(|_| abi.android_name)
        })
        .collect())
}

fn file_modified(path: &Path) -> std::time::SystemTime {
    fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .unwrap_or(std::time::UNIX_EPOCH)
}

fn resolve_zygisk_payload_apks(
    root: &Path,
    profile: ZygiskBuildProfile,
    provided: &[PathBuf],
) -> Result<Vec<(&'static str, PathBuf)>> {
    let explicit = !provided.is_empty();
    let candidates = if explicit {
        provided
            .iter()
            .map(|path| {
                path.canonicalize()
                    .with_context(|| format!("WeKit APK does not exist: {}", path.display()))
            })
            .collect::<Result<Vec<_>>>()?
    } else {
        let output_dir = root.join("app/build/outputs/apk");
        WalkDir::new(&output_dir)
            .into_iter()
            .filter_map(|entry| entry.ok())
            .filter(|entry| entry.file_type().is_file())
            .map(|entry| entry.into_path())
            .filter(|path| {
                path.extension().is_some_and(|extension| extension == "apk")
                    && !path
                        .file_name()
                        .is_some_and(|name| name.to_string_lossy().contains("unsigned"))
                    && path.file_name().is_some_and(|name| {
                        name.to_string_lossy()
                            .ends_with(&format!("-{}.apk", profile.name()))
                    })
            })
            .collect::<Vec<_>>()
    };

    let mut resolved: Vec<(&'static str, PathBuf)> = Vec::new();
    for candidate in candidates {
        if !candidate.is_file() {
            bail!("WeKit APK does not exist: {}", candidate.display());
        }
        let is_standard = candidate
            .components()
            .any(|component| component.as_os_str() == "standard");
        for abi in apk_abis(&candidate)? {
            let current = resolved
                .iter_mut()
                .find(|(current_abi, _)| *current_abi == abi);
            let use_candidate = match current {
                None => true,
                Some((_, current_path)) if explicit => {
                    file_modified(&candidate) > file_modified(current_path)
                }
                Some((_, current_path)) => {
                    let current_standard = current_path
                        .components()
                        .any(|component| component.as_os_str() == "standard");
                    (is_standard, file_modified(&candidate))
                        > (current_standard, file_modified(current_path))
                }
            };
            if use_candidate {
                if let Some((_, current_path)) = current {
                    *current_path = candidate.clone();
                } else {
                    resolved.push((abi, candidate.clone()));
                }
            }
        }
    }

    let missing = ZYGISK_ABIS
        .iter()
        .filter(|abi| {
            !resolved
                .iter()
                .any(|(resolved_abi, _)| *resolved_abi == abi.android_name)
        })
        .map(|abi| abi.android_name)
        .collect::<Vec<_>>();
    if !missing.is_empty() {
        let source = if explicit {
            "provided --apk paths"
        } else {
            "app/build/outputs/apk"
        };
        bail!(
            "no compatible WeKit APK for {} in {source}; build standard split APKs or pass --apk once per ABI",
            missing.join(", ")
        );
    }

    resolved.sort_by_key(|(abi, _)| {
        ZYGISK_ABIS
            .iter()
            .position(|candidate| candidate.android_name == *abi)
            .unwrap()
    });
    Ok(resolved)
}

fn dex_entry_order(name: &str) -> Option<u32> {
    if name == "classes.dex" {
        return Some(1);
    }
    let index = name
        .strip_prefix("classes")?
        .strip_suffix(".dex")?
        .parse::<u32>()
        .ok()?;
    (index >= 2).then_some(index)
}

fn export_zygisk_payload(apk: &Path, payload_dir: &Path, abi: &str) -> Result<()> {
    let abi_dir = payload_dir.join(abi);
    fs::create_dir_all(&abi_dir)?;

    let apk_destination = abi_dir.join("wekit.apk");
    fs::copy(apk, &apk_destination).with_context(|| {
        format!(
            "could not copy payload {} to {}",
            apk.display(),
            apk_destination.display()
        )
    })?;

    let input =
        fs::File::open(apk).with_context(|| format!("could not open APK {}", apk.display()))?;
    let mut archive = ZipArchive::new(input)
        .with_context(|| format!("could not inspect APK {}", apk.display()))?;
    let mut dex_entries = Vec::new();
    for index in 0..archive.len() {
        let entry = archive.by_index(index)?;
        let name = entry.name();
        if let Some(order) = dex_entry_order(name) {
            dex_entries.push((order, name.to_owned()));
        }
    }
    dex_entries.sort_by_key(|(order, _)| *order);
    if dex_entries.is_empty() || dex_entries[0].0 != 1 {
        bail!("APK {} does not contain classes.dex", apk.display());
    }
    for (expected, (actual, _)) in dex_entries.iter().enumerate() {
        if *actual != (expected as u32 + 1) {
            bail!(
                "APK {} has a non-contiguous classes*.dex sequence",
                apk.display()
            );
        }
    }

    let mut dex_list = String::new();
    for (_, name) in &dex_entries {
        let mut entry = archive.by_name(name)?;
        let destination = abi_dir.join(name);
        let mut output = fs::File::create(&destination)
            .with_context(|| format!("could not create {}", destination.display()))?;
        std::io::copy(&mut entry, &mut output)
            .with_context(|| format!("could not extract {name} from {}", apk.display()))?;
        dex_list.push_str(name);
        dex_list.push('\n');
    }
    fs::write(abi_dir.join("dex.list"), dex_list)?;
    Ok(())
}

fn copy_tree(source: &Path, destination: &Path) -> Result<()> {
    for entry in WalkDir::new(source).min_depth(1).sort_by_file_name() {
        let entry = entry.with_context(|| format!("could not traverse {}", source.display()))?;
        let relative = entry
            .path()
            .strip_prefix(source)
            .expect("walked path must be inside source");
        let target = destination.join(relative);
        if entry.file_type().is_dir() {
            fs::create_dir_all(&target)
                .with_context(|| format!("could not create {}", target.display()))?;
        } else if entry.file_type().is_file() {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(entry.path(), &target).with_context(|| {
                format!(
                    "could not copy {} to {}",
                    entry.path().display(),
                    target.display()
                )
            })?;
        } else {
            bail!(
                "unsupported non-file template entry: {}",
                entry.path().display()
            );
        }
    }
    Ok(())
}

fn normalize_crlf(root: &Path) -> Result<()> {
    for entry in WalkDir::new(root).min_depth(1).sort_by_file_name() {
        let entry = entry.with_context(|| format!("could not traverse {}", root.display()))?;
        if !entry.file_type().is_file() || entry.file_name() == "mazoku" {
            continue;
        }
        let content = fs::read(entry.path())?;
        if content.contains(&b'\r') {
            let normalized = content
                .into_iter()
                .filter(|byte| *byte != b'\r')
                .collect::<Vec<_>>();
            fs::write(entry.path(), normalized)?;
        }
    }
    Ok(())
}

fn expand_template(path: &Path, variables: &[(&str, String)]) -> Result<()> {
    if !path.exists() {
        return Ok(());
    }
    let mut text =
        fs::read_to_string(path).with_context(|| format!("could not read {}", path.display()))?;
    for (key, value) in variables {
        text = text.replace(&format!("@{key}@"), value);
        text = text.replace(&format!("${{{key}}}"), value);
    }
    fs::write(path, text).with_context(|| format!("could not write {}", path.display()))
}

fn strip_sepolicy_comments(path: &Path) -> Result<()> {
    let text =
        fs::read_to_string(path).with_context(|| format!("could not read {}", path.display()))?;
    let filtered = text
        .lines()
        .filter(|line| {
            let line = line.trim();
            !line.is_empty() && !line.starts_with('#')
        })
        .collect::<Vec<_>>()
        .join("\n");
    fs::write(path, format!("{filtered}\n"))
        .with_context(|| format!("could not write {}", path.display()))
}

fn git_output(root: &Path, args: &[&str]) -> Result<String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(root)
        .output()
        .with_context(|| format!("failed to run git {}", args.join(" ")))?;
    if !output.status.success() {
        bail!(
            "git {} failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    String::from_utf8(output.stdout)
        .map(|value| value.trim().to_owned())
        .context("git output was not UTF-8")
}

fn write_zip_from_directory(source: &Path, destination: &Path, write_hashes: bool) -> Result<()> {
    let output = fs::File::create(destination)
        .with_context(|| format!("could not create {}", destination.display()))?;
    let mut zip = ZipWriter::new(BufWriter::new(output));
    let directory_options = SimpleFileOptions::default();
    let file_options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    for entry in WalkDir::new(source).min_depth(1).sort_by_file_name() {
        let entry = entry.with_context(|| format!("could not traverse {}", source.display()))?;
        let relative = entry
            .path()
            .strip_prefix(source)
            .expect("walked path must be inside source");
        let name = relative.to_string_lossy().replace('\\', "/");
        if entry.file_type().is_dir() {
            zip.add_directory(format!("{name}/"), directory_options)?;
            continue;
        }
        if !entry.file_type().is_file() {
            bail!(
                "unsupported non-file archive entry: {}",
                entry.path().display()
            );
        }

        zip.start_file(&name, file_options)?;
        let mut input = fs::File::open(entry.path())?;
        let mut hasher = Sha256::new();
        let mut buffer = [0_u8; 8192];
        loop {
            let count = input.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            zip.write_all(&buffer[..count])?;
            hasher.update(&buffer[..count]);
        }
        if write_hashes {
            zip.start_file(format!("{name}.sha256"), file_options)?;
            zip.write_all(hex_encode(&hasher.finalize()).as_bytes())?;
        }
    }
    zip.finish()?.flush()?;
    Ok(())
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn package_zygisk_module(
    root: &Path,
    profile: ZygiskBuildProfile,
    explicit_apks: &[PathBuf],
    save_symbols: bool,
) -> Result<PathBuf> {
    let module_root = zygisk_dir(root);
    let module_dir = module_root.join("output/module").join(profile.name());
    remove_dir_if_exists(&module_dir)?;
    fs::create_dir_all(&module_dir)?;
    copy_tree(&module_root.join("template"), &module_dir)?;
    fs::copy(module_root.join("README.md"), module_dir.join("README.md"))?;
    normalize_crlf(&module_dir)?;

    let commit_count = git_output(root, &["rev-list", "HEAD", "--count"])?;
    let commit_hash = git_output(root, &["rev-parse", "--verify", "--short", "HEAD"])?;
    expand_template(
        &module_dir.join("module.prop"),
        &[
            ("moduleId", ZYGISK_MODULE_ID.to_owned()),
            ("moduleName", ZYGISK_MODULE_NAME.to_owned()),
            (
                "versionName",
                format!(
                    "{ZYGISK_VERSION_NAME} ({commit_count}-{commit_hash}-{})",
                    profile.name()
                ),
            ),
            ("versionCode", commit_count.clone()),
        ],
    )?;
    let script_variables = [
        ("DEBUG", (profile.name() == "debug").to_string()),
        ("SONAME", ZYGISK_MODULE_ID.to_owned()),
        (
            "SUPPORTED_ABIS",
            ZYGISK_ABIS
                .iter()
                .map(|abi| abi.magisk_name)
                .collect::<Vec<_>>()
                .join(" "),
        ),
    ];
    for name in [
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "uninstall.sh",
        "cleanup.sh",
    ] {
        expand_template(&module_dir.join(name), &script_variables)?;
    }
    strip_sepolicy_comments(&module_dir.join("sepolicy.rule"))?;

    copy_tree(
        &module_root.join("output/native").join(profile.name()),
        &module_dir,
    )?;
    let payload_dir = module_dir.join("payload");
    fs::create_dir_all(&payload_dir)?;
    for (abi, source) in resolve_zygisk_payload_apks(root, profile, explicit_apks)? {
        export_zygisk_payload(&source, &payload_dir, abi)?;
        println!(
            "zygisk(package): embedded {} -> payload/{abi}/wekit.apk + classes*.dex",
            source.display()
        );
    }

    let build_name = format!(
        "{ZYGISK_MODULE_NAME}-{ZYGISK_VERSION_NAME}-{commit_count}-{commit_hash}-{}",
        profile.name()
    );
    let release_dir = module_root.join("release");
    fs::create_dir_all(&release_dir)?;
    let zip_path = release_dir.join(format!("{build_name}.zip"));
    write_zip_from_directory(&module_dir, &zip_path, true)?;
    println!("zygisk(package): {}", zip_path.display());

    if save_symbols {
        let symbols_dir = module_root.join("symbols");
        fs::create_dir_all(&symbols_dir)?;
        let symbols_path = symbols_dir.join(format!("{build_name}-symbols.zip"));
        write_zip_from_directory(
            &module_root.join("output/unstripped").join(profile.name()),
            &symbols_path,
            false,
        )?;
        println!("zygisk(package): {}", symbols_path.display());
    }
    Ok(zip_path)
}

fn latest_zygisk_zip(root: &Path, profile: ZygiskBuildProfile) -> Result<PathBuf> {
    let release_dir = zygisk_dir(root).join("release");
    let suffix = format!("-{}.zip", profile.name());
    fs::read_dir(&release_dir)
        .with_context(|| format!("could not list {}", release_dir.display()))?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .file_name()
                    .is_some_and(|name| name.to_string_lossy().starts_with("WeKit-"))
                && path
                    .file_name()
                    .is_some_and(|name| name.to_string_lossy().ends_with(&suffix))
        })
        .max_by_key(|path| file_modified(path))
        .with_context(|| {
            format!(
                "no {} Zygisk ZIP found in {}",
                profile.name(),
                release_dir.display()
            )
        })
}

fn validate_root_manager(root: Option<&str>) -> Result<Option<&str>> {
    match root {
        None => Ok(None),
        Some("magisk" | "ksu" | "kernelsu" | "ap" | "apatch") => Ok(root),
        Some(value) => bail!("unsupported root manager `{value}`; use magisk, ksu, or ap"),
    }
}

fn run_adb(root: &Path, device: Option<&str>, args: &[String]) -> Result<()> {
    let mut adb_args = Vec::new();
    if let Some(device) = device {
        adb_args.push("-s".to_owned());
        adb_args.push(device.to_owned());
    }
    adb_args.extend(args.iter().cloned());
    run_cmd_owned("adb", &adb_args, root)
}

fn install_zygisk_zip(
    root: &Path,
    zip_path: &Path,
    device: Option<&str>,
    manager: Option<&str>,
) -> Result<()> {
    let manager = validate_root_manager(manager)?;
    let zip_name = zip_path
        .file_name()
        .and_then(|name| name.to_str())
        .context("Zygisk ZIP name must be UTF-8")?;
    let remote_zip = format!("/data/local/tmp/{zip_name}");
    let remote_script = "/data/local/tmp/install_wekit_zygisk.sh";
    let script = zygisk_dir(root).join("scripts/install_module.sh");
    run_adb(
        root,
        device,
        &[
            "push".to_owned(),
            zip_path.display().to_string(),
            remote_zip.clone(),
        ],
    )?;
    run_adb(
        root,
        device,
        &[
            "push".to_owned(),
            script.display().to_string(),
            remote_script.to_owned(),
        ],
    )?;

    let manager_arg = manager
        .map(|manager| format!(" {manager}"))
        .unwrap_or_default();
    let install_command = format!("sh {remote_script} {remote_zip}{manager_arg}");
    let install_result = run_adb(
        root,
        device,
        &[
            "shell".to_owned(),
            "su".to_owned(),
            "-c".to_owned(),
            install_command,
        ],
    );
    let cleanup_result = run_adb(
        root,
        device,
        &[
            "shell".to_owned(),
            "su".to_owned(),
            "-c".to_owned(),
            format!("rm -f {remote_script} {remote_zip}"),
        ],
    );
    install_result?;
    cleanup_result
}

fn task_zygisk_flash(args: &ZygiskFlashArgs) -> Result<()> {
    let root = workspace_root();
    let profile = ZygiskBuildProfile::from_release(args.build.release);
    let zip_path = if args.skip_build {
        latest_zygisk_zip(&root, profile)?
    } else {
        task_zygisk_build(&args.build)?
    };
    install_zygisk_zip(
        &root,
        &zip_path,
        args.device.as_deref(),
        args.root.as_deref(),
    )?;
    if args.reboot {
        run_adb(
            &root,
            args.device.as_deref(),
            &[
                "shell".to_owned(),
                "su".to_owned(),
                "-c".to_owned(),
                "svc power reboot || reboot".to_owned(),
            ],
        )?;
    }
    Ok(())
}

fn remove_dir_if_exists(path: &Path) -> Result<()> {
    if path.exists() {
        fs::remove_dir_all(path).with_context(|| format!("could not remove {}", path.display()))?;
    }
    Ok(())
}

fn task_zygisk_clean(args: &ZygiskCleanArgs) -> Result<()> {
    let root = workspace_root();
    let abis = resolve_zygisk_abis(&args.abis)?;
    let profiles = match args.profile {
        ZygiskCleanProfile::Debug => vec![ZygiskBuildProfile::Debug],
        ZygiskCleanProfile::Release => vec![ZygiskBuildProfile::Release],
        ZygiskCleanProfile::All => vec![ZygiskBuildProfile::Debug, ZygiskBuildProfile::Release],
    };
    for profile in profiles {
        for abi in &abis {
            for path in [
                zygisk_build_dir(&root, profile, abi),
                zygisk_native_output_dir(&root, profile, abi),
                zygisk_symbols_dir(&root, profile, abi),
            ] {
                if path.exists() {
                    println!("zygisk(clean): {}", path.display());
                    remove_dir_if_exists(&path)?;
                }
            }
        }
    }
    Ok(())
}

// ── Task: check / clippy ───────────────────────────────────────────────────────

fn task_cargo_cmd(subcommand: &str, abi_args: &[String], extra_args: &[&str]) -> Result<()> {
    let root = workspace_root();
    let native_dir = native_crate_dir(&root);
    let abis = resolve_abis(abi_args)?;

    for spec in &abis {
        println!(
            "{subcommand}: {} ({})",
            spec.android_name, spec.cargo_triple
        );

        let mut cmd_args = vec![subcommand, "--target", spec.cargo_triple];
        cmd_args.extend_from_slice(extra_args);
        run_cargo(&cmd_args, &native_dir)?;
    }

    Ok(())
}

// ── Process runners ────────────────────────────────────────────────────────────

fn run_cargo(args: &[&str], cwd: &Path) -> Result<()> {
    // Prefer the same `cargo` that invoked xtask (set by Cargo as $CARGO).
    let cargo = env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    run_cmd(&cargo, args, cwd)
}

fn run_gradlew(args: &[&str], cwd: &Path) -> Result<()> {
    let gradlew = if cfg!(target_os = "windows") {
        "gradlew.bat"
    } else {
        "./gradlew"
    };
    run_cmd(gradlew, args, cwd)
}

fn run_cmd_owned(program: &str, args: &[String], cwd: &Path) -> Result<()> {
    let refs = args.iter().map(String::as_str).collect::<Vec<_>>();
    run_cmd(program, &refs, cwd)
}

fn run_cmd(program: &str, args: &[&str], cwd: &Path) -> Result<()> {
    let status = Command::new(program)
        .args(args)
        .current_dir(cwd)
        .status()
        .with_context(|| format!("failed to spawn `{program} {}`", args.join(" ")))?;

    if !status.success() {
        bail!("`{program} {}` exited with {status}", args.join(" "));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const VERSION_CATALOG_PATH: &str = "gradle/libs.versions.toml";

    #[test]
    fn parses_zygisk_values_from_gradle_version_catalog() {
        let config = parse_zygisk_build_config(
            "[versions]\nndk = \"30.0.14904198\"\nminSdk = \"28\"\n",
            Path::new(VERSION_CATALOG_PATH),
        )
        .unwrap();

        assert_eq!(config.ndk_version, "30.0.14904198");
        assert_eq!(config.platform, "android-28");
    }

    #[test]
    fn rejects_missing_zygisk_values() {
        for catalog in [
            "[versions]\nminSdk = \"28\"\n",
            "[versions]\nndk = \"30.0.14904198\"\n",
        ] {
            assert!(parse_zygisk_build_config(catalog, Path::new(VERSION_CATALOG_PATH)).is_err());
        }
    }

    #[test]
    fn rejects_empty_ndk_version() {
        let error = parse_zygisk_build_config(
            "[versions]\nndk = \"  \"\nminSdk = \"28\"\n",
            Path::new(VERSION_CATALOG_PATH),
        )
        .err()
        .unwrap();

        assert!(error.to_string().contains("[versions].ndk"));
    }

    #[test]
    fn rejects_invalid_min_sdk() {
        for min_sdk in ["0", "android-28"] {
            let catalog = format!("[versions]\nndk = \"30.0.14904198\"\nminSdk = \"{min_sdk}\"\n");
            let error =
                parse_zygisk_build_config(&catalog, Path::new(VERSION_CATALOG_PATH)).unwrap_err();

            assert!(error.to_string().contains("[versions].minSdk"));
        }
    }
}
