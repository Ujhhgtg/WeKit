from __future__ import annotations

import keyword
import re
import warnings
from collections.abc import Callable
from typing import Any, TypeVar

_JavaClass = TypeVar("_JavaClass", bound=type[Any])
_pythonized: set[type[Any]] = set()
_reported_collisions: set[tuple[type[Any], str]] = set()


def _snake(name: str) -> str:
    converted = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    converted = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", converted).lower()
    return f"{converted}_" if keyword.iskeyword(converted) else converted


def pythonize(java_class: _JavaClass) -> _JavaClass:
    """Add non-destructive snake-case aliases to a Chaquopy proxy class."""
    if java_class in _pythonized:
        return java_class
    _pythonized.add(java_class)
    members = set(dir(java_class))
    method_arities: dict[str, set[int]] = {}
    for method in java_class.getClass().getMethods():
        method_arities.setdefault(str(method.getName()), set()).add(
            len(method.getParameterTypes())
        )
    for name in members:
        if name.startswith("_"):
            continue
        alias = _snake(name)
        if alias != name:
            if hasattr(java_class, alias):
                collision = (java_class, alias)
                if collision not in _reported_collisions:
                    _reported_collisions.add(collision)
                    warnings.warn(
                        f"Python alias collision on {java_class.__name__}.{alias}",
                        stacklevel=2,
                    )
            else:
                try:
                    setattr(java_class, alias, getattr(java_class, name))
                except (AttributeError, TypeError):
                    pass
        prefix = (
            "get" if name.startswith("get") else "is" if name.startswith("is") else None
        )
        if (
            prefix is not None
            and len(name) > len(prefix)
            and 0 in method_arities.get(name, set())
        ):
            suffix = name[len(prefix) :]
            property_name = _snake(suffix)
            setter_name = "set" + suffix
            if not hasattr(java_class, property_name):
                getter = lambda self, method=name: getattr(self, method)()
                setter: Callable[[Any, Any], object] | None = None
                if 1 in method_arities.get(setter_name, set()):
                    setter = lambda self, value, method=setter_name: getattr(
                        self, method
                    )(value)
                try:
                    setattr(java_class, property_name, property(getter, setter))
                except (AttributeError, TypeError):
                    pass
    return java_class


def install() -> None:
    """Install automatic additive pythonization for every Chaquopy Java proxy."""
    from java import chaquopy  # ty: ignore[unresolved-import]

    chaquopy.set_jclass_pythonizer(pythonize)
