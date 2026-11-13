//! WeKit xtask — build automation for the WeKit Android project.
//!
//! Usage: cargo xtask <COMMAND>
//!
//!   configure            Regenerate wekit-native/.cargo/config.toml from the local NDK.
//!   bootstrap build      Build the public dual-format bootstrap APK.
//!   bootstrap install    Build and install the bootstrap APK, or flash it as a Zygisk module.
//!   payload build        Build the private business payload APK.
//!   payload publish      Build the private release payload and publish it locally.
//!   check [OPTIONS]      Run `cargo check` on the native library.
//!   clippy [OPTIONS]     Run `cargo clippy` on the native library.
//!   fmt                  Format Rust (cargo fmt) and Kotlin/Gradle (spotlessApply).
//!   dex-test [OPTIONS]   Resolve WeKit DexSQL targets against desktop APKs.
//!   dex-report-diff      Compare member signatures in existing per-APK reports.
//!   dex-test-ci          Prepare APK sources and mutable Dex-Test Release assets.
//!   i18n-check           Validate the Android English and Chinese resource catalogs.
//!
//! Run `cargo xtask <COMMAND> --help` for per-command options.

use anyhow::{Context, Result, bail};
use clap::{Args, Parser, Subcommand};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
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
const MIN_SDK: u32 = 29;

/// Minimum NDK major version accepted by `configure`; the pinned NDK must be at least this new.
const MIN_NDK_MAJOR: u32 = 29;

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

    /// Build and install the public bootstrap APK.
    Bootstrap(BootstrapArgs),

    /// Build and publish the private business payload.
    Payload(PayloadArgs),

    /// Run `cargo check` on the native library for each target ABI.
    Check(NativeArgs),

    /// Run `cargo clippy` on the native library for each target ABI.
    Clippy(NativeArgs),

    /// Format Rust (cargo fmt) and Kotlin/Gradle (spotlessApply) sources.
    Fmt,

    /// Run DexSQL resolvers against one or more WeChat APKs on this Linux desktop.
    DexTest(dex_test::DexTestArgs),

    /// Compare adjacent per-APK Dex reports in the supplied order; no APK/JVM required.
    DexReportDiff(dex_test::diff::DexReportDiffArgs),

    /// Prepare inputs and outputs used by the cloud Dex resolution CI jobs.
    DexTestCi(dex_test_ci::DexTestCiArgs),

    /// Build extension packs and their manifest.json index.
    Extensions(extensions::ExtensionsArgs),

    /// Validate the Android English and Chinese resource catalogs.
    I18nCheck,
}

#[derive(Args)]
struct BootstrapArgs {
    #[command(subcommand)]
    command: BootstrapCommand,
}

#[derive(Subcommand)]
enum BootstrapCommand {
    /// Build the public dual-format bootstrap APK.
    Build(BootstrapBuildArgs),

    /// Build and install the bootstrap APK, or flash it as a Zygisk module.
    Install(InstallArgs),
}

#[derive(Args)]
struct BootstrapBuildArgs {
    /// Prepare this module's native inputs in its jniLibs/.
    /// Skips the Gradle Android build entirely.
    #[arg(long)]
    native_only: bool,

    /// Build bootstrap-local with the debug payload embedded by default.
    #[arg(long, conflicts_with = "native_only")]
    local: bool,

    /// Embed the release app payload instead of debug; bootstrap itself is always release.
    #[arg(long, requires = "local")]
    release: bool,

    /// Also archive unstripped Zygisk native symbols under target/zygisk-symbols/.
    #[arg(long)]
    save_symbols: bool,

    #[command(flatten)]
    native: NativeArgs,
}

#[derive(Args)]
struct PayloadArgs {
    #[command(subcommand)]
    command: PayloadCommand,
}

#[derive(Subcommand)]
enum PayloadCommand {
    /// Build the private business payload APK.
    Build(BuildArgs),

    /// Build the private release payload locally, then encrypt/sign and publish it.
    Publish(PublishArgs),
}

#[derive(Args)]
struct BuildArgs {
    /// Prepare this module's native inputs in its jniLibs/.
    /// Skips the Gradle Android build entirely.
    #[arg(long)]
    native_only: bool,

    /// Build a release build instead of debug.
    /// Ignored with --native-only.
    #[arg(long)]
    release: bool,

    /// Also archive unstripped Zygisk native symbols under target/zygisk-symbols/ (bootstrap only).
    #[arg(long)]
    save_symbols: bool,

