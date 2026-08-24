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
    def test_publish_retires_legacy_files_and_preserves_nonmanaged_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            (output / "tools").mkdir(parents=True)
            (output / "tools/keep.py").write_text("keep", encoding="utf-8")
            for legacy in ("template_api31.apk", "template_api34.apk", "monet_tables.json"):
                (output / legacy).write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")

            sync.publish_generated(generated, output)

            self.assertEqual("keep", (output / "tools/keep.py").read_text(encoding="utf-8"))
            self.assertEqual("new", (output / "monet_roles.json").read_text(encoding="utf-8"))
            self.assertFalse(any((output / name).exists() for name in sync.LEGACY_OUTPUT_NAMES))

    def test_publish_failure_before_swap_preserves_old_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "payload"
            generated = root / "generated"
            output.mkdir()
            (output / "monet_roles.json").write_text("old", encoding="utf-8")
            self._write_generated(generated, "new")

            with mock.patch.object(sync.shutil, "copyfile", side_effect=OSError("injected")):
                with self.assertRaises(OSError):
                    sync.publish_generated(generated, output)

            self.assertEqual("old", (output / "monet_roles.json").read_text(encoding="utf-8"))

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


if __name__ == "__main__":
    unittest.main()
