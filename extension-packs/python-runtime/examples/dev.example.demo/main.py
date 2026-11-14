from __future__ import annotations

from typing import TYPE_CHECKING

from java import dynamic_proxy  # ty: ignore[unresolved-import]

from dev.ujhhgtg.wekit.features.api.core import WeDatabaseListenerApi  # ty: ignore[unresolved-import]
from wekit import dex
from wekit.runtime import PluginContext

if TYPE_CHECKING:
    from wekit.runtime import HookParameter


def setup(ctx: PluginContext) -> None:
    ctx.log.info("Demo loading")
    target = ctx.dex.method(
        dex.method_spec(
            return_type=dex.class_spec(class_name=dex.name("void")),
            using_strings=dex.using_strings("some stable string"),
        )
    )

    def on_call(parameter: HookParameter) -> None:
        ctx.log.info(f"called with {parameter.args}")

    ctx.hooks.before(target, on_call)

    class Listener(dynamic_proxy(WeDatabaseListenerApi.IInsertListener)):
        def onInsert(self, table: str, values: object) -> None:
            ctx.log.info(f"insert: {table}")

    listener = ctx.track_reference(Listener())
    WeDatabaseListenerApi.INSTANCE.addListener(listener)
    ctx.defer(lambda: WeDatabaseListenerApi.INSTANCE.removeListener(listener))
    ctx.log.info("Demo loaded")