    #[command(flatten)]
    native: NativeArgs,
}

#[derive(Args)]
struct PublishArgs {
    /// Signed private business APK.
    #[arg(long, default_value = "app/build/outputs/apk/release/app-release.apk")]
    apk: PathBuf,
    /// API origin containing /ci endpoints.
    #[arg(long, default_value = "https://auth.wekit.ujhhgtg.dev")]
    api_url: String,
    /// Publication audience (retained for the GitHub OIDC compatibility path).
    #[arg(long, default_value = "wekit-payload-publication")]
    audience: String,
    /// P-256 release signing private key PEM path; never printed or uploaded.
    #[arg(
        long,
        default_value = "/home/ujhhgtg/.config/wekit-release/remote-payload-release-20261011.pem"
    )]
    signing_key_file: PathBuf,
    #[arg(long, default_value = "release-20261011-1")]
    key_id: String,
    /// Local release-machine secret JSON. Omit only when running inside GitHub Actions.
    #[arg(
        long,
        default_value = "/home/ujhhgtg/.config/wekit-release/remote-payload-production-secrets.json"
    )]
    local_secrets_file: Option<PathBuf>,
    #[arg(long, default_value = "stable")]
    channel: String,
    /// Explicit immutable payload ID; omit to generate one.
    #[arg(long)]
    payload_id: Option<String>,
    /// Prepare encrypted artifact without contacting publication API.
    #[arg(long)]
    prepare_only: bool,
    #[arg(long, default_value = "target/private-payload")]
    output_dir: PathBuf,
}

/// Arguments for `bootstrap install` (install + launch via Gradle).
#[derive(Args)]
struct InstallArgs {
    /// Install the APK as a Zygisk module through the device's root manager.
    #[arg(long)]
    zygisk: bool,

    /// Install bootstrap-local with the debug payload embedded by default.
    #[arg(long)]
    local: bool,

    /// Embed the release app payload instead of debug; bootstrap itself is always release.
    #[arg(long, requires = "local")]
    release: bool,

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

/// Arguments shared by --native-only builds, `check`, and `clippy`.
#[derive(Args)]
struct NativeArgs {
    /// Target ABI(s) to build. May be repeated. Defaults to arm64-v8a.
    ///
    /// Valid value: arm64-v8a
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,
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
        Cmd::Bootstrap(args) => match args.command {
            BootstrapCommand::Build(args) => task_build_bootstrap(&args)?,
            BootstrapCommand::Install(args) => task_install(args)?,
        },
        Cmd::Payload(args) => match args.command {
            PayloadCommand::Build(args) => task_build_module(&args, "app")?,
            PayloadCommand::Publish(args) => task_publish(args)?,
        },
        Cmd::Check(args) => task_cargo_cmd("check", &args.abis, &[])?,
        Cmd::Clippy(args) => task_cargo_cmd("clippy", &args.abis, &["--", "-D", "warnings"])?,
        Cmd::Fmt => task_fmt()?,
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
    root.join("crates/wekit-native")
}

fn zygisk_crate_dir(root: &Path) -> PathBuf {
    root.join("crates/wekit-zygisk")
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

    // The C++ ART bridge uses exactly the same pinned NDK/API as Rust and AGP.
    let ndk_dir = pinned_ndk_dir(&root, None)?;
    let zygisk_out = format!(
        "{out}WEKIT_ANDROID_NDK = \"{}\"\nWEKIT_ANDROID_API = \"{MIN_SDK}\"\n",
        ndk_dir.to_string_lossy().replace('\\', "/")
    );
    let zygisk_config_path = zygisk_crate_dir(&root).join(".cargo/config.toml");
    fs::create_dir_all(zygisk_config_path.parent().unwrap())?;
    fs::write(&zygisk_config_path, &zygisk_out)
        .with_context(|| format!("failed to write {}", zygisk_config_path.display()))?;
    println!("configure: wrote {}", zygisk_config_path.display());

    Ok(())
}

// ── Tasks: bootstrap / payload ─────────────────────────────────────────────────

/// Compose a Gradle task name from a verb and build profile.
fn gradle_variant_task(verb: &str, release: bool) -> String {
    let profile = if release { "Release" } else { "Debug" };
    format!("{verb}{profile}")
}

fn bootstrap_gradle_task(verb: &str, local: bool) -> String {
    format!(
        ":bootstrap:{verb}{}",
        if local { "Local" } else { "Release" }
    )
}

fn bootstrap_apk_path(local: bool, release_payload: bool) -> &'static str {
    if local {
        if release_payload {
            "bootstrap/build/outputs/apk/local/bootstrap-local-release.apk"
        } else {
            "bootstrap/build/outputs/apk/local/bootstrap-local-debug.apk"
        }
    } else {
        "bootstrap/build/outputs/apk/release/bootstrap-release.apk"
    }
}

