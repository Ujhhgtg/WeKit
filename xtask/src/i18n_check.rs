use anyhow::{Context, Result, anyhow, bail};
use regex::Regex;
use roxmltree::{Document, Node};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ResourceKind {
    String,
    Plurals,
    StringArray,
}

#[derive(Debug)]
struct ResourceEntry {
    name: String,
    kind: ResourceKind,
    translatable: bool,
    placeholders: BTreeMap<String, String>,
    markup_signature: Vec<String>,
    plural_quantities: BTreeSet<String>,
    has_default_text: bool,
}

#[derive(Debug)]
struct Catalog {
    entries: BTreeMap<String, ResourceEntry>,
}

fn parse_catalog(xml: &str) -> Result<Catalog> {
    let document = Document::parse(xml).context("malformed XML")?;
    let root = document.root_element();
    if root.tag_name().name() != "resources" {
        bail!("malformed XML: root element must be <resources>");
    }

    let placeholder_regex = Regex::new(
        r"%(?P<index>[1-9]\d*)\$(?:[-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?(?P<date>[tT])?(?P<conversion>[a-zA-Z])",
    )
    .expect("valid placeholder regex");
    let mut entries = BTreeMap::new();
    for node in root.children().filter(Node::is_element) {
        let kind = match node.tag_name().name() {
            "string" => ResourceKind::String,
            "plurals" => ResourceKind::Plurals,
            "string-array" => ResourceKind::StringArray,
            _ => continue,
        };
        let name = node
            .attribute("name")
            .context("resource without a name")?
            .to_owned();
        if entries.contains_key(&name) {
            bail!("duplicate resource: {name}");
        }

        let translatable = node.attribute("translatable") != Some("false");
        let mut placeholders = BTreeMap::new();
        let mut markup_signature = Vec::new();
        let mut plural_quantities = BTreeSet::new();
        let mut has_default_text = false;

        match kind {
            ResourceKind::String => {
                collect_value_metadata(
                    node,
                    "string",
                    &placeholder_regex,
                    &mut placeholders,
                    &mut markup_signature,
                );
                has_default_text = !node_text(node).trim().is_empty();
            }
            ResourceKind::StringArray => {
                for (index, item) in node
                    .children()
                    .filter(Node::is_element)
                    .filter(|item| item.tag_name().name() == "item")
                    .enumerate()
                {
                    markup_signature.push(format!("item:{index}:item"));
                    collect_value_metadata(
                        item,
                        &format!("item:{index}"),
                        &placeholder_regex,
                        &mut placeholders,
                        &mut markup_signature,
                    );
                    has_default_text |= !node_text(item).trim().is_empty();
                }
            }
            ResourceKind::Plurals => {
                for item in node.children().filter(Node::is_element) {
                    if item.tag_name().name() != "item" {
                        continue;
                    }
                    let quantity = item
                        .attribute("quantity")
                        .context("plural item without quantity")?;
                    if !is_legal_plural_quantity(quantity) {
                        bail!("illegal plural quantity: {name}: {quantity}");
                    }
                    if !plural_quantities.insert(quantity.to_owned()) {
                        bail!("duplicate plural quantity: {name}: {quantity}");
                    }
                    collect_value_metadata(
                        item,
                        &format!("quantity:{quantity}"),
                        &placeholder_regex,
                        &mut placeholders,
                        &mut markup_signature,
                    );
                    has_default_text |= !node_text(item).trim().is_empty();
                }
            }
        }

        entries.insert(
            name.clone(),
            ResourceEntry {
                name,
                kind,
                translatable,
                placeholders,
                markup_signature,
                plural_quantities,
                has_default_text,
            },
        );
    }
    Ok(Catalog { entries })
}

