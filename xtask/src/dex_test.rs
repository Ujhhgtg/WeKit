//! Desktop DexKit resolver orchestration.
//!
//! The Rust command owns APK discovery, the pinned Linux DexKit build, and one
//! Gradle/JVM worker per APK. The worker itself lives in the app's JVM test
//! source set so it executes the production Kotlin resolver code.

use anyhow::{Context, Result, bail};
use clap::Args;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

use crate::workspace_root;

const DEXKIT_REPOSITORY: &str = "https://github.com/LuckyPray/DexKit.git";
const DEXKIT_REVISION: &str = "ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a";

#[derive(Args, Debug)]
pub struct DexTestArgs {
    /// APK path. Repeat to test multiple versions; without this option ~/coding/wechat_*.apk is used.
    #[arg(long = "apk", value_name = "APK")]
    pub apks: Vec<PathBuf>,

    /// Report root. Defaults to <repository>/dex-test-results.
    #[arg(long, value_name = "DIR")]
    pub output_dir: Option<PathBuf>,

    /// Comma-separated exact feature class names or fully qualified class names.
    #[arg(long, value_name = "FEATURES", value_parser = parse_feature_filter)]
    pub features: Option<String>,

    /// Print every successful delegate and descriptor.
    #[arg(long)]
    pub verbose: bool,
}

#[derive(Clone, Debug)]
pub struct ApkIdentity {
    pub path: PathBuf,
    pub sha256: String,
}

#[derive(Clone, Debug)]
pub struct DexKitNative {
    pub version: String,
    pub revision: String,
    pub library_path: PathBuf,
    pub architecture: String,
}