fn embedded_payload_gradle_arg(local: bool, release_payload: bool) -> Option<String> {
    local.then(|| {
        format!(
            "-Pwekit.embeddedPayloadBuildType={}",
            if release_payload { "release" } else { "debug" }
        )
    })
}

/// Build the public release bootstrap or its offline embedded-payload variant.
fn task_build_bootstrap(args: &BootstrapBuildArgs) -> Result<()> {
    let root = workspace_root();
    task_configure()?;
    build_zygisk_native(&root, &args.native.abis, args.save_symbols)?;
    build_bootstrap_loader(
        &root,
        &args.native.abis,
        if args.local {
            OmvllMode::Disabled
        } else {
            OmvllMode::Required
        },
    )?;
    if args.local {
        task_build_native(&args.native.abis)?;
    }
    if !args.native_only {
        let gradle_task = bootstrap_gradle_task("assemble", args.local);
        let payload_arg = embedded_payload_gradle_arg(args.local, args.release);
        let mut gradle_args = vec![gradle_task.as_str(), "--no-configuration-cache"];
        if let Some(arg) = payload_arg.as_deref() {
            gradle_args.push(arg);
        }
        run_gradlew(&gradle_args, &root)?;
    }
    Ok(())
}

/// Install the bootstrap APK on a connected device or emulator via Gradle (native inputs compiled first).
fn task_install(args: InstallArgs) -> Result<()> {
    validate_root_manager(args.root.as_deref())?;
    let root = workspace_root();
    task_configure()?;
    build_zygisk_native(&root, &[], false)?;
    build_bootstrap_loader(
        &root,
        &[],
        if args.local {
            OmvllMode::Disabled
        } else {
            OmvllMode::Required
        },
    )?;
    if args.local {
        task_build_native(&[])?;
    }
    let verb = if args.zygisk { "assemble" } else { "install" };
    let gradle_task = bootstrap_gradle_task(verb, args.local);
    println!("bootstrap install: ./gradlew {gradle_task}");
    let mut command = Command::new(root.join(if cfg!(windows) {
        "gradlew.bat"
    } else {
        "gradlew"
    }));
    command.arg(&gradle_task).current_dir(&root);
    if args.local {
        command.arg("--no-configuration-cache");
        command.arg(embedded_payload_gradle_arg(true, args.release).unwrap());
    }
    if let Some(device) = &args.device {
        command.env("ANDROID_SERIAL", device);
    }
    run_checked(&mut command, "build/install dual-format APK")?;
    if args.zygisk {
        let apk = root.join(bootstrap_apk_path(args.local, args.release));
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

fn task_build_module(args: &BuildArgs, module: &str) -> Result<()> {
    let root = workspace_root();
    task_configure()?;
    if module == "bootstrap" {
        build_zygisk_native(&root, &args.native.abis, args.save_symbols)?;
        build_bootstrap_loader(
            &root,
            &args.native.abis,
            if args.release {
                OmvllMode::Required
            } else {
                OmvllMode::Optional
            },
        )?;
    } else {
        task_build_native(&args.native.abis)?;
    }
    if !args.native_only {
        let task = format!(
            ":{module}:{}",
            gradle_variant_task("assemble", args.release)
        );
        let mut tasks = vec![task.as_str()];
        if args.release {
            tasks.push("--no-configuration-cache");
        }
        run_gradlew(&tasks, &root)?;
    }
    Ok(())
}

struct OmvllLoaderConfig {
    plugin: PathBuf,
    config: PathBuf,
    python_path: PathBuf,
    clang_bin: PathBuf,
    runtime_library_path: PathBuf,
}

#[derive(Clone, Copy)]
enum OmvllMode {
    Required,
    Optional,
    Disabled,
}

/// Resolve the separately provisioned O-MVLL toolchain used for the public loader.
///
/// O-MVLL is coupled to the LLVM revision used by its NDK.  WeKit's Android build
/// currently uses NDK r30, while released O-MVLL toolchains target r29, so silently
/// passing the plugin to the project compiler would produce an unprotected or broken
/// loader.  Release builds therefore require an explicit, complete r29 toolchain.
fn omvll_loader_config(root: &Path, mode: OmvllMode) -> Result<Option<OmvllLoaderConfig>> {
    if matches!(mode, OmvllMode::Disabled) {
        return Ok(None);
    }

    let required = matches!(mode, OmvllMode::Required);
    let mut configured = HashMap::new();
    let config_file = root.join("omvll.env");
    if let Ok(contents) = fs::read_to_string(&config_file) {
        for line in contents.lines().map(str::trim) {
            let line = line.strip_prefix("export ").unwrap_or(line);
            let Some((name, value)) = line.split_once('=') else {
                continue;
            };
            if matches!(
                name.trim(),
                "WEKIT_OMVLL_PLUGIN"
                    | "WEKIT_OMVLL_CONFIG"
                    | "WEKIT_OMVLL_PYTHONPATH"
                    | "WEKIT_OMVLL_NDK"
            ) {
                configured.insert(
                    name.trim().to_owned(),
                    value.trim().trim_matches(['\"', '\'']).to_owned(),
                );
            }
        }
    }
    let value = |name: &str| {
        env::var_os(name)
            .map(PathBuf::from)
            .or_else(|| configured.get(name).map(PathBuf::from))
    };
    let plugin = value("WEKIT_OMVLL_PLUGIN");
    let config = value("WEKIT_OMVLL_CONFIG");
    let python_path = value("WEKIT_OMVLL_PYTHONPATH");
    let ndk = value("WEKIT_OMVLL_NDK");
    let any_configured =
        plugin.is_some() || config.is_some() || python_path.is_some() || ndk.is_some();
    if !required && !any_configured {
        return Ok(None);
    }
    let missing = [
        ("WEKIT_OMVLL_PLUGIN", plugin.as_ref()),
        ("WEKIT_OMVLL_CONFIG", config.as_ref()),
        ("WEKIT_OMVLL_PYTHONPATH", python_path.as_ref()),
        ("WEKIT_OMVLL_NDK", ndk.as_ref()),
    ]
    .into_iter()
    .filter_map(|(name, value)| value.is_none().then_some(name))
    .collect::<Vec<_>>();
    if !missing.is_empty() {
        bail!(
            "O-MVLL is required for a release bootstrap loader; missing {}. Configure {} or set WEKIT_OMVLL_*",
            missing.join(", "),
            config_file.display()
        );
    }
    let plugin = plugin.unwrap();
    let config = config.unwrap();
    let python_path = python_path.unwrap();
    let ndk = ndk.unwrap();
    for (name, path) in [
        ("WEKIT_OMVLL_PLUGIN", &plugin),
        ("WEKIT_OMVLL_CONFIG", &config),
        ("WEKIT_OMVLL_PYTHONPATH", &python_path),
        ("WEKIT_OMVLL_NDK", &ndk),
    ] {
        if !path.exists() {
            bail!("{name} does not exist: {}", path.display());
        }
    }
    let version = ndk
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default();
    if !version.starts_with("29.") {
        bail!(
            "WEKIT_OMVLL_NDK must be an NDK r29 toolchain matching the pinned O-MVLL plugin, got {}",
            ndk.display()
        );
    }
    let host = host_prebuilt_tag()?;
    let toolchain_bin = ndk.join("toolchains/llvm/prebuilt").join(host).join("bin");
    let clang_bin = env::var_os("WEKIT_OMVLL_CLANG")
        .map(PathBuf::from)
        .unwrap_or_else(|| toolchain_bin.join(format!("aarch64-linux-android{MIN_SDK}-clang")));
    if !clang_bin.is_file() {
        bail!(
            "O-MVLL clang compiler does not exist: {}",
            clang_bin.display()
        );
    }
    let runtime_library_path = env::var_os("WEKIT_OMVLL_LD_LIBRARY_PATH")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let root = toolchain_bin.parent().unwrap();
            let lib64 = root.join("lib64");
            if lib64.is_dir() {
                lib64
            } else {
                root.join("lib")
            }
        });
    if !runtime_library_path.is_dir() {
        bail!(
            "O-MVLL runtime library directory does not exist: {}",
            runtime_library_path.display()
        );
    }
    Ok(Some(OmvllLoaderConfig {
        plugin,
        config,
        python_path,
        clang_bin,
        runtime_library_path,
    }))
}

