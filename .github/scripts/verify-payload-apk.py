#!/usr/bin/env python3
"""Check the private APK has business JNI and no public injection/install contract."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile
from collections import Counter
from apk_dex_definitions import dex_definitions

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as apk:
    names = apk.namelist()
    assert 0 < args.apk.stat().st_size <= 128 * 1024 * 1024, "payload APK exceeds client limit"
    assert all(count == 1 for count in Counter(names).values()), "duplicate ZIP entry"
    assert apk.testzip() is None, "APK CRC failed"
    for name in ("AndroidManifest.xml", "classes.dex", "resources.arsc", "lib/arm64-v8a/libwekit_native.so"):
        assert apk.getinfo(name).file_size > 0, f"missing business content: {name}"
    assert not any(name.startswith("META-INF/xposed/") or name in ("module.prop", "customize.sh") for name in names), "public injection content in private APK"
    assert not any(name.endswith(("/libwekit_zygisk.so", "/libwekit_loader.so", "/libdexkit.so", "/libinvoke_tool.so", "/libproot.so")) for name in names), "nonbusiness native in payload"
    assert sum(entry.file_size for entry in apk.infolist()) <= 256 * 1024 * 1024, "expanded ZIP exceeds client limit"
    assert sum(entry.file_size for entry in apk.infolist() if re.fullmatch(r"classes(?:\d+)?\.dex", entry.filename)) <= 128 * 1024 * 1024, "expanded DEX exceeds client limit"
    assert sum(entry.file_size for entry in apk.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")) <= 64 * 1024 * 1024, "native exceeds client limit"
    # DEX string IDs include type descriptors even when the classes are R8-renamed. The
    # shared runtime is provided compileOnly, so verify definitions using dexdump instead.
    sdk = Path(__file__).resolve().parents[2] / "local.properties"
    android_sdk = None
    if sdk.is_file():
        for line in sdk.read_text().splitlines():
            if line.startswith("sdk.dir="):
                android_sdk = Path(line.removeprefix("sdk.dir="))
    if android_sdk is None:
        import os
        android_sdk = Path(os.environ["ANDROID_HOME"])
    tools = sorted((android_sdk / "build-tools").iterdir(), key=lambda p: [int(n) for n in re.findall(r"\d+", p.name)])[-1]
    subprocess.run([str(tools / "apksigner"), "verify", str(args.apk)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(args.apk)], check=True, stdout=subprocess.DEVNULL)
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(args.apk)], text=True)
    assert re.search(r"^package: name='dev\.ujhhgtg\.wekit' ", badging, re.M), "wrong payload application ID"
    assert "minSdkVersion:'29'" in badging and "targetSdkVersion:'37'" in badging, "wrong payload SDK contract"
    resources = subprocess.check_output([str(tools / "aapt2"), "dump", "resources", str(args.apk)], text=True)
    assert re.search(r"^Package name=dev\.ujhhgtg\.wekit id=69$", resources, re.M), "wrong payload resource package ID"
    definitions = dex_definitions(apk, tools)
    shared = ("Lkotlin/", "Lorg/luckypray/dexkit/", "Lcom/google/flatbuffers/", "Ldev/ujhhgtg/reflekt/",
              "Ldev/ujhhgtg/wekit/loader/api/", "Ldev/ujhhgtg/wekit/loader/abc/", "Ldev/ujhhgtg/wekit/bootstrap/")
    duplicated = [name for name in definitions if name.startswith(shared)]
    assert not duplicated, f"shared runtime duplicated in payload: {duplicated[:8]}"
    assert "Ldev/ujhhgtg/wekit/loader/startup/PayloadEntryImpl;" in definitions, "release entry not retained"
print("PASS private payload packaging/signature")
