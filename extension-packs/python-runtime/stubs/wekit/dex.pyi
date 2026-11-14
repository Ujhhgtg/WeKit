"""Hand-written spec-based DEX API (DexKit-free).

Specs are plain snake_case dicts produced by the builder helpers in this module and consumed by
ctx.dex / find_classes / find_methods / find_fields. Keys may be omitted; unknown keys are ignored.

Match modes: "equals" (default for name/type specs), "starts_with", "contains", "ends_with".
StringSpec builders default to "contains".

method_spec keys:
  descriptor: str | None
  name: NameSpec | str
  modifiers: int (DexKit Contains semantics)
  declared_class / return_type: class_spec
  param_types: list[None | str | type_spec] (None wildcard; [] = exactly zero params)
  param_count: int | [min, max] | {"min": int, "max": int}
  using_strings: list[str | string_spec]
  using_numbers: list[int | float]
  using_fields: list[using_field(...)]  (field=field_spec, using_type="read"|"write")
  invoke_methods / caller_methods / any_of / all_of: list[method_spec]
  opcodes: opcode_spec(...)  (names=..., match_type="contains"|"equals", count=range)
  annotations: list[annotation_spec(...)]

class_spec keys:
  class_name: NameSpec | str
  super_class / interfaces / fields / methods: class_spec / list[...]
  field_count / method_count / interface_count: range
  modifiers: int
  using_strings: list[str | string_spec]
  annotations: list[annotation_spec(...)]

field_spec keys:
  name: NameSpec | str
  modifiers: int
  type / declared_class: class_spec
  read_methods / write_methods: list[method_spec]

annotation_spec keys: type: NameSpec | str, visibility: "build"|"runtime"|"system"|"unknown",
  elements: list[annotation_element(name, value)]
annotation_element value forms: {"text": str}, {"descriptor": str}, {"number": int|float},
  {"boolean": bool}, {"list": [...]}, {"nested": annotation_spec}. Raw strings/numbers/bools and
  lists of those also work.

Results are immutable dataclasses; ctx.dex returns the same objects as find_*.
"""
from __future__ import annotations

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

Spec = dict[str, Any]

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

def name(value: str, match: str = "equals") -> Spec: ...
def type_(value: str, match: str = "equals") -> Spec: ...
def type_spec(value: str, match: str = "equals") -> Spec: ...
def string(value: str, match: str = "contains") -> Spec: ...
def string_spec(value: str, match: str = "contains") -> Spec: ...
def using_strings(*values: object) -> list[Spec]: ...
def count_range(minimum: int = 0, maximum: int | None = None) -> Spec: ...
def class_spec(
    *,
    class_name: Spec | str | None = None,
    super_class: Spec | None = None,
    interfaces: Sequence[Spec] = (),
    fields: Sequence[Spec] = (),
    methods: Sequence[Spec] = (),
    field_count: object = None,
    method_count: object = None,
    interface_count: object = None,
    modifiers: int | None = None,
    using_strings: Sequence[object] = (),
    annotations: Sequence[Spec] = (),
) -> Spec: ...
def method_spec(
    *,
    descriptor: str | None = None,
    name: Spec | str | None = None,
    modifiers: int | None = None,
    declared_class: Spec | None = None,
    return_type: Spec | None = None,
    param_types: Sequence[object] | None = None,
    param_count: object = None,
    using_strings: Sequence[object] = (),
    using_numbers: Sequence[int | float] = (),
    using_fields: Sequence[Spec] = (),
    invoke_methods: Sequence[Spec] = (),
    caller_methods: Sequence[Spec] = (),
    any_of: Sequence[Spec] = (),
    all_of: Sequence[Spec] = (),
    opcodes: Spec | None = None,
    annotations: Sequence[Spec] = (),
) -> Spec: ...
def field_spec(
    *,
    name: Spec | str | None = None,
    modifiers: int | None = None,
    type: Spec | None = None,
    declared_class: Spec | None = None,
    read_methods: Sequence[Spec] = (),
    write_methods: Sequence[Spec] = (),
) -> Spec: ...
def using_field(field: Spec | None = None, using_type: str | None = None) -> Spec: ...
def opcode_spec(
    *,
    names: Sequence[str] = (),
    match_type: str = "contains",
    count: object = None,
) -> Spec: ...
def annotation_element(name: Spec | str, value: object) -> Spec: ...
def annotation_spec(
    *,
    type: Spec | str | None = None,
    visibility: str | None = None,
    elements: Sequence[Spec] = (),
) -> Spec: ...
def text_value(value: str) -> Spec: ...
def descriptor_value(value: str) -> Spec: ...
def number_value(value: int | float) -> Spec: ...
def boolean_value(value: bool) -> Spec: ...
def list_value(*values: object) -> Spec: ...
def nested_value(annotation: Spec) -> Spec: ...
def find_classes(
    spec: Spec,
) -> list[ResolvedClass]: ...
def find_methods(
    spec: Spec,
) -> list[ResolvedMethod]: ...
def find_fields(
    spec: Spec,
) -> list[ResolvedField]: ...
