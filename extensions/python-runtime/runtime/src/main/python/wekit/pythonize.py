from __future__ import annotations

import re
import warnings
from collections.abc import Callable
from typing import Any, TypeVar

_JavaClass = TypeVar("_JavaClass", bound=type[Any])
_pythonized: set[type[Any]] = set()
_reported_collisions: set[tuple[type[Any], str]] = set()


def _snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def pythonize(java_class: _JavaClass) -> _JavaClass:
    """Add non-destructive snake-case aliases to a Chaquopy proxy class."""
    if java_class in _pythonized:
        return java_class
    _pythonized.add(java_class)
    members = set(dir(java_class))
    for name in members:
        alias = _snake(name)
        if alias != name:
            if hasattr(java_class, alias):
                collision = (java_class, alias)
                if collision not in _reported_collisions:
                    _reported_collisions.add(collision)
                    warnings.warn(f"Python alias collision on {java_class.__name__}.{alias}", stacklevel=2)
            else:
                try:
                    setattr(java_class, alias, getattr(java_class, name))
                except (AttributeError, TypeError):
                    pass
        prefix = "get" if name.startswith("get") else "is" if name.startswith("is") else None
        if prefix is not None and len(name) > len(prefix):
            suffix = name[len(prefix):]
            property_name = _snake(suffix[0].lower() + suffix[1:])
            setter_name = "set" + suffix
            if not hasattr(java_class, property_name):
                getter = lambda self, method=name: getattr(self, method)()
                setter: Callable[[Any, Any], object] | None = None
                if setter_name in members:
                    setter = lambda self, value, method=setter_name: getattr(
                        self, method
                    )(value)
                try:
                    setattr(java_class, property_name, property(getter, setter))
                except (AttributeError, TypeError):
                    pass
    return java_class
