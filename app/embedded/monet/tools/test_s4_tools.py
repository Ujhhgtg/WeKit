import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOLS = Path(__file__).resolve().parent


def load_module(name: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


sync = load_module("sync_s4_payload")
importer = load_module("gen_monet_tables")


class S4ToolTest(unittest.TestCase):
    def test_preserved_output_contract_includes_state_recovery_scripts(self):
        self.assertEqual(
            {
                "boot-completed.sh",
                "common.sh",
                "customize.sh",
                "service.sh",
                "update-binary",
                "updater-script",
                "tools/domestic_structural_profiles.b85",
                "tools/gen_monet_tables.py",
                "tools/sync_s4_payload.py",
                "tools/test_s4_tools.py",
            },
            set(sync.PRESERVED_OUTPUT_FILES),
        )

    def test_publish_uses_allowlist_and_preserves_required_repository_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            output.mkdir()
            output.chmod(0o751)
            self._write_preserved(output)
            (output / "stale.txt").write_text("stale", encoding="utf-8")
            (output / "__pycache__").mkdir()
            (output / "__pycache__/stale.pyc").write_bytes(b"stale")
            (output / "stale-dir").mkdir()
            (output / "stale-dir/file").write_text("stale", encoding="utf-8")
            (output / "stale-link").symlink_to(output / "stale.txt")
            for legacy in ("template_api31.apk", "template_api34.apk", "monet_tables.json"):
                (output / legacy).write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")

            sync.publish_generated(generated, output)

            for relative in sync.PRESERVED_OUTPUT_FILES:
                self.assertEqual(
                    f"preserved:{relative}",
                    (output / relative).read_text(encoding="utf-8"),
                )
                expected_mode = 0o755 if relative.endswith(("gen_monet_tables.py", "sync_s4_payload.py")) else 0o644
                self.assertEqual(expected_mode, (output / relative).stat().st_mode & 0o777)
            self.assertEqual("new", (output / "monet_roles.json").read_text(encoding="utf-8"))
            self.assertFalse(any((output / name).exists() for name in sync.LEGACY_OUTPUT_NAMES))
            self.assertFalse((output / "stale.txt").exists())
            self.assertFalse((output / "stale-dir").exists())
            self.assertFalse((output / "stale-link").exists())
            self.assertFalse((output / "__pycache__").exists())
            self.assertEqual(0o751, output.stat().st_mode & 0o777)

    def test_publish_rejects_missing_required_asset_without_moving_old_output(self):
        for missing in sync.PRESERVED_OUTPUT_FILES:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / "payload"
                generated = root / "generated"
                output.mkdir()
                self._write_preserved(output)
                (output / missing).unlink()
                (output / "monet_roles.json").write_text("old", encoding="utf-8")
                self._write_generated(generated, "new")

                with self.assertRaisesRegex(ValueError, "required preserved payload asset"):
                    sync.publish_generated(generated, output)

                self.assertEqual("old", (output / "monet_roles.json").read_text(encoding="utf-8"))

    def test_publish_rejects_symlinked_required_asset_without_moving_old_output(self):
        for symlinked in sync.PRESERVED_OUTPUT_FILES:
            with self.subTest(symlinked=symlinked), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / "payload"
                generated = root / "generated"
                output.mkdir()
                self._write_preserved(output)
                (output / symlinked).unlink()
                external = root / "external"
                external.write_text("external", encoding="utf-8")
                (output / symlinked).symlink_to(external)
                (output / "monet_roles.json").write_text("old", encoding="utf-8")
                self._write_generated(generated, "new")

                with self.assertRaisesRegex(ValueError, "required preserved payload asset"):
                    sync.publish_generated(generated, output)

                self.assertEqual("old", (output / "monet_roles.json").read_text(encoding="utf-8"))
                self.assertTrue((output / symlinked).is_symlink())

    def test_publish_fresh_output_has_deterministic_directory_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            self._write_generated(generated, "new")

            sync.publish_generated(generated, output)

            self.assertEqual(0o755, output.stat().st_mode & 0o777)

    def test_publish_rejects_unexpected_generated_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            output.mkdir()
            self._write_preserved(output)
            (output / "monet_roles.json").write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")
            (generated / "unexpected").write_text("stale", encoding="utf-8")

            with self.assertRaises(ValueError):
                sync.publish_generated(generated, output)

            self.assertEqual("old", (output / "monet_roles.json").read_text(encoding="utf-8"))

    def test_publish_failure_before_swap_preserves_old_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            output.mkdir()
            self._write_preserved(output)
            (output / "monet_roles.json").write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")

            with mock.patch.object(sync.shutil, "copyfile", side_effect=OSError("injected")):
                with self.assertRaises(OSError):
                    sync.publish_generated(generated, output)

            self.assertEqual("old", (output / "monet_roles.json").read_text(encoding="utf-8"))

    def test_failed_install_and_failed_rollback_leave_recoverable_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            output.mkdir()
            self._write_preserved(output)
            (output / "monet_roles.json").write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")
            real_replace = sync.os.replace
            replace_count = 0

            def fail_install_and_rollback(source, destination):
                nonlocal replace_count
                replace_count += 1
                if replace_count in (2, 3):
                    raise OSError(f"injected replace failure {replace_count}")
                return real_replace(source, destination)

            with mock.patch.object(sync.os, "replace", side_effect=fail_install_and_rollback):
                with self.assertRaisesRegex(RuntimeError, r"backup remains at .*\.payload\.backup-"):
                    sync.publish_generated(generated, output)

            backups = list(root.glob(".payload.backup-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual("old", (backups[0] / "monet_roles.json").read_text(encoding="utf-8"))

    def test_importer_rejects_input_alias_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            roles = root / "roles.json"
            profiles = root / "profiles.json"
            evidence = root / "evidence.b85"
            roles.write_text("roles", encoding="utf-8")
            profiles.write_text("profiles", encoding="utf-8")
            evidence.write_text("evidence", encoding="utf-8")
            before = {path: path.read_bytes() for path in (roles, profiles, evidence)}

            for aliased in (roles, profiles, evidence):
                with self.assertRaises(ValueError):
                    importer.validate_output_path(aliased, roles, profiles, evidence)
            self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_atomic_json_write_replaces_complete_document(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "profiles.json"
            output.write_text("old", encoding="utf-8")
            importer.write_json(output, {"schemaVersion": 1})
            self.assertEqual({"schemaVersion": 1}, json.loads(output.read_text(encoding="utf-8")))

    def test_atomic_json_write_failure_preserves_old_document(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "profiles.json"
            output.write_text("old", encoding="utf-8")
            with mock.patch.object(importer.os, "replace", side_effect=OSError("injected")):
                with self.assertRaises(OSError):
                    importer.write_json(output, {"schemaVersion": 1})
            self.assertEqual("old", output.read_text(encoding="utf-8"))
            self.assertEqual([output], list(output.parent.iterdir()))

    @staticmethod
    def _write_generated(generated: Path, marker: str):
        (generated / "templates").mkdir(parents=True)
        for name in sync.EXPECTED_TEMPLATE_NAMES:
            (generated / "templates" / name).write_text(marker, encoding="utf-8")
        for name in sync.MANAGED_OUTPUT_FILES:
            (generated / name).write_text(marker, encoding="utf-8")

    @staticmethod
    def _write_preserved(output: Path):
        for relative in sync.PRESERVED_OUTPUT_FILES:
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"preserved:{relative}", encoding="utf-8")
            mode = 0o755 if relative.endswith(("gen_monet_tables.py", "sync_s4_payload.py")) else 0o644
            path.chmod(mode)


if __name__ == "__main__":
    unittest.main()
