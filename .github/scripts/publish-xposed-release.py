#!/usr/bin/env python3
"""Publish the standard CI APK as a separate, immutable-by-convention release."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


REPOSITORY = "Xposed-Modules-Repo/dev.ujhhgtg.wekit"
API = f"repos/{REPOSITORY}"
APK_NAME = "app-standard-release.apk"


def gh_api(endpoint, *, method="GET", data=None):
    command = ["gh", "api", endpoint, "--method", method]
    if data is not None:
        command += ["--input", "-"]
    result = subprocess.run(
        command, input=json.dumps(data) if data is not None else None,
        text=True, capture_output=True, check=True,
    )
    return json.loads(result.stdout)


def inspect_apk(apk, commit, build_tools):
    if apk.name != APK_NAME:
        raise ValueError(f"Expected {APK_NAME}, got {apk.name}")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A full build commit SHA is required")
    with zipfile.ZipFile(apk) as archive:
        for name in ("assets/xposed_init", "META-INF/xposed/java_init.list"):
            if not archive.read(name).strip():
                raise ValueError(f"Missing standard Xposed entry: {name}")

    subprocess.run(
        [str(build_tools / "apksigner"), "verify", str(apk)], check=True,
    )
    badging = subprocess.check_output(
        [str(build_tools / "aapt2"), "dump", "badging", str(apk)], text=True,
    )
    package = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        badging, re.M,
    )
    if not package or package[1] != REPOSITORY.split("/")[1]:
        raise ValueError("APK package does not match the module repository")
    if package[3] != f"git+{commit[:8]}":
        raise ValueError("APK versionName does not match the build commit")
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not sdk:
        raise ValueError("APK minSdk is missing")
    digest = "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest()
    return f"{package[2]}-{package[3]}", package[3], sdk[1], digest


def find_release(tag):
    # List releases also includes drafts for users with push access. Errors must
    # propagate: an authorization or network failure is not a missing release.
    page = 1
    while True:
        releases = gh_api(f"{API}/releases?per_page=100&page={page}")
        matches = [release for release in releases if release["tag_name"] == tag]
        if len(matches) > 1:
            raise ValueError(f"Multiple releases found for {tag}")
        if matches:
            return matches[0]
        if len(releases) < 100:
            return None
        page += 1


def verify_asset(release, apk, digest):
    assets = release["assets"]
    if len(assets) != 1 or assets[0]["name"] != APK_NAME:
        raise ValueError("Release must contain exactly the standard APK")
    asset = assets[0]
    if asset["state"] != "uploaded" or asset["size"] != apk.stat().st_size:
        raise ValueError("Release APK is incomplete or has a different size")
    remote_digest = asset.get("digest")
    if remote_digest is None:
        # Older GitHub assets can lack a digest. Verify bytes instead of treating
        # a missing digest as success or replacing the asset.
        with tempfile.TemporaryFile() as output:
            subprocess.run(
                ["gh", "api", f"{API}/releases/assets/{asset['id']}",
                 "-H", "Accept: application/octet-stream"],
                stdout=output, check=True,
            )
            output.seek(0)
            remote_digest = "sha256:" + hashlib.file_digest(output, "sha256").hexdigest()
    if remote_digest != digest:
        raise ValueError("The same version already contains a different APK; refusing to overwrite")


def publish(apk, payload, digest, marker):
    repository = gh_api(API)
    release = find_release(payload["tag_name"])
    if release is not None:
        if not release["draft"]:
            verify_asset(release, apk, digest)
            if release["prerelease"]:
                raise ValueError("Existing release is a prerelease, expected an ordinary release")
            print(f"Already published: {release['html_url']}")
            return
        if marker not in (release["body"] or "").splitlines():
            raise ValueError("Existing draft belongs to a different build; refusing to change it")
    else:
        # This branch belongs to the metadata repository, not the build repo.
        release = gh_api(
            f"{API}/releases", method="POST",
            data={**payload, "target_commitish": repository["default_branch"]},
        )

    if not release["assets"]:
        subprocess.run(
            ["gh", "release", "upload", payload["tag_name"], str(apk),
             "--repo", REPOSITORY], check=True,
        )
    release = gh_api(f"{API}/releases/{release['id']}")
    verify_asset(release, apk, digest)
    if not release["draft"] or marker not in (release["body"] or "").splitlines():
        raise ValueError("Release changed while uploading; refusing to modify it")
    result = gh_api(
        f"{API}/releases/{release['id']}", method="PATCH",
        data={"draft": False, "prerelease": False},
    )
    if result["draft"] or result["prerelease"]:
        raise ValueError("GitHub did not publish an ordinary release")
    verify_asset(result, apk, digest)
    print(f"Published: {result['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--dry-run", action="store_true", help="Validate APK and print notes without accessing GitHub")
    args = parser.parse_args()
    if not args.dry_run and not os.environ.get("GH_TOKEN"):
        raise ValueError("GH_TOKEN is missing; configure the XPOSED_REPO_TOKEN Actions secret")
    tools = args.build_tools
    if tools is None:
        sdk = Path(os.environ["ANDROID_HOME"])
        stable = [p for p in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
        tools = max(stable, key=lambda p: tuple(map(int, p.name.split("."))))
    apk = args.apk.resolve()
    tag, version, min_sdk, digest = inspect_apk(apk, args.commit, tools)
    marker = f"<!-- wekit-build:{args.commit}:{digest} -->"
    notes = args.notes_file.read_text().strip()
    body = f"""WeKit CI 构建 · standard（LSPosed）

版本：`{tag}`

构建提交：`{args.commit}`

{notes}

### 安装

- 本发布仅提供 `app-standard-release.apk`，适用于 LSPosed。
- APK 最低 Android SDK：{min_sdk}；架构：ARM64。
- 安装后在 LSPosed 中启用模块并勾选微信，完全结束并重启微信。
- 设置入口：微信「我 → 设置 → WeKit 设置」。
- [兼容范围与使用说明](https://docs.wekit.ujhhgtg.dev/getting-started) · [其他框架所需的 legacy 版本](https://github.com/Ujhhgtg/WeKit/releases/tag/CI)

### 校验

`{APK_NAME}`

SHA-256：`{digest.removeprefix('sha256:')}`

{marker}
"""
    payload = {"tag_name": tag, "name": version, "body": body, "draft": True, "prerelease": False}
    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        publish(apk, payload, digest, marker)


if __name__ == "__main__":
    main()
