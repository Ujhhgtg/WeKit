//! WeKit xtask — build automation for the WeKit Android project.
//!
//! Usage: cargo xtask <COMMAND>
//!
//!   configure            Regenerate wekit-native/.cargo/config.toml from the local NDK.
//!   build [OPTIONS]      Build the project (default: full Android debug build via Gradle).
//!   cloudflared-build    Build the embedded cloudflared bridge for Android.
//!   zygisk <COMMAND>     Build, package, and install the Zygisk module.
//!   check [OPTIONS]      Run `cargo check` on the native library.
//!   clippy [OPTIONS]     Run `cargo clippy` on the native library.
//!   dex-test [OPTIONS]   Resolve WeKit DexKit targets against desktop APKs.
//!   dex-test-ci          Prepare APK sources and mutable Dex-Test Release assets.
//!   i18n-check           Validate the Android English and Chinese resource catalogs.
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

mod dex_test;
mod dex_test_ci;
mod i18n_check;

// ── Project constants (mirror app/build.gradle.kts / libs.versions.toml) ──────

/// Matches `minSdk` in libs.versions.toml.
const MIN_SDK: u32 = 28;

/// Minimum NDK major version accepted by `configure`; the pinned NDK must be at least this new.
const MIN_NDK_MAJOR: u32 = 29;

const CLOUDFLARED_COMMIT: &str = "8679787525edc8575b2948a7c4a50b6292c6d426";

// ── ABI table ─────────────────────────────────────────────────────────────────

struct AbiSpec {
    /// Directory name in `jniLibs/` and Android ABI filter.
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

#[derive(Debug, Eq, PartialEq)]
struct GoAndroidTarget {
    arch: &'static str,
    arm: Option<&'static str>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ApkNativeBuildStep {
    Configure,
    Cloudflared,
    WeKitNative,
}

const APK_NATIVE_BUILD_STEPS: &[ApkNativeBuildStep] = &[
    ApkNativeBuildStep::Configure,
    ApkNativeBuildStep::Cloudflared,
    ApkNativeBuildStep::WeKitNative,
];

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
        android_name: "armeabi-v7a",
        cargo_triple: "armv7-linux-androideabi",
        clang_prefix: "armv7a-linux-androideabi",
        // Kept with hyphens to match ConfigureCargoTask.kt's template verbatim.
        env_key: "armv7-linux-androideabi",
    },
];

/// ABIs included in the universal APK (the default build targets).
static RELEASE_ABIS: &[&str] = &["arm64-v8a", "armeabi-v7a"];

const ZYGISK_CARGO_PACKAGE: &str = "wekit-zygisk";
const ZYGISK_MODULE_ID: &str = "wekit_zygisk";
const ZYGISK_MODULE_NAME: &str = "WeKit";

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

    /// Build the pinned cloudflared C bridge and copy it to jniLibs.
    CloudflaredBuild(NativeArgs),

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

    /// Run DexKit resolvers against one or more WeChat APKs on this Linux desktop.
    DexTest(dex_test::DexTestArgs),

    /// Prepare inputs and outputs used by the cloud Dex resolution CI jobs.
    DexTestCi(dex_test_ci::DexTestCiArgs),

    /// Validate the Android English and Chinese resource catalogs.
    I18nCheck,
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

    /// Explicitly install the debug build (default).
    #[arg(long, conflicts_with = "release")]
    debug: bool,

    /// Install the release build instead of debug.
    #[arg(long, conflicts_with = "debug")]
    release: bool,
}

impl RunArgs {
    fn is_release(&self) -> bool {
        match (self.debug, self.release) {
            (false, false) | (true, false) => false,
            (false, true) => true,
            (true, true) => unreachable!("clap rejects --debug with --release"),
        }
    }
}

#[derive(Args)]
struct ZygiskArgs {
    #[command(subcommand)]
    command: ZygiskCmd,
}

#[derive(Subcommand)]
enum ZygiskCmd {
    /// Build the installable Zygisk ZIP. Defaults to debug APKs and release Zygisk artifacts.
    Build(ZygiskBuildArgs),

    /// Build or reuse a Zygisk ZIP, then install it through a connected device's root manager.
    Flash(ZygiskFlashArgs),

    /// Build only the Zygisk native loader(s), without an APK or module ZIP.
    Native(ZygiskNativeArgs),

    /// Remove Zygisk native output and unstripped symbol directories.
    Clean(ZygiskCleanArgs),
}

#[derive(Args)]
struct ZygiskProfileArgs {
    /// Use the Debug Zygisk profile.
    #[arg(long, conflicts_with = "release")]
    debug: bool,

    /// Use the optimized release Zygisk profile (default).
    #[arg(long, conflicts_with = "debug")]
    release: bool,
}

#[derive(Args)]
struct ZygiskApkProfileArgs {
    /// Build or select debug APKs (default).
    #[arg(long, conflicts_with = "apk_release")]
    apk_debug: bool,

