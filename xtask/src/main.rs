//! WeKit xtask — build automation for the WeKit Android project.
//!
//! Usage: cargo xtask <COMMAND>
//!
//!   configure            Regenerate wekit-native/.cargo/config.toml from the local NDK.
//!   build [OPTIONS]      Build the project (default: full Android debug build via Gradle).
//!   cloudflared-build    Build the embedded cloudflared bridge for Android.
//!   check [OPTIONS]      Run `cargo check` on the native library.
//!   clippy [OPTIONS]     Run `cargo clippy` on the native library.
//!   dex-test [OPTIONS]   Resolve WeKit DexKit targets against desktop APKs.
//!   dex-report-diff      Compare member signatures in existing per-APK reports.
//!   dex-test-ci          Prepare APK sources and mutable Dex-Test Release assets.
//!   i18n-check           Validate the Android English and Chinese resource catalogs.
//!
//! Run `cargo xtask <COMMAND> --help` for per-command options.

use anyhow::{Context, Result, bail};
use clap::{Args, Parser, Subcommand, ValueEnum};
use fs2::FileExt;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    env, fs,
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    process::Command,
};
use walkdir::WalkDir;
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

mod dex_test;
mod dex_test_ci;
mod extensions;
mod i18n_check;

// ── Project constants (mirror app/build.gradle.kts / libs.versions.toml) ──────

/// Matches `minSdk` in libs.versions.toml.
const MIN_SDK: u32 = 28;

/// Minimum NDK major version accepted by `configure`; the pinned NDK must be at least this new.
const MIN_NDK_MAJOR: u32 = 29;

const CLOUDFLARED_COMMIT: &str = "8679787525edc8575b2948a7c4a50b6292c6d426";
pub(crate) const PROOT_COMMIT: &str = "6f8ebfd8e24887dfba64c3f2d7d5fe9dc059b60e";

// ── ABI table ─────────────────────────────────────────────────────────────────

struct AbiSpec {
    /// Directory name in `jniLibs/` and Android ABI filter.
    android_name: &'static str,
    /// Cargo target triple passed to `--target`.
    cargo_triple: &'static str,
    /// Clang binary prefix inside the NDK `bin/` dir (the part before
    /// `{MIN_SDK}-clang`).
    clang_prefix: &'static str,
    /// Prefix used for `CC_`, `CXX_`, `AR_` keys in `.cargo/config.toml`.
    /// Matches the hardcoded strings in `ConfigureCargoTask.kt`.
    env_key: &'static str,
}

#[derive(Debug, Eq, PartialEq)]
struct GoAndroidTarget {
    arch: &'static str,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ApkNativeBuildStep {
    Configure,
    WeKitNative,
    ZygiskNative,
}

const APK_NATIVE_BUILD_STEPS: &[ApkNativeBuildStep] = &[
    ApkNativeBuildStep::Configure,
    ApkNativeBuildStep::WeKitNative,
    ApkNativeBuildStep::ZygiskNative,
];

// Order matches the template in ConfigureCargoTask.kt so that
// `cargo xtask configure` and the Gradle task produce identical output.
static ABI_TABLE: &[AbiSpec] = &[AbiSpec {
    android_name: "arm64-v8a",
    cargo_triple: "aarch64-linux-android",
    clang_prefix: "aarch64-linux-android",
    env_key: "aarch64_linux_android",
}];

/// ABIs included in release APKs (the default build targets).
static RELEASE_ABIS: &[&str] = &["arm64-v8a"];

