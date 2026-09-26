#!/usr/bin/env python3
"""Exercise publication retries and conflicts without touching GitHub."""

import copy
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-xposed-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.apk = Path(directory.name) / publisher.APK_NAME
        self.apk.write_bytes(b"signed APK fixture")
        self.digest = "sha256:" + hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.marker = "<!-- wekit-build:test -->"
        self.payload = {
            "tag_name": "1774-git+2be8d346", "name": "git+2be8d346",
            "body": self.marker, "draft": True, "prerelease": False,
        }
        self.asset = {
            "id": 7, "name": publisher.APK_NAME, "state": "uploaded",
            "size": self.apk.stat().st_size, "digest": self.digest,
        }
        self.release = None
        self.mutations = []
        self.api_calls = []

    def existing(self, *, draft=False, assets=True):
        self.release = {
            **self.payload, "id": 42, "draft": draft,
            "assets": [copy.deepcopy(self.asset)] if assets else [],
            "html_url": "https://github.com/example/releases/test",
        }

    def api(self, endpoint, *, method="GET", data=None):
        self.api_calls.append(endpoint)
        if method == "POST":
            self.assertEqual(data["target_commitish"], "main")
            self.assertTrue(data["draft"])
            self.assertFalse(data["prerelease"])
            self.mutations.append("create-draft")
            self.existing(draft=True, assets=False)
        elif method == "PATCH":
            self.assertEqual(self.release["assets"], [self.asset])
            self.assertEqual(data, {"draft": False, "prerelease": False})
            self.mutations.append("publish")
            self.release.update(data)
        elif endpoint == publisher.API:
            return {"default_branch": "main"}
        elif "?per_page=" in endpoint:
            return [copy.deepcopy(self.release)] if self.release else []
        return copy.deepcopy(self.release)

    def run_command(self, command, **kwargs):
        if command[:3] == ["gh", "release", "upload"]:
            self.assertEqual(command[4], str(self.apk))
            self.assertNotIn("--clobber", command)
            self.mutations.append("upload")
            self.release["assets"] = [copy.deepcopy(self.asset)]
        elif command[:2] == ["gh", "api"]:
            kwargs["stdout"].write(self.apk.read_bytes())
        else:
            self.fail(f"Unexpected command: {command}")

    def publish(self):
        with patch.object(publisher, "gh_api", side_effect=self.api), patch.object(
            publisher.subprocess, "run", side_effect=self.run_command,
        ):
            publisher.publish(self.apk, self.payload, self.digest, self.marker)

    def test_new_version_uploads_before_publishing(self):
        self.publish()
        self.assertEqual(self.mutations, ["create-draft", "upload", "publish"])

    def test_identical_published_version_is_read_only(self):
        self.existing()
        self.publish()
        self.assertEqual(self.mutations, [])

    def test_different_apk_cannot_replace_published_version(self):
        self.existing()
        self.release["assets"][0]["digest"] = "sha256:different"
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            self.publish()
        self.assertEqual(self.mutations, [])

    def test_matching_draft_resumes_before_or_after_upload(self):
        for assets in (False, True):
            with self.subTest(assets=assets):
                self.mutations.clear()
                self.existing(draft=True, assets=assets)
                self.publish()
                self.assertEqual(self.mutations, (["upload"] if not assets else []) + ["publish"])

    def test_unrelated_draft_is_untouched(self):
        self.existing(draft=True)
        self.release["body"] = "another build"
        with self.assertRaisesRegex(ValueError, "different build"):
            self.publish()
        self.assertEqual(self.mutations, [])

    def test_legacy_or_incomplete_asset_blocks_publication(self):
        for change in ({"name": "app-legacy-release.apk"}, {"state": "starter"}, {"size": 0}):
            with self.subTest(change=change):
                self.existing(draft=True)
                self.release["assets"][0].update(change)
                with self.assertRaises(ValueError):
                    self.publish()
                self.assertEqual(self.mutations, [])

    def test_missing_remote_digest_downloads_and_verifies_bytes(self):
        self.existing()
        self.release["assets"][0]["digest"] = None
        self.publish()
        self.assertEqual(self.mutations, [])

    def test_prerelease_is_not_silently_accepted_or_edited(self):
        self.existing()
        self.release["prerelease"] = True
        with self.assertRaisesRegex(ValueError, "prerelease"):
            self.publish()
        self.assertEqual(self.mutations, [])

    def test_github_failure_is_not_treated_as_absent_version(self):
        with patch.object(publisher, "gh_api", side_effect=[
            {"default_branch": "main"}, subprocess.CalledProcessError(1, ["gh", "api"]),
        ]) as api, self.assertRaises(subprocess.CalledProcessError):
            publisher.publish(self.apk, self.payload, self.digest, self.marker)
        self.assertTrue(all(call.kwargs.get("method", "GET") == "GET" for call in api.call_args_list))

    def test_find_release_includes_older_pages(self):
        self.existing(draft=True)
        with patch.object(publisher, "gh_api", side_effect=[
            [{"tag_name": str(i)} for i in range(100)], [self.release],
        ]) as api:
            self.assertEqual(publisher.find_release(self.payload["tag_name"]), self.release)
        self.assertIn("page=2", api.call_args.args[0])

    def test_upload_failure_leaves_draft_for_retry(self):
        with patch.object(publisher, "gh_api", side_effect=self.api), patch.object(
            publisher.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["gh", "release", "upload"]),
        ), self.assertRaises(subprocess.CalledProcessError):
            publisher.publish(self.apk, self.payload, self.digest, self.marker)
        self.assertTrue(self.release["draft"])
        self.assertEqual(self.mutations, ["create-draft"])

    def test_realistic_badging_formats_and_commit_mismatch(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/xposed_init", "legacy.entry")
            archive.writestr("META-INF/xposed/java_init.list", "modern.entry")
        commit = "2be8d3462d9ee2e1173faa7566dfcdb1a6206f46"
        for sdk_field in ("sdkVersion", "minSdkVersion"):
            badging = (
                "package: name='dev.ujhhgtg.wekit' versionCode='1774' versionName='git+2be8d346'\n"
                f"{sdk_field}:'28'\n"
            )
            with self.subTest(sdk_field=sdk_field), patch.object(publisher.subprocess, "run"), patch.object(
                publisher.subprocess, "check_output", return_value=badging,
            ):
                self.assertEqual(publisher.inspect_apk(self.apk, commit, Path("/tools"))[:3],
                                 ("1774-git+2be8d346", "git+2be8d346", "28"))
                with self.assertRaisesRegex(ValueError, "does not match the build commit"):
                    publisher.inspect_apk(self.apk, "0" * 40, Path("/tools"))


if __name__ == "__main__":
    unittest.main()