fn build_bootstrap_loader(root: &Path, abi_names: &[String], omvll_mode: OmvllMode) -> Result<()> {
    let omvll = omvll_loader_config(root, omvll_mode)?;
    let ndk = pinned_ndk_dir(root, None)?;
    let bin = ndk
        .join("toolchains/llvm/prebuilt")
        .join(host_prebuilt_tag()?)
        .join("bin");
    let source = root.join("bootstrap/src/main/cpp/wekit_loader.c");
    for abi in resolve_abis(abi_names)? {
        let destination = root
            .join("bootstrap/src/main/jniLibs")
            .join(abi.android_name)
            .join("libwekit_loader.so");
        fs::create_dir_all(destination.parent().unwrap())?;
        let temporary = destination.with_extension("so.part");
        let compiler = omvll
            .as_ref()
            .map(|config| config.clang_bin.clone())
            .unwrap_or_else(|| {
                bin.join(format!(
                    "{}{MIN_SDK}-clang{}",
                    abi.clang_prefix,
                    if cfg!(windows) { ".cmd" } else { "" }
                ))
            });
        let mut command = Command::new(compiler);
        command
            .args([
                "-std=c11",
                "-O2",
                "-fPIC",
                "-shared",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-z,relro,-z,now",
            ])
            .arg(&source)
            .arg("-o")
            .arg(&temporary);
        if let Some(config) = &omvll {
            command.arg(format!("-fpass-plugin={}", config.plugin.display()));
            command.env("OMVLL_CONFIG", &config.config);
            command.env("OMVLL_PYTHONPATH", &config.python_path);
            command.env("LD_LIBRARY_PATH", &config.runtime_library_path);
        }
        if omvll.is_some() {
            let output = command
                .output()
                .context("failed to start O-MVLL bootstrap loader compiler")?;
            let stdout = String::from_utf8_lossy(&output.stdout);
            let stderr = String::from_utf8_lossy(&output.stderr);
            print!("{stdout}");
            eprint!("{stderr}");
            if !output.status.success() {
                bail!("compile bootstrap memfd JNI failed with {}", output.status);
            }
            for marker in [
                "WEKIT_OMVLL_SELECTED flatten_cfg loader_request",
                "WEKIT_OMVLL_SELECTED flatten_cfg loader_decrypt_payload",
                "WEKIT_OMVLL_SELECTED flatten_cfg loader_configuration",
                "WEKIT_OMVLL_SELECTED arithmetic loader_request",
                "WEKIT_OMVLL_SELECTED arithmetic loader_decrypt_payload",
                "WEKIT_OMVLL_SELECTED arithmetic loader_configuration",
                "WEKIT_OMVLL_SELECTED strings wekit_loader.c",
            ] {
                if !stdout.contains(marker) && !stderr.contains(marker) {
                    bail!("O-MVLL compiler did not report required transform: {marker}");
                }
            }
        } else {
            run_checked(&mut command, "compile bootstrap memfd JNI")?;
        }
        let strip = bin.join("llvm-strip");
        run_cmd_owned(
            strip.to_str().context("non-UTF-8 llvm-strip path")?,
            &["--strip-all".into(), temporary.display().to_string()],
            root,
        )?;
        let nm = bin.join("llvm-nm");
        let symbols = Command::new(&nm)
            .args(["-D", "--defined-only"])
            .arg(&temporary)
            .output()
            .with_context(|| {
                format!("failed to inspect stripped loader: {}", temporary.display())
            })?;
        if !symbols.status.success() {
            bail!(
                "llvm-nm failed while inspecting stripped loader: {}",
                temporary.display()
            );
        }
        let symbol_text = String::from_utf8_lossy(&symbols.stdout);
        let exported = symbol_text
            .lines()
            .filter_map(|line| line.split_whitespace().last())
            .collect::<Vec<_>>();
        if exported != ["JNI_OnLoad"] {
            bail!(
                "stripped loader exports unexpected symbols: {}",
                exported.join(", ")
            );
        }
        copy_if_changed(&temporary, &destination)?;
        fs::remove_file(temporary)?;
        println!("build(loader): {}", destination.display());
    }
    Ok(())
}

