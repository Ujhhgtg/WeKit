from typing import Protocol

from ._types import Logger


class _LoggingContext(Protocol):
    log: Logger


def logger(ctx: _LoggingContext) -> Logger:
    return ctx.log
