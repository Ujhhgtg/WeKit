from __future__ import annotations

import importlib
import sys
import unittest
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from types import ModuleType


RUNTIME_PYTHON = Path(__file__).resolve().parents[2] / "main" / "python"
MIGRATION_HEADING = "The `httpx` package has been replaced by `httpx2` in this runtime."


@contextmanager
def isolated_httpx_import() -> Iterator[None]:
    previous = {
        name: module
        for name, module in sys.modules.items()
        if name == "httpx" or name.startswith("httpx.")
    }
    for name in previous:
        del sys.modules[name]
    sys.path.insert(0, str(RUNTIME_PYTHON))
    importlib.invalidate_caches()
    try:
        yield
    finally:
        for name in tuple(sys.modules):
            if name == "httpx" or name.startswith("httpx."):
                del sys.modules[name]
        sys.path.remove(str(RUNTIME_PYTHON))
        sys.modules.update(previous)
        importlib.invalidate_caches()


class HttpxShimTest(unittest.TestCase):
    def test_import_httpx_explains_how_to_migrate(self) -> None:
        with isolated_httpx_import(), self.assertRaises(ImportError) as raised:
            importlib.import_module("httpx")

        message = str(raised.exception)
        self.assertIn(MIGRATION_HEADING, message)
        self.assertIn("import httpx2", message)
        self.assertIn("httpx2.alias_httpx()", message)

    def test_nested_httpx_import_raises_the_same_migration_error(self) -> None:
        with isolated_httpx_import(), self.assertRaises(ImportError) as raised:
            exec("from httpx.any.deep.module import value", {})

        self.assertIn(MIGRATION_HEADING, str(raised.exception))

    def test_registered_httpx_alias_bypasses_the_shim(self) -> None:
        alias = ModuleType("httpx")
        marker = object()
        setattr(alias, "marker", marker)

        with isolated_httpx_import():
            sys.modules["httpx"] = alias
            imported = importlib.import_module("httpx")

        self.assertIs(alias, imported)
        self.assertIs(marker, imported.marker)


if __name__ == "__main__":
    unittest.main()
