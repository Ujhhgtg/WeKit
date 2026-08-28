from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class StringMatchMode(str, Enum):
    CONTAINS = "CONTAINS"
    STARTS_WITH = "STARTS_WITH"
    ENDS_WITH = "ENDS_WITH"
    REGEX = "REGEX"
    EQUALS = "EQUALS"


@dataclass(frozen=True)
class StringMatcher:
    value: str
    mode: StringMatchMode = StringMatchMode.CONTAINS
    ignore_case: bool = False


@dataclass(frozen=True)
class ClassMatcher:
    descriptor: str | None = None
    name: StringMatcher | None = None
    source_file: StringMatcher | None = None
    modifiers: int | None = None
    super_class: str | None = None
    interfaces: list[str] = field(default_factory=list)
    using_strings: list[StringMatcher] = field(default_factory=list)
    search_packages: list[str] = field(default_factory=list)
    exclude_packages: list[str] = field(default_factory=list)
    ignore_packages_case: bool = False
    fields: list[FieldMatcher] = field(default_factory=list)
    methods: list[MethodMatcher] = field(default_factory=list)
    all_of: list[ClassMatcher] = field(default_factory=list)
    any_of: list[ClassMatcher] = field(default_factory=list)
    none_of: list[ClassMatcher] = field(default_factory=list)


@dataclass(frozen=True)
class MethodMatcher:
    descriptor: str | None = None
    name: StringMatcher | None = None
    modifiers: int | None = None
    declared_class: str | None = None
    return_type: str | None = None
    parameter_types: list[str | None] | None = None
    parameter_count: int | None = None
    proto_shorty: str | None = None
    op_codes: list[int] = field(default_factory=list)
    op_names: list[str] = field(default_factory=list)
    using_strings: list[StringMatcher] = field(default_factory=list)
    using_numbers: list[int | float] = field(default_factory=list)
    using_fields: list[str] = field(default_factory=list)
    invoked_methods: list[str] = field(default_factory=list)
    caller_methods: list[str] = field(default_factory=list)
    search_packages: list[str] = field(default_factory=list)
    exclude_packages: list[str] = field(default_factory=list)
    ignore_packages_case: bool = False
    all_of: list[MethodMatcher] = field(default_factory=list)
    any_of: list[MethodMatcher] = field(default_factory=list)
    none_of: list[MethodMatcher] = field(default_factory=list)


@dataclass(frozen=True)
class FieldMatcher:
    descriptor: str | None = None
    name: StringMatcher | None = None
    modifiers: int | None = None
    declared_class: str | None = None
    type: str | None = None
    search_packages: list[str] = field(default_factory=list)
    exclude_packages: list[str] = field(default_factory=list)
    ignore_packages_case: bool = False
    read_methods: list[MethodMatcher] = field(default_factory=list)
    write_methods: list[MethodMatcher] = field(default_factory=list)
    all_of: list[FieldMatcher] = field(default_factory=list)
    any_of: list[FieldMatcher] = field(default_factory=list)
    none_of: list[FieldMatcher] = field(default_factory=list)


def contains(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchMode.CONTAINS, ignore_case)


def starts_with(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchMode.STARTS_WITH, ignore_case)


def ends_with(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchMode.ENDS_WITH, ignore_case)


def regex(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchMode.REGEX, ignore_case)


def eq(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchMode.EQUALS, ignore_case)