fn task_publish_payload(args: &PublishArgs) -> Result<()> {
    let root = workspace_root();
    let mut command = Command::new(if cfg!(windows) { "python" } else { "python3" });
    command
        .current_dir(&root)
        .arg(root.join(".github/scripts/publish-payload.py"))
        .arg("--apk")
        .arg(&args.apk)
        .args([
            "--api-url",
            &args.api_url,
            "--audience",
            &args.audience,
            "--key-id",
            &args.key_id,
            "--channel",
            &args.channel,
        ])
        .arg("--signing-key-file")
        .arg(&args.signing_key_file)
        .arg("--output-dir")
        .arg(&args.output_dir);
    if let Some(id) = &args.payload_id {
        command.args(["--payload-id", id]);
    }
    if args.prepare_only {
        command.arg("--prepare-only");
    }
    if let Some(path) = &args.local_secrets_file {
        command.arg("--local-secrets-file").arg(path);
    }
    run_checked(&mut command, "prepare/publish private encrypted payload")
}

/// Build exactly the private release payload, then publish that immutable APK.
/// The bootstrap APK is intentionally not part of this command: it is the
/// separately distributed loader and must not be rebuilt for each payload.
fn task_publish(args: PublishArgs) -> Result<()> {
    let build = BuildArgs {
        native_only: false,
        release: true,
        save_symbols: false,
        native: NativeArgs { abis: Vec::new() },
    };
    task_build_module(&build, "app")?;
    task_publish_payload(&args)
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
            &[
                "build",
                "--locked",
                "--release",
                "--target",
                spec.cargo_triple,
            ],
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

        println!(
            "build(native):  {} → {}",
            so_src.display(),
            so_dst.display()
        );
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
    // Do not recursively initialize LSPlant's unrelated SSH test submodules.
    // The explicit override checks out its pinned gitlink despite update=none.
    run_cmd(
        "git",
        &[
            "-c",
            "submodule.third_party/lsplant.update=checkout",
            "submodule",
            "update",
            "--init",
            "--",
            "third_party/lsplant",
            "third_party/xz-embedded",
        ],
        root,
    )?;
    run_cmd(
        "git",
        &[
            "submodule",
            "update",
            "--init",
            "--recursive",
            "--",
            "lsplant/src/main/jni/external/dex_builder",
        ],
        &root.join("third_party/lsplant"),
    )?;
    let ndk = pinned_ndk_dir(root, None)?;
    let strip = ndk
        .join("toolchains/llvm/prebuilt")
        .join(host_prebuilt_tag()?)
        .join("bin/llvm-strip");
    let cargo = env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let tooling = zygisk_dir(root);
    for abi in resolve_abis(abi_names)? {
        // The package release profile retains symbols; only the packaged copy is stripped.
        run_cmd(
            "uv",
            &[
                "run",
                "--locked",
                "--project",
                &tooling.to_string_lossy(),
                "--group",
                "build",
                &cargo,
                "build",
                "--locked",
                "-p",
                ZYGISK_CARGO_PACKAGE,
                "--target",
                abi.cargo_triple,
                "--release",
            ],
            &zygisk_crate_dir(root),
        )?;
        let name = format!("lib{ZYGISK_MODULE_ID}.so");
        let source = root
            .join("target")
            .join(abi.cargo_triple)
            .join("release")
            .join(&name);
        let destination = root
            .join("bootstrap/src/main/jniLibs")
            .join(abi.android_name)
            .join(&name);
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
        "lib/arm64-v8a/libwekit_loader.so",
        "lib/arm64-v8a/libandroidx.graphics.path.so",
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

// ── Task: fmt ──────────────────────────────────────────────────────────────────

fn task_fmt() -> Result<()> {
    let root = workspace_root();

    println!("fmt: cargo fmt --all");
    run_cargo(&["fmt", "--all"], &root)?;

    println!("fmt: ./gradlew spotlessApply");
    run_gradlew(&["spotlessApply"], &root)?;

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

    fn parse_install_args(extra: &[&str]) -> InstallArgs {
        let mut argv = vec!["xtask", "bootstrap", "install"];
        argv.extend_from_slice(extra);
        match Cli::try_parse_from(argv).unwrap().command {
            Cmd::Bootstrap(BootstrapArgs {
                command: BootstrapCommand::Install(args),
            }) => args,
            _ => unreachable!(),
        }
    }

    fn parse_bootstrap_build_args(extra: &[&str]) -> BootstrapBuildArgs {
        let mut argv = vec!["xtask", "bootstrap", "build"];
        argv.extend_from_slice(extra);
        match Cli::try_parse_from(argv).unwrap().command {
            Cmd::Bootstrap(BootstrapArgs {
                command: BootstrapCommand::Build(args),
            }) => args,
            _ => unreachable!(),
        }
    }

    fn parse_payload_build_args(extra: &[&str]) -> BuildArgs {
        let mut argv = vec!["xtask", "payload", "build"];
        argv.extend_from_slice(extra);
        match Cli::try_parse_from(argv).unwrap().command {
            Cmd::Payload(PayloadArgs {
                command: PayloadCommand::Build(args),
            }) => args,
            _ => unreachable!(),
        }
    }

    #[test]
    fn scoped_build_commands_parse_their_module_options() {
        assert_eq!(
            bootstrap_gradle_task("assemble", false),
            ":bootstrap:assembleRelease"
        );
        assert_eq!(
            bootstrap_gradle_task("assemble", true),
            ":bootstrap:assembleLocal"
        );
        assert!(!parse_bootstrap_build_args(&[]).local);
        let local = parse_bootstrap_build_args(&["--local"]);
        let local_release = parse_bootstrap_build_args(&["--local", "--release"]);
        assert!(local.local && !local.release);
        assert!(local_release.local && local_release.release);
        assert_eq!(
            embedded_payload_gradle_arg(local.local, local.release).as_deref(),
            Some("-Pwekit.embeddedPayloadBuildType=debug")
        );
        assert_eq!(
            embedded_payload_gradle_arg(local_release.local, local_release.release).as_deref(),
            Some("-Pwekit.embeddedPayloadBuildType=release")
        );
        assert!(Cli::try_parse_from(["xtask", "bootstrap", "build", "--release"]).is_err());
        assert!(
            Cli::try_parse_from(["xtask", "bootstrap", "build", "--local", "--native-only"])
                .is_err()
        );
        assert!(parse_payload_build_args(&["--release"]).release);
        assert!(parse_payload_build_args(&["--native-only"]).native_only);
        assert!(Cli::try_parse_from(["xtask", "payload", "install"]).is_err());
        assert!(Cli::try_parse_from(["xtask", "build"]).is_err());
    }

    #[test]
    fn bootstrap_install_selects_release_or_local() {
        let release = parse_install_args(&[]);
        let local = parse_install_args(&["--local"]);
        let local_release = parse_install_args(&["--local", "--release"]);
        assert!(!release.local);
        assert!(local.local && !local.release);
        assert!(local_release.local && local_release.release);
        assert_eq!(
            bootstrap_gradle_task("install", release.local),
            ":bootstrap:installRelease"
        );
        assert_eq!(
            bootstrap_gradle_task("install", local.local),
            ":bootstrap:installLocal"
        );
        assert!(Cli::try_parse_from(["xtask", "bootstrap", "install", "--debug"]).is_err());
        assert!(Cli::try_parse_from(["xtask", "bootstrap", "install", "--release"]).is_err());
    }

    #[test]
    fn local_bootstrap_skips_omvll_configuration() {
        assert!(
            omvll_loader_config(Path::new("missing-omvll-config"), OmvllMode::Disabled)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn install_zygisk_accepts_install_options_and_rejects_them_for_app_install() {
        let args = parse_install_args(&[
            "--zygisk",
            "--local",
            "--release",
            "--device",
            "serial",
            "--root",
            "ksu",
            "--reboot",
        ]);
        assert!(args.zygisk && args.local && args.release && args.reboot);
        assert_eq!(
            bootstrap_apk_path(args.local, args.release),
            "bootstrap/build/outputs/apk/local/bootstrap-local-release.apk"
        );
        assert_eq!(
            bootstrap_apk_path(true, false),
            "bootstrap/build/outputs/apk/local/bootstrap-local-debug.apk"
        );
        assert_eq!(
            bootstrap_gradle_task("assemble", args.local),
            ":bootstrap:assembleLocal"
        );
        assert_eq!(args.device.as_deref(), Some("serial"));
        assert_eq!(args.root.as_deref(), Some("ksu"));
        assert!(Cli::try_parse_from(["xtask", "bootstrap", "install", "--root", "ksu"]).is_err());
        assert!(Cli::try_parse_from(["xtask", "bootstrap", "install", "--reboot"]).is_err());
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
