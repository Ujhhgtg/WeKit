from __future__ import annotations

from collections.abc import Callable
from typing import Protocol

from ._types import HookHost, HookParameter, HookToken


class _HookContext(Protocol):
    hooks: HookHost


HookCallback = Callable[[HookParameter], object]


def before(
    ctx: _HookContext, member: object, callback: HookCallback, priority: int = 50
) -> HookToken:
    return ctx.hooks.before(member, callback, priority)


def after(
    ctx: _HookContext, member: object, callback: HookCallback, priority: int = 50
) -> HookToken:
    return ctx.hooks.after(member, callback, priority)


def replace(
    ctx: _HookContext, member: object, callback: HookCallback, priority: int = 50
) -> HookToken:
    return ctx.hooks.replace(member, callback, priority)


def invoke_original(ctx: _HookContext, parameter: HookParameter) -> object:
    return ctx.hooks.invokeOriginal(parameter)


def unhook(ctx: _HookContext, token: HookToken) -> None:
    ctx.hooks.unhook(token)
