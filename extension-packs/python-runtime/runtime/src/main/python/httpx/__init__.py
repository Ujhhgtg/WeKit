raise ImportError(
    """The `httpx` package has been replaced by `httpx2` in this runtime.
`httpx2` is a drop-in replacement, so you can migrate by simply changing imports from `httpx` to `httpx2`.

For process-wide compatibility with code which still imports `httpx`, you can *instead* run this before importing that code:

    import httpx2
    httpx2.alias_httpx()

`alias_httpx()` must be called before the first import of `httpx` or `httpcore`."""
)
