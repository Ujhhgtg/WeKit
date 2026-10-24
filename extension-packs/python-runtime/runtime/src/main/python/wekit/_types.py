from __future__ import annotations

from collections.abc import Sequence
from typing import Any, Protocol


class JavaFile(Protocol):
    def getAbsolutePath(self) -> str: ...


class RuntimeConfig(Protocol):
    def getApplication(self) -> Any: ...
    def getLookupClassLoader(self) -> Any: ...
    def getTaskDrainTimeoutMs(self) -> int: ...
    def getMaxManifestBytes(self) -> int: ...
    def getMaxPluginFileBytes(self) -> int: ...


class PluginRequest(Protocol):
    def getId(self) -> str: ...
    def getRoot(self) -> JavaFile: ...
    def getEntry(self) -> str: ...
    def getDataDirectory(self) -> JavaFile: ...
    def getCacheDirectory(self) -> JavaFile: ...


class Logger(Protocol):
    def debug(self, message: str, *arguments: object) -> None: ...
    def info(self, message: str, *arguments: object) -> None: ...
    def warning(self, message: str, *arguments: object) -> None: ...
    def error(self, message: str, *arguments: object) -> None: ...
    def exception(
        self, message: str, error: BaseException, *arguments: object
    ) -> None: ...


class HookHost(Protocol):
    def before(self, member: object, callback: object, priority: int) -> HookToken: ...
    def after(self, member: object, callback: object, priority: int) -> HookToken: ...
    def replace(self, member: object, callback: object, priority: int) -> HookToken: ...
    def invokeOriginal(self, parameter: object) -> object: ...
    def unhook(self, token: HookToken) -> None: ...


class HookParameter(Protocol):
    member: object
    thisObject: object | None
    args: list[object | None]
    result: object | None
    throwable: BaseException | None


class HookToken(Protocol):
    def getId(self) -> str: ...


class ResolvedClass(Protocol):
    descriptor: str
    name: str
    modifiers: int
    host_version: str
    host_build_tag: str


class ResolvedMember(Protocol):
    descriptor: str
    name: str
    declared_class: str
    return_type: str
    param_types: list[str]
    modifiers: int
    is_constructor: bool
    host_version: str
    host_build_tag: str


class ResolvedField(Protocol):
    descriptor: str
    name: str
    declared_class: str
    type_name: str
    modifiers: int
    host_version: str
    host_build_tag: str


class DexHost(Protocol):
    def findClass(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> ResolvedClass: ...
    def findClasses(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> Sequence[ResolvedClass]: ...
    def findMethod(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> ResolvedMember: ...
    def findMethods(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> Sequence[ResolvedMember]: ...
    def findConstructor(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> ResolvedMember: ...
    def findConstructors(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> Sequence[ResolvedMember]: ...
    def findField(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> ResolvedField: ...
    def findFields(
        self,
        spec_json: str,
        search_packages: list[str],
        exclude_packages: list[str],
    ) -> Sequence[ResolvedField]: ...


class TaskHandle(Protocol):
    def cancel(self) -> None: ...
    def isDone(self) -> bool: ...
    def awaitResult(self) -> object: ...


class TaskHost(Protocol):
    def main(self, task: object) -> TaskHandle: ...
    def mainAsync(self, task: object) -> TaskHandle: ...
    def spawn(self, task: object) -> TaskHandle: ...


class PluginHost(Protocol):
    def logger(self, plugin_id: str) -> Logger: ...
    def hooks(self, plugin_id: str) -> HookHost: ...
    def dex(self, plugin_id: str) -> DexHost: ...
    def tasks(self, plugin_id: str) -> TaskHost: ...
    def beginExecution(self, plugin_id: str, phase: str) -> int: ...
    def finishExecution(self, plugin_id: str, token: int) -> None: ...
