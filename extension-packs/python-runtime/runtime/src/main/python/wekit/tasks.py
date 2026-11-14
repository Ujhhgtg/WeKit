from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Protocol

from ._types import TaskHandle


class _TasksFacade(Protocol):
    def main(self, callback: Callable[[], object]) -> TaskHandle: ...
    async def main_async(self, callback: Callable[[], object]) -> object: ...


class _TaskContext(Protocol):
    tasks: _TasksFacade

    def spawn(self, task: Awaitable[object] | Callable[[], object]) -> TaskHandle: ...


def main(ctx: _TaskContext, callback: Callable[[], object]) -> TaskHandle:
    return ctx.tasks.main(callback)


async def main_async(ctx: _TaskContext, callback: Callable[[], object]) -> object:
    return await ctx.tasks.main_async(callback)


def spawn(
    ctx: _TaskContext, callback: Awaitable[object] | Callable[[], object]
) -> TaskHandle:
    return ctx.spawn(callback)
