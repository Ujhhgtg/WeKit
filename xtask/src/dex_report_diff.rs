//! Offline comparison of per-APK reports. Descriptor names are never treated as ABI identity.

use super::{ApkOutcome, ApkReport, DelegateReport, FeatureReport, write_json_atomic};
use anyhow::{Context, Result, bail, ensure};
use clap::Args;
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;

#[derive(Args, Debug)]
pub struct DexReportDiffArgs {
    /// Per-APK JSON reports, oldest to newest. Compares adjacent inputs; not summary.json.
    #[arg(required = true, num_args = 2.., value_name = "REPORT")]
    reports: Vec<PathBuf>,
    /// Save the complete comparison (including descriptor/name changes) as JSON.
    #[arg(long, value_name = "JSON")]
    output: Option<PathBuf>,
    /// Also print changes affecting only member names or descriptors.
    #[arg(long)]
    include_renames: bool,
    /// Exit nonzero on substantive changes or non-passing input reports.
    #[arg(long)]
    fail_on_change: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportIdentity {
    path: PathBuf,
    schema_version: i64,
    version_name: String,
    version_code: i64,
    build_tag: String,
    is_google_play: bool,
    apk_sha256: String,
    outcome: ApkOutcome,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ComparisonReport {
    schema_version: u32,
    reports: Vec<ReportIdentity>,
    comparisons: Vec<Comparison>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Comparison {
    from_report: usize,
    to_report: usize,
    shared_features: usize,
    shared_delegates: usize,
    warnings: Vec<String>,
    changes: Vec<Change>,
}

#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ChangeKind {
    FeatureReportAdded,
    FeatureReportRemoved,
    FeatureOutcomeChanged,
    ResolverChanged,
    DelegateAdded,
    DelegateRemoved,
    ResolutionChanged,
    MemberKindChanged,
    ParameterCountChanged,
    ParameterTypesChanged,
    ReturnTypeChanged,
    MemberRenamed,
    DescriptorChanged,
}

impl ChangeKind {
    fn informational(&self) -> bool {
        matches!(self, Self::MemberRenamed | Self::DescriptorChanged)
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Change {
    feature: String,
    delegate: Option<String>,
    kinds: Vec<ChangeKind>,
    before: Option<Snapshot>,
    after: Option<Snapshot>,
    detail: Option<String>,
}

impl Change {
    fn substantive(&self) -> bool {
        self.kinds.iter().any(|kind| !kind.informational())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct MemberSignature {
    kind: String,
    declaring_class: String,
    name: String,
    parameter_count: usize,
    parameter_types: Vec<String>,
    return_type: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    status: String,
    is_placeholder: bool,
    descriptor: Option<String>,
    member: Option<MemberSignature>,
    message: Option<String>,
    blocked_by: Option<String>,
}

pub fn task_dex_report_diff(args: DexReportDiffArgs) -> Result<()> {
    let mut paths = Vec::new();
    let mut reports = Vec::new();
    for path in &args.reports {
        let path = path
            .canonicalize()
            .with_context(|| format!("report {}", path.display()))?;
        ensure!(
            !paths.contains(&path),
            "duplicate input report: {}",
            path.display()
        );
        let report: ApkReport =
            serde_json::from_slice(&std::fs::read(&path)?).with_context(|| {
                format!(
                    "{}: expected a per-APK report, not summary.json",
                    path.display()
                )
            })?;
        validate_report(&report).with_context(|| format!("invalid report {}", path.display()))?;
        paths.push(path);
        reports.push(report);
    }
    let comparison = compare_reports(&reports, &paths)?;
    render(&comparison, args.include_renames);
    if let Some(output) = args.output {
        let output = std::path::absolute(output)?;
        ensure!(
            !paths.iter().any(|input| output == *input || output.canonicalize().ok().as_ref() == Some(input)),
            "output must not overwrite an input report"
        );
        // The shared writer also uses this sibling as its temporary file.
        let temp = output.with_extension("json.tmp");
        ensure!(
            !paths.contains(&temp),
            "temporary output would overwrite an input report"
        );
        write_json_atomic(&output, &comparison)?;
        println!("\ncomparison: {}", output.display());
    }
    if args.fail_on_change
        && (reports
            .iter()
            .any(|r| !matches!(r.outcome, ApkOutcome::Pass))
            || comparison
                .comparisons
                .iter()
                .any(|p| p.changes.iter().any(Change::substantive)))
    {
        bail!("Dex reports contain substantive changes or non-passing inputs");
    }
    Ok(())
}

fn validate_report(report: &ApkReport) -> Result<()> {
    ensure!(
        (1..=2).contains(&report.schema_version),
        "unsupported schema version {}",
        report.schema_version
    );
    ensure!(!report.version_name.is_empty(), "missing host versionName");
    let features = index_features(report)?;
    for feature in features.values() {
        for delegate in index_delegates(feature)?.values() {
            snapshot(delegate)
                .with_context(|| format!("{} / {}", feature.class_name, delegate.key))?;
        }
    }
    Ok(())
}

fn index_features(report: &ApkReport) -> Result<BTreeMap<&str, &FeatureReport>> {
    let mut features = BTreeMap::new();
    for feature in &report.features {
        ensure!(!feature.class_name.is_empty(), "empty feature className");
        ensure!(
            features
                .insert(feature.class_name.as_str(), feature)
                .is_none(),
            "duplicate feature {}",
            feature.class_name
        );
    }
    Ok(features)
}

fn index_delegates(feature: &FeatureReport) -> Result<BTreeMap<&str, &DelegateReport>> {
    let mut delegates = BTreeMap::new();
    let prefix = format!("{}:", feature.class_name.rsplit('.').next().unwrap());
    for delegate in &feature.delegates {
        // Older schema 1 reports qualified each key with the feature's simple name.
        let key = delegate.key.strip_prefix(&prefix).unwrap_or(&delegate.key);
        ensure!(
            !key.is_empty(),
            "empty delegate key in {}",
            feature.class_name
        );
        ensure!(
            delegates.insert(key, delegate).is_none(),
            "duplicate delegate {} / {key}",
            feature.class_name
        );
    }
    Ok(delegates)
}

fn snapshot(delegate: &DelegateReport) -> Result<Snapshot> {
    let member = if delegate.status == "SUCCESS" && !delegate.is_placeholder {
        let descriptor = delegate
            .descriptor
            .as_deref()
            .filter(|s| !s.is_empty())
            .context("successful delegate has no descriptor")?;
        if descriptor.contains('(') || (descriptor.contains("->") && !descriptor.contains(':')) {
            Some(parse_member(descriptor)?)
        } else {
            None
        }
    } else {
        None
    };
    Ok(Snapshot {
        status: delegate.status.clone(),
        is_placeholder: delegate.is_placeholder,
        descriptor: delegate.descriptor.clone(),
        member,
        message: delegate.message.clone(),
        blocked_by: delegate.blocked_by.clone(),
    })
}

fn compare_reports(reports: &[ApkReport], paths: &[PathBuf]) -> Result<ComparisonReport> {
    ensure!(
        reports.len() >= 2 && reports.len() == paths.len(),
        "at least two per-APK reports are required"
    );
    let identities = reports
        .iter()
        .zip(paths)
        .map(|(r, path)| ReportIdentity {
            path: path.clone(),
            schema_version: r.schema_version,
            version_name: r.version_name.clone(),
            version_code: r.version_code,
            build_tag: r.build_tag.clone(),
            is_google_play: r.is_google_play,
            apk_sha256: r.apk_sha256.clone(),
            outcome: r.outcome.clone(),
        })
        .collect();
    let comparisons = reports
        .windows(2)
        .enumerate()
        .map(|(index, pair)| compare_pair(&pair[0], &pair[1], index))
        .collect::<Result<_>>()?;
    Ok(ComparisonReport {
        schema_version: 1,
        reports: identities,
        comparisons,
    })
}

fn compare_pair(before: &ApkReport, after: &ApkReport, index: usize) -> Result<Comparison> {
    let mut result = Comparison {
        from_report: index,
        to_report: index + 1,
        shared_features: 0,
        shared_delegates: 0,
        warnings: Vec::new(),
        changes: Vec::new(),
    };
    if before.is_google_play != after.is_google_play {
        result
            .warnings
            .push("Channels differ: changes may be channel-specific, not version-specific.".into());
    }
    if !matches!(before.outcome, ApkOutcome::Pass) || !matches!(after.outcome, ApkOutcome::Pass) {
        result.warnings.push("A report did not pass; missing or unresolved targets are not evidence of host removal.".into());
    }
    let left = index_features(before)?;
    let right = index_features(after)?;
    if left.keys().collect::<Vec<_>>() != right.keys().collect::<Vec<_>>() {
        result.warnings.push("Feature coverage differs (possibly --features or different source revisions); omitted features are not host removals.".into());
    }
    let features: BTreeSet<_> = left.keys().chain(right.keys()).copied().collect();
    for feature in features {
        let (Some(a), Some(b)) = (left.get(feature), right.get(feature)) else {
            result.changes.push(Change {
                feature: feature.into(),
                delegate: None,
                kinds: vec![if left.contains_key(feature) {
                    ChangeKind::FeatureReportRemoved
                } else {
                    ChangeKind::FeatureReportAdded
                }],
                before: None,
                after: None,
                detail: Some(
                    "Feature is absent from one report; host availability is unknown.".into(),
                ),
            });
            continue;
        };
        result.shared_features += 1;
        let mut kinds = Vec::new();
        let mut details = Vec::new();
        if a.method_hash != b.method_hash {
            kinds.push(ChangeKind::ResolverChanged);
            details.push(format!(
                "resolver hash: {} -> {}; differences cannot be attributed solely to the host",
                a.method_hash, b.method_hash
            ));
        }
        if a.outcome != b.outcome {
            kinds.push(ChangeKind::FeatureOutcomeChanged);
            details.push(format!("feature outcome: {} -> {}", a.outcome, b.outcome));
        }
        if !kinds.is_empty() {
            result.changes.push(Change {
                feature: feature.into(),
                delegate: None,
                kinds,
                before: None,
                after: None,
                detail: Some(details.join("; ")),
            });
        }
        let a = index_delegates(a)?;
        let b = index_delegates(b)?;
        let keys: BTreeSet<_> = a.keys().chain(b.keys()).copied().collect();
        for key in keys {
            let old = a.get(key).map(|d| snapshot(d)).transpose()?;
            let new = b.get(key).map(|d| snapshot(d)).transpose()?;
            let mut kinds = Vec::new();
            match (&old, &new) {
                (None, Some(_)) => kinds.push(ChangeKind::DelegateAdded),
                (Some(_), None) => kinds.push(ChangeKind::DelegateRemoved),
                (Some(x), Some(y)) => {
                    result.shared_delegates += 1;
                    if x.status != y.status || x.is_placeholder != y.is_placeholder {
                        kinds.push(ChangeKind::ResolutionChanged);
                    }
                    if x.status == "SUCCESS"
                        && y.status == "SUCCESS"
                        && !x.is_placeholder
                        && !y.is_placeholder
                    {
                        match (&x.member, &y.member) {
                            (Some(m), Some(n)) => {
                                if m.kind != n.kind {
                                    kinds.push(ChangeKind::MemberKindChanged);
                                }
                                if m.parameter_count != n.parameter_count {
                                    kinds.push(ChangeKind::ParameterCountChanged);
                                }
                                if m.parameter_types != n.parameter_types {
                                    kinds.push(ChangeKind::ParameterTypesChanged);
                                }
                                if m.return_type != n.return_type {
                                    kinds.push(ChangeKind::ReturnTypeChanged);
                                }
                                if kinds.is_empty()
                                    && (m.name != n.name || m.declaring_class != n.declaring_class)
                                {
                                    kinds.push(ChangeKind::MemberRenamed);
                                }
                            }
                            (None, None) => {
                                if x.descriptor != y.descriptor {
                                    kinds.push(ChangeKind::DescriptorChanged);
                                }
                            }
                            _ => kinds.push(ChangeKind::MemberKindChanged),
                        }
                    }
                }
                (None, None) => unreachable!(),
            }
            if !kinds.is_empty() {
                result.changes.push(Change {
                    feature: feature.into(),
                    delegate: Some(key.into()),
                    kinds,
                    before: old,
                    after: new,
                    detail: None,
                });
            }
        }
    }
    Ok(result)
}

/// Read the JVM/Dex descriptor grammar, including multidimensional arrays, without loading classes.
fn parse_member(descriptor: &str) -> Result<MemberSignature> {
    let (owner, rest) = descriptor
        .split_once("->")
        .context("method descriptor lacks ->")?;
    ensure!(
        owner.starts_with('L') && owner.ends_with(';'),
        "invalid declaring class: {descriptor}"
    );
    let mut owner_cursor = 0;
    let declaring_class = parse_type(owner, &mut owner_cursor, false)?;
    ensure!(
        owner_cursor == owner.len(),
        "invalid declaring class: {descriptor}"
    );
    let (name, signature) = rest.split_once('(').context("method descriptor lacks (")?;
    ensure!(!name.is_empty(), "empty method name");
    let (parameters, returns) = signature
        .split_once(')')
        .context("method descriptor lacks )")?;
    let mut cursor = 0;
    let mut parameter_types = Vec::new();
    while cursor < parameters.len() {
        parameter_types.push(parse_type(parameters, &mut cursor, false)?);
    }
    cursor = 0;
    let return_type = parse_type(returns, &mut cursor, true)?;
    ensure!(
        cursor == returns.len(),
        "trailing return descriptor: {descriptor}"
    );
    ensure!(
        name != "<init>" || return_type == "void",
        "constructor must return void"
    );
    Ok(MemberSignature {
        kind: if name == "<init>" {
            "CONSTRUCTOR"
        } else {
            "METHOD"
        }
        .into(),
        declaring_class,
        name: name.into(),
        parameter_count: parameter_types.len(),
        parameter_types,
        return_type,
    })
}

fn parse_type(input: &str, cursor: &mut usize, allow_void: bool) -> Result<String> {
    let bytes = input.as_bytes();
    let mut dimensions = 0;
    while bytes.get(*cursor) == Some(&b'[') {
        dimensions += 1;
        *cursor += 1;
    }
    let tag = *bytes.get(*cursor).context("truncated type descriptor")?;
    *cursor += 1;
    let base = match tag {
        b'Z' => "boolean",
        b'B' => "byte",
        b'C' => "char",
        b'S' => "short",
        b'I' => "int",
        b'J' => "long",
        b'F' => "float",
        b'D' => "double",
        b'V' if allow_void && dimensions == 0 => "void",
        b'L' => {
            let end = input[*cursor..]
                .find(';')
                .context("unterminated object descriptor")?
                + *cursor;
            let name = &input[*cursor..end];
            ensure!(
                !name.is_empty() && !name.contains(['[', '(', ')', '.']),
                "invalid object descriptor"
            );
            *cursor = end + 1;
            return Ok(format!(
                "{}{}",
                name.replace('/', "."),
                "[]".repeat(dimensions)
            ));
        }
        _ => bail!("invalid type descriptor tag {}", tag as char),
    };
    Ok(format!("{base}{}", "[]".repeat(dimensions)))
}

fn render(report: &ComparisonReport, include_renames: bool) {
    for pair in &report.comparisons {
        let label = |r: &ReportIdentity| {
            format!(
                "{} / {} / {} / {}",
                r.version_name,
                r.version_code,
                if r.is_google_play {
                    "Google Play"
                } else {
                    "domestic"
                },
                r.build_tag
            )
        };
        println!(
            "\n=== {} -> {} ===",
            label(&report.reports[pair.from_report]),
            label(&report.reports[pair.to_report])
        );
        for warning in &pair.warnings {
            println!("warning: {warning}");
        }
        for change in &pair.changes {
            if !include_renames && !change.substantive() {
                continue;
            }
            println!(
                "[{}] {}{}",
                change
                    .kinds
                    .iter()
                    .map(|k| serde_json::to_value(k)
                        .unwrap()
                        .as_str()
                        .unwrap()
                        .to_owned())
                    .collect::<Vec<_>>()
                    .join(", "),
                change.feature,
                change
                    .delegate
                    .as_ref()
                    .map(|d| format!(" / {d}"))
                    .unwrap_or_default()
            );
            if let Some(detail) = &change.detail {
                println!("  {detail}");
            }
            for (label, snapshot) in [("before", &change.before), ("after", &change.after)] {
                if let Some(s) = snapshot {
                    println!(
                        "  {label}: {} {}",
                        s.status,
                        s.descriptor.as_deref().unwrap_or("(unresolved)")
                    );
                    if let Some(m) = &s.member {
                        println!(
                            "    {} parameter(s): ({}) -> {}",
                            m.parameter_count,
                            m.parameter_types.join(", "),
                            m.return_type
                        );
                    }
                    if let Some(message) = &s.message {
                        println!("    {message}");
                    }
                    if let Some(blocked) = &s.blocked_by {
                        println!("    blocked by: {blocked}");
                    }
                }
            }
        }
        let substantive = pair.changes.iter().filter(|c| c.substantive()).count();
        println!(
            "{} shared features, {} shared delegates; {substantive} substantive change(s), {} name/descriptor-only change(s)",
            pair.shared_features,
            pair.shared_delegates,
            pair.changes.len() - substantive
        );
    }
    println!(
        "\nType names are compared exactly. Obfuscated type changes require source review; a signature match does not prove runtime compatibility."
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;
    use serde_json::json;

    fn fixture(descriptor: &str) -> ApkReport {
        serde_json::from_value(json!({
            "schemaVersion": 2, "workerPid": 1, "apkPath": "/wechat.apk", "fileName": "wechat.apk",
            "label": "wechat", "apkSize": 1, "apkSha256": "hash", "versionCode": 3160,
            "versionName": "8.0.77", "buildTag": "#7637", "isGooglePlay": false, "dexCount": 1,
            "environment": {"dexKitVersion":"2.2.0", "dexKitRevision":"rev", "architecture":"x86_64", "jvmVersion":"21"},
            "startedAt":"2026-09-05", "finishedAt":"2026-09-05", "elapsedMillis":1, "outcome":"PASS",
            "counts":{"success":1,"expectedFailure":0,"unexpectedFailure":0,"blocked":0,"incomplete":0},
            "features":[{"className":"test.Feature", "displayName":"Test", "technicalId":"test", "methodHash":"same",
                "outcome":"PASS", "elapsedMillis":1, "delegates":[{"key":"member", "status":"SUCCESS", "descriptor":descriptor, "isPlaceholder":false}]}]
        })).unwrap()
    }

    fn member(report: &mut ApkReport) -> &mut DelegateReport {
        &mut report.features[0].delegates[0]
    }

    #[test]
    fn descriptor_parser_preserves_arrays_and_wide_primitive_types() {
        let m = parse_member("Ltest/Host;->send(ZBCSIJFD[[I[Ljava/lang/String;)[[Ltest/Result;")
            .unwrap();
        assert_eq!(m.parameter_count, 10);
        assert_eq!(
            m.parameter_types,
            [
                "boolean",
                "byte",
                "char",
                "short",
                "int",
                "long",
                "float",
                "double",
                "int[][]",
                "java.lang.String[]"
            ]
        );
        assert_eq!(m.return_type, "test.Result[][]");
        assert_eq!(m.declaring_class, "test.Host");
        let ctor = parse_member("Ltest/Host;-><init>()V").unwrap();
        assert_eq!(ctor.kind, "CONSTRUCTOR");
        assert!(ctor.parameter_types.is_empty());
    }

    #[test]
    fn descriptor_parser_rejects_malformed_and_truncated_types() {
        for d in [
            "",
            "Lx;->m",
            "Lx;->(I)V",
            "Lx;->m([)V",
            "Lx;->m(L;)V",
            "Lx;->m(V)V",
            "Lx;->m([V)V",
            "Lx;->m()",
            "Lx;->m()Vjunk",
            "Lx;->m(Lfoo)V",
            "Lx;-><init>()I",
            "Ix;->m()V",
        ] {
            assert!(parse_member(d).is_err(), "accepted {d}");
        }
    }

    #[test]
    fn detects_text_constructor_five_to_six_args_in_old_reports() {
        let mut a =
            fixture("Ly11/r0;-><init>(Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;)V");
        a.schema_version = 1;
        member(&mut a).key = "Feature:member".into();
        a.features[0].technical_id = None;
        let b = fixture(
            "Lv51/r0;-><init>(Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;Ljava/lang/String;)V",
        );
        validate_report(&a).unwrap();
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(p.shared_delegates, 1);
        assert_eq!(p.changes.len(), 1);
        assert_eq!(
            p.changes[0].kinds,
            [
                ChangeKind::ParameterCountChanged,
                ChangeKind::ParameterTypesChanged
            ]
        );
        assert_eq!(
            p.changes[0]
                .after
                .as_ref()
                .unwrap()
                .member
                .as_ref()
                .unwrap()
                .parameter_count,
            6
        );
    }

    #[test]
    fn checks_types_and_return_even_when_count_is_unchanged() {
        let a = fixture("La;->x(I[Ljava/lang/String;)Z");
        let b = fixture("Lb;->y(J[[Ljava/lang/String;)I");
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(
            p.changes[0].kinds,
            [
                ChangeKind::ParameterTypesChanged,
                ChangeKind::ReturnTypeChanged
            ]
        );
    }

    #[test]
    fn unknown_host_type_renaming_remains_visible_for_review() {
        let a = fixture("La;->x(Lold/Type;)V");
        let b = fixture("Lb;->y(Lnew/Type;)V");
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(p.changes[0].kinds, [ChangeKind::ParameterTypesChanged]);
        assert!(p.changes[0].substantive());
    }

    #[test]
    fn owner_and_method_renames_are_informational() {
        let a = fixture("La;->x(I)V");
        let b = fixture("Lb;->y(I)V");
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(p.changes[0].kinds, [ChangeKind::MemberRenamed]);
        assert!(!p.changes[0].substantive());
    }

    #[test]
    fn placeholders_and_failures_are_not_parsed_as_real_methods() {
        let a = fixture("La;->x(I)V");
        let mut b =
            fixture("Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;");
        member(&mut b).status = "EXPECTED_FAILURE".into();
        member(&mut b).is_placeholder = true;
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(p.changes[0].kinds, [ChangeKind::ResolutionChanged]);
        assert!(p.changes[0].after.as_ref().unwrap().member.is_none());
        member(&mut b).status = "BLOCKED".into();
        member(&mut b).descriptor = None;
        member(&mut b).blocked_by = Some("earlier".into());
        b.outcome = ApkOutcome::Fail;
        let p = compare_pair(&a, &b, 0).unwrap();
        assert_eq!(
            p.changes[0].after.as_ref().unwrap().blocked_by.as_deref(),
            Some("earlier")
        );
        assert!(!p.warnings.is_empty());
        let unchanged = compare_pair(&b, &b, 0).unwrap();
        assert!(!unchanged.warnings.is_empty());
    }

    #[test]
    fn distinguishes_report_coverage_and_resolver_changes_from_host_changes() {
        let a = fixture("La;->x(I)V");
        let mut b = a.clone();
        b.features[0].method_hash = "changed".into();
        b.features[0].delegates.push(DelegateReport {
            key: "new".into(),
            ..member(&mut a.clone()).clone()
        });
        let mut added = b.features[0].clone();
        added.class_name = "test.Second".into();
        b.features.push(added);
        let p = compare_pair(&a, &b, 0).unwrap();
        assert!(
            p.changes
                .iter()
                .any(|c| c.kinds.contains(&ChangeKind::ResolverChanged))
        );
        assert!(
            p.changes
                .iter()
                .any(|c| c.kinds.contains(&ChangeKind::DelegateAdded))
        );
        assert!(
            p.changes
                .iter()
                .any(|c| c.kinds.contains(&ChangeKind::FeatureReportAdded))
        );
        assert!(p.warnings.iter().any(|w| w.contains("coverage")));
    }

    #[test]
    fn validates_duplicate_keys_schema_and_missing_success_descriptors() {
        let mut a = fixture("La;->x()V");
        let mut duplicate = member(&mut a).clone();
        duplicate.key = "Feature:member".into();
        a.features[0].delegates.push(duplicate);
        assert!(validate_report(&a).is_err());
        a.features[0].delegates.pop();
        a.features.push(a.features[0].clone());
        assert!(validate_report(&a).is_err());
        a.features.pop();
        a.schema_version = 99;
        assert!(validate_report(&a).is_err());
        a.schema_version = 2;
        member(&mut a).descriptor = None;
        assert!(validate_report(&a).is_err());
    }

    #[test]
    fn preserves_order_build_tag_and_channel_even_with_equal_version_codes() {
        let a = fixture("La;->x()V");
        let mut b = a.clone();
        b.version_name = "8.0.78".into();
        b.build_tag = "#7844".into();
        let mut c = b.clone();
        c.is_google_play = true;
        let reports = compare_reports(&[a, b, c], &["a".into(), "b".into(), "c".into()]).unwrap();
        assert_eq!(reports.comparisons.len(), 2);
        assert_eq!(reports.comparisons[1].from_report, 1);
        assert_eq!(reports.comparisons[1].to_report, 2);
        assert_eq!(reports.reports[0].build_tag, "#7637");
        assert_eq!(reports.reports[1].build_tag, "#7844");
        assert!(
            reports.comparisons[1]
                .warnings
                .iter()
                .any(|w| w.contains("Channels"))
        );
    }

    #[test]
    fn cli_accepts_report_groups_and_requires_at_least_two_inputs() {
        #[derive(Parser)]
        struct Cli {
            #[command(flatten)]
            args: DexReportDiffArgs,
        }
        let args = Cli::try_parse_from([
            "diff",
            "a.json",
            "b.json",
            "c.json",
            "--output",
            "diff.json",
            "--include-renames",
            "--fail-on-change",
        ])
        .unwrap()
        .args;
        assert_eq!(args.reports.len(), 3);
        assert!(args.fail_on_change && args.include_renames);
        assert!(Cli::try_parse_from(["diff", "a.json"]).is_err());
    }

    #[test]
    fn offline_command_writes_details_before_failing_and_preserves_inputs() {
        let root = std::env::temp_dir().join(format!(
            "wekit-dex-diff-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&root).unwrap();
        let a = root.join("a.json");
        let b = root.join("b.json");
        let output = root.join("comparison.json");
        write_json_atomic(&a, &fixture("La;->x(I)V")).unwrap();
        write_json_atomic(&b, &fixture("Lb;->x(II)V")).unwrap();
        let original = std::fs::read(&a).unwrap();
        let args = |output, fail_on_change| DexReportDiffArgs {
            reports: vec![a.clone(), b.clone()],
            output,
            include_renames: false,
            fail_on_change,
        };
        assert!(task_dex_report_diff(args(Some(output.clone()), true)).is_err());
        let json: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&output).unwrap()).unwrap();
        assert_eq!(
            json["comparisons"][0]["changes"][0]["after"]["member"]["parameterCount"],
            2
        );
        assert!(task_dex_report_diff(args(Some(a.clone()), false)).is_err());
        assert_eq!(original, std::fs::read(&a).unwrap());
        assert!(
            task_dex_report_diff(DexReportDiffArgs {
                reports: vec![a.clone(), a],
                output: None,
                include_renames: false,
                fail_on_change: false
            })
            .is_err()
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