const ZYGISK_CARGO_PACKAGE: &str = "wekit-zygisk";
const ZYGISK_MODULE_ID: &str = "wekit_zygisk";
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
    /// Default: prepares native inputs, then runs `./gradlew assembleDebug`.
    /// Pass --native-only to compile only the Rust .so and copy it to jniLibs/.
    Build(BuildArgs),

    /// Build the pinned cloudflared C bridge for the cloudflared extension
    /// pack (the pack zip is built from target/cloudflared).
    CloudflaredBuild(NativeArgs),

    /// Build and install the app, or flash the same APK as a Zygisk module.
    ///
    /// Defaults to installStandardDebug. With --zygisk, assembles the selected
    /// APK and installs it through the device's root manager instead.
    Run(RunArgs),

    /// Run `cargo check` on the native library for each target ABI.
    Check(NativeArgs),

    /// Run `cargo clippy` on the native library for each target ABI.
    Clippy(NativeArgs),

    /// Run DexKit resolvers against one or more WeChat APKs on this Linux desktop.
    DexTest(dex_test::DexTestArgs),

    /// Compare adjacent per-APK Dex reports in the supplied order; no APK/JVM required.
    DexReportDiff(dex_test::diff::DexReportDiffArgs),

    /// Prepare inputs and outputs used by the cloud Dex resolution CI jobs.
    DexTestCi(dex_test_ci::DexTestCiArgs),

    /// Build extension packs (script-deps DEX, cloudflared zip, llama-native zip) and their
    /// manifest.json index (which always includes the static qwen3.8-4b-distill model entry).
    Extensions(extensions::ExtensionsArgs),

    /// Validate the Android English and Chinese resource catalogs.
    I18nCheck,
}

#[derive(Args)]
struct BuildArgs {
    /// Prepare all application and Zygisk native inputs in jniLibs/.
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

    /// Also archive unstripped Zygisk native symbols under target/zygisk-symbols/.
    #[arg(long)]
    save_symbols: bool,

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

    /// Install the APK as a Zygisk module through the device's root manager.
    #[arg(long)]
    zygisk: bool,

    /// adb device serial (also used for normal APK installation).
    #[arg(short, long)]
    device: Option<String>,

    /// Root manager: magisk, ksu, or ap. Auto-detected when omitted.
    #[arg(long, requires = "zygisk")]
    root: Option<String>,

    /// Reboot after a successful module installation.
    #[arg(short, long, requires = "zygisk")]
    reboot: bool,
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

/// Arguments shared by --native-only builds, `check`, and `clippy`.
#[derive(Args)]
struct NativeArgs {
    /// Target ABI(s) to build. May be repeated. Defaults to arm64-v8a.
    ///
    /// Valid value: arm64-v8a
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
        Cmd::Check(args) => task_cargo_cmd("check", &args.abis, &[])?,
        Cmd::Clippy(args) => task_cargo_cmd("clippy", &args.abis, &["--", "-D", "warnings"])?,
        Cmd::DexTest(args) => dex_test::task_dex_test(args)?,
        Cmd::DexReportDiff(args) => dex_test::diff::task_dex_report_diff(args)?,
        Cmd::DexTestCi(args) => dex_test_ci::task_dex_test_ci(args)?,
        Cmd::I18nCheck => i18n_check::check_repository(&workspace_root())?,
        Cmd::Extensions(args) => extensions::run(&workspace_root(), &args)?,
    }
    Ok(())
}

// ── Workspace / path helpers ───────────────────────────────────────────────────

