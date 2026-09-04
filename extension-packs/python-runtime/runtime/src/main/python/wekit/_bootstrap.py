from __future__ import annotations

import asyncio
import importlib
import importlib.machinery
import inspect
import json
import re
import sys
import threading
import traceback
import types
from collections.abc import Awaitable, Callable, Sequence
from concurrent.futures import Future
from contextvars import ContextVar
from io import TextIOBase
from pathlib import Path
from typing import Any, TypeVar, cast

from java import jclass  # ty: ignore[unresolved-import]

from ._types import (
    DexHost,
    HookHost,
    HookParameter,
    HookToken,
    Logger,
    PluginHost,
    PluginRequest,
    RuntimeConfig,
    ResolvedClass,
    ResolvedField,
    ResolvedMember,
    TaskHandle,
    TaskHost,
)
from .dexkit import ClassMatcher, DexKitBinding, FieldMatcher, MethodMatcher

_config: RuntimeConfig | None = None
_instances: dict[str, tuple[str, PluginContext, _Scope]] = {}
_lock: threading.RLock = threading.RLock()
_loop: asyncio.AbstractEventLoop | None = None
_loop_thread: threading.Thread | None = None
_current_context: ContextVar[PluginContext | None] = ContextVar(
    "wekit_plugin_context", default=None
)
_T = TypeVar("_T")


class _Scope:
    def __init__(self) -> None:
        self._lock: threading.RLock = threading.RLock()
        self._closed: bool = False
        self._cleanup: list[Callable[[], object]] = []
        self._references: list[object] = []

    def defer(self, action: Callable[[], _T]) -> Callable[[], _T]:
        with self._lock:
            if self._closed:
                raise RuntimeError("plugin scope is closed")
            self._cleanup.append(action)
        return action

    def close(self) -> list[BaseException]:
        with self._lock:
            if self._closed:
                return []
            self._closed = True
            cleanup = list(reversed(self._cleanup))
            self._cleanup.clear()
            self._references.clear()
        errors: list[BaseException] = []
        for action in cleanup:
            try:
                action()
            except BaseException as error:
                errors.append(error)
        return errors

    def track_reference(self, reference: _T) -> _T:
        with self._lock:
            if self._closed:
                raise RuntimeError("plugin scope is closed")
            self._references.append(reference)
        return reference


class PluginContext:
    def __init__(self, request: PluginRequest, host: PluginHost, scope: _Scope) -> None:
        assert _config is not None
        self.id: str = request.getId()
        self.root: Path = Path(request.getRoot().getAbsolutePath())
        self.data_dir: Path = Path(request.getDataDirectory().getAbsolutePath())
        self.cache_dir: Path = Path(request.getCacheDirectory().getAbsolutePath())
        manifest_file = self.root / "plugin.json"
        if manifest_file.stat().st_size > _config.getMaxManifestBytes():
            raise ValueError("plugin manifest exceeds the configured size limit")
        self.manifest: dict[str, Any] = json.loads(
            manifest_file.read_text(encoding="utf-8")
        )
        self.host_context: Any = _config.getApplication()
        self.log: _Logger = _Logger(host.logger(self.id))
        self.hooks: _Hooks = _Hooks(host.hooks(self.id), self)
        self.dex: _Dex = _Dex(host.dex(self.id))
        self.tasks: _Tasks = _Tasks(host.tasks(self.id), self)
        self.jvm: _Jvm = _Jvm(_config.getLookupClassLoader())
        self._scope: _Scope = scope

    def defer(self, action: Callable[[], _T]) -> Callable[[], _T]:
        return self._scope.defer(action)

    def track_reference(self, reference: _T) -> _T:
        return self._scope.track_reference(reference)

    def defer_async(
        self, action: Callable[[], Awaitable[object]]
    ) -> Callable[[], object]:
        def cleanup() -> object:
            assert _loop is not None and _config is not None
            result = action()
            if inspect.isawaitable(result):

                async def await_result() -> object:
                    return await result

                future = asyncio.run_coroutine_threadsafe(await_result(), _loop)
                try:
                    future.result(timeout=_config.getTaskDrainTimeoutMs() / 1000)
                except TimeoutError:
                    future.cancel()
                    raise
            return None

        return self._scope.defer(cleanup)

    def spawn(self, task: Awaitable[object] | Callable[[], object]) -> TaskHandle:
        return self.tasks.spawn(task)