fn validate_pair(source_xml: &str, target_xml: &str, locale: &str) -> Result<()> {
    let source = parse_catalog(source_xml).map_err(|error| anyhow!("English source: {error}"))?;
    let target = parse_catalog(target_xml).map_err(|error| anyhow!("{locale}: {error}"))?;
    let mut errors = Vec::new();

    for entry in source.entries.values() {
        if !entry.has_default_text {
            errors.push(format!("missing English default: {}", entry.name));
        }
    }

    for (name, target_entry) in &target.entries {
        let Some(source_entry) = source.entries.get(name) else {
            errors.push(format!("target-only resource: {name}"));
            continue;
        };
        if !source_entry.translatable {
            errors.push(format!("non-translatable resource in target: {name}"));
            continue;
        }
        if source_entry.kind != target_entry.kind {
            errors.push(format!("resource kind mismatch: {name}"));
            continue;
        }

        match source_entry.kind {
            ResourceKind::Plurals => {
                if let Err(error) = validate_plural_entry(source_entry, target_entry) {
                    errors.push(error.to_string());
                }
            }
            ResourceKind::String | ResourceKind::StringArray => {
                if source_entry.placeholders != target_entry.placeholders {
                    errors.push(format!("placeholder mismatch: {name}"));
                }
                if source_entry.markup_signature != target_entry.markup_signature {
                    errors.push(format!("markup mismatch: {name}"));
                }
            }
        }
    }
    fail_if_errors(locale, errors)
}

pub fn check_repository(root: &Path) -> Result<()> {
    let res = root.join("app/src/main/res");
    let mut errors = Vec::new();
    for entry in fs::read_dir(&res).with_context(|| format!("read {}", res.display()))? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if (name == "values-zh" || name.starts_with("values-zh-"))
            && name != "values-zh-rCN"
            && name != "values-zh-rTW"
        {
            errors.push(format!("unexpected Chinese resource directory: {name}"));
        }
    }

    let source_path = res.join("values/strings.xml");
    let source = match read_catalog(&source_path) {
        Ok(source) => Some(source),
        Err(error) => {
            errors.push(error.to_string());
            None
        }
    };
    for (locale, relative_path) in [
        ("zh-rCN", "values-zh-rCN/strings.xml"),
        ("zh-rTW", "values-zh-rTW/strings.xml"),
    ] {
        let target_path = res.join(relative_path);
        match read_catalog(&target_path) {
            Ok(target) => {
                if let Some(source) = &source
                    && let Err(error) = validate_pair(source, &target, locale)
                {
                    errors.push(format!("{} [{locale}]: {error}", target_path.display()));
                }
            }
            Err(error) => errors.push(format!("{} [{locale}]: {error}", target_path.display())),
        }
    }
    fail_if_errors("repository", errors)?;
    println!("i18n catalogs are valid");
    Ok(())
}

fn fail_if_errors(context: &str, errors: Vec<String>) -> Result<()> {
    if errors.is_empty() {
        Ok(())
    } else {
        bail!("{context} validation failed:\n{}", errors.join("\n"))
    }
}

fn read_catalog(path: &Path) -> Result<String> {
    fs::read_to_string(path).with_context(|| format!("read catalog {}", path.display()))
}

fn validate_plural_entry(source: &ResourceEntry, target: &ResourceEntry) -> Result<()> {
    for quantity in &target.plural_quantities {
        if !is_legal_plural_quantity(quantity) {
            bail!("illegal plural quantity: {}: {quantity}", target.name);
        }
        if !source.plural_quantities.contains(quantity) {
            continue;
        }
        let prefix = format!("quantity:{quantity}:");
        let source_placeholders = metadata_for_prefix(&source.placeholders, &prefix);
        let target_placeholders = metadata_for_prefix(&target.placeholders, &prefix);
        if source_placeholders != target_placeholders {
            bail!("placeholder mismatch: {}", source.name);
        }
        let source_markup = signatures_for_prefix(&source.markup_signature, &prefix);
        let target_markup = signatures_for_prefix(&target.markup_signature, &prefix);
        if source_markup != target_markup {
            bail!("markup mismatch: {}", source.name);
        }
    }
    Ok(())
}

fn metadata_for_prefix(values: &BTreeMap<String, String>, prefix: &str) -> Vec<String> {
    values
        .iter()
        .filter(|(key, _)| key.starts_with(prefix))
        .map(|(_, value)| value.clone())
        .collect()
}

fn signatures_for_prefix(values: &[String], prefix: &str) -> Vec<String> {
    values
        .iter()
        .filter_map(|value| value.strip_prefix(prefix).map(str::to_owned))
        .collect()
}

