from __future__ import annotations

from typing import Any

from java import jclass  # ty: ignore[unresolved-import]


def class_for_name(name: str) -> type[Any]:
    """Return the canonical Chaquopy proxy for a JVM class."""
    return jclass(name)