class _AsyncHandle:
    def __init__(self, future: Future[object], completed: threading.Event) -> None:
        self._future: Future[object] = future
        self._completed: threading.Event = completed

    @classmethod
    def submit(
        cls,
        awaitable: Awaitable[object],
        loop: asyncio.AbstractEventLoop,
        context: PluginContext,
    ) -> _AsyncHandle:
        completed = threading.Event()

        async def owned() -> object:
            token = _current_context.set(context)
            try:
                return await awaitable
            finally:
                _current_context.reset(token)
                completed.set()

        return cls(asyncio.run_coroutine_threadsafe(owned(), loop), completed)

    def cancel(self) -> None:
        self._future.cancel()

    def isDone(self) -> bool:
        return self._future.done()

    def awaitResult(self) -> object:
        return self._future.result()

    def close(self, timeout_ms: int) -> None:
        self._future.cancel()
        if not self._completed.wait(timeout_ms / 1000):
            raise TimeoutError(
                "Python coroutine ignored cancellation and leaked past the drain window"
            )


class _Logger:
    def __init__(self, delegate: Logger) -> None:
        self._delegate: Logger = delegate

    def debug(self, message: object, *arguments: object) -> None:
        self._delegate.debug(str(message), *arguments)

    def info(self, message: object, *arguments: object) -> None:
        self._delegate.info(str(message), *arguments)

    def warning(self, message: object, *arguments: object) -> None:
        self._delegate.warning(str(message), *arguments)

    def error(self, message: object, *arguments: object) -> None:
        self._delegate.error(str(message), *arguments)

    def exception(
        self, message: object = "", error: BaseException | None = None
    ) -> None:
        formatted = (
            "".join(traceback.format_exception(error))
            if error is not None
            else traceback.format_exc()
        )
        self._delegate.error(f"{message}\n{formatted}".strip())


class _Tasks:
    def __init__(self, delegate: TaskHost, context: PluginContext) -> None:
        self._delegate: TaskHost = delegate
        self._context: PluginContext = context

    def main(self, callback: Callable[[], object]) -> TaskHandle:
        return self._delegate.main(lambda: _run_context(self._context, callback))

    async def main_async(self, callback: Callable[[], object]) -> object:
        handle = self._delegate.mainAsync(lambda: _run_context(self._context, callback))
        while not handle.isDone():
            await asyncio.sleep(0.01)
        return handle.awaitResult()

    def spawn(self, task: Awaitable[object] | Callable[[], object]) -> TaskHandle:
        assert _loop is not None and _config is not None
        config = _config
        if inspect.isawaitable(task):
            handle = _AsyncHandle.submit(task, _loop, self._context)
            try:
                self._context._scope.defer(
                    lambda: handle.close(config.getTaskDrainTimeoutMs())
                )
            except BaseException:
                handle.cancel()
                raise
            return handle
        callback = cast(Callable[[], object], task)
        return self._delegate.spawn(lambda: _run_context(self._context, callback))