/// Walk up from `cwd` until we find a `Cargo.toml` that declares `[workspace]`.
pub(crate) fn workspace_root() -> PathBuf {
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

fn proot_source_dir(root: &Path) -> PathBuf {
    root.join("third_party/proot-static")
}

fn proot_patch_path(root: &Path) -> PathBuf {
    root.join("patches/proot/android-ptrace-events.patch")
}

fn proot_build_source_dir(root: &Path) -> PathBuf {
    root.join("target/proot-static/source")
}

pub(crate) fn proot_artifact_paths(root: &Path) -> (PathBuf, PathBuf) {
    let artifacts = root.join("target/proot-static/artifacts");
    (artifacts.join("proot"), artifacts.join("loader"))
}

fn proot_cache_key_path(root: &Path) -> PathBuf {
    root.join("target/proot-static/cache-key")
}

fn proot_cache_key(root: &Path, ndk: &Path) -> Result<String> {
    let patch = fs::read(proot_patch_path(root))?;
    let build_script = fs::read(proot_source_dir(root).join("tools/build-static-aarch64.sh"))?;
    let mut hasher = Sha256::new();
    hasher.update(b"wekit-proot-cache-v1\0");
    hasher.update(PROOT_COMMIT.as_bytes());
    hasher.update(ndk.to_string_lossy().as_bytes());
    hasher.update(MIN_SDK.to_le_bytes());
    hasher.update(patch);
    hasher.update(build_script);
    Ok(hex_encode(&hasher.finalize()))
}

fn proot_cache_is_valid(root: &Path, ndk: &Path) -> Result<bool> {
    let (launcher, loader) = proot_artifact_paths(root);
    if !launcher.is_file() || !loader.is_file() {
        return Ok(false);
    }
    let cached = match fs::read_to_string(proot_cache_key_path(root)) {
        Ok(value) => value,
        Err(_) => return Ok(false),
    };
    Ok(cached.trim() == proot_cache_key(root, ndk)?)
}

fn proot_jni_artifact_paths(root: &Path) -> (PathBuf, PathBuf) {
    let arm64 = jni_libs_dir(root).join("arm64-v8a");
    (arm64.join("libproot.so"), arm64.join("libproot_loader.so"))
}

fn invoke_tool_artifact_paths(root: &Path, spec: &AbiSpec) -> (PathBuf, PathBuf) {
    (
        root.join("target")
            .join(spec.cargo_triple)
            .join("release/invoke_tool"),
        jni_libs_dir(root)
            .join(spec.android_name)
            .join("libinvoke_tool.so"),
    )
}

fn chroot_cleanup_artifact_paths(root: &Path, spec: &AbiSpec) -> (PathBuf, PathBuf) {
    (
        root.join("target")
            .join(spec.cargo_triple)
            .join("release/chroot_cleanup"),
        jni_libs_dir(root)
            .join(spec.android_name)
            .join("libchroot_cleanup.so"),
    )
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

fn should_build_proot(abis: &[&AbiSpec]) -> bool {
    abis.iter().any(|abi| abi.android_name == "arm64-v8a")
}

fn go_android_target(spec: &AbiSpec) -> GoAndroidTarget {
    match spec.android_name {
        "arm64-v8a" => GoAndroidTarget { arch: "arm64" },
        name => unreachable!("unsupported Android ABI {name}"),
    }
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

    // Write for wekit-llama (same linker config; llama-cpp-sys-2's build.rs drives its own cmake)
    let llama_config_path = root.join("app/src/main/rust/wekit-llama/.cargo/config.toml");
    fs::create_dir_all(llama_config_path.parent().unwrap())?;
    fs::write(&llama_config_path, &out)
        .with_context(|| format!("failed to write {}", llama_config_path.display()))?;
    println!("configure: wrote {}", llama_config_path.display());

    Ok(())
}

// ── Task: build ────────────────────────────────────────────────────────────────

fn task_build(args: BuildArgs) -> Result<()> {
    if args.native_only {
        task_prepare_apk_native_inputs(&args.native.abis, args.save_symbols)
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
    task_prepare_apk_native_inputs(&args.native.abis, args.save_symbols)?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("assemble", args.flavor.as_ref(), args.release);
    println!("build: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Install the app on a connected device or emulator via the Gradle wrapper (native lib compiled first).
fn task_run(args: RunArgs) -> Result<()> {
    validate_root_manager(args.root.as_deref())?;
    task_prepare_apk_native_inputs(&[], false)?;
    let root = workspace_root();
    let verb = if args.zygisk { "assemble" } else { "install" };
    let gradle_task = gradle_variant_task(verb, Some(&args.flavor), args.is_release());
    println!("run: ./gradlew {gradle_task}");
    let mut command = Command::new(root.join(if cfg!(windows) {
        "gradlew.bat"
    } else {
        "gradlew"
    }));
    command.arg(&gradle_task).current_dir(&root);
    if let Some(device) = &args.device {
        command.env("ANDROID_SERIAL", device);
    }
    run_checked(&mut command, "build/install dual-format APK")?;
    if args.zygisk {
        let flavor = match args.flavor {
            Flavor::Standard => "standard",
            Flavor::Legacy => "legacy",
        };
        let profile = if args.is_release() {
            "release"
        } else {
            "debug"
        };
        let apk = root.join(format!(
            "app/build/outputs/apk/{flavor}/{profile}/app-{flavor}-{profile}.apk"
        ));
        validate_module_apk(&apk)?;
        install_zygisk_apk(&root, &apk, args.device.as_deref(), args.root.as_deref())?;
        if args.reboot {
            run_adb(
                &root,
                args.device.as_deref(),
                &[
                    "shell".into(),
                    "su".into(),
                    "-c".into(),
                    "svc power reboot || reboot".into(),
                ],
            )?;
        }
    }
    Ok(())
}

fn apk_native_build_steps() -> &'static [ApkNativeBuildStep] {
    APK_NATIVE_BUILD_STEPS
}

fn task_prepare_apk_native_inputs(abi_args: &[String], save_symbols: bool) -> Result<()> {
    for step in apk_native_build_steps() {
        match step {
            ApkNativeBuildStep::Configure => task_configure()?,
            ApkNativeBuildStep::WeKitNative => task_build_native(abi_args)?,
            ApkNativeBuildStep::ZygiskNative => {
                build_zygisk_native(&workspace_root(), abi_args, save_symbols)?
            }
        }
    }
    Ok(())
}

fn verify_proot_checkout(root: &Path) -> Result<()> {
    let source = proot_source_dir(root);
    let script = source.join("tools/build-static-aarch64.sh");
    if !script.is_file() {
        bail!(
            "PRoot source is not initialized at {}; run `git submodule update --init --recursive`",
            source.display(),
        );
    }
    verify_proot_source_checkout(&source, PROOT_COMMIT)
}

fn proot_git_output(source: &Path, args: &[&str]) -> Result<String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(source)
        .output()
        .with_context(|| format!("failed to inspect PRoot source at {}", source.display()))?;
    if !output.status.success() {
        bail!("`git {}` failed in {}", args.join(" "), source.display());
    }
    Ok(String::from_utf8(output.stdout)?.trim().to_owned())
}

fn verify_proot_source_checkout(source: &Path, expected_commit: &str) -> Result<()> {
    let actual = proot_git_output(source, &["rev-parse", "HEAD"])?;
    if actual != expected_commit {
        bail!("PRoot source is at {actual}, expected pinned {expected_commit}");
    }

    let changes = proot_git_output(
        source,
        &["status", "--porcelain=v1", "--untracked-files=all"],
    )?;
    if !changes.is_empty() {
        bail!(
            "PRoot source checkout is not clean; remove tracked or non-ignored untracked changes before building:\n{changes}"
        );
    }
    Ok(())
}

fn run_checked(command: &mut Command, action: &str) -> Result<()> {
    let status = command
        .status()
        .with_context(|| format!("failed to start {action}"))?;
    if !status.success() {
        bail!("{action} failed with {status}");
    }
    Ok(())
}

fn prepare_proot_build_source(root: &Path) -> Result<PathBuf> {
    let source = proot_source_dir(root);
    let build_source = proot_build_source_dir(root);
    let patch = proot_patch_path(root);
    if !patch.is_file() {
        bail!("pinned PRoot patch is missing: {}", patch.display());
    }

    let _ = Command::new("git")
        .args(["worktree", "remove", "--force"])
        .arg(&build_source)
        .current_dir(&source)
        .status();
    if build_source.exists() {
        fs::remove_dir_all(&build_source)
            .with_context(|| format!("failed to remove {}", build_source.display()))?;
    }
    run_checked(
        Command::new("git")
            .args(["worktree", "prune"])
            .current_dir(&source),
        "PRoot worktree prune",
    )?;
    run_checked(
        Command::new("git")
            .args(["worktree", "add", "--detach"])
            .arg(&build_source)
            .arg(PROOT_COMMIT)
            .current_dir(&source),
        "PRoot build worktree creation",
    )?;
    run_checked(
        Command::new("git")
            .args(["apply", "--check"])
            .arg(&patch)
            .current_dir(&build_source),
        "PRoot patch validation",
    )?;
    run_checked(
        Command::new("git")
            .arg("apply")
            .arg(&patch)
            .current_dir(&build_source),
        "PRoot patch application",
    )?;
    Ok(build_source)
}

fn task_build_proot(root: &Path) -> Result<()> {
    verify_proot_checkout(root)?;
    let build_root = root.join("target/proot-static");
    fs::create_dir_all(&build_root)?;
    let build_lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(build_root.join("build.lock"))?;
    build_lock
        .lock_exclusive()
        .context("failed to lock the PRoot build workspace")?;
    let ndk = pinned_ndk_dir(root, None)?;
    if proot_cache_is_valid(root, &ndk)? {
        println!("build(proot): reusing cached artifacts");
        copy_proot_artifacts(root)?;
        return Ok(());
    }

    let build_source = prepare_proot_build_source(root)?;
    let status = Command::new("bash")
        .arg(build_source.join("tools/build-static-aarch64.sh"))
        .env("NDK", &ndk)
        .env("API", MIN_SDK.to_string())
        .env("OUT", root.join("target/proot-static/build"))
        .env(
            "REPO_ARTIFACT_DIR",
            root.join("target/proot-static/artifacts"),
        )
        .status()
        .context("failed to start pinned PRoot build")?;
    if !status.success() {
        bail!("pinned PRoot build failed with {status}");
    }
    let (launcher, loader) = proot_artifact_paths(root);
    if !launcher.is_file() || !loader.is_file() {
        bail!("pinned PRoot build did not produce launcher and loader");
    }
    copy_proot_artifacts(root)?;
    fs::write(proot_cache_key_path(root), proot_cache_key(root, &ndk)?)
        .context("failed to record the PRoot build cache key")?;
    Ok(())
}

fn copy_proot_artifacts(root: &Path) -> Result<()> {
    let (launcher, loader) = proot_artifact_paths(root);
    let (launcher_dst, loader_dst) = proot_jni_artifact_paths(root);
    fs::create_dir_all(launcher_dst.parent().unwrap())?;
    copy_if_changed(&launcher, &launcher_dst)?;
    copy_if_changed(&loader, &loader_dst)?;
    Ok(())
}

/// Native-only build: cargo build + copy .so to jniLibs/.
fn task_build_native(abi_args: &[String]) -> Result<()> {
    let root = workspace_root();
    let native_dir = native_crate_dir(&root);
    let abis = resolve_abis(abi_args)?;

    if should_build_proot(&abis) {
        task_build_proot(&root)?;
    }

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
        copy_if_changed(&so_src, &so_dst).with_context(|| {
            format!("could not copy {} → {}", so_src.display(), so_dst.display())
        })?;

        let (invoke_tool_src, invoke_tool_dst) = invoke_tool_artifact_paths(&root, spec);
        copy_if_changed(&invoke_tool_src, &invoke_tool_dst).with_context(|| {
            format!(
                "could not copy invoke_tool PIE {} → {}",
                invoke_tool_src.display(),
                invoke_tool_dst.display()
            )
        })?;

        let (cleanup_src, cleanup_dst) = chroot_cleanup_artifact_paths(&root, spec);
        copy_if_changed(&cleanup_src, &cleanup_dst).with_context(|| {
            format!(
                "could not copy chroot_cleanup PIE {} → {}",
                cleanup_src.display(),
                cleanup_dst.display()
            )
        })?;

        println!(
            "build(native):  {} → {}",
            so_src.display(),
            so_dst.display()
        );
        println!(
            "build(native):  {} → {}",
            invoke_tool_src.display(),
            invoke_tool_dst.display()
        );
        println!(
            "build(native):  {} → {}",
            cleanup_src.display(),
            cleanup_dst.display()
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

pub(crate) fn task_build_cloudflared(abi_args: &[String]) -> Result<()> {
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
                "-buildvcs=false",
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
    }
    Ok(())
}

// ── Task: zygisk ──────────────────────────────────────────────────────────────

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

fn copy_if_changed(source: &Path, destination: &Path) -> Result<()> {
    let bytes = fs::read(source)?;
    if fs::read(destination).ok().as_deref() != Some(bytes.as_slice()) {
        fs::create_dir_all(destination.parent().context("destination has no parent")?)?;
        fs::copy(source, destination)?;
    }
    Ok(())
}

fn build_zygisk_native(root: &Path, abi_names: &[String], save_symbols: bool) -> Result<()> {
    let ndk = pinned_ndk_dir(root, None)?;
    let strip = ndk
        .join("toolchains/llvm/prebuilt")
        .join(host_prebuilt_tag()?)
        .join("bin/llvm-strip");
    for abi in resolve_abis(abi_names)? {
        // The package release profile retains symbols; only the packaged copy is stripped.
        run_cargo(
            &[
                "build",
                "-p",
                ZYGISK_CARGO_PACKAGE,
                "--target",
                abi.cargo_triple,
                "--release",
            ],
            &zygisk_dir(root).join("native"),
        )?;
        let name = format!("lib{ZYGISK_MODULE_ID}.so");
        let source = root
            .join("target")
            .join(abi.cargo_triple)
            .join("release")
            .join(&name);
        let destination = jni_libs_dir(root).join(abi.android_name).join(&name);
        let cache = root.join("target/zygisk").join(abi.android_name);
        fs::create_dir_all(&cache)?;
        let mut hash = Sha256::new();
        hash.update(fs::read(&source)?);
        hash.update(fs::read(&strip)?);
        let input_hash = hex_encode(&hash.finalize());
        let marker = cache.join("strip.sha256");
        let current = fs::read(&destination)
            .ok()
            .map(|bytes| format!("{input_hash}\n{}", hex_encode(&Sha256::digest(bytes))));
        if current.is_none() || fs::read_to_string(&marker).ok() != current {
            let temporary = cache.join(&name);
            run_cmd_owned(
                strip.to_str().context("non-UTF-8 strip path")?,
                &[
                    "--strip-all".into(),
                    "-o".into(),
                    temporary.display().to_string(),
                    source.display().to_string(),
                ],
                root,
            )?;
            copy_if_changed(&temporary, &destination)?;
            fs::write(
                &marker,
                format!(
                    "{input_hash}\n{}",
                    hex_encode(&Sha256::digest(fs::read(&destination)?))
                ),
            )?;
            println!("build(zygisk): {}", destination.display());
        } else {
            println!("build(zygisk): unchanged {}", destination.display());
        }
        if save_symbols {
            let symbols = root.join("target/zygisk-symbols");
            let stage = symbols.join("files").join(abi.android_name);
            copy_if_changed(&source, &stage.join(&name))?;
            let commit = git_output(root, &["rev-parse", "--short=8", "HEAD"])?;
            let archive = symbols.join(format!("WeKit-{commit}-{}-symbols.zip", abi.android_name));
            write_zip_from_directory(&stage, &archive)?;
            println!("build(symbols): {}", archive.display());
        }
    }
    Ok(())
}

fn validate_module_apk(path: &Path) -> Result<()> {
    let mut archive = ZipArchive::new(fs::File::open(path)?)?;
    for name in [
        "AndroidManifest.xml",
        "classes.dex",
        "resources.arsc",
        "module.prop",
        "customize.sh",
        "META-INF/com/google/android/update-binary",
        "META-INF/com/google/android/updater-script",
        "lib/arm64-v8a/libwekit_zygisk.so",
        "lib/arm64-v8a/libwekit_native.so",
    ] {
        let mut entry = archive.by_name(name).with_context(|| {
            format!(
                "{} is not a dual-format APK: missing {name}",
                path.display()
            )
        })?;
        if entry.size() == 0 {
            bail!("empty APK entry: {name}");
        }
        std::io::copy(&mut entry, &mut std::io::sink())?;
    }
    Ok(())
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
/// once the write fully succeeded, so a failed symbol export never publishes a partial archive.
fn write_zip_from_directory(source: &Path, destination: &Path) -> Result<()> {
    let file_name = destination.file_name().with_context(|| {
        format!(
            "archive destination has no file name: {}",
            destination.display()
        )
    })?;
    let mut temp_name = file_name.to_owned();
    temp_name.push(".partial");
    let temp_path = destination.with_file_name(temp_name);

    if let Err(error) = stream_zip_from_directory(source, &temp_path) {
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

fn stream_zip_from_directory(source: &Path, destination: &Path) -> Result<()> {
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
        std::io::copy(&mut input, &mut zip)?;
    }
    zip.finish()?.flush()?;
    Ok(())
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
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

fn install_zygisk_apk(
    root: &Path,
    apk_path: &Path,
    device: Option<&str>,
    manager: Option<&str>,
) -> Result<()> {
    let manager = validate_root_manager(manager)?;
    // The bytes are the signed APK; only the device-side extension changes.
    let remote_zip = format!("/data/local/tmp/wekit-module-{}.zip", std::process::id());
    let remote_script = "/data/local/tmp/install_wekit_zygisk.sh";
    let script = zygisk_dir(root).join("scripts/install_module.sh");
    run_adb(
        root,
        device,
        &[
            "push".to_owned(),
            apk_path.display().to_string(),
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
    fn apk_native_build_plan_runs_configure_before_wekit_native() {
        assert_eq!(
            apk_native_build_steps(),
            &[
                ApkNativeBuildStep::Configure,
                ApkNativeBuildStep::WeKitNative,
                ApkNativeBuildStep::ZygiskNative,
            ],
        );
    }

    #[test]
    fn invoke_tool_is_packaged_as_an_abi_native_artifact() {
        let root = Path::new("/workspace");
        let (source, destination) = invoke_tool_artifact_paths(root, &ABI_TABLE[0]);
        assert_eq!(
            source,
            root.join("target/aarch64-linux-android/release/invoke_tool")
        );
        assert_eq!(
            destination,
            root.join("app/src/main/jniLibs/arm64-v8a/libinvoke_tool.so")
        );
    }

    #[test]
    fn chroot_cleanup_is_packaged_as_an_abi_native_artifact() {
        let root = Path::new("/workspace");
        let (source, destination) = chroot_cleanup_artifact_paths(root, &ABI_TABLE[0]);
        assert_eq!(
            source,
            root.join("target/aarch64-linux-android/release/chroot_cleanup")
        );
        assert_eq!(
            destination,
            root.join("app/src/main/jniLibs/arm64-v8a/libchroot_cleanup.so")
        );
    }

    #[test]
    fn proot_is_packaged_as_arm64_native_artifacts() {
        let root = Path::new("/workspace");
        let (launcher, loader) = proot_jni_artifact_paths(root);
        assert_eq!(
            launcher,
            root.join("app/src/main/jniLibs/arm64-v8a/libproot.so")
        );
        assert_eq!(
            loader,
            root.join("app/src/main/jniLibs/arm64-v8a/libproot_loader.so")
        );
    }

    #[test]
    fn proot_build_selection_is_arm64_only() {
        assert!(should_build_proot(&[&ABI_TABLE[0]]));
        assert!(!should_build_proot(&[]));
    }

    #[test]
    fn proot_build_uses_versioned_patch_and_generated_worktree() {
        let root = Path::new("/workspace");
        assert_eq!(
            proot_patch_path(root),
            root.join("patches/proot/android-ptrace-events.patch"),
        );
        assert_eq!(
            proot_build_source_dir(root),
            root.join("target/proot-static/source"),
        );
    }

    #[test]
    fn proot_cache_requires_matching_inputs_and_artifacts() {
        static NEXT_CACHE_ID: AtomicU64 = AtomicU64::new(0);
        let root = env::temp_dir().join(format!(
            "wekit-proot-cache-test-{}-{}",
            std::process::id(),
            NEXT_CACHE_ID.fetch_add(1, Ordering::Relaxed),
        ));
        let patch = proot_patch_path(&root);
        let source = proot_source_dir(&root);
        let artifacts = root.join("target/proot-static/artifacts");
        fs::create_dir_all(patch.parent().unwrap()).unwrap();
        fs::create_dir_all(source.join("tools")).unwrap();
        fs::create_dir_all(&artifacts).unwrap();
        fs::write(&patch, "patch\n").unwrap();
        fs::write(source.join("tools/build-static-aarch64.sh"), "build\n").unwrap();
        fs::write(artifacts.join("proot"), "proot\n").unwrap();
        fs::write(artifacts.join("loader"), "loader\n").unwrap();

        assert!(!proot_cache_is_valid(&root, Path::new("/ndk")).unwrap());

        fs::write(
            proot_cache_key_path(&root),
            proot_cache_key(&root, Path::new("/ndk")).unwrap(),
        )
        .unwrap();
        assert!(proot_cache_is_valid(&root, Path::new("/ndk")).unwrap());

        fs::write(&patch, "changed patch\n").unwrap();
        assert!(!proot_cache_is_valid(&root, Path::new("/ndk")).unwrap());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn proot_checkout_accepts_clean_pinned_revision() {
        let repo = test_git_repo();
        verify_proot_source_checkout(&repo.path, &repo.head).unwrap();
    }

    #[test]
    fn proot_checkout_rejects_tracked_changes() {
        let repo = test_git_repo();
        fs::write(repo.path.join("go.mod"), "modified input\n").unwrap();
        let error = verify_proot_source_checkout(&repo.path, &repo.head).unwrap_err();
        assert!(error.to_string().contains("not clean"));
    }

    #[test]
    fn proot_checkout_rejects_untracked_input() {
        let repo = test_git_repo();
        fs::write(repo.path.join("injected.c"), "int injected;\n").unwrap();
        let error = verify_proot_source_checkout(&repo.path, &repo.head).unwrap_err();
        assert!(error.to_string().contains("injected.c"));
    }

    #[test]
    fn proot_checkout_allows_ignored_build_artifacts() {
        let repo = test_git_repo();
        fs::create_dir(repo.path.join("ignored-build")).unwrap();
        fs::write(repo.path.join("ignored-build/generated.o"), "object\n").unwrap();
        verify_proot_source_checkout(&repo.path, &repo.head).unwrap();
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
    fn run_zygisk_accepts_install_options_and_rejects_them_for_app_install() {
        let args = parse_run_args(&[
            "--zygisk",
            "--flavor",
            "legacy",
            "--release",
            "--device",
            "serial",
            "--root",
            "ksu",
            "--reboot",
        ]);
        assert!(args.zygisk && args.is_release() && args.reboot);
        assert!(matches!(args.flavor, Flavor::Legacy));
        assert_eq!(args.device.as_deref(), Some("serial"));
        assert_eq!(args.root.as_deref(), Some("ksu"));
        assert!(Cli::try_parse_from(["xtask", "run", "--root", "ksu"]).is_err());
        assert!(Cli::try_parse_from(["xtask", "run", "--reboot"]).is_err());
        assert!(Cli::try_parse_from(["xtask", "zygisk", "build"]).is_err());
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
