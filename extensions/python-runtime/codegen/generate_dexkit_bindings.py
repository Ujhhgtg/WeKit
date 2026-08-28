from __future__ import annotations

import argparse
import io
import keyword
import re
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path


MATCHER_PREFIX = "org/luckypray/dexkit/query/matchers/"
ENUM_PREFIX = "org/luckypray/dexkit/query/enums/"
COLLECTION_PREFIX = "org/luckypray/dexkit/query/"
JAVA_MODIFIERS = {
    "abstract",
    "default",
    "final",
    "native",
    "static",
    "strictfp",
    "synchronized",
}
PYTHONIC_ITERABLE_ADDERS = {
    "org.luckypray.dexkit.query.matchers.ClassMatcher": {
        "using_strings": "add_using_string",
        "interfaces": "add_interface",
        "fields": "add_field",
        "methods": "add_method",
    },
    "org.luckypray.dexkit.query.matchers.MethodMatcher": {
        "using_strings": "add_using_string",
        "using_numbers": "add_using_number",
        "using_fields": "add_using_field",
        "invoke_methods": "add_invoke",
        "caller_methods": "add_caller",
    },
    "org.luckypray.dexkit.query.matchers.FieldMatcher": {
        "read_methods": "add_read_method",
        "write_methods": "add_write_method",
    },
}
PYTHONIC_OPTION_TYPES = {
    "org.luckypray.dexkit.query.matchers.ClassMatcher": {
        "interfaces": "InterfacesMatcher | Sequence[ClassMatcher | str]",
        "using_strings": "Sequence[StringMatcher | str] | StringMatcherList",
        "fields": "FieldsMatcher | Sequence[FieldMatcher]",
        "methods": "MethodsMatcher | Sequence[MethodMatcher]",
    },
    "org.luckypray.dexkit.query.matchers.MethodMatcher": {
        "using_strings": "Sequence[StringMatcher | str] | StringMatcherList",
        "using_fields": "Sequence[UsingFieldMatcher | FieldMatcher | str]",
        "invoke_methods": "MethodsMatcher | Sequence[MethodMatcher | str]",
        "caller_methods": "MethodsMatcher | Sequence[MethodMatcher | str]",
    },
    "org.luckypray.dexkit.query.matchers.FieldMatcher": {
        "read_methods": "MethodsMatcher | Sequence[MethodMatcher | str]",
        "write_methods": "MethodsMatcher | Sequence[MethodMatcher | str]",
    },
}


@dataclass(frozen=True)
class JavaParameter:
    name: str
    type_name: str
    varargs: bool


@dataclass(frozen=True)
class JavaCallable:
    name: str
    parameters: tuple[JavaParameter, ...]
    return_type: str | None
    is_static: bool

    @property
    def signature_key(self) -> tuple[str, tuple[str, ...], str | None, bool]:
        return (
            self.name,
            tuple(parameter.type_name for parameter in self.parameters),
            self.return_type,
            self.is_static,
        )


@dataclass(frozen=True)
class JavaType:
    name: str
    constructors: tuple[JavaCallable, ...]
    methods: tuple[JavaCallable, ...]
    enum_constants: tuple[str, ...]

    @property
    def simple_name(self) -> str:
        return self.name.rsplit(".", 1)[-1]

    @property
    def is_enum(self) -> bool:
        return bool(self.enum_constants)


def snake_case(name: str) -> str:
    converted = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    converted = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", converted).lower()
    if keyword.iskeyword(converted):
        converted += "_"
    return converted


def split_top_level(value: str, delimiter: str = ",") -> list[str]:
    if not value.strip():
        return []
    parts: list[str] = []
    start = 0
    depth = 0
    for index, character in enumerate(value):
        if character in "<([":
            depth += 1
        elif character in ">)]":
            depth -= 1
        elif character == delimiter and depth == 0:
            parts.append(value[start:index].strip())
            start = index + 1
    parts.append(value[start:].strip())
    return parts