class _Hooks:
    def __init__(self, delegate: HookHost, context: PluginContext) -> None:
        self._delegate: HookHost = delegate
        self._context: PluginContext = context

    def _wrap(
        self, callback: Callable[[HookParameter], _T]
    ) -> Callable[[HookParameter], _T]:
        def wrapped(parameter: HookParameter) -> _T:
            result = _run_context(self._context, callback, parameter)
            if inspect.isawaitable(result):
                if inspect.iscoroutine(result):
                    result.close()
                raise TypeError("synchronous hook callbacks cannot return an awaitable")
            return result

        return wrapped

    def before(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken:
        return self._delegate.before(member, self._wrap(callback), priority)

    def after(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken:
        return self._delegate.after(member, self._wrap(callback), priority)

    def replace(
        self,
        member: object,
        callback: Callable[[HookParameter], object],
        priority: int = 50,
    ) -> HookToken:
        return self._delegate.replace(member, self._wrap(callback), priority)

    def invoke_original(self, parameter: HookParameter) -> object:
        return self._delegate.invokeOriginal(parameter)

    def unhook(self, token: HookToken) -> None:
        self._delegate.unhook(token)


class _Dex:
    def __init__(self, delegate: DexHost) -> None:
        self._delegate: DexHost = delegate

    def class_(
        self,
        matcher: ClassMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> ResolvedClass:
        return self._delegate.findClass(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def classes(
        self,
        matcher: ClassMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> Sequence[ResolvedClass]:
        return self._delegate.findClasses(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def method(
        self,
        matcher: MethodMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> ResolvedMember:
        return self._delegate.findMethod(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def methods(
        self,
        matcher: MethodMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> Sequence[ResolvedMember]:
        return self._delegate.findMethods(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def constructor(
        self,
        matcher: MethodMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> ResolvedMember:
        return self._delegate.findConstructor(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def constructors(
        self,
        matcher: MethodMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> Sequence[ResolvedMember]:
        return self._delegate.findConstructors(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def field(
        self,
        matcher: FieldMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> ResolvedField:
        return self._delegate.findField(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    def fields(
        self,
        matcher: FieldMatcher,
        *,
        search_packages: Sequence[str] = (),
        exclude_packages: Sequence[str] = (),
        ignore_packages_case: bool = False,
    ) -> Sequence[ResolvedField]:
        return self._delegate.findFields(
            self._java(matcher),
            list(search_packages),
            list(exclude_packages),
            ignore_packages_case,
        )

    @staticmethod
    def _java(matcher: DexKitBinding) -> object:
        if not isinstance(matcher, DexKitBinding):
            raise TypeError("ctx.dex requires a generated DexKit binding")
        return matcher._to_java()


class _Jvm:
    def __init__(self, loader: Any) -> None:
        self.loader: Any = loader

    def load_class(self, name: str) -> object:
        return self.loader.loadClass(name)

    def proxy(self, name: str) -> type[Any]:
        return jclass(name)


class _LogStream(TextIOBase):
    def __init__(self, error: bool) -> None:
        self._error: bool = error

    def write(self, value: str) -> int:
        text = str(value).rstrip()
        context = _current_context.get()
        if text and context is not None:
            (context.log.error if self._error else context.log.info)(text)
        return len(value)

    def flush(self) -> None:
        pass


def initialize(config: RuntimeConfig) -> None:
    global _config, _loop, _loop_thread
    with _lock:
        if _config is not None:
            return
        _config = config
        _loop = asyncio.new_event_loop()
        _loop_thread = threading.Thread(
            target=_loop.run_forever, name="WeKit-Python-Asyncio", daemon=True
        )
        _loop_thread.start()
        sys.stdout = _LogStream(False)
        sys.stderr = _LogStream(True)
        if "_wekit_plugins" not in sys.modules:
            package = types.ModuleType("_wekit_plugins")
            package.__path__ = []
            sys.modules["_wekit_plugins"] = package


def activate_plugin(request: PluginRequest, host: PluginHost) -> None:
    plugin_id = request.getId()
    with _lock:
        if plugin_id in _instances:
            raise RuntimeError(f"plugin is already active: {plugin_id}")
        importlib.invalidate_caches()
        namespace = "_wekit_plugins.p_" + plugin_id.encode("utf-8").hex()
        root = Path(request.getRoot().getAbsolutePath())
        entry = request.getEntry()
        module_path = root.joinpath(*entry.split("."))
        source = module_path.with_suffix(".py")
        if not source.is_file():
            source = module_path / "__init__.py"
        if not source.is_file():
            raise ImportError(f"plugin entry does not exist: {entry}")
        assert _config is not None
        if source.stat().st_size > _config.getMaxPluginFileBytes():
            raise ImportError(
                f"plugin entry exceeds the configured size limit: {entry}"
            )

        scope = _Scope()
        try:
            context = PluginContext(request, host, scope)
            package = types.ModuleType(namespace)
            package.__package__ = namespace
            package.__path__ = [str(root)]
            package.__spec__ = importlib.machinery.ModuleSpec(
                namespace, loader=None, is_package=True
            )
            sys.modules[namespace] = package
            qualified_entry = f"{namespace}.{entry}"
            module = _run_boundary(
                context,
                host,
                "import",
                importlib.import_module,
                f".{entry}",
                namespace,
            )
            setup = getattr(module, "setup", None)
            if not callable(setup):
                raise AttributeError(f"{qualified_entry} must define setup(ctx)")
            _run_boundary(
                context,
                host,
                "setup",
                cast(Callable[[PluginContext], object], setup),
                context,
            )
            _instances[plugin_id] = (namespace, context, scope)
        except BaseException:
            scope.close()
            _remove_namespace(namespace)
            raise


def deactivate_plugin(plugin_id: str) -> None:
    with _lock:
        instance = _instances.pop(plugin_id, None)
        if instance is None:
            return
        namespace, _, scope = instance
        errors = scope.close()
        _remove_namespace(namespace)
        if errors:
            raise BaseExceptionGroup(f"cleanup failed for {plugin_id}", errors)


def reload_plugin(request: PluginRequest, host: PluginHost) -> None:
    plugin_id = request.getId()
    deactivate_plugin(plugin_id)
    importlib.invalidate_caches()
    activate_plugin(request, host)


def _remove_namespace(namespace: str) -> None:
    for name in list(sys.modules):
        if name == namespace or name.startswith(namespace + "."):
            sys.modules.pop(name, None)


def _run_context(
    context: PluginContext, callback: Callable[..., _T], *args: object
) -> _T:
    token = _current_context.set(context)
    thread = None
    previous_loader = None
    try:
        if _config is not None:
            thread = jclass("java.lang.Thread").currentThread()
            previous_loader = thread.getContextClassLoader()
            thread.setContextClassLoader(_config.getLookupClassLoader())
        return callback(*args)
    finally:
        if thread is not None:
            thread.setContextClassLoader(previous_loader)
        _current_context.reset(token)


def _run_boundary(
    context: PluginContext,
    host: PluginHost,
    phase: str,
    callback: Callable[..., _T],
    *args: object,
) -> _T:
    token = host.beginExecution(context.id, phase)
    try:
        return _run_context(context, callback, *args)
    finally:
        host.finishExecution(context.id, token)