#[derive(Clone, Debug)]
pub struct ApkMetadata {
    pub version_code: i64,
    pub version_name: String,
    pub build_tag: String,
    pub is_google_play: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ApkOutcome {
    Pass,
    Fail,
    InfrastructureFailure,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DelegateReport {
    key: String,
    status: String,
    descriptor: Option<String>,
    is_placeholder: bool,
    message: Option<String>,
    exception_type: Option<String>,
    stack_trace: Option<String>,
    blocked_by: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct FeatureReport {
    class_name: String,
    display_name: String,
    method_hash: String,
    outcome: String,
    elapsed_millis: i64,
    delegates: Vec<DelegateReport>,
    feature_error: Option<ErrorReport>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Counts {
    success: i64,
    expected_failure: i64,
    unexpected_failure: i64,
    blocked: i64,
    incomplete: i64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Environment {
    dex_kit_version: String,
    dex_kit_revision: String,
    architecture: String,
    jvm_version: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorReport {
    message: Option<String>,
    exception_type: Option<String>,
    stack_trace: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ApkReport {
    schema_version: i64,
    worker_pid: i64,
    apk_path: String,
    file_name: String,
    label: String,
    apk_size: i64,
    apk_sha256: String,
    version_code: i64,
    version_name: String,
    build_tag: String,
    is_google_play: bool,
    dex_count: i64,
    environment: Environment,
    started_at: String,
    finished_at: String,
    elapsed_millis: i64,
    outcome: ApkOutcome,
    counts: Counts,
    features: Vec<FeatureReport>,
    infrastructure_error: Option<ErrorReport>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Summary {
    schema_version: i64,
    run_id: String,
    started_at: String,
    finished_at: String,
    outcome: ApkOutcome,
    reports: Vec<SummaryReport>,
    counts: Counts,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SummaryReport {
    label: String,
    apk_path: String,
    report_path: String,
    outcome: ApkOutcome,
    version_code: i64,
    version_name: String,
    is_google_play: bool,
    counts: Counts,
}

pub fn task_dex_test(args: DexTestArgs) -> Result<()> {
    let root = workspace_root();
    let started = now_rfc3339();
    let apks = if args.apks.is_empty() {
        discover_apks()?.into_iter().map(|path| canonical_apk(path)).collect::<Result<Vec<_>>>()?
    } else {
        normalize_explicit_apks(&args.apks)?
    };
    if apks.is_empty() {
        bail!("no WeChat APKs found; pass --apk /absolute/path/to/wechat.apk or place files under ~/coding/wechat_*.apk")
    }

    let output_root = args.output_dir
        .map(|path| if path.is_absolute() { path } else { env::current_dir().unwrap().join(path) })
        .unwrap_or_else(|| root.join("dex-test-results"));
    let run_dir = create_run_dir(&output_root)?;
    let native = ensure_linux_dexkit(&root)?;
    let identities = apks.iter().map(|path| ApkIdentity { path: path.clone(), sha256: sha256_file(path).unwrap_or_default() }).collect::<Vec<_>>();
    let report_names = report_file_names(&identities);

    println!("dex-test: {} APK(s), native {} ({})", apks.len(), native.version, native.library_path.display());
    let mut reports = Vec::with_capacity(apks.len());
    for (index, apk) in apks.iter().enumerate() {
        let report_path = run_dir.join(&report_names[index]);
        let metadata = match read_apk_metadata(&root, apk) {
            Ok(metadata) => metadata,
            Err(error) => {
                let report = infrastructure_report(apk, &native, &error);
                write_json_atomic(&report_path, &report)?;
                render_apk(&report, &report_path, args.verbose);
                reports.push((report, report_path));
                continue;
            }
        };
        println!("\n--- [{} / {}] {} ---", index + 1, apks.len(), apk.display());
        let status = run_worker(
            &root,
            apk,
            &metadata,
            &native,
            &report_path,
            args.features.as_deref(),
        );
        let report = match (status, read_report(&report_path)) {
            (_, Ok(report)) => report,
            (Ok(status), Err(error)) => infrastructure_report(apk, &native, &anyhow::anyhow!("worker exited {status}; report unavailable: {error}")),
            (Err(error), _) => infrastructure_report(apk, &native, &error),
        };
        if !matches!(report.outcome, ApkOutcome::InfrastructureFailure) && report.apk_sha256.is_empty() {
            // Keep worker reports self-contained even when an older worker omitted file identity.
            let mut report = report;
            report.apk_sha256 = identities[index].sha256.clone();
            report.apk_size = fs::metadata(apk).map(|meta| meta.len() as i64).unwrap_or_default();
            write_json_atomic(&report_path, &report)?;
            reports.push((report, report_path));
        } else {
            reports.push((report, report_path));
        }
        render_apk(&reports.last().unwrap().0, &reports.last().unwrap().1, args.verbose);
    }

    let summary = build_summary(&run_dir, &started, &reports);
    let summary_path = run_dir.join("summary.json");
    write_json_atomic(&summary_path, &summary)?;
    render_summary(&summary);
    println!("\nreports: {}", run_dir.display());
    if matches!(summary.outcome, ApkOutcome::Pass) {
        Ok(())
    } else {
        bail!("dex resolution test found failures; reports: {}", run_dir.display())
    }
}

fn parse_feature_filter(value: &str) -> std::result::Result<String, String> {
    let selectors = value.split(',').map(str::trim).collect::<Vec<_>>();
    if selectors.is_empty() || selectors.iter().any(|selector| selector.is_empty()) {
        return Err("features must be a comma-separated list of non-empty class names".to_string());
    }
    Ok(selectors.join(","))
}

fn discover_apks() -> Result<Vec<PathBuf>> {
    let home = env::var_os("HOME").context("HOME is not set; cannot discover ~/coding/wechat_*.apk")?;
    let dir = PathBuf::from(home).join("coding");
    let mut files = fs::read_dir(&dir).with_context(|| format!("cannot read {}", dir.display()))?
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| path.is_file())
        .filter(|path| path.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.starts_with("wechat_") && name.ends_with(".apk")))
        .collect::<Vec<_>>();
    files.sort_by(|a, b| natural_cmp(&a.to_string_lossy(), &b.to_string_lossy()));
    Ok(files)
}

fn canonical_apk(path: PathBuf) -> Result<PathBuf> {
    let canonical = fs::canonicalize(&path).with_context(|| format!("cannot resolve APK path {}", path.display()))?;
    if !canonical.is_file() {
        bail!("APK is not a regular file: {}", canonical.display())
    }
    Ok(canonical)
}

pub fn normalize_explicit_apks(paths: &[PathBuf]) -> Result<Vec<PathBuf>> {
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for path in paths {
        let canonical = canonical_apk(path.clone())?;
        if seen.insert(canonical.clone()) {
            result.push(canonical);
        }
    }
    Ok(result)
}

pub fn natural_cmp(left: &str, right: &str) -> Ordering {
    let left_parts = split_natural(left);
    let right_parts = split_natural(right);
    for (a, b) in left_parts.iter().zip(right_parts.iter()) {
        let ordering = match (a.parse::<u64>(), b.parse::<u64>()) {
            (Ok(a), Ok(b)) => a.cmp(&b),
            _ => a.cmp(b),
        };
        if ordering != Ordering::Equal { return ordering; }
    }
    left_parts.len().cmp(&right_parts.len()).then_with(|| left.cmp(right))
}

fn split_natural(value: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut start = 0;
    let bytes = value.as_bytes();
    for index in 1..=bytes.len() {
        if index == bytes.len() || bytes[index].is_ascii_digit() != bytes[start].is_ascii_digit() {
            parts.push(&value[start..index]);
            start = index;
        }
    }
    parts
}

fn create_run_dir(root: &Path) -> Result<PathBuf> {
    fs::create_dir_all(root)?;
    let base = OffsetDateTime::now_utc().format(&time::macros::format_description!("[year]-[month]-[day]T[hour]-[minute]-[second]Z"))?;
    let mut candidate = root.join(&base);
    let mut suffix = 2;
    while candidate.exists() {
        candidate = root.join(format!("{base}-{suffix}"));
        suffix += 1;
    }
    fs::create_dir(&candidate)?;
    Ok(candidate)
}

pub fn report_file_names(identities: &[ApkIdentity]) -> Vec<String> {
    let mut occurrences = HashMap::<String, usize>::new();
    for identity in identities {
        let filename = identity.path.file_name().and_then(|name| name.to_str()).unwrap_or("apk").to_owned();
        *occurrences.entry(filename).or_default() += 1;
    }
    identities.iter().map(|identity| {
        let filename = identity.path.file_name().and_then(|name| name.to_str()).unwrap_or("apk");
        let stem = filename.strip_suffix(".apk").unwrap_or(filename);
        if occurrences[filename] == 1 {
            format!("{stem}.json")
        } else {
            format!("{stem}-{}.json", &identity.sha256[..identity.sha256.len().min(8)])
        }
    }).collect()
}

fn ensure_linux_dexkit(root: &Path) -> Result<DexKitNative> {
    if env::consts::OS != "linux" {
        bail!("dex-test currently supports Linux only (detected {})", env::consts::OS)
    }
    let architecture = match env::consts::ARCH {
        "x86_64" => "x86_64",
        "aarch64" => "aarch64",
        other => bail!("unsupported Linux architecture for DexKit native build: {other}"),
    }.to_string();
    let versions = fs::read_to_string(root.join("gradle/libs.versions.toml"))?;
    let catalog: crate::GradleVersionCatalog = toml::from_str(&versions)?;
    let version = catalog.versions.dexkit.context("versions.dexkit is missing")?;
    let cache_root = root.join(".wekit/dex-test");
    let source_dir = cache_root.join("source").join(format!("DexKit-{version}"));
    if source_dir.exists() {
        validate_dexkit_source(&source_dir, &version)?;
    } else {
        fs::create_dir_all(source_dir.parent().unwrap())?;
        let temp = source_dir.with_extension(format!("tmp-{}", std::process::id()));
        if temp.exists() { bail!("stale DexKit temporary checkout exists: {}", temp.display()); }
        run_command(Command::new("git").args(["clone", "--depth", "1", "--branch", &version, DEXKIT_REPOSITORY]).arg(&temp), "clone DexKit")?;
        validate_dexkit_source(&temp, &version)?;
        fs::rename(&temp, &source_dir)?;
    }
    let build_dir = cache_root.join("native").join(&version).join(&architecture).join("cmake");
    fs::create_dir_all(&build_dir)?;
    let source_cpp = source_dir.join("dexkit/src/main/cpp");
    run_command(
        Command::new("cmake").args(["-S", source_cpp.to_str().context("non-utf8 DexKit source path")?, "-B", build_dir.to_str().context("non-utf8 DexKit build path")?, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG", "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG", "-DDEXKIT_ENABLE_INTERNAL_METRICS=OFF", "-DDEXKIT_ENABLE_INTERNAL_METRICS_API=OFF"]),
        "configure DexKit with CMake",
    )?;
    run_command(Command::new("cmake").args(["--build", build_dir.to_str().context("non-utf8 DexKit build path")?, "--target", "dexkit"]), "build DexKit native library")?;
    let library_path = find_named_file(&build_dir, "libdexkit.so").context("DexKit build did not produce libdexkit.so")?;
    Ok(DexKitNative { version, revision: DEXKIT_REVISION.to_string(), library_path, architecture })
}

fn validate_dexkit_source(source: &Path, version: &str) -> Result<()> {
    let head = command_output(Command::new("git").args(["-C", source.to_str().unwrap(), "rev-parse", "HEAD"]), "read DexKit revision")?;
    if head.trim() != DEXKIT_REVISION {
        bail!("cached DexKit {version} revision is {}, expected {DEXKIT_REVISION}; remove only {} and retry", head.trim(), source.display())
    }
    let tag = command_output(Command::new("git").args(["-C", source.to_str().unwrap(), "describe", "--tags", "--exact-match"]), "read DexKit tag")?;
    if tag.trim() != version {
        bail!("cached DexKit checkout tag is {}, expected {version}", tag.trim())
    }
    Ok(())
}

fn find_named_file(root: &Path, name: &str) -> Option<PathBuf> {
    walkdir::WalkDir::new(root).into_iter().filter_map(Result::ok).map(|entry| entry.into_path()).find(|path| path.file_name().and_then(|value| value.to_str()) == Some(name) && path.is_file()).and_then(|path| fs::canonicalize(path).ok())
}

fn read_apk_metadata(_root: &Path, apk: &Path) -> Result<ApkMetadata> {
    let analyzer = find_apkanalyzer().context("cannot find apkanalyzer under ANDROID_HOME/ANDROID_SDK_ROOT")?;
    let application_id = command_output(Command::new(&analyzer).args(["manifest", "application-id", apk.to_str().context("non-utf8 APK path")?]), "read APK application id")?;
    if application_id.trim() != "com.tencent.mm" {
        bail!("{} is not a WeChat APK (application id: {})", apk.display(), application_id.trim())
    }
    let version_code = command_output(Command::new(&analyzer).args(["manifest", "version-code", apk.to_str().unwrap()]), "read APK version code")?.trim().parse()?;
    let version_name = command_output(Command::new(&analyzer).args(["manifest", "version-name", apk.to_str().unwrap()]), "read APK version name")?.trim().to_string();
    let manifest = command_output(Command::new(&analyzer).args(["manifest", "print", apk.to_str().unwrap()]), "print APK manifest")?;
    let build_tag = manifest_attribute(&manifest, "com.tencent.mm.BuildInfo.BUILD_TAG", "android:value").unwrap_or_default();
    Ok(ApkMetadata { version_code, version_name, is_google_play: build_tag.to_ascii_uppercase().contains("GP"), build_tag })
}

fn find_apkanalyzer() -> Option<PathBuf> {
    let mut roots = Vec::new();
    if let Some(root) = env::var_os("ANDROID_HOME") { roots.push(PathBuf::from(root)); }
    if let Some(root) = env::var_os("ANDROID_SDK_ROOT") { roots.push(PathBuf::from(root)); }
    roots.into_iter().flat_map(|root| [root.join("cmdline-tools/latest/bin/apkanalyzer"), root.join("tools/bin/apkanalyzer")]).find(|path| path.is_file())
}

fn manifest_attribute(xml: &str, name: &str, attribute: &str) -> Option<String> {
    let marker = format!("android:name=\"{name}\"");
    let start = xml.find(&marker)?;
    let end = xml[start..].find("/>").map(|offset| start + offset).unwrap_or(xml.len());
    let section = &xml[start..end];
    let marker = format!("{attribute}=\"");
    let value_start = section.find(&marker)? + marker.len();
    let value_end = section[value_start..].find('"')? + value_start;
    Some(section[value_start..value_end].to_string())
}

fn run_worker(
    root: &Path,
    apk: &Path,
    metadata: &ApkMetadata,
    native: &DexKitNative,
    report: &Path,
    features: Option<&str>,
) -> Result<i32> {
    let gradle = root.join("gradlew");
    let mut properties = vec![
        ("wekit.dexTest.apk", apk.to_string_lossy().to_string()),
        ("wekit.dexTest.nativeLibrary", native.library_path.to_string_lossy().to_string()),
        ("wekit.dexTest.report", report.to_string_lossy().to_string()),
        ("wekit.dexTest.dexKitVersion", native.version.clone()),
        ("wekit.dexTest.dexKitRevision", native.revision.clone()),
        ("wekit.dexTest.versionCode", metadata.version_code.to_string()),
        ("wekit.dexTest.versionName", metadata.version_name.clone()),
        ("wekit.dexTest.buildTag", metadata.build_tag.clone()),
        ("wekit.dexTest.isGooglePlay", metadata.is_google_play.to_string()),
    ];
    if let Some(features) = features {
        properties.push(("wekit.dexTest.features", features.to_string()));
    }
    let mut command = Command::new(&gradle);
    command.current_dir(root).args([":app:testStandardDebugUnitTest", "-PdexTestWorker=true"]);
    for (key, value) in properties { command.arg(format!("-P{key}={value}")); }
    println!("worker: {:?}", command);
    let status = command.status().with_context(|| format!("launch worker for {}", apk.display()))?;
    Ok(status.code().unwrap_or(1))
}

fn read_report(path: &Path) -> Result<ApkReport> {
    let text = fs::read_to_string(path).with_context(|| format!("read worker report {}", path.display()))?;
    serde_json::from_str(&text).with_context(|| format!("parse worker report {}", path.display()))
}

fn infrastructure_report(apk: &Path, native: &DexKitNative, error: &anyhow::Error) -> ApkReport {
    ApkReport {
        schema_version: 1,
        worker_pid: 0,
        apk_path: apk.to_string_lossy().to_string(),
        file_name: apk.file_name().and_then(|name| name.to_str()).unwrap_or("apk").to_string(),
        label: apk.file_stem().and_then(|name| name.to_str()).unwrap_or("apk").to_string(),
        apk_size: fs::metadata(apk).map(|meta| meta.len() as i64).unwrap_or_default(),
        apk_sha256: sha256_file(apk).unwrap_or_default(),
        version_code: 0,
        version_name: String::new(),
        build_tag: String::new(),
        is_google_play: false,
        dex_count: 0,
        environment: Environment { dex_kit_version: native.version.clone(), dex_kit_revision: native.revision.clone(), architecture: native.architecture.clone(), jvm_version: String::new() },
        started_at: now_rfc3339(),
        finished_at: now_rfc3339(),
        elapsed_millis: 0,
        outcome: ApkOutcome::InfrastructureFailure,
        counts: Counts::default(),
        features: Vec::new(),
        infrastructure_error: Some(ErrorReport { message: Some(format!("{error:#}")), exception_type: Some("xtask::InfrastructureFailure".to_string()), stack_trace: None }),
    }
}

fn build_summary(run_dir: &Path, started: &str, reports: &[(ApkReport, PathBuf)]) -> Summary {
    let mut counts = Counts::default();
    let mut summaries = Vec::new();
    for (report, path) in reports {
        counts.success += report.counts.success;
        counts.expected_failure += report.counts.expected_failure;
        counts.unexpected_failure += report.counts.unexpected_failure;
        counts.blocked += report.counts.blocked;
        counts.incomplete += report.counts.incomplete;
        summaries.push(SummaryReport { label: report.label.clone(), apk_path: report.apk_path.clone(), report_path: path.strip_prefix(run_dir).unwrap_or(path).to_string_lossy().to_string(), outcome: report.outcome.clone(), version_code: report.version_code, version_name: report.version_name.clone(), is_google_play: report.is_google_play, counts: report.counts.clone() });
    }
    let outcome = if reports.iter().all(|(report, _)| matches!(report.outcome, ApkOutcome::Pass)) { ApkOutcome::Pass } else if reports.iter().any(|(report, _)| matches!(report.outcome, ApkOutcome::InfrastructureFailure)) { ApkOutcome::InfrastructureFailure } else { ApkOutcome::Fail };
    Summary { schema_version: 1, run_id: run_dir.file_name().and_then(|name| name.to_str()).unwrap_or_default().to_string(), started_at: started.to_string(), finished_at: now_rfc3339(), outcome, reports: summaries, counts }
}

fn render_apk(report: &ApkReport, path: &Path, verbose: bool) {
    println!("\n=== {} ===", report.file_name);
    println!("DEX files: {}  elapsed: {:.1}s", report.dex_count, report.elapsed_millis as f64 / 1000.0);
    for feature in &report.features {
        if feature.outcome == "PASS" && !verbose { println!("[PASS] {}", feature.display_name); continue; }
        println!("[{}] {}", feature.outcome, feature.display_name);
        for delegate in &feature.delegates {
            if !verbose && delegate.status == "SUCCESS" { continue; }
            println!("  {} [{}] {}", delegate.key, delegate.status, delegate.descriptor.as_deref().unwrap_or(""));
            if let Some(message) = &delegate.message { println!("    {message}"); }
        }
        if let Some(error) = &feature.feature_error { println!("  error: {}", error.message.as_deref().or(error.exception_type.as_deref()).unwrap_or("unknown")); }
    }
    if let Some(error) = &report.infrastructure_error { println!("[INFRASTRUCTURE_FAILURE] {}", error.message.as_deref().unwrap_or("unknown")); }
    println!("  success={} expected={} unexpected={} blocked={} incomplete={}", report.counts.success, report.counts.expected_failure, report.counts.unexpected_failure, report.counts.blocked, report.counts.incomplete);
    println!("  report: {}", path.display());
}

fn render_summary(summary: &Summary) {
    println!("\nSummary: {:?}", summary.outcome);
    for report in &summary.reports {
        println!("{}  {:?}  {} success  {} expected  {} unexpected  {} blocked", report.label, report.outcome, report.counts.success, report.counts.expected_failure, report.counts.unexpected_failure, report.counts.blocked);
    }
    println!("totals: success={} expected={} unexpected={} blocked={} incomplete={}", summary.counts.success, summary.counts.expected_failure, summary.counts.unexpected_failure, summary.counts.blocked, summary.counts.incomplete);
}

fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    fs::create_dir_all(path.parent().context("report has no parent")?)?;
    let temp = path.with_extension("json.tmp");
    fs::write(&temp, serde_json::to_vec_pretty(value)?)?;
    fs::rename(&temp, path).or_else(|_| { fs::copy(&temp, path)?; fs::remove_file(&temp) })?;
    Ok(())
}

fn command_output(command: &mut Command, description: &str) -> Result<String> {
    let output = command.output().with_context(|| format!("{description}: spawn failed"))?;
    if !output.status.success() { bail!("{description} failed ({}): {}", output.status, String::from_utf8_lossy(&output.stderr).trim()); }
    Ok(String::from_utf8(output.stdout).with_context(|| format!("{description} produced non-UTF-8 output"))?)
}

fn run_command(command: &mut Command, description: &str) -> Result<()> {
    let status = command.status().with_context(|| format!("{description}: spawn failed"))?;
    if !status.success() { bail!("{description} failed with {status}; reproduce command: {command:?}"); }
    Ok(())
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut input = fs::File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 1024 * 1024];
    loop {
        let read = std::io::Read::read(&mut input, &mut buffer)?;
        if read == 0 { break; }
        digest.update(&buffer[..read]);
    }
    Ok(digest.finalize().iter().map(|byte| format!("{byte:02x}")).collect())
}

fn now_rfc3339() -> String {
    OffsetDateTime::now_utc().format(&Rfc3339).unwrap_or_else(|_| {
        SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_secs().to_string()).unwrap_or_else(|_| "0".to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[derive(Parser)]
    struct TestCli {
        #[command(flatten)]
        args: DexTestArgs,
    }

    #[test]
    fn parses_feature_filter() {
        let cli = TestCli::try_parse_from(["dex-test", "--features", "AntiReadReceipts, AntiSecMsg"]).unwrap();
        assert_eq!(cli.args.features.as_deref(), Some("AntiReadReceipts,AntiSecMsg"));
    }

    #[test]
    fn rejects_empty_feature_filter_entries() {
        assert!(TestCli::try_parse_from(["dex-test", "--features", "AntiReadReceipts,"]).is_err());
        assert!(TestCli::try_parse_from(["dex-test", "--features", ""]).is_err());
    }

    #[test]
    fn natural_sort_orders_version_numbers() {
        let mut names = vec!["wechat_8074.apk", "wechat_8069_3020_play.apk", "wechat_8069.apk"];
        names.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(names, vec!["wechat_8069.apk", "wechat_8069_3020_play.apk", "wechat_8074.apk"]);
    }

    #[test]
    fn manifest_metadata_parser_detects_google_play() {
        let xml = r#"<meta-data android:name="com.tencent.mm.BuildInfo.BUILD_TAG" android:value="Android_Wechat_RELEASE_GP_AppBundle" />"#;
        assert_eq!(manifest_attribute(xml, "com.tencent.mm.BuildInfo.BUILD_TAG", "android:value").as_deref(), Some("Android_Wechat_RELEASE_GP_AppBundle"));
    }
}
