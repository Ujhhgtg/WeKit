from collections.abc import Awaitable, Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, Protocol, TypeVar

from .dexkit import ClassMatcher, FieldMatcher, MethodMatcher

_T = TypeVar("_T")

class Logger(Protocol):
    def debug(self, message: object, *arguments: object) -> None: ...
    def info(self, message: object, *arguments: object) -> None: ...
    def warning(self, message: object, *arguments: object) -> None: ...
    def error(self, message: object, *arguments: object) -> None: ...
    def exception(
        self, message: object = "", error: BaseException | None = None
    ) -> None: ...

class HookParameter(Protocol):
    member: object
    this_object: object | None
    thisObject: object | None
    args: list[object | None]
    result: object | None
    throwable: BaseException | None

class HookToken(Protocol):
    def getId(self) -> str: ...

class Hooks(Protocol):
    def before(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken: ...
    def after(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken: ...
    def replace(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken: ...
    def invoke_original(self, parameter: HookParameter) -> object: ...
    def unhook(self, token: HookToken) -> None: ...

class ResolvedClass(Protocol):
    descriptor: str
    name: str
    hostVersion: str
    hostBuildTag: str

class ResolvedMember(Protocol):
    descriptor: str
    hostVersion: str
    hostBuildTag: str
    kind: object

class ResolvedField(Protocol):
    descriptor: str
    hostVersion: str
    hostBuildTag: str

class Dex(Protocol):
    def class_(self, matcher: ClassMatcher) -> ResolvedClass: ...
    def classes(self, matcher: ClassMatcher) -> Sequence[ResolvedClass]: ...
    def method(self, matcher: MethodMatcher) -> ResolvedMember: ...
    def methods(self, matcher: MethodMatcher) -> Sequence[ResolvedMember]: ...
    def constructor(self, matcher: MethodMatcher) -> ResolvedMember: ...
    def constructors(self, matcher: MethodMatcher) -> Sequence[ResolvedMember]: ...
    def field(self, matcher: FieldMatcher) -> ResolvedField: ...
    def fields(self, matcher: FieldMatcher) -> Sequence[ResolvedField]: ...

class TaskHandle(Protocol):
    def cancel(self) -> None: ...
    def isDone(self) -> bool: ...
    def awaitResult(self) -> object: ...

class Tasks(Protocol):
    def main(self, callback: Callable[[], object]) -> TaskHandle: ...
    async def main_async(self, callback: Callable[[], object]) -> object: ...
    def spawn(
        self, task: Awaitable[object] | Callable[[], object]
    ) -> TaskHandle: ...

class Jvm(Protocol):
    loader: object
    def load_class(self, name: str) -> object: ...
    def proxy(self, name: str) -> type[Any]: ...
    def pythonize(self, java_class: type[_T]) -> type[_T]: ...

class PluginContext:
    id: str
    manifest: Mapping[str, Any]
    root: Path
    data_dir: Path
    cache_dir: Path
    host_context: object
    log: Logger
    hooks: Hooks
    dex: Dex
    tasks: Tasks
    jvm: Jvm
    def defer(self, action: Callable[[], _T]) -> Callable[[], _T]: ...
    def track_reference(self, reference: _T) -> _T: ...
    def defer_async(
        self, action: Callable[[], Awaitable[object]]
    ) -> Callable[[], object]: ...
    def spawn(
        self, task: Awaitable[object] | Callable[[], object]
    ) -> TaskHandle: ...
