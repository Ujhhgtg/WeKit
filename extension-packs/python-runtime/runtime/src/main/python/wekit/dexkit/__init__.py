from __future__ import annotations

from ._generated import *
from ._generated import __all__ as _generated_all


def contains(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchType.Contains, ignore_case)


def starts_with(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchType.StartsWith, ignore_case)


def ends_with(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchType.EndsWith, ignore_case)


def regex(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchType.SimilarRegex, ignore_case)


def eq(value: str, *, ignore_case: bool = False) -> StringMatcher:
    return StringMatcher(value, StringMatchType.Equals, ignore_case)


__all__ = _generated_all + [
    "contains",
    "starts_with",
    "ends_with",
    "regex",
    "eq",
]