def split_return_and_name(value: str) -> tuple[str, str]:
    depth = 0
    for index in range(len(value) - 1, -1, -1):
        character = value[index]
        if character == ">":
            depth += 1
        elif character == "<":
            depth -= 1
        elif character == " " and depth == 0:
            return value[:index].strip(), value[index + 1 :].strip()
    raise ValueError(f"cannot split Java declaration: {value}")


def discover_classes(classes_jar: bytes) -> list[str]:
    with zipfile.ZipFile(io.BytesIO(classes_jar)) as archive:
        names: list[str] = []
        for entry in archive.namelist():
            if not entry.endswith(".class") or "$" in entry:
                continue
            simple_name = entry.rsplit("/", 1)[-1].removesuffix(".class")
            is_collection = entry.startswith(COLLECTION_PREFIX) and (
                simple_name.endswith("MatcherList")
                or simple_name == "StringMatchersGroupList"
            )
            if (
                entry.startswith(MATCHER_PREFIX)
                or entry.startswith(ENUM_PREFIX)
                or is_collection
            ):
                names.append(entry.removesuffix(".class").replace("/", "."))
        return sorted(names)


def parse_callable(
    declaration: str,
    class_name: str,
    local_names: dict[int, str],
) -> JavaCallable | None:
    prefix, raw_parameters = declaration.removesuffix(";").split("(", 1)
    raw_parameters = raw_parameters.removesuffix(")")
    prefix = prefix.removeprefix("public ")
    tokens = prefix.split()
    modifiers: set[str] = set()
    while tokens and tokens[0] in JAVA_MODIFIERS:
        modifiers.add(tokens.pop(0))
    callable_part = " ".join(tokens)
    if callable_part == class_name:
        name = class_name.rsplit(".", 1)[-1]
        return_type: str | None = None
    else:
        return_type, name = split_return_and_name(callable_part)

    raw_types = split_top_level(raw_parameters)
    if (
        "$" in name
        or any(
            "kotlin.jvm.internal.DefaultConstructorMarker" in item for item in raw_types
        )
        or any("kotlin.jvm.functions.Function" in item for item in raw_types)
    ):
        return None
    is_static = "static" in modifiers
    parameters: list[JavaParameter] = []
    slot = 0 if is_static else 1
    for index, raw_type in enumerate(raw_types):
        varargs = raw_type.endswith("...")
        type_name = raw_type.removesuffix("...")
        parameter_name = local_names.get(slot, f"arg_{index + 1}")
        if not parameter_name.isidentifier() or keyword.iskeyword(parameter_name):
            parameter_name = (
                name[3].lower() + name[4:]
                if len(raw_types) == 1 and name.startswith("set") and len(name) > 3
                else f"arg_{index + 1}"
            )
        parameters.append(JavaParameter(snake_case(parameter_name), type_name, varargs))
        slot += 2 if type_name in {"long", "double"} else 1
    return JavaCallable(name, tuple(parameters), return_type, is_static)


