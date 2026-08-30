//! CI helpers for the documented WeChat APK matrix and mutable Dex-Test Release.

use anyhow::{Context, Result, bail};
use clap::{Args, Subcommand};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Args, Debug)]
pub struct DexTestCiArgs {
    #[command(subcommand)]
    command: DexTestCiCommand,
}

#[derive(Subcommand, Debug)]
enum DexTestCiCommand {
    /// Extract fixed WeChat APK sources from docs/getting-started.md.
    Sources {
        #[arg(long, value_name = "PATH")]
        doc: PathBuf,
        #[arg(long, value_name = "PATH")]
        output: PathBuf,
    },
    /// Copy PASS reports to canonical Release asset names and write release metadata.
    StageRelease {
        #[arg(long, value_name = "DIR")]
        run_dir: PathBuf,
        #[arg(long, value_name = "DIR")]
        output_dir: PathBuf,
        #[arg(long)]
        sha: String,
    },
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
enum HostChannel {
    Domestic,
    GooglePlay,
}

impl HostChannel {
    fn as_str(self) -> &'static str {
        match self {
            Self::Domestic => "domestic",
            Self::GooglePlay => "google-play",
        }
    }
}

#[derive(Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ApkSource {
    version_name: String,
    channel: HostChannel,
    source_url: String,
    file_name: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SourceManifest {
    schema_version: i64,
    sources: Vec<ApkSource>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StagedReport {
    schema_version: i64,
    outcome: String,
    version_name: String,
    version_code: i64,
    is_google_play: bool,
    counts: StagedCounts,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StagedCounts {
    success: i64,
    expected_failure: i64,
    unexpected_failure: i64,
    blocked: i64,
    incomplete: i64,
}

pub fn task_dex_test_ci(args: DexTestCiArgs) -> Result<()> {
    match args.command {
        DexTestCiCommand::Sources { doc, output } => {
            let markdown = fs::read_to_string(&doc)
                .with_context(|| format!("read APK source document {}", doc.display()))?;
            let sources = extract_sources(&markdown)?;
            write_json(
                &output,
                &SourceManifest {
                    schema_version: 1,
                    sources,
                },
            )?;
            println!("wrote APK source manifest to {}", output.display());
        }
        DexTestCiCommand::StageRelease {
            run_dir,
            output_dir,
            sha,
        } => {
            stage_release(&run_dir, &output_dir, &sha)?;
            println!(
                "staged Dex-Test Release input under {}",
                output_dir.display()
            );
        }
    }
    Ok(())
}

fn extract_sources(markdown: &str) -> Result<Vec<ApkSource>> {
    let link = Regex::new(
        r"\[(?P<version>[0-9]+\.[0-9]+\.[0-9]+) (?P<label>官方|APKMirror)\]\((?P<url>https?://[^)]+)\)",
    )
    .expect("valid APK source regex");
    let mut identities = HashSet::new();
    let mut sources = Vec::new();
    for capture in link.captures_iter(markdown) {
        let version_name = capture["version"].to_owned();
        let channel = if &capture["label"] == "官方" {
            HostChannel::Domestic
        } else {
            HostChannel::GooglePlay
        };
        if !identities.insert((version_name.clone(), channel)) {
            bail!(
                "duplicate APK source for {} {}",
                version_name,
                channel.as_str()
            );
        }
        let compact_version = version_name.replace('.', "");
        sources.push(ApkSource {
            version_name,
            channel,
            source_url: capture["url"].to_owned(),
            file_name: format!(
                "wechat_{compact_version}_{}.apk",
                channel.as_str().replace('-', "_")
            ),
        });
    }
    if sources.is_empty() {
        bail!("no supported WeChat APK links found in source document");
    }
    Ok(sources)
}

fn stage_release(run_dir: &Path, output_dir: &Path, sha: &str) -> Result<()> {
    let summary_path = run_dir.join("summary.json");
    if !summary_path.is_file() {
        bail!("Dex test summary is missing: {}", summary_path.display());
    }
    fs::create_dir_all(output_dir)
        .with_context(|| format!("create stage directory {}", output_dir.display()))?;

    let mut report_paths = fs::read_dir(run_dir)
        .with_context(|| format!("read Dex test run directory {}", run_dir.display()))?
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| {
            path.extension()
                .is_some_and(|extension| extension == "json")
                && path.file_name().is_some_and(|name| name != "summary.json")
        })
        .collect::<Vec<_>>();
    report_paths.sort();

    let mut reports = Vec::with_capacity(report_paths.len());
    for path in report_paths {
        let bytes =
            fs::read(&path).with_context(|| format!("read Dex test report {}", path.display()))?;
        let report: StagedReport = serde_json::from_slice(&bytes)
            .with_context(|| format!("parse Dex test report {}", path.display()))?;
        if report.schema_version != 2 {
            bail!("unsupported Dex test report schema in {}", path.display());
        }
        reports.push((path, bytes, report));
    }

    let mut asset_names = HashSet::new();
    let mut uploaded_assets = Vec::new();
    for (_, bytes, report) in &reports {
        if report.outcome != "PASS" {
            continue;
        }
        let asset_name = canonical_asset_name(report);
        if !asset_names.insert(asset_name.clone()) {
            bail!("duplicate canonical Dex report asset: {asset_name}");
        }
        fs::write(output_dir.join(&asset_name), bytes)
            .with_context(|| format!("write staged report {asset_name}"))?;
        uploaded_assets.push(asset_name);
    }

    let mut notes = format!(
        "本 Release 保存各受支持微信版本最近一次成功的 DEX 解析报告。\n\n本次提交：`{sha}`\n\n## 本次解析\n\n"
    );
    for (_, _, report) in &reports {
        let channel = if report.is_google_play {
            "google-play"
        } else {
            "domestic"
        };
        notes.push_str(&format!(
            "- {} ({}, {}): {} — success={}, expected={}, unexpected={}, blocked={}, incomplete={}\n",
            report.version_name,
            report.version_code,
            channel,
            report.outcome,
            report.counts.success,
            report.counts.expected_failure,
            report.counts.unexpected_failure,
            report.counts.blocked,
            report.counts.incomplete,
        ));
    }
    fs::write(output_dir.join("release-notes.md"), notes)
        .context("write Dex-Test release notes")?;
    fs::write(
        output_dir.join("assets.txt"),
        uploaded_assets
            .iter()
            .map(|name| format!("{name}\n"))
            .collect::<String>(),
    )
    .context("write Dex-Test asset list")?;

    if !uploaded_assets.is_empty() {
        fs::copy(&summary_path, output_dir.join("summary.json"))
            .context("stage Dex test summary")?;
    }
    Ok(())
}

fn canonical_asset_name(report: &StagedReport) -> String {
    format!(
        "wechat-{}-{}-{}.json",
        report.version_name,
        report.version_code,
        if report.is_google_play {
            "google-play"
        } else {
            "domestic"
        }
    )
}

fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create output directory {}", parent.display()))?;
    }
    fs::write(path, serde_json::to_vec_pretty(value)?)
        .with_context(|| format!("write JSON {}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn extracts_ordered_domestic_and_google_play_sources() {
        let markdown = r#"
| 状态 | 版本 | 下载 |
|------|------|------|
| ✅ | 8.0.69 | [8.0.69 官方](https://example.com/weixin8069.apk) [8.0.69 APKMirror](https://www.apkmirror.com/apk/wechat/wechat/wechat-8-0-69-release/) |
| ✅ | 8.0.76 | [8.0.76 官方](https://example.com/weixin8076.apk) [8.0.68 APKMirror](https://www.apkmirror.com/apk/wechat/wechat/wechat-8-0-68-release/) |
"#;

        let sources = extract_sources(markdown).unwrap();

        assert_eq!(sources.len(), 4);
        assert_eq!(sources[0].version_name, "8.0.69");
        assert_eq!(sources[0].channel, HostChannel::Domestic);
        assert_eq!(sources[0].file_name, "wechat_8069_domestic.apk");
        assert_eq!(sources[1].channel, HostChannel::GooglePlay);
        assert_eq!(sources[1].file_name, "wechat_8069_google_play.apk");
        assert_eq!(sources[2].file_name, "wechat_8076_domestic.apk");
        assert_eq!(sources[3].file_name, "wechat_8068_google_play.apk");
    }

    #[test]
    fn rejects_empty_and_duplicate_source_sets() {
        assert!(extract_sources("# no APK links").is_err());
        let duplicate = r#"
[8.0.69 官方](https://example.com/first.apk)
[8.0.69 官方](https://example.com/second.apk)
"#;
        assert!(
            extract_sources(duplicate)
                .unwrap_err()
                .to_string()
                .contains("duplicate")
        );
    }

    #[test]
    fn stages_only_passed_reports_and_keeps_all_outcomes_in_notes() {
        let root = temporary_root("partial-stage");
        let run_dir = root.join("run");
        let output_dir = root.join("output");
        fs::create_dir_all(&run_dir).unwrap();
        write_report(
            &run_dir.join("domestic.json"),
            "PASS",
            "8.0.69",
            3040,
            false,
        );
        write_report(&run_dir.join("play.json"), "FAIL", "8.0.69", 3020, true);
        write_summary(&run_dir, "FAIL");

        stage_release(&run_dir, &output_dir, "abcdef12").unwrap();

        let asset_name = "wechat-8.0.69-3040-domestic.json";
        assert!(output_dir.join(asset_name).is_file());
        assert!(
            !output_dir
                .join("wechat-8.0.69-3020-google-play.json")
                .exists()
        );
        assert_eq!(
            fs::read_to_string(output_dir.join("assets.txt")).unwrap(),
            format!("{asset_name}\n")
        );
        let notes = fs::read_to_string(output_dir.join("release-notes.md")).unwrap();
        assert!(notes.contains("abcdef12"));
        assert!(notes.contains("8.0.69 (3040, domestic): PASS"));
        assert!(notes.contains("8.0.69 (3020, google-play): FAIL"));
        assert!(output_dir.join("summary.json").is_file());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn zero_pass_reports_produce_an_empty_asset_list() {
        let root = temporary_root("zero-pass");
        let run_dir = root.join("run");
        let output_dir = root.join("output");
        fs::create_dir_all(&run_dir).unwrap();
        write_report(&run_dir.join("failed.json"), "FAIL", "8.0.77", 3141, false);
        write_summary(&run_dir, "FAIL");

        stage_release(&run_dir, &output_dir, "abcdef12").unwrap();

        assert_eq!(
            fs::read_to_string(output_dir.join("assets.txt")).unwrap(),
            ""
        );
        assert!(!output_dir.join("summary.json").exists());
        assert_eq!(fs::read_dir(&output_dir).unwrap().count(), 2);
        fs::remove_dir_all(root).unwrap();
    }

    fn write_report(path: &Path, outcome: &str, version: &str, code: i64, play: bool) {
        fs::write(
            path,
            format!(
                r#"{{
  "schemaVersion": 2,
  "outcome": "{outcome}",
  "versionName": "{version}",
  "versionCode": {code},
  "isGooglePlay": {play},
  "counts": {{"success": 3, "expectedFailure": 1, "unexpectedFailure": 0, "blocked": 0, "incomplete": 0}}
}}"#,
            ),
        )
        .unwrap();
    }

    fn write_summary(run_dir: &Path, outcome: &str) {
        fs::write(
            run_dir.join("summary.json"),
            format!(r#"{{"schemaVersion":1,"outcome":"{outcome}","reports":[]}}"#),
        )
        .unwrap();
    }

    fn temporary_root(label: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "wekit-dex-test-ci-{label}-{}-{nonce}",
            std::process::id()
        ))
    }
}
