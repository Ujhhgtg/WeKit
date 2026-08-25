use anyhow::{Context, Result, bail};
use clap::Args;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashSet;
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};
use zip::ZipArchive;

use crate::dex_test::{ApkMetadata, DexKitNative, ensure_linux_dexkit, read_apk_metadata};
use crate::workspace_root;

#[derive(Args, Debug)]
pub struct MonetTestArgs {
    /// Standalone WeChat APK. Repeat to validate multiple samples.
    #[arg(long = "apk", value_name = "APK")]
    pub apks: Vec<PathBuf>,

    /// WeChat split-APK archive. Repeat to validate multiple samples.
    #[arg(long = "apks", value_name = "APKS")]
    pub archives: Vec<PathBuf>,

    /// Decoded app/src/main/res directory. Repeat to validate multiple samples.
    #[arg(long = "decoded-res", value_name = "RES_DIR")]
    pub decoded_resources: Vec<PathBuf>,

    /// Report root. Defaults to <repository>/monet-test-results.
    #[arg(long, value_name = "DIR")]
    pub output_dir: Option<PathBuf>,

    /// Print per-role success details in addition to failures and skips.
    #[arg(long)]
    pub verbose: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum SampleInput {
    Apk(PathBuf),
    Archive(PathBuf),
    Decoded(PathBuf),
}

impl SampleInput {
    fn path(&self) -> &Path {
        match self {
            Self::Apk(path) | Self::Archive(path) | Self::Decoded(path) => path,
        }
    }