    /// Build or select release APKs.
    #[arg(long, conflicts_with = "apk_debug")]
    apk_release: bool,
}

#[derive(Args)]
struct ZygiskNativeArgs {
    /// Target ABI(s). May be repeated. Defaults to arm64-v8a and armeabi-v7a.
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,

    #[command(flatten)]
    profile: ZygiskProfileArgs,

    /// Android NDK version under ANDROID_HOME/ndk/. Defaults to gradle/libs.versions.toml.
    #[arg(long, value_name = "VERSION")]
    ndk: Option<String>,

    /// Delete each selected ABI's native output and unstripped symbols before building.
    #[arg(long)]
    force: bool,
}

#[derive(Args)]
struct ZygiskBuildArgs {
    #[command(flatten)]
    apk_profile: ZygiskApkProfileArgs,

    #[command(flatten)]
    zygisk_profile: ZygiskProfileArgs,

    /// Delete Zygisk native output and unstripped symbols before building native loaders.
    #[arg(long)]
    force: bool,

    /// Android NDK version under ANDROID_HOME/ndk/. Defaults to gradle/libs.versions.toml.
    #[arg(long, value_name = "VERSION")]
    ndk: Option<String>,

    /// Universal APK to embed instead of using automatic APK discovery.
    #[arg(long = "apk", value_name = "APK")]
    apk: Option<PathBuf>,

