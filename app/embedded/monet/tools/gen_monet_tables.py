#!/usr/bin/env python3
"""Import conservative decoded domestic color evidence into the V2 role/profile schema.

This is an explicit verification/import utility, not the final payload generator. It never
emits the retired ``monet_tables.json``, mutates the checked-in role/profile inputs, or marks
decoded resources as exact/selectable. Only a runtime ``monet-resource-graph-v1`` digest can
select an exact profile. The normal S4 sync does not invoke this tool; its output therefore
cannot vary with ambient decoded trees.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


DOMESTIC_SOURCES = {
    "8.0.65": "wechat_8065",
    "8.0.67": "wechat_8067",
    "8.0.69": "wechat_8069",
    "8.0.74": "wechat_8074",
    "8.0.76": "wechat_8076",
}
LITERAL_RE = re.compile(r"literal:COLOR_(?:RGB8|ARGB8):(\d+)")
HEX_RE = re.compile(r"^#([0-9a-fA-F]{3,8})$")


def load_color_file(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    root = ET.parse(path).getroot()
    return {
        element.attrib["name"]: (element.text or "").strip()
        for element in root.findall("color")
        if "name" in element.attrib
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resource_snapshot(source: Path) -> tuple[int, str]:
    resource_root = source / "app/src/main/res"
    paths = []
    for path in resource_root.rglob("*.xml"):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(resource_root)
        directory = relative.parts[0]
        if (
            directory.startswith("drawable")
            or directory.startswith("layout")
            or (directory.startswith("values") and relative.name == "colors.xml")
        ):
            paths.append((relative.as_posix(), path))
    manifest = "".join(
        f"{relative}\t{sha256_file(path)}\n"
        for relative, path in sorted(paths)
    ).encode("utf-8")
    return len(paths), hashlib.sha256(manifest).hexdigest()


def normalize_literal(value: str | None) -> int | None:
    if value is None:
        return None
    match = HEX_RE.match(value.strip())
    if not match:
        return None
    raw = match.group(1)
    if len(raw) == 3:
        raw = "ff" + "".join(character * 2 for character in raw)
    elif len(raw) == 4:
        raw = "".join(character * 2 for character in raw)
    elif len(raw) == 6:
        raw = "ff" + raw
    elif len(raw) != 8:
        return None
    return int(raw, 16)


def resolve_color(name: str, values: dict[str, str], expanding: set[str] | None = None) -> int | None:
    expanding = set() if expanding is None else expanding
    if name in expanding:
        return None
    raw = values.get(name)
    if raw is None:
        return None
    if raw.startswith("@color/"):
        return resolve_color(raw.removeprefix("@color/"), values, expanding | {name})
    return normalize_literal(raw)


def signature_literal(signature: str | None) -> int | None:
    if signature is None:
        return None
    matches = LITERAL_RE.findall(signature)
    return int(matches[-1]) if matches else None


def import_version(
    version_name: str,
    source: Path,
    roles_by_id: dict[str, dict[str, object]],
    exact_keys: dict[str, dict[str, str]],
) -> dict[str, object]:
    resource_root = source / "app/src/main/res"
    light = load_color_file(resource_root / "values/colors.xml")
    night_only = load_color_file(resource_root / "values-night/colors.xml")
    if not light:
        raise ValueError(f"decoded domestic source has no values/colors.xml: {source}")
    night = {**light, **night_only}
    pairs = {
        name: (resolve_color(name, light), resolve_color(name, night))
        for name in light
    }

    evidence: dict[str, dict[str, str]] = {}
    for role_id, role in sorted(roles_by_id.items()):
        if role.get("type") != "color":
            continue
        expected_light = signature_literal(role.get("defaultValue"))
        expected_night = signature_literal(role.get("nightValue"))
        if expected_light is None:
            continue
        if expected_night is None:
            expected_night = expected_light
        preferred = exact_keys.get(role_id, {}).get("name")
        candidates = [
            name
            for name, pair in pairs.items()
            if pair == (expected_light, expected_night)
        ]
        if preferred in candidates:
            candidates = [preferred]
        if len(candidates) == 1:
            evidence[role_id] = {"type": "color", "name": candidates[0]}

    return {
        "versionName": version_name,
        "channel": "domestic",
        "selectable": False,
        "sourceKind": "decoded-resources",
        "reason": "Structural color evidence only; exact APK graph digest is unavailable.",
        "roles": evidence,
    }


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def validate_output_path(output: Path, roles: Path, profiles: Path, evidence: Path) -> None:
    resolved_output = output.resolve()
    aliases = {roles.resolve(), profiles.resolve(), evidence.resolve()}
    if resolved_output in aliases:
        raise ValueError("import output must not alias roles, profiles, or embedded evidence")


def main() -> None:
    payload = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roles", type=Path, default=payload / "monet_roles.json")
    parser.add_argument("--profiles", type=Path, default=payload / "monet_profiles.json")
    parser.add_argument("--wechat-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    evidence = payload / "tools/domestic_structural_profiles.b85"
    validate_output_path(arguments.output, arguments.roles, arguments.profiles, evidence)

    catalog = json.loads(arguments.roles.read_text(encoding="utf-8"))
    profiles = json.loads(arguments.profiles.read_text(encoding="utf-8"))
    roles_by_id = {role["id"]: role for role in catalog["roles"]}
    verified = profiles.get("verifiedProfiles", [])
    if len(verified) != 1 or verified[0].get("versionCode") != 3084:
        raise ValueError("expected the single audited Play 3084 exact profile")
    exact_keys = verified[0]["roles"]

    retained = [
        profile
        for profile in profiles.get("structuralOnlyProfiles", [])
        if profile.get("channel") != "domestic"
    ]
    domestic_profiles = [
        profile
        for profile in profiles.get("structuralOnlyProfiles", [])
        if profile.get("channel") == "domestic"
    ]
    expected_domestic = {
        profile["versionName"]: profile
        for profile in domestic_profiles
    }
    if len(domestic_profiles) != 5 or len(expected_domestic) != 5 or set(expected_domestic) != set(DOMESTIC_SOURCES):
        raise ValueError("checked profile does not contain the five audited domestic versions")

    imported = []
    for version_name, directory in DOMESTIC_SOURCES.items():
        expected = expected_domestic[version_name]
        source = arguments.wechat_root / directory
        file_count, snapshot_sha256 = resource_snapshot(source)
        expected_source = expected.get("sourceEvidence")
        if expected_source != {
            "resourceFileCount": file_count,
            "resourceSnapshotSha256": snapshot_sha256,
        }:
            raise ValueError(
                f"{version_name} decoded resource snapshot drift: "
                f"{file_count} files, SHA-256 {snapshot_sha256}"
            )
        profile = import_version(
            version_name,
            source,
            roles_by_id,
            exact_keys,
        )
        profile["sourceEvidence"] = expected_source
        if profile != expected:
            raise ValueError(f"{version_name} regenerated structural role map drift")
        imported.append(profile)
    profiles["structuralOnlyProfiles"] = retained + imported

    # Emit only after the decoded snapshots and regenerated role maps exactly match the
    # checked evidence. This cannot promote structural-only data into an exact profile.
    write_json(arguments.output, profiles)
    for profile in imported:
        print(f"{profile['versionName']}: {len(profile['roles'])} conservative color candidates")


if __name__ == "__main__":
    main()