fn collect_value_metadata(
    node: Node<'_, '_>,
    scope: &str,
    placeholder_regex: &Regex,
    placeholders: &mut BTreeMap<String, String>,
    markup_signature: &mut Vec<String>,
) {
    let text = node_text(node);
    let mut tokens = placeholder_regex
        .captures_iter(&text)
        .map(|captures| {
            let index = captures.name("index").expect("placeholder index").as_str();
            let date = captures
                .name("date")
                .map(|value| value.as_str().to_ascii_lowercase())
                .unwrap_or_default();
            let conversion = captures
                .name("conversion")
                .expect("placeholder conversion")
                .as_str()
                .to_ascii_lowercase();
            format!("%{index}${date}{conversion}")
        })
        .collect::<Vec<_>>();
    tokens.sort();
    for (index, token) in tokens.into_iter().enumerate() {
        placeholders.insert(format!("{scope}:placeholder:{index}"), token);
    }
    collect_markup(node, scope, markup_signature);
}

fn collect_markup(node: Node<'_, '_>, scope: &str, signature: &mut Vec<String>) {
    for child in node.children().filter(Node::is_element) {
        let mut attributes = child
            .attributes()
            .map(|attribute| format!("{}={}", attribute.name(), attribute.value()))
            .collect::<Vec<_>>();
        attributes.sort();
        signature.push(format!(
            "{scope}:open:{}[{}]",
            child.tag_name().name(),
            attributes.join(",")
        ));
        collect_markup(child, scope, signature);
        signature.push(format!("{scope}:close:{}", child.tag_name().name()));
    }
}

fn node_text(node: Node<'_, '_>) -> String {
    node.descendants()
        .filter(Node::is_text)
        .filter_map(|descendant| descendant.text())
        .collect()
}

fn is_legal_plural_quantity(quantity: &str) -> bool {
    matches!(quantity, "zero" | "one" | "two" | "few" | "many" | "other")
}

#[cfg(test)]
mod tests {
    use super::{check_repository, parse_catalog, validate_pair};
    use std::{
        fs,
        path::PathBuf,
        time::{SystemTime, UNIX_EPOCH},
    };

    const SOURCE: &str = r#"<resources>
        <string name="hello">Hello</string>
        <string name="bye">Bye</string>
    </resources>"#;

    #[test]
    fn accepts_missing_target_keys_and_rejects_target_only_keys() {
        let missing = r#"<resources><string name="hello">你好</string></resources>"#;
        validate_pair(SOURCE, missing, "zh-rCN").unwrap();

        let target_only = r#"<resources>
            <string name="hello">你好</string>
            <string name="extra">额外</string>
        </resources>"#;
        let error = validate_pair(SOURCE, target_only, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("target-only resource: extra"), "{error}");
    }

    #[test]
    fn rejects_placeholder_index_or_type_changes() {
        let source = r#"<resources><string name="welcome">Hello %1$s</string></resources>"#;
        let target = r#"<resources><string name="welcome">你好 %2$d</string></resources>"#;
        let error = validate_pair(source, target, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("placeholder mismatch: welcome"), "{error}");
    }

    #[test]
    fn accepts_different_legal_plural_quantity_sets() {
        let source = r#"<resources><plurals name="message_count">
            <item quantity="one">%1$d message</item>
            <item quantity="other">%1$d messages</item>
        </plurals></resources>"#;
        let target = r#"<resources><plurals name="message_count">
            <item quantity="other">%1$d 条消息</item>
        </plurals></resources>"#;
        validate_pair(source, target, "zh-rCN").unwrap();
    }

    #[test]
    fn rejects_resource_kind_changes_and_duplicate_keys() {
        let source = r#"<resources><string-array name="modes">
            <item>One</item>
        </string-array></resources>"#;
        let changed_kind = r#"<resources><string name="modes">一</string></resources>"#;
        let error = validate_pair(source, changed_kind, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("resource kind mismatch: modes"), "{error}");

        let duplicate = r#"<resources>
            <string name="same">One</string>
            <string name="same">Two</string>
        </resources>"#;
        let error = parse_catalog(duplicate).unwrap_err().to_string();
        assert!(error.contains("duplicate resource: same"), "{error}");
    }

