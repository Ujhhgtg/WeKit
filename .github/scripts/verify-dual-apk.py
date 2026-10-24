#!/usr/bin/env python3
"""Check the actual signed APK contract, optionally simulate module installation."""

import argparse
from collections import Counter
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile
from apk_dex_definitions import dex_definitions


REQUIRED = (
    "META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list",
    "AndroidManifest.xml", "classes.dex", "resources.arsc", "module.prop",
    "customize.sh", "uninstall.sh", "sepolicy.rule", "config.sh", "action.sh",
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
    "webroot/index.html", "webroot/css/app.css", "webroot/js/bridge.js",
    "webroot/js/app.js", "webroot/js/kernelsu.js",
    "lib/arm64-v8a/libwekit_loader.so", "lib/arm64-v8a/libwekit_zygisk.so",
    "lib/arm64-v8a/libandroidx.graphics.path.so",
)
EXPECTED_BOOTSTRAP_CERT_SHA256 = "6bc3be50d46327b8c66b564ff946a9c51bbb72a198fc7cd651e9726901584315"


def check_apk(apk, tools, expected_cert_sha256=EXPECTED_BOOTSTRAP_CERT_SHA256, expected_apk_sha256=None):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert not [name for name, count in Counter(names).items() if count != 1], "duplicate ZIP entries"
        assert archive.testzip() is None, "ZIP CRC failed"
        for name in REQUIRED:
            assert archive.getinfo(name).file_size > 0 or name == "sepolicy.rule", f"missing or empty: {name}"
        for name in ("lib/arm64-v8a/libwekit_loader.so",
                     "lib/arm64-v8a/libandroidx.graphics.path.so"):
            assert archive.getinfo(name).compress_type == zipfile.ZIP_STORED, f"framework native lookup requires STORED: {name}"
        assert not any(name.endswith((".apk", "dex.list")) or name.startswith(("payload/", "zygisk/")) for name in names), "nested payload or duplicated loader"
        assert not any(name in names for name in ("verify.sh", "post-fs-data.sh", "service.sh")), "obsolete installer files"
        dex = [name for name in names if name.endswith(".dex")]
        assert set(dex) == {"classes.dex", *(f"classes{i}.dex" for i in range(2, len(dex) + 1))}, "noncanonical or duplicate DEX"
        assert {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')} == {"arm64-v8a"}
        assert "assets/xposed_init" not in names
        assert not any(name.endswith("/libwekit_native.so") for name in names), "business JNI in public bootstrap"
        for name in names:
            assert "frida" not in name.lower() and "gum-js-loop" not in name.lower(), "Frida artifact name shipped"
        for name in ("lib/arm64-v8a/libwekit_loader.so", "lib/arm64-v8a/libwekit_zygisk.so"):
            native = archive.read(name).lower()
            assert b"frida" not in native and b"gum-js-loop" not in native, f"Frida marker shipped in {name}"
        assert archive.read("META-INF/xposed/java_init.list").decode().strip() == "dev.ujhhgtg.wekit.bootstrap.loader.entry.lxp.LxpHookEntry"
        props = dict(line.split('=', 1) for line in archive.read("module.prop").decode().splitlines() if '=' in line and not line.startswith('#'))
        assert props["id"] == "wekit_zygisk"
        definitions = dex_definitions(archive, tools)
        business = ("Ldev/ujhhgtg/wekit/features/", "Ldev/ujhhgtg/wekit/agent/", "Ldev/ujhhgtg/wekit/data/")
        assert not any(name.startswith(business) for name in definitions), "business classes in public bootstrap"
        assert "Ldev/ujhhgtg/wekit/loader/abc/IClassLoaderHelper;" not in definitions, "obsolete loader helper retained"
        for name in ("Ldev/ujhhgtg/wekit/loader/api/PayloadEntry;",
                     "Ldev/ujhhgtg/wekit/loader/api/PayloadEnvironment;",
                     "Ldev/ujhhgtg/wekit/loader/api/AuthorizationControls;",
                     "Ldev/ujhhgtg/wekit/loader/abc/IHookBridge;",
                     "Ldev/ujhhgtg/wekit/loader/abc/ILoaderService;",
                     "Ldev/ujhhgtg/reflekt/ReflektKt;",
                     "Lkotlin/jvm/internal/Intrinsics;",
                     "Lkotlin/reflect/jvm/ReflectJvmMapping;"):
            assert definitions[name] == 1, f"missing/duplicate shared definition: {name}"

    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    assert package and package.group(2) == props["versionCode"] and package.group(3) == props["version"], "APK/module version mismatch"
    assert package.group(1) == "dev.ujhhgtg.wekit", "wrong public application ID"
    assert "minSdkVersion:'29'" in badging and "targetSdkVersion:'37'" in badging, "wrong public SDK contract"
    resources = subprocess.check_output([str(tools / "aapt2"), "dump", "resources", str(apk)], text=True)
    assert re.search(r"^Package name=dev\.ujhhgtg\.wekit\.bootstrap id=68$", resources, re.M), "wrong public resource package name/ID"
    signer_output = subprocess.check_output(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    certificate_digests = re.findall(r"Signer(?: #\d+)? certificate SHA-256 digest: ([0-9A-Fa-f:]+)", signer_output)
    assert len(certificate_digests) == 1, "APK must have exactly one signer certificate"
    certificate_digest = certificate_digests[0].replace(":", "").lower()
    if expected_cert_sha256 is not None:
        assert certificate_digest == expected_cert_sha256.lower().replace(":", ""), "unexpected bootstrap signer certificate"
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True, stdout=subprocess.DEVNULL)
    apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    if expected_apk_sha256 is not None:
        assert apk_sha256 == expected_apk_sha256.lower(), "unexpected bootstrap APK SHA-256"
    print(f"PASS {apk.name}: signed/aligned, signer {certificate_digest}, sha256 {apk_sha256}, {len(dex)} DEX, {props['description']}")


def check_installer(apk):
    with tempfile.TemporaryDirectory(prefix="wekit-module-check-") as work:
        work = Path(work)
        module = work / "module"
        module.mkdir()
        temp = work / "tmp"
        temp.mkdir()
        adb = work / "adb"
        (adb / "wekit").mkdir(parents=True)
        (adb / "modules/wekit").mkdir(parents=True)
        choices = b"0\tcom.tencent.mm\t1\n10\tcom.tencent.mm\t0\n"
        (adb / "wekit/injection-targets.tsv").write_bytes(choices)
        old_payload = module / "payload"
        old_payload.mkdir()
        (old_payload / "classes2.dex").write_bytes(b"obsolete")
        package = work / "module.zip"
        shutil.copyfile(apk, package)
        assert hashlib.sha256(package.read_bytes()).digest() == hashlib.sha256(apk.read_bytes()).digest()
        with zipfile.ZipFile(apk) as archive:
            # Redirect the installer's fixed root state paths into this test's
            # temporary directory. No device or real /data/adb is accessed.
            installer = work / "customize.sh"
            installer.write_text(archive.read("customize.sh").decode().replace("/data/adb", str(adb)))
            loader = archive.read("lib/arm64-v8a/libwekit_zygisk.so")
        harness = r'''
ui_print() { :; }
abort() { echo "$*" >&2; exit 1; }
set_perm() { chmod "$4" "$1"; }
set_perm_recursive() { chmod "$5" "$1"/* && chmod "$4" "$1"; }
# Reproduce BusyBox's successful exit when an extraction pattern matches nothing.
unzip() {
  command unzip "$@"
  unzip_status=$?
  [ "$unzip_status" = 11 ] && return 0
  return "$unzip_status"
}
. "$INSTALLER_SCRIPT"
'''
        env = dict(os.environ, BOOTMODE="true", KSU="1", ARCH="arm64", ZIPFILE=str(package),
                   MODPATH=str(module), TMPDIR=str(temp), INSTALLER_SCRIPT=str(installer))
        def run(ok, **changes):
            result = subprocess.run(["sh", "-c", harness], env=env | changes, capture_output=True, text=True)
            assert (result.returncode == 0) == ok, result.stderr

        run(True)
        assert (module / "module.apk").read_bytes() == apk.read_bytes()
        assert (module / "zygisk/arm64-v8a.so").read_bytes() == loader
        assert not (module / "payload").exists()
        assert not list(module.rglob("*.dex"))
        assert (adb / "wekit_zygisk/injection-targets.tsv").read_bytes() == choices
        assert (adb / "modules/wekit/disable").exists()
        retained = b"10\tcom.tencent.mm\t1\n"
        (adb / "wekit_zygisk/injection-targets.tsv").write_bytes(retained)
        run(True)
        assert (adb / "wekit_zygisk/injection-targets.tsv").read_bytes() == retained
        run(False, ARCH="x86_64")
        missing = work / "missing.zip"
        with zipfile.ZipFile(apk) as source, zipfile.ZipFile(missing, "w") as dest:
            for entry in source.infolist():
                if entry.filename != "lib/arm64-v8a/libwekit_zygisk.so":
                    dest.writestr(entry, source.read(entry))
        run(False, ZIPFILE=str(missing))
        corrupt = work / "corrupt.zip"
        corrupt.write_bytes(b"not a zip")
        run(False, ZIPFILE=str(corrupt))
        assert (module / "module.apk").read_bytes() == apk.read_bytes(), "failed install changed the APK"
        print(f"PASS {apk.name}: installer, original APK/loader bytes, migration, preserved choices, invalid inputs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--check-installer", action="store_true")
    parser.add_argument("--expected-cert-sha256", default=EXPECTED_BOOTSTRAP_CERT_SHA256)
    parser.add_argument("--expected-apk-sha256", default=os.environ.get("WEKIT_BOOTSTRAP_APK_SHA256"))
    parser.add_argument("--allow-nonproduction-signer", action="store_true")
    args = parser.parse_args()
    tools = args.build_tools
    if tools is None:
        sdk = Path(os.environ["ANDROID_HOME"])
        stable = [path for path in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", path.name)]
        tools = max(stable, key=lambda path: tuple(map(int, path.name.split('.'))))
    for apk in args.apk:
        expected_cert = None if args.allow_nonproduction_signer else args.expected_cert_sha256
        check_apk(apk.resolve(), tools.resolve(), expected_cert, args.expected_apk_sha256)
        if args.check_installer:
            check_installer(apk.resolve())


if __name__ == "__main__":
    main()
