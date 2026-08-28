from collections.abc import Callable
from .runtime import HookParameter, HookToken, PluginContext

HookCallback = Callable[[HookParameter], object]

def before(
    ctx: PluginContext,
    member: object,
    callback: HookCallback,
    priority: int = 50,
) -> HookToken: ...
def after(
    ctx: PluginContext,
    member: object,
    callback: HookCallback,
    priority: int = 50,
) -> HookToken: ...
def replace(
    ctx: PluginContext,
    member: object,
    callback: HookCallback,
    priority: int = 50,
) -> HookToken: ...
def invoke_original(ctx: PluginContext, parameter: HookParameter) -> object: ...
def unhook(ctx: PluginContext, token: HookToken) -> None: ...