def inspect_class(classes_path: Path, class_name: str) -> JavaType:
    output = subprocess.run(
        ["javap", "-classpath", str(classes_path), "-public", "-l", class_name],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    is_enum = " extends java.lang.Enum<" in output
    enum_constants: list[str] = []
    callable_blocks: list[tuple[str, dict[int, str]]] = []
    current_declaration: str | None = None
    current_local_names: dict[int, str] = {}
    in_local_variables = False

    def finish_callable() -> None:
        nonlocal current_declaration, current_local_names
        if current_declaration is not None:
            callable_blocks.append((current_declaration, current_local_names))
        current_declaration = None
        current_local_names = {}

    for raw_line in output.splitlines():
        line = raw_line.strip()
        if line.startswith("public ") and line.endswith(";"):
            finish_callable()
            in_local_variables = False
            if "(" in line:
                current_declaration = line
            elif is_enum:
                match = re.search(
                    rf"public static final {re.escape(class_name)} ([A-Za-z_$][\w$]*);$",
                    line,
                )
                if match:
                    enum_constants.append(match.group(1))
            continue
        if line == "LocalVariableTable:":
            in_local_variables = current_declaration is not None
            continue
        if in_local_variables:
            match = re.match(r"(\d+)\s+\d+\s+(\d+)\s+(\S+)\s+\S+", line)
            if match:
                if int(match.group(1)) == 0 and not match.group(3).startswith("$"):
                    current_local_names[int(match.group(2))] = match.group(3)
            elif line and not line.startswith("Start "):
                in_local_variables = False
    finish_callable()

    constructors: dict[tuple[str, tuple[str, ...], str | None, bool], JavaCallable] = {}
    methods: dict[tuple[str, tuple[str, ...], str | None, bool], JavaCallable] = {}
    for declaration, local_names in callable_blocks:
        callable_ = parse_callable(declaration, class_name, local_names)
        if callable_ is None:
            continue
        destination = constructors if callable_.return_type is None else methods
        destination[callable_.signature_key] = callable_
    return JavaType(
        class_name,
        tuple(constructors.values()),
        tuple(methods.values()),
        tuple(enum_constants),
    )


def strip_generic_prefix(type_name: str) -> str:
    if type_name.startswith("? extends "):
        return type_name.removeprefix("? extends ")
    if type_name.startswith("? super "):
        return type_name.removeprefix("? super ")
    return type_name


def python_type(type_name: str, owner: JavaType, known_types: set[str]) -> str:
    type_name = strip_generic_prefix(type_name.strip())
    if type_name == owner.name:
        return "Self"
    primitives = {
        "boolean": "bool",
        "byte": "int",
        "short": "int",
        "int": "int",
        "long": "int",
        "float": "float",
        "double": "float",
        "char": "str",
        "void": "None",
        "java.lang.Boolean": "bool",
        "java.lang.Byte": "int",
        "java.lang.Short": "int",
        "java.lang.Integer": "int",
        "java.lang.Long": "int",
        "java.lang.Float": "float",
        "java.lang.Double": "float",
        "java.lang.Character": "str",
        "java.lang.String": "str",
        "java.lang.CharSequence": "str",
        "java.lang.Number": "int | float",
        "java.lang.Object": "object",
        "java.util.Collection": "Sequence[object]",
        "org.luckypray.dexkit.query.base.IAnnotationEncodeValue": "DexKitBinding",
        "org.luckypray.dexkit.query.base.INumberEncodeValue": "DexKitBinding",
        "?": "object",
    }
    if type_name in primitives:
        return primitives[type_name]
    if type_name == "byte[]":
        return "bytes"
    if type_name.endswith("[]"):
        return f"Sequence[{python_type(type_name[:-2], owner, known_types)}]"
    generic_match = re.fullmatch(r"([^<]+)<(.+)>", type_name)
    if generic_match:
        outer = generic_match.group(1)
        arguments = split_top_level(generic_match.group(2))
        if outer in {
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
        }:
            item_type = python_type(arguments[0], owner, known_types)
            return f"Sequence[{item_type}]"
        if outer == "java.util.Map" and len(arguments) == 2:
            key_type = python_type(arguments[0], owner, known_types)
            value_type = python_type(arguments[1], owner, known_types)
            return f"Mapping[{key_type}, {value_type}]"
        if outer == "java.lang.Class":
            return "builtins.type[Any]"
        type_name = outer
    if type_name in known_types:
        return type_name.rsplit(".", 1)[-1]
    if type_name.startswith("java.lang.reflect.") or type_name.startswith("kotlin."):
        return "object"
    return "object"


def group_methods(item: JavaType) -> list[tuple[str, list[JavaCallable]]]:
    grouped: dict[str, list[JavaCallable]] = {}
    for method in item.methods:
        grouped.setdefault(method.name, []).append(method)
    return list(grouped.items())


def render_parameter_list(
    callable_: JavaCallable,
    owner: JavaType,
    known_types: set[str],
    receiver: str,
) -> str:
    rendered = [receiver]
    for parameter in callable_.parameters:
        annotation = python_type(parameter.type_name, owner, known_types)
        if parameter.varargs:
            rendered.append(f"*{parameter.name}: {annotation}")
        else:
            rendered.append(f"{parameter.name}: {annotation}")
    return ", ".join(rendered)


def option_types(item: JavaType, known_types: set[str]) -> dict[str, set[str]]:
    options: dict[str, set[str]] = {}
    for method in item.methods:
        if (
            method.is_static
            or method.return_type != item.name
            or len(method.parameters) != 1
            or method.parameters[0].varargs
        ):
            continue
        annotation = python_type(method.parameters[0].type_name, item, known_types)
        annotation = re.sub(r"\bSelf\b", item.simple_name, annotation)
        options.setdefault(snake_case(method.name), set()).add(annotation)
    for name, annotation in PYTHONIC_OPTION_TYPES.get(item.name, {}).items():
        options[name] = {annotation}
    return options


def render_runtime(types: list[JavaType]) -> str:
    exported = [item.simple_name for item in types]
    lines = [
        "from __future__ import annotations",
        "",
        "from collections.abc import Iterable",
        "from enum import Enum",
        "from typing import Any, ClassVar, Self",
        "",
        "from java import jclass  # ty: ignore[unresolved-import]",
        "",
        "_BINDINGS: dict[str, type[DexKitBinding]] = {}",
        "_ENUM_JAVA_NAMES: dict[type[_DexKitEnum], str] = {}",
        "_JAVA_ENUMS: dict[str, Any] = {}",
        "",
        "",
        "def _unwrap(value: object) -> object:",
        "    if isinstance(value, DexKitBinding):",
        "        return value._delegate",
        "    if isinstance(value, _DexKitEnum):",
        "        return value._to_java()",
        "    if isinstance(value, (list, tuple, set)):",
        "        return [_unwrap(item) for item in value]",
        "    if isinstance(value, dict):",
        "        return {_unwrap(key): _unwrap(item) for key, item in value.items()}",
        "    return value",
        "",
        "",
        "def _wrap(value: object, current: DexKitBinding | None = None) -> object:",
        "    if current is not None and value is current._delegate:",
        "        return current",
        "    if isinstance(value, (list, tuple, set)):",
        "        return [_wrap(item) for item in value]",
        "    try:",
        "        java_name = str(value.getClass().getName())  # ty: ignore[unresolved-attribute]",
        "    except (AttributeError, TypeError):",
        "        return value",
        "    binding = _BINDINGS.get(java_name)",
        "    if binding is not None:",
        "        return binding._from_java(value)",
        "    enum_type = _JAVA_ENUMS.get(java_name)",
        "    if enum_type is not None:",
        "        return enum_type(str(value.name()))  # ty: ignore[unresolved-attribute]",
        "    if java_name.startswith(('java.util.ArrayList', 'java.util.Arrays$ArrayList', 'java.util.Collections$')):",
        "        return [_wrap(item) for item in value]  # ty: ignore[not-iterable]",
        "    return value",
        "",
        "",
        "class _DexKitEnum:",
        "    name: str",
        "",
        "    def _to_java(self) -> object:",
        "        java_name = _ENUM_JAVA_NAMES[type(self)]",
        "        return jclass(java_name).valueOf(self.name)",
        "",
        "",
        "class DexKitBinding:",
        "    _java_name: ClassVar[str]",
        "    _java_class: ClassVar[Any | None] = None",
        "    _iterable_adders: ClassVar[dict[str, str]] = {}",
        "",
        "    def __init__(self, *args: object, **kwargs: object) -> None:",
        "        self._delegate: object = self._get_java_class()(",
        "            *[_unwrap(argument) for argument in args]",
        "        )",
        "        for name, value in kwargs.items():",
        "            adder_name = self._iterable_adders.get(name)",
        "            if adder_name is not None and isinstance(value, Iterable) and not isinstance(value, (str, bytes)):",
        "                adder = getattr(self, adder_name)",
        "                for item in value:",
        "                    adder(item)",
        "                continue",
        "            setter = getattr(self, name, None)",
        "            if setter is None or not callable(setter):",
        '                raise TypeError(f"{type(self).__name__} has no matcher option {name!r}")',
        "            setter(value)",
        "",
        "    @classmethod",
        "    def _get_java_class(cls) -> Any:",
        "        if cls._java_class is None:",
        "            cls._java_class = jclass(cls._java_name)",
        "        return cls._java_class",
        "",
        "    @classmethod",
        "    def _from_java(cls, delegate: object) -> Self:",
        "        instance = cls.__new__(cls)",
        "        instance._delegate = delegate",
        "        return instance",
        "",
        "    def _call(self, java_name: str, *args: object) -> object:",
        "        result = getattr(self._delegate, java_name)(",
        "            *[_unwrap(argument) for argument in args]",
        "        )",
        "        return _wrap(result, self)",
        "",
        "    @classmethod",
        "    def _call_static(cls, java_name: str, *args: object) -> object:",
        "        result = getattr(cls._get_java_class(), java_name)(",
        "            *[_unwrap(argument) for argument in args]",
        "        )",
        "        return _wrap(result)",
        "",
        "    def _to_java(self) -> object:",
        "        return self._delegate",
        "",
        "    def __getattr__(self, name: str) -> object:",
        "        attribute = getattr(self._delegate, name)",
        "        if callable(attribute):",
        "            return lambda *args: _wrap(",
        "                attribute(*[_unwrap(argument) for argument in args]), self",
        "            )",
        "        return _wrap(attribute)",
        "",
    ]
    for item in types:
        name = item.simple_name
        if item.is_enum:
            lines.extend([f"class {name}(_DexKitEnum, Enum):"])
            lines.extend(
                [f"    {constant} = {constant!r}" for constant in item.enum_constants]
            )
            lines.extend(
                [
                    "",
                    f"_ENUM_JAVA_NAMES[{name}] = {item.name!r}",
                    f"_JAVA_ENUMS[{item.name!r}] = {name}",
                    "",
                ]
            )
            continue
        lines.extend(
            [f"class {name}(DexKitBinding):", f"    _java_name = {item.name!r}"]
        )
        adders = PYTHONIC_ITERABLE_ADDERS.get(item.name)
        if adders:
            lines.append(f"    _iterable_adders = {adders!r}")
        method_groups = group_methods(item)
        if not method_groups:
            lines.append("    pass")
        canonical_aliases: list[tuple[str, str]] = []
        for java_name, overloads in method_groups:
            static_modes = {method.is_static for method in overloads}
            if len(static_modes) != 1:
                raise ValueError(
                    f"mixed static and instance overloads: {item.name}.{java_name}"
                )
            python_name = snake_case(java_name)
            is_static = overloads[0].is_static
            if is_static:
                lines.append("    @classmethod")
            receiver = "cls" if is_static else "self"
            helper = "_call_static" if is_static else "_call"
            lines.extend(
                [
                    f"    def {python_name}({receiver}, *args: object) -> object:",
                    f"        return {receiver}.{helper}({java_name!r}, *args)",
                    "",
                ]
            )
            if (
                java_name != python_name
                and java_name.isidentifier()
                and not keyword.iskeyword(java_name)
            ):
                canonical_aliases.append((java_name, python_name))
        lines.extend(
            [
                f"    {java_name} = {python_name}"
                for java_name, python_name in canonical_aliases
            ]
        )
        lines.extend(["", f"_BINDINGS[{item.name!r}] = {name}", ""])
    lines.append(f"__all__ = {exported + ['DexKitBinding']!r}")
    lines.append("")
    return "\n".join(lines)


def render_stub(types: list[JavaType]) -> str:
    known_types = {item.name for item in types}
    lines = [
        "from __future__ import annotations",
        "",
        "import builtins",
        "",
        "from enum import Enum",
        "from typing import Any, ClassVar, Mapping, Sequence, Self, TypedDict, Unpack, overload",
        "",
        "class DexKitBinding:",
        "    def _to_java(self) -> object: ...",
        "",
    ]
    for item in types:
        if item.is_enum:
            lines.append(f"class {item.simple_name}(Enum):")
            lines.extend(
                [
                    f"    {constant}: ClassVar[{item.simple_name}]"
                    for constant in item.enum_constants
                ]
            )
            lines.append("")
            continue
        options = option_types(item, known_types)
        if options:
            lines.append(f"class _{item.simple_name}Options(TypedDict, total=False):")
            for option_name, annotations in sorted(options.items()):
                lines.append(f"    {option_name}: {' | '.join(sorted(annotations))}")
            lines.append("")
        lines.append(f"class {item.simple_name}(DexKitBinding):")
        constructors = list(item.constructors)
        constructor_is_overloaded = max(len(constructors), 1) + int(bool(options)) > 1
        if constructors:
            for constructor in constructors:
                if constructor_is_overloaded:
                    lines.append("    @overload")
                parameters = render_parameter_list(
                    constructor, item, known_types, "self"
                )
                lines.append(f"    def __init__({parameters}) -> None: ...")
        else:
            if constructor_is_overloaded:
                lines.append("    @overload")
            lines.append("    def __init__(self) -> None: ...")
        if options:
            if constructor_is_overloaded:
                lines.append("    @overload")
            lines.append(
                f"    def __init__(self, **kwargs: Unpack[_{item.simple_name}Options]) -> None: ..."
            )
        method_groups = group_methods(item)
        if not method_groups:
            lines.append("    pass")
        for java_name, overloads in method_groups:
            python_name = snake_case(java_name)
            for emitted_name in dict.fromkeys([python_name, java_name]):
                if not emitted_name.isidentifier() or keyword.iskeyword(emitted_name):
                    continue
                for overload in overloads:
                    if len(overloads) > 1:
                        lines.append("    @overload")
                    if overload.is_static:
                        lines.append("    @classmethod")
                    receiver = "cls" if overload.is_static else "self"
                    parameters = render_parameter_list(
                        overload, item, known_types, receiver
                    )
                    assert overload.return_type is not None
                    return_type = python_type(overload.return_type, item, known_types)
                    lines.append(
                        f"    def {emitted_name}({parameters}) -> {return_type}: ..."
                    )
        lines.append("")
    return "\n".join(lines)


def render_stub_init(types: list[JavaType]) -> str:
    names = [item.simple_name for item in types] + ["DexKitBinding"]
    imports = ",\n    ".join(names)
    return "\n".join(
        [
            "from ._generated import (",
            f"    {imports},",
            ")",
            "",
            "def contains(value: str, *, ignore_case: bool = False) -> StringMatcher: ...",
            "def starts_with(value: str, *, ignore_case: bool = False) -> StringMatcher: ...",
            "def ends_with(value: str, *, ignore_case: bool = False) -> StringMatcher: ...",
            "def regex(value: str, *, ignore_case: bool = False) -> StringMatcher: ...",
            "def eq(value: str, *, ignore_case: bool = False) -> StringMatcher: ...",
            "",
            f"__all__ = {names + ['contains', 'starts_with', 'ends_with', 'regex', 'eq']!r}",
            "",
        ]
    )


def write_if_changed(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.read_text(encoding="utf-8") != content:
        path.write_text(content, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--python-out", type=Path, required=True)
    parser.add_argument("--stub-out", type=Path, required=True)
    args = parser.parse_args()

    with zipfile.ZipFile(args.aar) as aar:
        classes_jar = aar.read("classes.jar")
    with tempfile.TemporaryDirectory(prefix="wekit-dexkit-codegen-") as temporary:
        classes_path = Path(temporary, "classes.jar")
        classes_path.write_bytes(classes_jar)
        types = [
            inspect_class(classes_path, name) for name in discover_classes(classes_jar)
        ]

    write_if_changed(
        args.python_out / "wekit/dexkit/_generated.py", render_runtime(types)
    )
    write_if_changed(args.stub_out / "wekit/dexkit/_generated.pyi", render_stub(types))
    write_if_changed(
        args.stub_out / "wekit/dexkit/__init__.pyi", render_stub_init(types)
    )


if __name__ == "__main__":
    main()
