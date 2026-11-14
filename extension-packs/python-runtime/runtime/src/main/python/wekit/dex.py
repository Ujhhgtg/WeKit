"""Spec-based DEX queries for WeKit Python plugins (DexKit-free).

Build plain snake_case spec dicts with the helpers in this module and pass them to ctx.dex or to
find_classes / find_methods / find_fields when a plugin context is active. Specs are executed by
WeKit's query matcher over the host APK; results are immutable Python dataclasses with descriptors,
names, types and modifiers.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

__all__ = [
    "ResolvedClass",
    "ResolvedMethod",
    "ResolvedField",
    "annotation_element",
    "annotation_spec",
    "boolean_value",
    "class_spec",
    "count_range",
    "descriptor_value",
    "field_spec",
    "find_classes",
    "find_fields",
    "find_methods",
    "list_value",
    "method_spec",
    "name",
    "nested_value",
    "number_value",
    "opcode_spec",
    "string",
    "string_spec",
    "text_value",
    "type_",
    "type_spec",
    "using_field",
    "using_strings",
]

_MATCH_MODES = {
    "equals": "equals",
    "eq": "equals",
    "starts_with": "starts_with",
    "startswith": "starts_with",
    "prefix": "starts_with",
    "contains": "contains",
    "ends_with": "ends_with",
    "endswith": "ends_with",
    "suffix": "ends_with",
}


@dataclass(frozen=True)
class ResolvedClass:
    descriptor: str
    name: str
    modifiers: int
    host_version: str
    host_build_tag: str


@dataclass(frozen=True)
class ResolvedMethod:
    descriptor: str
    name: str
    declared_class: str
    return_type: str
    param_types: tuple[str, ...]
    modifiers: int
    is_constructor: bool
    host_version: str
    host_build_tag: str


@dataclass(frozen=True)
class ResolvedField:
    descriptor: str
    name: str
    declared_class: str
    type_name: str
    modifiers: int
    host_version: str
    host_build_tag: str


def _mode(value: str) -> str:
    try:
        return _MATCH_MODES[value.strip().lower()]
    except KeyError as error:
        raise ValueError(f"unknown match mode: {value!r}") from error


def name(value: str, match: str = "equals") -> dict[str, Any]:
    return {"value": value, "match": _mode(match)}


def type_(value: str, match: str = "equals") -> dict[str, Any]:
    return {"value": value, "match": _mode(match)}


type_spec = type_


def string(value: str, match: str = "contains") -> dict[str, Any]:
    return {"value": value, "match": _mode(match)}


string_spec = string


def _range(value: object) -> object:
    if value is None or isinstance(value, int):
        return value
    if isinstance(value, Mapping):
        return dict(value)
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        return list(value)
    raise TypeError(f"count/range must be int, [min, max] or {{min, max}}: {value!r}")


def count_range(minimum: int = 0, maximum: int | None = None) -> dict[str, Any]:
    if maximum is None:
        maximum = minimum
    return {"min": minimum, "max": maximum}


def class_spec(
    *,
    class_name: dict[str, Any] | str | None = None,
    super_class: dict[str, Any] | None = None,
    interfaces: Sequence[dict[str, Any]] = (),
    fields: Sequence[dict[str, Any]] = (),
    methods: Sequence[dict[str, Any]] = (),
    field_count: object = None,
    method_count: object = None,
    interface_count: object = None,
    modifiers: int | None = None,
    using_strings: Sequence[object] = (),
    annotations: Sequence[dict[str, Any]] = (),
) -> dict[str, Any]:
    spec: dict[str, Any] = {}
    if class_name is not None:
        spec["class_name"] = name(class_name) if isinstance(class_name, str) else class_name
    if super_class is not None:
        spec["super_class"] = super_class
    if interfaces:
        spec["interfaces"] = list(interfaces)
    if fields:
        spec["fields"] = list(fields)
    if methods:
        spec["methods"] = list(methods)
    if field_count is not None:
        spec["field_count"] = _range(field_count)
    if method_count is not None:
        spec["method_count"] = _range(method_count)
    if interface_count is not None:
        spec["interface_count"] = _range(interface_count)
    if modifiers is not None:
        spec["modifiers"] = modifiers
    if using_strings:
        spec["using_strings"] = [_string_spec(value) for value in using_strings]
    if annotations:
        spec["annotations"] = list(annotations)
    return spec


def method_spec(
    *,
    descriptor: str | None = None,
    name: dict[str, Any] | str | None = None,
    modifiers: int | None = None,
    declared_class: dict[str, Any] | None = None,
    return_type: dict[str, Any] | None = None,
    param_types: Sequence[object] | None = None,
    param_count: object = None,
    using_strings: Sequence[object] = (),
    using_numbers: Sequence[int | float] = (),
    using_fields: Sequence[dict[str, Any]] = (),
    invoke_methods: Sequence[dict[str, Any]] = (),
    caller_methods: Sequence[dict[str, Any]] = (),
    any_of: Sequence[dict[str, Any]] = (),
    all_of: Sequence[dict[str, Any]] = (),
    opcodes: dict[str, Any] | None = None,
    annotations: Sequence[dict[str, Any]] = (),
) -> dict[str, Any]:
    spec: dict[str, Any] = {}
    if descriptor is not None:
        spec["descriptor"] = descriptor
    if name is not None:
        spec["name"] = globals()["name"](name) if isinstance(name, str) else name
    if modifiers is not None:
        spec["modifiers"] = modifiers
    if declared_class is not None:
        spec["declared_class"] = declared_class
    if return_type is not None:
        spec["return_type"] = return_type
    if param_types is not None:
        spec["param_types"] = [
            item if item is None or isinstance(item, Mapping) else _type_spec_value(item)
            for item in param_types
        ]
    if param_count is not None:
        spec["param_count"] = _range(param_count)
    if using_strings:
        spec["using_strings"] = [_string_spec(value) for value in using_strings]
    if using_numbers:
        spec["using_numbers"] = list(using_numbers)
    if using_fields:
        spec["using_fields"] = list(using_fields)
    if invoke_methods:
        spec["invoke_methods"] = list(invoke_methods)
    if caller_methods:
        spec["caller_methods"] = list(caller_methods)
    if any_of:
        spec["any_of"] = list(any_of)
    if all_of:
        spec["all_of"] = list(all_of)
    if opcodes is not None:
        spec["opcodes"] = opcodes
    if annotations:
        spec["annotations"] = list(annotations)
    return spec


def field_spec(
    *,
    name: dict[str, Any] | str | None = None,
    modifiers: int | None = None,
    type: dict[str, Any] | None = None,
    declared_class: dict[str, Any] | None = None,
    read_methods: Sequence[dict[str, Any]] = (),
    write_methods: Sequence[dict[str, Any]] = (),
) -> dict[str, Any]:
    spec: dict[str, Any] = {}
    if name is not None:
        spec["name"] = globals()["name"](name) if isinstance(name, str) else name
    if modifiers is not None:
        spec["modifiers"] = modifiers
    if type is not None:
        spec["type"] = type
    if declared_class is not None:
        spec["declared_class"] = declared_class
    if read_methods:
        spec["read_methods"] = list(read_methods)
    if write_methods:
        spec["write_methods"] = list(write_methods)
    return spec


def using_field(field: dict[str, Any] | None = None, using_type: str | None = None) -> dict[str, Any]:
    spec: dict[str, Any] = {}
    if field is not None:
        spec["field"] = field
    if using_type is not None:
        if using_type not in ("read", "write"):
            raise ValueError("using_type must be 'read' or 'write'")
        spec["using_type"] = using_type
    return spec


def using_strings(*values: object) -> list[dict[str, Any]]:
    return [_string_spec(value) for value in values]


def _string_spec(value: object) -> dict[str, Any]:
    if isinstance(value, str):
        return {"value": value, "match": "contains"}
    if isinstance(value, Mapping):
        return dict(value)
    raise TypeError(f"using_strings entries must be str or string spec: {value!r}")


def _type_spec_value(value: object) -> dict[str, Any]:
    if isinstance(value, str):
        return type_(value)
    raise TypeError(f"param_types entries must be None, str or type spec: {value!r}")


def opcode_spec(
    *,
    names: Sequence[str] = (),
    match_type: str = "contains",
    count: object = None,
) -> dict[str, Any]:
    if match_type not in ("contains", "equals"):
        raise ValueError("opcode match_type must be 'contains' or 'equals'")
    spec: dict[str, Any] = {"names": list(names), "match_type": match_type}
    if count is not None:
        spec["count"] = _range(count)
    return spec


def annotation_element(name: dict[str, Any] | str, value: object) -> dict[str, Any]:
    return {
        "name": globals()["name"](name) if isinstance(name, str) else name,
        "value": value,
    }


def annotation_spec(
    *,
    type: dict[str, Any] | str | None = None,
    visibility: str | None = None,
    elements: Sequence[dict[str, Any]] = (),
) -> dict[str, Any]:
    spec: dict[str, Any] = {}
    if type is not None:
        spec["type"] = globals()["name"](type) if isinstance(type, str) else type
    if visibility is not None:
        normalized = visibility.strip().lower()
        if normalized not in ("build", "runtime", "system", "unknown"):
            raise ValueError(f"unknown annotation visibility: {visibility!r}")
        spec["visibility"] = normalized
    if elements:
        spec["elements"] = list(elements)
    return spec


def text_value(value: str) -> dict[str, Any]:
    return {"text": value}


def descriptor_value(value: str) -> dict[str, Any]:
    return {"descriptor": value}


def number_value(value: int | float) -> dict[str, Any]:
    return {"number": value}


def boolean_value(value: bool) -> dict[str, Any]:
    return {"boolean": value}


def list_value(*values: object) -> dict[str, Any]:
    return {"list": list(values)}


def nested_value(annotation: dict[str, Any]) -> dict[str, Any]:
    return {"nested": annotation}


def _json(spec: Mapping[str, Any]) -> str:
    if not isinstance(spec, Mapping):
        raise TypeError("dex query requires a spec dict")
    return json.dumps(dict(spec), separators=(",", ":"))


def _context() -> Any:
    from . import _bootstrap

    return _bootstrap.current_context()


def _get(value: object, snake: str, getter: str) -> Any:
    direct = getattr(value, snake, None)
    if direct is not None:
        return direct
    method = getattr(value, getter, None)
    if callable(method):
        return method()
    raise AttributeError(f"resolved DEX object has no {snake}/{getter}")


def to_resolved_class(value: object) -> ResolvedClass:
    return ResolvedClass(
        descriptor=str(_get(value, "descriptor", "getDescriptor")),
        name=str(_get(value, "name", "getName")),
        modifiers=int(_get(value, "modifiers", "getModifiers")),
        host_version=str(_get(value, "host_version", "getHostVersion")),
        host_build_tag=str(_get(value, "host_build_tag", "getHostBuildTag")),
    )


def to_resolved_method(value: object) -> ResolvedMethod:
    params = _get(value, "param_types", "getParamTypes")
    return ResolvedMethod(
        descriptor=str(_get(value, "descriptor", "getDescriptor")),
        name=str(_get(value, "name", "getName")),
        declared_class=str(_get(value, "declared_class", "getDeclaredClass")),
        return_type=str(_get(value, "return_type", "getReturnType")),
        param_types=tuple(str(item) for item in params),
        modifiers=int(_get(value, "modifiers", "getModifiers")),
        is_constructor=bool(_get(value, "is_constructor", "isConstructor")),
        host_version=str(_get(value, "host_version", "getHostVersion")),
        host_build_tag=str(_get(value, "host_build_tag", "getHostBuildTag")),
    )


def to_resolved_field(value: object) -> ResolvedField:
    return ResolvedField(
        descriptor=str(_get(value, "descriptor", "getDescriptor")),
        name=str(_get(value, "name", "getName")),
        declared_class=str(_get(value, "declared_class", "getDeclaredClass")),
        type_name=str(_get(value, "type_name", "getTypeName")),
        modifiers=int(_get(value, "modifiers", "getModifiers")),
        host_version=str(_get(value, "host_version", "getHostVersion")),
        host_build_tag=str(_get(value, "host_build_tag", "getHostBuildTag")),
    )


def find_classes(
    spec: dict[str, Any],
) -> list[ResolvedClass]:
    return _context().dex.classes(
        spec,
    )


def find_methods(
    spec: dict[str, Any],
) -> list[ResolvedMethod]:
    return _context().dex.methods(
        spec,
    )


def find_fields(
    spec: dict[str, Any],
) -> list[ResolvedField]:
    return _context().dex.fields(
        spec,
    )