    fn label(&self) -> String {
        match self {
            Self::Decoded(path) => decoded_label(path),
            Self::Apk(path) => path_label(path, ".apk"),
            Self::Archive(path) => path_label(path, ".apks"),
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum SampleOutcome {
    Pass,
    Fail,
    InfrastructureFailure,
}

#[derive(Clone, Debug)]
struct SampleResult {
    label: String,
    input_path: PathBuf,
    report_path: PathBuf,
    outcome: SampleOutcome,
    error: Option<String>,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AggregateSummary {
    schema_version: u32,
    run_id: String,
    outcome: SampleOutcome,
    reports: Vec<AggregateReport>,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct AggregateReport {
    label: String,
    input_path: String,
    report_path: String,
    outcome: SampleOutcome,
    error: Option<String>,
}

fn validate_inputs(inputs: &[SampleInput]) -> Result<()> {
    if inputs.is_empty() {
        bail!("monet-test requires at least one --apk, --apks, or --decoded-res input")
    }
    Ok(())
}

fn report_names(inputs: &[SampleInput]) -> Vec<String> {
    let mut used = HashSet::from([
        SUMMARY_FILE_NAME.to_string(),
        SUMMARY_TEMP_FILE_NAME.to_string(),
    ]);
    inputs
        .iter()
        .map(|input| {
            let label = input.label();
            let preferred = format!("{label}.json");
            if used.insert(preferred.clone()) {
                return preferred;
            }
            let mut suffix = 2;
            loop {
                let candidate = format!("{label}-{suffix}.json");
                if used.insert(candidate.clone()) {
                    return candidate;
                }
                suffix += 1;
            }
        })
        .collect()
}

fn decoded_label(path: &Path) -> String {
    let components = path
        .components()
        .filter_map(|component| component.as_os_str().to_str())
        .collect::<Vec<_>>();
    components
        .windows(5)
        .rev()
        .find(|window| window[1..] == ["app", "src", "main", "res"])
        .map(|window| window[0].to_string())
        .unwrap_or_else(|| path_label(path, ""))
}

fn path_label(path: &Path, suffix: &str) -> String {
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("sample");
    name.strip_suffix(suffix).unwrap_or(name).to_string()
}

fn write_summary(
    run_dir: &Path,
    run_id: &str,
    results: &[SampleResult],
) -> Result<AggregateSummary> {
    let outcome = if results
        .iter()
        .all(|result| result.outcome == SampleOutcome::Pass)
    {
        SampleOutcome::Pass
    } else if results
        .iter()
        .any(|result| result.outcome == SampleOutcome::InfrastructureFailure)
    {
        SampleOutcome::InfrastructureFailure
    } else {
        SampleOutcome::Fail
    };
    let summary = AggregateSummary {
        schema_version: 1,
        run_id: run_id.to_string(),
        outcome,
        reports: results
            .iter()
            .map(|result| AggregateReport {
                label: result.label.clone(),
                input_path: result.input_path.to_string_lossy().to_string(),
                report_path: result
                    .report_path
                    .strip_prefix(run_dir)
                    .unwrap_or(&result.report_path)
                    .to_string_lossy()
                    .to_string(),
                outcome: result.outcome,
                error: result.error.clone(),
            })
            .collect(),
    };
    fs::create_dir_all(run_dir)?;
    let output = run_dir.join(SUMMARY_FILE_NAME);
    let temporary = run_dir.join(SUMMARY_TEMP_FILE_NAME);
    fs::write(&temporary, serde_json::to_vec_pretty(&summary)?)?;
    fs::rename(temporary, output)?;
    Ok(summary)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerReport {
    schema_version: u32,
    outcome: SampleOutcome,
    input: WorkerInput,
    #[serde(default)]
    roles: Vec<WorkerRole>,
    #[serde(default)]
    core_failures: Vec<String>,
    #[serde(default)]
    optional_skips: Vec<String>,
    dex_evidence: WorkerDexEvidence,
    #[serde(default)]
    overlays: Vec<WorkerOverlay>,
    failure_message: Option<String>,
    infrastructure_error: Option<WorkerError>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerInput {
    file_name: String,
    version_name: Option<String>,
    is_google_play: Option<bool>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerRole {
    role_id: String,
    failure: Option<String>,
    final_target: Option<WorkerTarget>,
}

#[derive(Debug, Deserialize)]
struct WorkerTarget {
    #[serde(rename = "type")]
    resource_type: String,
    name: String,
}

#[derive(Debug, Deserialize)]
struct WorkerDexEvidence {
    status: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerOverlay {
    file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerError {
    message: Option<String>,
}

pub fn task_monet_test(args: MonetTestArgs) -> Result<()> {
    let root = workspace_root();
    let inputs = normalize_inputs(&args)?;
    validate_inputs(&inputs)?;
    let run_dir = resolve_run_dir(&root, args.output_dir.as_deref())?;
    let run_id = run_dir
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("monet-test")
        .to_string();
    let native = if requires_dexkit_native(&inputs) {
        Some(ensure_linux_dexkit(&root)?)
    } else {
        None
    };
    let names = report_names(&inputs);
    let mut results = Vec::with_capacity(inputs.len());

    if let Some(native) = &native {
        println!(
            "monet-test: {} sample(s), DexKit {} ({})",
            inputs.len(),
            native.version,
            native.library_path.display(),
        );
    } else {
        println!(
            "monet-test: {} decoded sample(s), DexKit not initialized",
            inputs.len()
        );
    }
    for (index, input) in inputs.iter().enumerate() {
        let report_path = run_dir.join(&names[index]);
        println!(
            "\n--- [{} / {}] {} ---",
            index + 1,
            inputs.len(),
            input.path().display(),
        );
        let metadata = sample_metadata(&root, &run_dir, input);
        let worker = metadata.and_then(|metadata| {
            run_worker(&root, input, &metadata, native.as_ref(), &report_path).and_then(|status| {
                if status == 0 {
                    read_worker_report(&report_path)
                } else {
                    bail!("Monet JVM worker exited with status {status}")
                }
            })
        });
        let report = match worker {
            Ok(report) => report,
            Err(error) => {
                write_infrastructure_report(&report_path, input, native.as_ref(), &error)?;
                read_worker_report(&report_path)?
            }
        };
        render_worker_report(&report, &report_path, args.verbose);
        let error = report.failure_message.clone().or_else(|| {
            report
                .infrastructure_error
                .as_ref()
                .and_then(|error| error.message.clone())
        });
        results.push(SampleResult {
            label: input.label(),
            input_path: input.path().to_path_buf(),
            report_path,
            outcome: report.outcome,
            error,
        });
    }

    let summary = write_summary(&run_dir, &run_id, &results)?;
    println!("\nSummary: {:?}", summary.outcome);
    for report in &summary.reports {
        println!(
            "{}  {:?}{}",
            report.label,
            report.outcome,
            report
                .error
                .as_deref()
                .map(|error| format!("  {error}"))
                .unwrap_or_default(),
        );
    }
    println!("reports: {}", run_dir.display());
    if summary.outcome == SampleOutcome::Pass {
        Ok(())
    } else {
        bail!(
            "Monet sample validation found failures; reports: {}",
            run_dir.display()
        )
    }
}

fn requires_dexkit_native(inputs: &[SampleInput]) -> bool {
    inputs
        .iter()
        .any(|input| matches!(input, SampleInput::Apk(_) | SampleInput::Archive(_)))
}

fn normalize_inputs(args: &MonetTestArgs) -> Result<Vec<SampleInput>> {
    let mut inputs = Vec::new();
    let mut seen = HashSet::<(u8, PathBuf)>::new();
    for (kind, paths) in [
        (0u8, &args.apks),
        (1u8, &args.archives),
        (2u8, &args.decoded_resources),
    ] {
        for path in paths {
            let canonical = fs::canonicalize(path)
                .with_context(|| format!("cannot resolve Monet sample {}", path.display()))?;
            match kind {
                0 => {
                    require_regular_file(&canonical, "APK")?;
                    require_extension(&canonical, "apk")?;
                }
                1 => {
                    require_regular_file(&canonical, "APKS")?;
                    require_extension(&canonical, "apks")?;
                }
                2 => {
                    if !canonical.is_dir() {
                        bail!(
                            "decoded resource input is not a directory: {}",
                            canonical.display()
                        );
                    }
                    if !canonical.join("values/public.xml").is_file() {
                        bail!(
                            "decoded resource input has no values/public.xml: {}",
                            canonical.display()
                        );
                    }
                }
                _ => unreachable!(),
            }
            if seen.insert((kind, canonical.clone())) {
                inputs.push(match kind {
                    0 => SampleInput::Apk(canonical),
                    1 => SampleInput::Archive(canonical),
                    2 => SampleInput::Decoded(canonical),
                    _ => unreachable!(),
                });
            }
        }
    }
    Ok(inputs)
}

fn require_regular_file(path: &Path, kind: &str) -> Result<()> {
    if !path.is_file() {
        bail!("{kind} input is not a regular file: {}", path.display())
    }
    Ok(())
}

fn require_extension(path: &Path, expected: &str) -> Result<()> {
    if path.extension().and_then(|value| value.to_str()) != Some(expected) {
        bail!("expected .{expected} input: {}", path.display())
    }
    Ok(())
}

fn resolve_run_dir(root: &Path, explicit: Option<&Path>) -> Result<PathBuf> {
    if let Some(path) = explicit {
        let resolved = if path.is_absolute() {
            path.to_path_buf()
        } else {
            env::current_dir()?.join(path)
        };
        fs::create_dir_all(&resolved)?;
        return fs::canonicalize(&resolved)
            .with_context(|| format!("cannot resolve output directory {}", resolved.display()));
    }
    let output_root = root.join("monet-test-results");
    fs::create_dir_all(&output_root)?;
    let timestamp = OffsetDateTime::now_utc().format(&time::macros::format_description!(
        "[year]-[month]-[day]T[hour]-[minute]-[second]Z"
    ))?;
    let mut run_dir = output_root.join(&timestamp);
    let mut suffix = 2;
    while run_dir.exists() {
        run_dir = output_root.join(format!("{timestamp}-{suffix}"));
        suffix += 1;
    }
    fs::create_dir(&run_dir)?;
    Ok(run_dir)
}

fn sample_metadata(root: &Path, run_dir: &Path, input: &SampleInput) -> Result<ApkMetadata> {
    match input {
        SampleInput::Apk(apk) => read_apk_metadata(root, apk),
        SampleInput::Archive(apks) => read_apks_metadata(root, run_dir, apks),
        SampleInput::Decoded(path) => Ok(ApkMetadata {
            version_code: 0,
            version_name: infer_decoded_version(path).unwrap_or_else(|| input.label()),
            build_tag: String::new(),
            is_google_play: false,
        }),
    }
}

fn read_apks_metadata(root: &Path, run_dir: &Path, apks: &Path) -> Result<ApkMetadata> {
    let owned = extract_base_apk_for_metadata(apks, run_dir)?;
    let metadata = read_apk_metadata(root, &owned.path);
    let cleanup = owned.cleanup();
    match (metadata, cleanup) {
        (Ok(metadata), Ok(())) => Ok(metadata),
        (Err(error), Ok(())) => Err(error),
        (Ok(_), Err(error)) => Err(error),
        (Err(error), Err(cleanup_error)) => Err(error.context(cleanup_error)),
    }
}

struct OwnedMetadataApk {
    path: PathBuf,
    root: PathBuf,
}

impl OwnedMetadataApk {
    fn cleanup(self) -> Result<()> {
        fs::remove_file(&self.path)
            .with_context(|| format!("remove owned metadata APK {}", self.path.display()))?;
        fs::remove_dir(&self.root)
            .with_context(|| format!("remove owned metadata directory {}", self.root.display()))
    }
}

fn extract_base_apk_for_metadata(apks: &Path, scratch_parent: &Path) -> Result<OwnedMetadataApk> {
    let file = File::open(apks)?;
    let mut archive = ZipArchive::new(file)
        .with_context(|| format!("open APKS metadata archive {}", apks.display()))?;
    let mut base_indices = Vec::new();
    let mut names = HashSet::new();
    for entry_index in 0..archive.len() {
        let entry = archive.by_index(entry_index)?;
        if entry.is_dir() || !entry.name().ends_with(".apk") {
            continue;
        }
        validate_nested_apk_name(entry.name())?;
        if !names.insert(entry.name().to_string()) {
            bail!("duplicate nested APK entry: {}", entry.name())
        }
        if is_base_apk_name(entry.name()) {
            base_indices.push(entry_index);
        }
    }
    if base_indices.len() != 1 {
        bail!(
            "APKS must contain exactly one base.apk, found {}",
            base_indices.len()
        )
    }
    let root = create_owned_metadata_dir(scratch_parent)?;
    let temporary = root.join("base.apk");
    let extraction = (|| -> Result<OwnedMetadataApk> {
        let mut base = archive.by_index(base_indices[0])?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .with_context(|| format!("create owned metadata APK {}", temporary.display()))?;
        std::io::copy(&mut base, &mut output)?;
        output.flush()?;
        Ok(OwnedMetadataApk {
            path: temporary.clone(),
            root: root.clone(),
        })
    })();
    drop(archive);
    if extraction.is_err() {
        if temporary.exists() {
            let _ = fs::remove_file(&temporary);
        }
        let _ = fs::remove_dir(&root);
    }
    extraction
}

fn create_owned_metadata_dir(parent: &Path) -> Result<PathBuf> {
    static NEXT_ID: AtomicU64 = AtomicU64::new(0);

    fs::create_dir_all(parent)?;
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    for _ in 0..1024 {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let candidate = parent.join(format!(
            ".monet-metadata-{}-{time:x}-{id:x}",
            std::process::id(),
        ));
        match fs::create_dir(&candidate) {
            Ok(()) => return Ok(candidate),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => {
                return Err(error).with_context(|| {
                    format!("create owned metadata directory {}", candidate.display())
                });
            }
        }
    }
    bail!("could not allocate a unique owned metadata directory")
}

fn validate_nested_apk_name(name: &str) -> Result<()> {
    if name.is_empty() || name.starts_with('/') || name.contains('\\') {
        bail!("unsafe nested APK entry name: {name}")
    }
    if name.split('/').any(|segment| {
        segment.is_empty() || segment == "." || segment == ".." || segment.contains(':')
    }) {
        bail!("unsafe nested APK entry name: {name}")
    }
    Ok(())
}

fn is_base_apk_name(name: &str) -> bool {
    name == "base.apk" || name.ends_with("/base.apk")
}

fn infer_decoded_version(path: &Path) -> Option<String> {
    let label = decoded_label(path);
    let digits = label.strip_prefix("wechat_")?.get(..4)?;
    if !digits.starts_with("80") || !digits.chars().all(|value| value.is_ascii_digit()) {
        return None;
    }
    Some(format!("8.0.{}", &digits[2..]))
}

fn run_worker(
    root: &Path,
    input: &SampleInput,
    metadata: &ApkMetadata,
    native: Option<&DexKitNative>,
    report: &Path,
) -> Result<i32> {
    let gradle = root.join("gradlew");
    let mut properties = vec![
        ("wekit.monetTest.inputKind", input.kind_name().to_string()),
        (
            "wekit.monetTest.inputPath",
            input.path().to_string_lossy().to_string(),
        ),
        (
            "wekit.monetTest.report",
            report.to_string_lossy().to_string(),
        ),
        (
            "wekit.monetTest.dexKitVersion",
            native
                .map(|value| value.version.clone())
                .unwrap_or_else(|| "not-loaded".to_string()),
        ),
        (
            "wekit.monetTest.dexKitRevision",
            native
                .map(|value| value.revision.clone())
                .unwrap_or_else(|| "not-loaded".to_string()),
        ),
        (
            "wekit.monetTest.versionCode",
            metadata.version_code.to_string(),
        ),
        ("wekit.monetTest.versionName", metadata.version_name.clone()),
        (
            "wekit.monetTest.isGooglePlay",
            metadata.is_google_play.to_string(),
        ),
    ];
    if let Some(native) = native {
        properties.push((
            "wekit.monetTest.nativeLibrary",
            native.library_path.to_string_lossy().to_string(),
        ));
    } else if !matches!(input, SampleInput::Decoded(_)) {
        bail!("DexKit native was not initialized for compiled Monet input")
    }
    let mut command = Command::new(gradle);
    command.current_dir(root).args([
        ":extensions:monet-generator:testDebugUnitTest",
        "-PmonetTestWorker=true",
        "--no-configuration-cache",
    ]);
    for (key, value) in properties {
        command.arg(format!("-P{key}={value}"));
    }
    println!("worker: {command:?}");
    let status = command
        .status()
        .with_context(|| format!("launch Monet worker for {}", input.path().display()))?;
    Ok(status.code().unwrap_or(1))
}

fn read_worker_report(path: &Path) -> Result<WorkerReport> {
    let report: WorkerReport = serde_json::from_reader(
        File::open(path).with_context(|| format!("open worker report {}", path.display()))?,
    )
    .with_context(|| format!("parse worker report {}", path.display()))?;
    if report.schema_version != 1 {
        bail!(
            "worker report {} has unsupported schemaVersion {}",
            path.display(),
            report.schema_version
        )
    }
    Ok(report)
}

fn write_infrastructure_report(
    path: &Path,
    input: &SampleInput,
    native: Option<&DexKitNative>,
    error: &anyhow::Error,
) -> Result<()> {
    let decoded = matches!(input, SampleInput::Decoded(_));
    let report = json!({
        "schemaVersion": 1,
        "workerPid": 0,
        "startedAt": now_rfc3339(),
        "finishedAt": now_rfc3339(),
        "elapsedMillis": 0,
        "outcome": "INFRASTRUCTURE_FAILURE",
        "environment": {
            "dexKitVersion": native.map(|value| value.version.as_str()).unwrap_or("not-loaded"),
            "dexKitRevision": native.map(|value| value.revision.as_str()).unwrap_or("not-loaded"),
            "architecture": native.map(|value| value.architecture.as_str()).unwrap_or(std::env::consts::ARCH),
            "jvmVersion": "",
        },
        "input": {
            "kind": input.kind_name(),
            "path": input.path().to_string_lossy(),
            "fileName": input.label(),
            "sizeBytes": if input.path().is_file() { fs::metadata(input.path()).ok().map(|value| value.len()) } else { None },
            "sha256": serde_json::Value::Null,
            "metadataSource": if decoded { "PATH_INFERRED" } else { "APK_MANIFEST" },
            "versionCode": serde_json::Value::Null,
            "versionName": serde_json::Value::Null,
            "isGooglePlay": serde_json::Value::Null,
            "channel": if decoded { "domestic" } else { "unknown" },
            "nestedApkCount": 0,
            "resourceApkCount": 0,
            "dexCount": 0,
        },
        "resources": serde_json::Value::Null,
        "roles": [],
        "coreFailures": [],
        "optionalSkips": [],
        "dexEvidence": {
            "status": if decoded { "UNAVAILABLE" } else { "FAILED" },
            "reason": format!("{error:#}"),
            "queries": [],
        },
        "overlays": [],
        "failureMessage": format!("{error:#}"),
        "infrastructureError": {
            "message": format!("{error:#}"),
            "exceptionType": "xtask::InfrastructureFailure",
            "stackTrace": serde_json::Value::Null,
        },
    });
    fs::create_dir_all(path.parent().context("report path has no parent")?)?;
    let temporary = path.with_extension("json.tmp");
    fs::write(&temporary, serde_json::to_vec_pretty(&report)?)?;
    fs::rename(temporary, path)?;
    Ok(())
}

fn render_worker_report(report: &WorkerReport, path: &Path, verbose: bool) {
    println!("=== {} ===", report.input.file_name);
    println!(
        "outcome={:?} version={} channel={} dex={} overlays={}",
        report.outcome,
        report.input.version_name.as_deref().unwrap_or("unknown"),
        match report.input.is_google_play {
            Some(true) => "google-play",
            Some(false) => "domestic",
            None => "unavailable",
        },
        report.dex_evidence.status,
        report.overlays.len(),
    );
    if !report.core_failures.is_empty() {
        println!("core failures: {}", report.core_failures.join(", "));
    }
    if !report.optional_skips.is_empty() {
        println!("optional skips: {}", report.optional_skips.join(", "));
    }
    for role in &report.roles {
        if role.failure.is_some() || verbose {
            let target = role
                .final_target
                .as_ref()
                .map(|target| format!("{}/{}", target.resource_type, target.name))
                .unwrap_or_else(|| "-".to_string());
            println!(
                "role {}  {}  {}",
                role.role_id,
                role.failure.as_deref().unwrap_or("PASS"),
                target,
            );
        }
    }
    if !report.overlays.is_empty() {
        println!(
            "validated overlays: {}",
            report
                .overlays
                .iter()
                .map(|overlay| overlay.file_name.as_str())
                .collect::<Vec<_>>()
                .join(", ")
        );
    }
    println!("report: {}", path.display());
}

impl SampleInput {
    fn kind_name(&self) -> &'static str {
        match self {
            Self::Apk(_) => "APK",
            Self::Archive(_) => "APKS",
            Self::Decoded(_) => "DECODED_RES",
        }
    }
}

fn now_rfc3339() -> String {
    OffsetDateTime::now_utc()
        .format(&Rfc3339)
        .unwrap_or_else(|_| "unknown".to_string())
}

const SUMMARY_FILE_NAME: &str = "summary.json";
const SUMMARY_TEMP_FILE_NAME: &str = "summary.json.tmp";

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;
    use std::fs;
    use std::path::PathBuf;
    use zip::write::SimpleFileOptions;

    #[derive(Parser)]
    struct TestCli {
        #[command(flatten)]
        args: MonetTestArgs,
    }

    #[test]
    fn parses_repeatable_sample_kinds_and_output_options() {
        let cli = TestCli::try_parse_from([
            "monet-test",
            "--apk",
            "first.apk",
            "--apk",
            "second.apk",
            "--apks",
            "bundle.apks",
            "--decoded-res",
            "wechat_8069/app/src/main/res",
            "--output-dir",
            "reports",
            "--verbose",
        ])
        .unwrap();

        assert_eq!(
            cli.args.apks,
            vec![PathBuf::from("first.apk"), PathBuf::from("second.apk")],
        );
        assert_eq!(cli.args.archives, vec![PathBuf::from("bundle.apks")]);
        assert_eq!(
            cli.args.decoded_resources,
            vec![PathBuf::from("wechat_8069/app/src/main/res")],
        );
        assert_eq!(cli.args.output_dir, Some(PathBuf::from("reports")));
        assert!(cli.args.verbose);
    }

    #[test]
    fn apks_and_decoded_samples_get_distinct_report_names() {
        let inputs = vec![
            SampleInput::Archive(PathBuf::from("wechat-3084.apks")),
            SampleInput::Decoded(PathBuf::from("wechat_8069/app/src/main/res")),
        ];

        assert_eq!(
            report_names(&inputs),
            vec!["wechat-3084.json", "wechat_8069.json"],
        );
    }

    #[test]
    fn report_names_avoid_global_suffix_and_aggregate_collisions() {
        let inputs = vec![
            SampleInput::Apk(PathBuf::from("foo.apk")),
            SampleInput::Archive(PathBuf::from("foo.apks")),
            SampleInput::Apk(PathBuf::from("foo-2.apk")),
            SampleInput::Apk(PathBuf::from("summary.apk")),
            SampleInput::Apk(PathBuf::from("summary-2.apk")),
        ];

        assert_eq!(
            report_names(&inputs),
            vec![
                "foo.json",
                "foo-2.json",
                "foo-2-2.json",
                "summary-2.json",
                "summary-2-2.json",
            ],
        );
    }

    #[test]
    fn zero_inputs_are_rejected() {
        let error = validate_inputs(&[]).unwrap_err();

        assert!(error.to_string().contains("at least one"));
    }

    #[test]
    fn decoded_only_inputs_do_not_require_dexkit_native() {
        assert!(!requires_dexkit_native(&[SampleInput::Decoded(
            PathBuf::from("wechat_8065/app/src/main/res",)
        )]));
        assert!(requires_dexkit_native(&[
            SampleInput::Decoded(PathBuf::from("wechat_8065/app/src/main/res")),
            SampleInput::Archive(PathBuf::from("wechat.apks")),
        ]));
    }

    #[test]
    fn failed_sample_fails_aggregate_without_removing_other_reports() {
        let run_dir = std::env::temp_dir().join(format!(
            "wekit-monet-test-summary-{}-{}",
            std::process::id(),
            std::thread::current().name().unwrap_or("unnamed"),
        ));
        if run_dir.exists() {
            fs::remove_dir_all(&run_dir).unwrap();
        }
        fs::create_dir(&run_dir).unwrap();
        let first_report = run_dir.join("first.json");
        let second_report = run_dir.join("second.json");
        fs::write(&first_report, "first").unwrap();
        fs::write(&second_report, "second").unwrap();

        let summary = write_summary(
            &run_dir,
            "test-run",
            &[
                SampleResult {
                    label: "first".to_string(),
                    input_path: PathBuf::from("first.apk"),
                    report_path: first_report.clone(),
                    outcome: SampleOutcome::Pass,
                    error: None,
                },
                SampleResult {
                    label: "second".to_string(),
                    input_path: PathBuf::from("second.apk"),
                    report_path: second_report.clone(),
                    outcome: SampleOutcome::Fail,
                    error: Some("ambiguous core role".to_string()),
                },
            ],
        )
        .unwrap();

        assert_eq!(summary.outcome, SampleOutcome::Fail);
        assert!(first_report.is_file());
        assert!(second_report.is_file());
        assert!(run_dir.join("summary.json").is_file());

        fs::remove_dir_all(run_dir).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn metadata_extraction_owns_unique_scratch_and_preserves_predictable_paths() {
        use std::os::unix::fs::symlink;

        let run_dir = std::env::temp_dir().join(format!(
            "wekit-monet-metadata-scratch-{}",
            std::process::id(),
        ));
        if run_dir.exists() {
            fs::remove_dir_all(&run_dir).unwrap();
        }
        fs::create_dir(&run_dir).unwrap();
        let sentinel = run_dir.join(".metadata-0-base.apk");
        let sentinel_link = run_dir.join(".metadata-1-base.apk");
        fs::write(&sentinel, "sentinel").unwrap();
        symlink(&sentinel, &sentinel_link).unwrap();
        let apks = run_dir.join("sample.apks");
        let mut archive = zip::ZipWriter::new(File::create(&apks).unwrap());
        archive
            .start_file("base.apk", SimpleFileOptions::default())
            .unwrap();
        archive.write_all(b"base-apk").unwrap();
        archive.finish().unwrap();

        let owned = extract_base_apk_for_metadata(&apks, &run_dir).unwrap();
        let owned_root = owned.path.parent().unwrap().to_path_buf();

        assert_eq!(fs::read_to_string(&sentinel).unwrap(), "sentinel");
        assert_eq!(fs::read_link(&sentinel_link).unwrap(), sentinel);
        assert_ne!(owned_root, run_dir);
        assert_eq!(fs::read(&owned.path).unwrap(), b"base-apk");
        owned.cleanup().unwrap();
        assert!(!owned_root.exists());

        fs::remove_dir_all(run_dir).unwrap();
    }
}