    /// Reuse APK outputs for the selected APK profile instead of running Gradle.
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
    /// Valid values: arm64-v8a, armeabi-v7a
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
        Cmd::CloudflaredBuild(args) => task_build_cloudflared(&args.abis)?,
        Cmd::Run(args) => task_run(args)?,
        Cmd::Zygisk(args) => task_zygisk(args)?,
        Cmd::Check(args) => task_cargo_cmd("check", &args.abis, &[])?,
        Cmd::Clippy(args) => task_cargo_cmd("clippy", &args.abis, &["--", "-D", "warnings"])?,
        Cmd::DexTest(args) => dex_test::task_dex_test(args)?,
        Cmd::DexTestCi(args) => dex_test_ci::task_dex_test_ci(args)?,
        Cmd::I18nCheck => i18n_check::check_repository(&workspace_root())?,
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

fn cloudflared_bridge_dir(root: &Path) -> PathBuf {
    root.join("app/src/main/go/wekit-cloudflared")
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

fn go_android_target(spec: &AbiSpec) -> GoAndroidTarget {
    match spec.android_name {
        "arm64-v8a" => GoAndroidTarget {
            arch: "arm64",
            arm: None,
        },
        "armeabi-v7a" => GoAndroidTarget {
            arch: "arm",
            arm: Some("7"),
        },
        name => unreachable!("unsupported Android ABI {name}"),
    }
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
    if let Ok(home) = env::var("ANDROID_HOME")
        && !home.is_empty()
    {
        return Ok(home);
    }

    if let Ok(home) = env::var("ANDROID_SDK_ROOT")
        && !home.is_empty()
    {
        return Ok(home);
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

/// Return the `bin/` path inside the *pinned* NDK's prebuilt llvm dir.
///
/// The version comes from `[versions].ndk` in `gradle/libs.versions.toml` — the same value AGP
/// consumes as `ndkVersion` and the Zygisk strip step uses. Picking the highest installed NDK
/// instead would silently compile and link the native lib with a toolchain nothing else uses.
fn find_ndk_bin_dir(root: &Path) -> Result<String> {
    let ndk_version = pinned_ndk_version(root)?;
    let major = ndk_version
        .split('.')
        .next()
        .and_then(|part| part.parse::<u32>().ok())
        .unwrap_or(0);
    if major < MIN_NDK_MAJOR {
        bail!(
            "pinned NDK {ndk_version} is below the required major version {MIN_NDK_MAJOR}; \
             bump [versions].ndk in gradle/libs.versions.toml"
        );
    }

    let ndk_dir = pinned_ndk_dir(root, None)?;
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
    let ndk_bin_dir = find_ndk_bin_dir(&root)?;

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

    // Write for wekit-native
    let config_path = native_crate_dir(&root).join(".cargo/config.toml");
    fs::create_dir_all(config_path.parent().unwrap())?;
    fs::write(&config_path, &out)
        .with_context(|| format!("failed to write {}", config_path.display()))?;
    println!("configure: wrote {}", config_path.display());

    // Write for wekit-zygisk (same linker config + extra linker flags for symbol visibility)
    let zygisk_config_path = zygisk_dir(&root).join("native/.cargo/config.toml");
    fs::create_dir_all(zygisk_config_path.parent().unwrap())?;
    fs::write(&zygisk_config_path, &out)
        .with_context(|| format!("failed to write {}", zygisk_config_path.display()))?;
    println!("configure: wrote {}", zygisk_config_path.display());

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
    task_prepare_apk_native_inputs(&args.native.abis)?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("assemble", args.flavor.as_ref(), args.release);
    println!("build: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Install the app on a connected device or emulator via the Gradle wrapper (native lib compiled first).
fn task_run(args: RunArgs) -> Result<()> {
    task_prepare_apk_native_inputs(&[])?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("install", Some(&args.flavor), args.is_release());
    println!("run: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

fn apk_native_build_steps() -> &'static [ApkNativeBuildStep] {
    APK_NATIVE_BUILD_STEPS
}

fn task_prepare_apk_native_inputs(abi_args: &[String]) -> Result<()> {
    for step in apk_native_build_steps() {
        match step {
            ApkNativeBuildStep::Configure => task_configure()?,
            ApkNativeBuildStep::Cloudflared => task_build_cloudflared(abi_args)?,
            ApkNativeBuildStep::WeKitNative => task_build_native(abi_args)?,
        }
    }
    Ok(())
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

fn verify_cloudflared_pin(root: &Path) -> Result<()> {
    let source = root.join("third_party/cloudflared");
    if !source.join("go.mod").is_file() {
        bail!(
            "cloudflared source is not initialized at {}; run `git submodule update --init --recursive`",
            source.display()
        );
    }
    verify_cloudflared_checkout(&source, CLOUDFLARED_COMMIT)
}

fn cloudflared_git_output(source: &Path, args: &[&str]) -> Result<String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(source)
        .output()
        .with_context(|| {
            format!(
                "failed to inspect cloudflared source at {}",
                source.display()
            )
        })?;
    if !output.status.success() {
        bail!("`git {}` failed in {}", args.join(" "), source.display());
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
}

fn verify_cloudflared_checkout(source: &Path, expected_commit: &str) -> Result<()> {
    let actual = cloudflared_git_output(source, &["rev-parse", "HEAD"])?;
    if actual != expected_commit {
        bail!("cloudflared source revision is {actual}, expected pinned {expected_commit}");
    }

    let changes = cloudflared_git_output(
        source,
        &["status", "--porcelain=v1", "--untracked-files=all"],
    )?;
    if !changes.is_empty() {
        bail!(
            "cloudflared source checkout is not clean; remove tracked or non-ignored untracked changes before building:\n{changes}"
        );
    }
    Ok(())
}

fn task_build_cloudflared(abi_args: &[String]) -> Result<()> {
    let root = workspace_root();
    verify_cloudflared_pin(&root)?;
    let bridge_dir = cloudflared_bridge_dir(&root);
    let ndk_bin_dir = PathBuf::from(find_ndk_bin_dir(&root)?);
    let abis = resolve_abis(abi_args)?;

    for spec in abis {
        let target = go_android_target(spec);
        let cc = ndk_bin_dir.join(format!("{}{MIN_SDK}-clang", spec.clang_prefix));
        if !cc.is_file() {
            bail!("Android C compiler not found: {}", cc.display());
        }

        let build_dir = root.join("target/cloudflared").join(spec.android_name);
        fs::create_dir_all(&build_dir)
            .with_context(|| format!("could not create {}", build_dir.display()))?;
        let so_src = build_dir.join("libwekit_cloudflared.so");
        println!(
            "cloudflared-build: {} (android/{})",
            spec.android_name, target.arch
        );
        let mut command = Command::new("go");
        command
            .args([
                "build",
                "-mod=readonly",
                "-buildmode=c-shared",
                "-trimpath",
                "-ldflags=-s -w",
                "-o",
            ])
            .arg(&so_src)
            .arg(".")
            .current_dir(&bridge_dir)
            .env("CGO_ENABLED", "1")
            .env("GOOS", "android")
            .env("GOARCH", target.arch)
            .env("CC", &cc);
        if let Some(goarm) = target.arm {
            command.env("GOARM", goarm);
        }
        let status = command.status().with_context(|| {
            format!(
                "failed to spawn Go cloudflared build for {}",
                spec.android_name
            )
        })?;
        if !status.success() {
            bail!(
                "Go cloudflared build for {} exited with {status}",
                spec.android_name
            );
        }

        let so_dst_dir = jni_libs_dir(&root).join(spec.android_name);
        fs::create_dir_all(&so_dst_dir)
            .with_context(|| format!("could not create {}", so_dst_dir.display()))?;
        let so_dst = so_dst_dir.join("libwekit_cloudflared.so");
        fs::copy(&so_src, &so_dst).with_context(|| {
            format!("could not copy {} → {}", so_src.display(), so_dst.display())
        })?;
        println!(
            "cloudflared-build:  {} → {}",
            so_src.display(),
            so_dst.display()
        );
    }
    Ok(())
}

// ── Task: zygisk ──────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ZygiskBuildProfile {
    Debug,
    Release,
}

impl ZygiskBuildProfile {
    fn name(self) -> &'static str {
        match self {
            Self::Debug => "debug",
            Self::Release => "release",
        }
    }
}

impl ZygiskProfileArgs {
    fn resolve(&self) -> ZygiskBuildProfile {
        match (self.debug, self.release) {
            (true, false) => ZygiskBuildProfile::Debug,
            (false, _) => ZygiskBuildProfile::Release,
            (true, true) => unreachable!("clap rejects --debug with --release"),
        }
    }
}

impl ZygiskApkProfileArgs {
    fn resolve(&self) -> ZygiskBuildProfile {
        match (self.apk_debug, self.apk_release) {
            (false, true) => ZygiskBuildProfile::Release,
            (_, false) => ZygiskBuildProfile::Debug,
            (true, true) => unreachable!("clap rejects --apk-debug with --apk-release"),
        }
    }
}

fn zygisk_version_name(commit_hash: &str, profile: ZygiskBuildProfile) -> String {
    format!("git+{commit_hash}-{}", profile.name())
}

#[derive(Deserialize)]
struct GradleVersionCatalog {
    versions: GradleVersions,
}

#[derive(Deserialize)]
struct GradleVersions {
    ndk: String,
    #[serde(default)]
    dexkit: Option<String>,
}

fn task_zygisk(args: ZygiskArgs) -> Result<()> {
    match args.command {
        ZygiskCmd::Build(args) => {
            task_zygisk_build(&args)?;
        }
        ZygiskCmd::Flash(args) => task_zygisk_flash(&args)?,
        ZygiskCmd::Native(args) => task_zygisk_native(&args)?,
        ZygiskCmd::Clean(args) => task_zygisk_clean(&args)?,
    }
    Ok(())
}

fn parse_pinned_ndk_version(text: &str, path: &Path) -> Result<String> {
    let catalog: GradleVersionCatalog =
        toml::from_str(text).with_context(|| format!("could not parse {}", path.display()))?;
    let ndk_version = catalog.versions.ndk.trim();
    if ndk_version.is_empty() {
        bail!("[versions].ndk in {} must not be empty", path.display());
    }
    Ok(ndk_version.to_owned())
}

fn pinned_ndk_version(root: &Path) -> Result<String> {
    let path = root.join("gradle/libs.versions.toml");
    let text =
        fs::read_to_string(&path).with_context(|| format!("could not read {}", path.display()))?;
    parse_pinned_ndk_version(&text, &path)
}

/// Resolve an NDK install dir, defaulting to the version pinned in `gradle/libs.versions.toml`.
fn pinned_ndk_dir(root: &Path, requested_version: Option<&str>) -> Result<PathBuf> {
    let configured_version = pinned_ndk_version(root)?;
    let ndk_version = requested_version.unwrap_or(&configured_version);
    if ndk_version.is_empty() {
        bail!("NDK version must not be empty");
    }
    let android_home = find_android_home(root)?;
    let ndk_dir = PathBuf::from(android_home).join("ndk").join(ndk_version);
    if !ndk_dir.is_dir() {
        bail!(
            "NDK {ndk_version} (pinned in gradle/libs.versions.toml) not found: {}",
            ndk_dir.display()
        );
    }
    Ok(ndk_dir)
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

fn build_zygisk_native(
    root: &Path,
    profile: ZygiskBuildProfile,
    requested_ndk: Option<&str>,
    abi_names: &[String],
    force: bool,
    save_symbols: bool,
) -> Result<()> {
    let abis = resolve_zygisk_abis(abi_names)?;
    let ndk_dir = pinned_ndk_dir(root, requested_ndk)?;
    task_configure()?;

    for abi in abis {
        let output_dir = zygisk_native_output_dir(root, profile, abi);
        let symbols_dir = zygisk_symbols_dir(root, profile, abi);
        if force {
            remove_dir_if_exists(&output_dir)?;
            remove_dir_if_exists(&symbols_dir)?;
        }
        build_zygisk_native_rust(root, profile, abi, &ndk_dir, save_symbols)?;
    }
    Ok(())
}

fn build_zygisk_native_rust(
    root: &Path,
    profile: ZygiskBuildProfile,
    abi: &ZygiskAbiSpec,
    ndk_dir: &Path,
    save_symbols: bool,
) -> Result<()> {
    let zygisk_native = zygisk_dir(root).join("native");
    let cargo_triple = ABI_TABLE
        .iter()
        .find(|a| a.android_name == abi.android_name)
        .map(|a| a.cargo_triple)
        .with_context(|| format!("unknown ABI {}", abi.android_name))?;

    let mut args = vec![
        "build".to_owned(),
        "-p".to_owned(),
        ZYGISK_CARGO_PACKAGE.to_owned(),
        "--target".to_owned(),
        cargo_triple.to_owned(),
    ];
    if matches!(profile, ZygiskBuildProfile::Release) {
        args.push("--release".to_owned());
        if save_symbols {
            args.extend([
                "--config".to_owned(),
                "profile.release.strip=\"none\"".to_owned(),
            ]);
        }
    }

    println!("zygisk(rust): {} ({})", abi.android_name, profile.name());
    run_cargo(
        &args.iter().map(String::as_str).collect::<Vec<_>>(),
        &zygisk_native,
    )?;

    let profile_dir = profile.name();
    let src_so = root
        .join("target")
        .join(cargo_triple)
        .join(profile_dir)
        .join(format!("lib{ZYGISK_MODULE_ID}.so"));

    let sym_dir = zygisk_symbols_dir(root, profile, abi);
    remove_dir_if_exists(&sym_dir)?;
    if save_symbols {
        fs::create_dir_all(&sym_dir)?;
        let sym_so = sym_dir.join(format!("lib{ZYGISK_MODULE_ID}.so"));
        fs::copy(&src_so, &sym_so)
            .with_context(|| format!("copy unstripped: {}", src_so.display()))?;
    }

    // Strip into output/native
    let out_dir = zygisk_native_output_dir(root, profile, abi);
    remove_dir_if_exists(&out_dir)?;
    fs::create_dir_all(&out_dir)?;
    let out_so = out_dir.join(format!("lib{ZYGISK_MODULE_ID}.so"));
    fs::copy(&src_so, &out_so)?;

    let strip = ndk_dir
        .join("toolchains/llvm/prebuilt")
        .join(host_prebuilt_tag()?)
        .join("bin/llvm-strip");
    run_cmd_owned(
        strip.to_str().unwrap(),
        &[
            "--strip-all".to_owned(),
            out_so.to_str().unwrap().to_owned(),
        ],
        root,
    )?;

    if !out_so.is_file() {
        bail!("Rust zygisk build did not produce {}", out_so.display());
    }
    println!("zygisk(strip): {} → {}", src_so.display(), out_so.display());
    Ok(())
}

fn task_zygisk_native(args: &ZygiskNativeArgs) -> Result<()> {
    let root = workspace_root();
    build_zygisk_native(
        &root,
        args.profile.resolve(),
        args.ndk.as_deref(),
        &args.abis,
        args.force,
        false,
    )
}

fn task_zygisk_build(args: &ZygiskBuildArgs) -> Result<PathBuf> {
    let root = workspace_root();
    let apk_profile = args.apk_profile.resolve();
    let zygisk_profile = args.zygisk_profile.resolve();
    if !args.skip_apk_build {
        // Gradle does NOT compile wekit-native — the `configureCargo` / native-build tasks were
        // removed from the build script when the toolchain moved into xtask, so `assemble*` only
        // packages whatever prebuilt .so already sits in app/src/main/jniLibs. `task_build_android`
        // and `task_run` account for that; this path used to not, which meant `./x zygisk build`
        // and `./x zygisk flash` silently shipped a stale libwekit_native.so no matter how many
        // times the Rust sources changed.
        //
        // Both ABIs unconditionally: the module payload requires a universal APK (see
        // `resolve_zygisk_payload_apk`), so a single-ABI build would be rejected later anyway.
        task_prepare_apk_native_inputs(&[])?;

        let gradle_task = gradle_variant_task(
            "assemble",
            Some(&Flavor::Standard),
            matches!(apk_profile, ZygiskBuildProfile::Release),
        );
        println!("zygisk(apk): ./gradlew {gradle_task}");
        run_gradlew(&[&gradle_task], &root)?;
    }

    build_zygisk_native(
        &root,
        zygisk_profile,
        args.ndk.as_deref(),
        &[],
        args.force,
        args.save_symbols,
    )?;
    package_zygisk_module(
        &root,
        zygisk_profile,
        apk_profile,
        args.apk.as_deref(),
        args.save_symbols,
    )
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

fn resolve_zygisk_payload_apk(
    root: &Path,
    profile: ZygiskBuildProfile,
    provided: Option<&Path>,
) -> Result<PathBuf> {
    let candidates = if let Some(path) = provided {
        vec![
            path.canonicalize()
                .with_context(|| format!("WeKit APK does not exist: {}", path.display()))?,
        ]
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

    let mut resolved: Option<(bool, std::time::SystemTime, PathBuf)> = None;
    for candidate in candidates {
        if !candidate.is_file() {
            bail!("WeKit APK does not exist: {}", candidate.display());
        }
        let abis = apk_abis(&candidate)?;
        if !ZYGISK_ABIS
            .iter()
            .all(|abi| abis.contains(&abi.android_name))
        {
            continue;
        }
        let is_standard = candidate
            .components()
            .any(|component| component.as_os_str() == "standard");
        let modified = file_modified(&candidate);
        let use_candidate =
            resolved
                .as_ref()
                .is_none_or(|(current_standard, current_modified, _)| {
                    provided.is_none()
                        && (is_standard, modified) > (*current_standard, *current_modified)
                });
        if use_candidate {
            resolved = Some((is_standard, modified, candidate));
        }
    }

    resolved.map(|(_, _, path)| path).with_context(|| {
        let source = if provided.is_some() {
            "the provided --apk"
        } else {
            "app/build/outputs/apk"
        };
        format!(
            "no universal WeKit APK containing {} found in {source}",
            ZYGISK_ABIS
                .iter()
                .map(|abi| abi.android_name)
                .collect::<Vec<_>>()
                .join(" and ")
        )
    })
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

fn export_zygisk_payload(apk: &Path, payload_dir: &Path) -> Result<()> {
    fs::create_dir_all(payload_dir)?;
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

    let apk_destination = payload_dir.join("wekit.apk");
    fs::copy(apk, &apk_destination).with_context(|| {
        format!(
            "could not copy payload {} to {}",
            apk.display(),
            apk_destination.display()
        )
    })?;
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

/// Zip `source` into `destination`, atomically.
///
/// The archive is streamed into a sibling `.partial` file and renamed onto `destination` only
/// once the write fully succeeded. Writing straight to the final name would leave a truncated
/// `.zip` behind on any mid-write failure, and `latest_zygisk_zip` picks by newest mtime — so the
/// next `./x zygisk flash --skip-build` would happily flash the corrupt archive.
fn write_zip_from_directory(source: &Path, destination: &Path, write_hashes: bool) -> Result<()> {
    let file_name = destination.file_name().with_context(|| {
        format!(
            "archive destination has no file name: {}",
            destination.display()
        )
    })?;
    let mut temp_name = file_name.to_owned();
    temp_name.push(".partial");
    let temp_path = destination.with_file_name(temp_name);

    if let Err(error) = stream_zip_from_directory(source, &temp_path, write_hashes) {
        let _ = fs::remove_file(&temp_path);
        return Err(error);
    }

    if let Err(error) = fs::rename(&temp_path, destination) {
        let _ = fs::remove_file(&temp_path);
        return Err(error).with_context(|| {
            format!(
                "could not move {} onto {}",
                temp_path.display(),
                destination.display()
            )
        });
    }

    Ok(())
}

fn stream_zip_from_directory(source: &Path, destination: &Path, write_hashes: bool) -> Result<()> {
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
    apk_profile: ZygiskBuildProfile,
    explicit_apk: Option<&Path>,
    save_symbols: bool,
) -> Result<PathBuf> {
    let module_root = zygisk_dir(root);
    let module_dir = module_root.join("output/module").join(profile.name());
    remove_dir_if_exists(&module_dir)?;
    fs::create_dir_all(&module_dir)?;
    copy_tree(&module_root.join("template"), &module_dir)?;
    fs::copy(module_root.join("README.md"), module_dir.join("README.md"))?;
    normalize_crlf(&module_dir)?;

    let version_code = git_output(root, &["rev-list", "--count", "HEAD"])?;
    // Fixed width: bare `--short` widens as history grows and varies across git versions, and the
    // hash ends up in versionName, module.prop and the ZIP filename.
    let commit_hash = git_output(root, &["rev-parse", "--short=8", "HEAD"])?;
    let version_name = zygisk_version_name(&commit_hash, profile);
    expand_template(
        &module_dir.join("module.prop"),
        &[
            ("moduleId", ZYGISK_MODULE_ID.to_owned()),
            ("moduleName", ZYGISK_MODULE_NAME.to_owned()),
            ("versionName", version_name.clone()),
            ("versionCode", version_code.clone()),
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
    let source = resolve_zygisk_payload_apk(root, apk_profile, explicit_apk)?;
    export_zygisk_payload(&source, &payload_dir)?;
    println!(
        "zygisk(package): embedded {} -> payload/wekit.apk (DEX extracted during installation)",
        source.display()
    );

    let build_name = format!("{ZYGISK_MODULE_NAME}-{version_code}-{version_name}");
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
    let profile = args.build.zygisk_profile.resolve();
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
    use std::sync::atomic::{AtomicU64, Ordering};

    const VERSION_CATALOG_PATH: &str = "gradle/libs.versions.toml";

    fn parse_zygisk_build_args(extra: &[&str]) -> ZygiskBuildArgs {
        let mut argv = vec!["xtask", "zygisk", "build"];
        argv.extend_from_slice(extra);
        match Cli::try_parse_from(argv).unwrap().command {
            Cmd::Zygisk(ZygiskArgs {
                command: ZygiskCmd::Build(args),
            }) => args,
            _ => unreachable!(),
        }
    }

    fn parse_run_args(extra: &[&str]) -> RunArgs {
        let mut argv = vec!["xtask", "run"];
        argv.extend_from_slice(extra);
        match Cli::try_parse_from(argv).unwrap().command {
            Cmd::Run(args) => args,
            _ => unreachable!(),
        }
    }

    struct TestGitRepo {
        path: PathBuf,
        head: String,
    }

    impl Drop for TestGitRepo {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.path).unwrap();
        }
    }

    fn test_git_repo() -> TestGitRepo {
        static NEXT_ID: AtomicU64 = AtomicU64::new(0);
        let path = env::temp_dir().join(format!(
            "wekit-cloudflared-pin-test-{}-{}",
            std::process::id(),
            NEXT_ID.fetch_add(1, Ordering::Relaxed),
        ));
        fs::create_dir(&path).unwrap();
        for args in [
            vec!["init", "-q"],
            vec!["config", "user.name", "WeKit Test"],
            vec!["config", "user.email", "wekit-test@example.invalid"],
        ] {
            assert!(
                Command::new("git")
                    .args(args)
                    .current_dir(&path)
                    .status()
                    .unwrap()
                    .success()
            );
        }
        fs::write(path.join("go.mod"), "module example.invalid/pinned\n").unwrap();
        fs::write(path.join(".gitignore"), "ignored-build/\n").unwrap();
        assert!(
            Command::new("git")
                .args(["add", "go.mod", ".gitignore"])
                .current_dir(&path)
                .status()
                .unwrap()
                .success()
        );
        assert!(
            Command::new("git")
                .args(["commit", "-q", "-m", "pin"])
                .current_dir(&path)
                .status()
                .unwrap()
                .success()
        );
        let head = String::from_utf8(
            Command::new("git")
                .args(["rev-parse", "HEAD"])
                .current_dir(&path)
                .output()
                .unwrap()
                .stdout,
        )
        .unwrap()
        .trim()
        .to_owned();
        TestGitRepo { path, head }
    }

    #[test]
    fn cloudflared_build_accepts_both_android_abis() {
        let command = Cli::try_parse_from([
            "xtask",
            "cloudflared-build",
            "--abi",
            "arm64-v8a",
            "--abi",
            "armeabi-v7a",
        ])
        .unwrap()
        .command;

        let Cmd::CloudflaredBuild(args) = command else {
            panic!("expected cloudflared-build command");
        };
        assert_eq!(args.abis, ["arm64-v8a", "armeabi-v7a"]);
    }

    #[test]
    fn cloudflared_build_maps_android_abis_to_go_targets() {
        let arm64 = go_android_target(&ABI_TABLE[0]);
        assert_eq!(arm64.arch, "arm64");
        assert_eq!(arm64.arm, None);

        let arm32 = go_android_target(&ABI_TABLE[1]);
        assert_eq!(arm32.arch, "arm");
        assert_eq!(arm32.arm, Some("7"));
    }

    #[test]
    fn apk_native_build_plan_refreshes_cloudflared_before_wekit_native() {
        assert_eq!(
            apk_native_build_steps(),
            &[
                ApkNativeBuildStep::Configure,
                ApkNativeBuildStep::Cloudflared,
                ApkNativeBuildStep::WeKitNative,
            ],
        );
    }

    #[test]
    fn cloudflared_checkout_accepts_clean_pinned_revision() {
        let repo = test_git_repo();
        verify_cloudflared_checkout(&repo.path, &repo.head).unwrap();
    }

    #[test]
    fn cloudflared_checkout_rejects_wrong_revision() {
        let repo = test_git_repo();
        let error =
            verify_cloudflared_checkout(&repo.path, "0000000000000000000000000000000000000000")
                .unwrap_err();
        assert!(error.to_string().contains("expected pinned"));
    }

    #[test]
    fn cloudflared_checkout_rejects_tracked_changes() {
        let repo = test_git_repo();
        fs::write(
            repo.path.join("go.mod"),
            "module example.invalid/modified\n",
        )
        .unwrap();
        let error = verify_cloudflared_checkout(&repo.path, &repo.head).unwrap_err();
        assert!(error.to_string().contains("not clean"));
    }

    #[test]
    fn cloudflared_checkout_rejects_untracked_go_source() {
        let repo = test_git_repo();
        fs::write(repo.path.join("injected.go"), "package injected\n").unwrap();
        let error = verify_cloudflared_checkout(&repo.path, &repo.head).unwrap_err();
        assert!(error.to_string().contains("injected.go"));
    }

    #[test]
    fn cloudflared_checkout_allows_ignored_build_artifacts() {
        let repo = test_git_repo();
        fs::create_dir(repo.path.join("ignored-build")).unwrap();
        fs::write(
            repo.path.join("ignored-build/generated.go"),
            "package ignored\n",
        )
        .unwrap();
        verify_cloudflared_checkout(&repo.path, &repo.head).unwrap();
    }

    #[test]
    fn run_debug_flag_matches_the_default() {
        let default = parse_run_args(&[]);
        let explicit_debug = parse_run_args(&["--debug"]);

        assert_eq!(
            gradle_variant_task("install", Some(&default.flavor), default.is_release()),
            "installStandardDebug",
        );
        assert_eq!(
            gradle_variant_task(
                "install",
                Some(&explicit_debug.flavor),
                explicit_debug.is_release(),
            ),
            "installStandardDebug",
        );
    }

    #[test]
    fn run_profile_flags_select_the_expected_variant() {
        let legacy_debug = parse_run_args(&["--flavor", "legacy", "--debug"]);
        let release = parse_run_args(&["--release"]);

        assert_eq!(
            gradle_variant_task(
                "install",
                Some(&legacy_debug.flavor),
                legacy_debug.is_release(),
            ),
            "installLegacyDebug",
        );
        assert_eq!(
            gradle_variant_task("install", Some(&release.flavor), release.is_release()),
            "installStandardRelease",
        );
    }

    #[test]
    fn run_rejects_conflicting_profile_flags() {
        assert!(Cli::try_parse_from(["xtask", "run", "--debug", "--release"]).is_err());
    }

    #[test]
    fn zygisk_build_defaults_to_debug_apk_and_release_zygisk() {
        let args = parse_zygisk_build_args(&[]);

        assert_eq!(args.apk_profile.resolve(), ZygiskBuildProfile::Debug);
        assert_eq!(args.zygisk_profile.resolve(), ZygiskBuildProfile::Release);
    }

    #[test]
    fn zygisk_build_profiles_can_be_overridden_independently() {
        let args = parse_zygisk_build_args(&["--apk-release", "--debug"]);
        assert_eq!(args.apk_profile.resolve(), ZygiskBuildProfile::Release);
        assert_eq!(args.zygisk_profile.resolve(), ZygiskBuildProfile::Debug);

        let args = parse_zygisk_build_args(&["--apk-debug", "--release"]);
        assert_eq!(args.apk_profile.resolve(), ZygiskBuildProfile::Debug);
        assert_eq!(args.zygisk_profile.resolve(), ZygiskBuildProfile::Release);
    }

    #[test]
    fn zygisk_build_rejects_conflicting_profile_flags() {
        assert!(Cli::try_parse_from(["xtask", "zygisk", "build", "--debug", "--release"]).is_err());
        assert!(
            Cli::try_parse_from(["xtask", "zygisk", "build", "--apk-debug", "--apk-release",])
                .is_err()
        );
    }

    #[test]
    fn zygisk_build_accepts_only_one_universal_apk() {
        let args = parse_zygisk_build_args(&["--apk", "wekit-universal.apk"]);
        assert_eq!(args.apk, Some(PathBuf::from("wekit-universal.apk")));

        assert!(
            Cli::try_parse_from([
                "xtask",
                "zygisk",
                "build",
                "--apk",
                "first.apk",
                "--apk",
                "second.apk",
            ])
            .is_err()
        );
    }

    #[test]
    fn zygisk_native_defaults_to_release_and_accepts_debug_override() {
        for (extra, expected) in [
            (&[][..], ZygiskBuildProfile::Release),
            (&["--debug"][..], ZygiskBuildProfile::Debug),
        ] {
            let mut argv = vec!["xtask", "zygisk", "native"];
            argv.extend_from_slice(extra);
            let profile = match Cli::try_parse_from(argv).unwrap().command {
                Cmd::Zygisk(ZygiskArgs {
                    command: ZygiskCmd::Native(args),
                }) => args.profile.resolve(),
                _ => unreachable!(),
            };
            assert_eq!(profile, expected);
        }
    }

    #[test]
    fn formats_zygisk_version_names_like_gradle_with_profile_suffix() {
        assert_eq!(
            zygisk_version_name("8920253", ZygiskBuildProfile::Debug),
            "git+8920253-debug"
        );
        assert_eq!(
            zygisk_version_name("8920253", ZygiskBuildProfile::Release),
            "git+8920253-release"
        );
    }

    #[test]
    fn parses_zygisk_ndk_from_gradle_version_catalog() {
        let ndk_version = parse_pinned_ndk_version(
            "[versions]\nndk = \"30.0.14904198\"\nminSdk = \"28\"\n",
            Path::new(VERSION_CATALOG_PATH),
        )
        .unwrap();

        assert_eq!(ndk_version, "30.0.14904198");
    }

    #[test]
    fn rejects_missing_pinned_ndk_version() {
        let catalog = "[versions]\nminSdk = \"28\"\n";
        assert!(parse_pinned_ndk_version(catalog, Path::new(VERSION_CATALOG_PATH)).is_err());
    }

    #[test]
    fn rejects_empty_ndk_version() {
        let error = parse_pinned_ndk_version(
            "[versions]\nndk = \"  \"\nminSdk = \"28\"\n",
            Path::new(VERSION_CATALOG_PATH),
        )
        .err()
        .unwrap();

        assert!(error.to_string().contains("[versions].ndk"));
    }
}