    #[test]
    fn compares_markup_tag_structure_and_ignores_translation_text() {
        let source =
            r#"<resources><string name="styled"><b>Hello</b> <i>%1$s</i></string></resources>"#;
        let compatible =
            r#"<resources><string name="styled"><b>你好</b> <i>%1$s</i></string></resources>"#;
        validate_pair(source, compatible, "zh-rTW").unwrap();

        let reordered =
            r#"<resources><string name="styled"><i>%1$s</i> <b>你好</b></string></resources>"#;
        let error = validate_pair(source, reordered, "zh-rTW")
            .unwrap_err()
            .to_string();
        assert!(error.contains("markup mismatch: styled"), "{error}");
    }

    #[test]
    fn rejects_malformed_xml_and_missing_english_default_text() {
        let malformed = r#"<resources><string name="hello">Hello</resources>"#;
        assert!(
            parse_catalog(malformed)
                .unwrap_err()
                .to_string()
                .contains("malformed XML")
        );

        let missing = r#"<resources><string name="empty"></string></resources>"#;
        let error = validate_pair(missing, "<resources/>", "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("missing English default: empty"), "{error}");
    }

    #[test]
    fn ignores_escaped_percent_and_compares_indexed_placeholders() {
        let source =
            r#"<resources><string name="progress">100%% — %1$s / %2$d</string></resources>"#;
        let compatible =
            r#"<resources><string name="progress">%2$d / %1$s — 100%%</string></resources>"#;
        validate_pair(source, compatible, "zh-rCN").unwrap();

        let changed =
            r#"<resources><string name="progress">100%% — %1$d / %2$d</string></resources>"#;
        let error = validate_pair(source, changed, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("placeholder mismatch: progress"), "{error}");
    }

    #[test]
    fn normalizes_indexed_formatter_flags_width_and_precision() {
        let source = r#"<resources><string name="ratio">100%% — %1$.2f</string></resources>"#;
        let compatible = r#"<resources><string name="ratio">%1$.3f — 100%%</string></resources>"#;
        validate_pair(source, compatible, "zh-rCN").unwrap();

        for changed in [
            r#"<resources><string name="ratio">%2$.2f</string></resources>"#,
            r#"<resources><string name="ratio">%1$.2s</string></resources>"#,
        ] {
            let error = validate_pair(source, changed, "zh-rCN")
                .unwrap_err()
                .to_string();
            assert!(error.contains("placeholder mismatch: ratio"), "{error}");
        }
    }

    #[test]
    fn compares_ordered_markup_attributes() {
        let source = r#"<resources><string name="link"><a href="one" style="bold">Open</a></string></resources>"#;
        let compatible = r#"<resources><string name="link"><a href="one" style="bold">打开</a></string></resources>"#;
        validate_pair(source, compatible, "zh-rCN").unwrap();

        let changed = r#"<resources><string name="link"><a href="two" style="bold">打开</a></string></resources>"#;
        let error = validate_pair(source, changed, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("markup mismatch: link"), "{error}");
    }

    #[test]
    fn compares_string_array_item_count_and_positions() {
        let source = r#"<resources><string-array name="modes">
            <item>One</item><item>Two</item>
        </string-array></resources>"#;
        let compatible = r#"<resources><string-array name="modes">
            <item>一</item><item>二</item>
        </string-array></resources>"#;
        validate_pair(source, compatible, "zh-rCN").unwrap();

        let missing_item = r#"<resources><string-array name="modes">
            <item>一</item>
        </string-array></resources>"#;
        let error = validate_pair(source, missing_item, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("markup mismatch: modes"), "{error}");
    }

    #[test]
    fn rejects_illegal_plural_quantities_and_nontranslatable_targets() {
        let source = r#"<resources>
            <plurals name="count"><item quantity="other">%1$d items</item></plurals>
            <string name="wire" translatable="false">stable</string>
        </resources>"#;
        let illegal = r#"<resources><plurals name="count"><item quantity="manyish">%1$d 条</item></plurals></resources>"#;
        let error = validate_pair(source, illegal, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(
            error.contains("illegal plural quantity: count: manyish"),
            "{error}"
        );

        let translated = r#"<resources><string name="wire">变化</string></resources>"#;
        let error = validate_pair(source, translated, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(
            error.contains("non-translatable resource in target: wire"),
            "{error}"
        );
    }

    #[test]
    fn aggregates_pair_and_repository_errors_with_locale_and_path() {
        let source = r#"<resources>
            <string name="hello">Hello</string>
            <string name="wire" translatable="false">stable</string>
        </resources>"#;
        let target = r#"<resources>
            <string name="extra">额外</string>
            <string name="wire">变化</string>
        </resources>"#;
        let error = validate_pair(source, target, "zh-rCN")
            .unwrap_err()
            .to_string();
        assert!(error.contains("zh-rCN"), "{error}");
        assert!(error.contains("target-only resource: extra"), "{error}");
        assert!(
            error.contains("non-translatable resource in target: wire"),
            "{error}"
        );

        let root = temporary_root("aggregate-errors");
        write_catalog(&root, "values", source);
        write_catalog(&root, "values-zh-rCN", target);
        write_catalog(
            &root,
            "values-zh-rTW",
            r#"<resources><string name="hello">你好</resources>"#,
        );
        let error = check_repository(&root).unwrap_err().to_string();
        assert!(error.contains("values-zh-rCN/strings.xml"), "{error}");
        assert!(error.contains("zh-rCN"), "{error}");
        assert!(error.contains("target-only resource: extra"), "{error}");
        assert!(error.contains("values-zh-rTW/strings.xml"), "{error}");
        assert!(error.contains("zh-rTW"), "{error}");
        assert!(error.contains("malformed XML"), "{error}");

        write_catalog(&root, "values-zh-rCN", r#"<resources/>"#);
        write_catalog(
            &root,
            "values-zh-rTW",
            r#"<resources>
                <string name="hello">你好</string>
                <string name="hello">嗨</string>
            </resources>"#,
        );
        let error = check_repository(&root).unwrap_err().to_string();
        assert!(error.contains("values-zh-rTW/strings.xml"), "{error}");
        assert!(error.contains("zh-rTW"), "{error}");
        assert!(error.contains("duplicate resource: hello"), "{error}");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn repository_check_reads_exact_catalogs_and_rejects_unexpected_chinese_directories() {
        let root = temporary_root("repository-check");
        write_catalog(
            &root,
            "values",
            r#"<resources><string name="hello">Hello</string></resources>"#,
        );
        write_catalog(
            &root,
            "values-zh-rCN",
            r#"<resources><string name="hello">你好</string></resources>"#,
        );
        write_catalog(&root, "values-zh-rTW", r#"<resources/>"#);
        check_repository(&root).unwrap();

        write_catalog(&root, "values-zh-rHK", r#"<resources/>"#);
        let error = check_repository(&root).unwrap_err().to_string();
        assert!(
            error.contains("unexpected Chinese resource directory: values-zh-rHK"),
            "{error}"
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_unqualified_values_zh_directory() {
        let root = temporary_root("unqualified-values-zh");
        write_catalog(
            &root,
            "values",
            r#"<resources><string name="hello">Hello</string></resources>"#,
        );
        write_catalog(&root, "values-zh-rCN", r#"<resources/>"#);
        write_catalog(&root, "values-zh-rTW", r#"<resources/>"#);
        write_catalog(&root, "values-zh", r#"<resources/>"#);
        let error = check_repository(&root).unwrap_err().to_string();
        assert!(
            error.contains("unexpected Chinese resource directory: values-zh"),
            "{error}"
        );
        fs::remove_dir_all(root).unwrap();
    }

    fn temporary_root(label: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root =
            std::env::temp_dir().join(format!("wekit-i18n-{label}-{}-{nonce}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        root
    }

    fn write_catalog(root: &PathBuf, directory: &str, xml: &str) {
        let path = root.join("app/src/main/res").join(directory);
        fs::create_dir_all(&path).unwrap();
        fs::write(path.join("strings.xml"), xml).unwrap();
    }
}
