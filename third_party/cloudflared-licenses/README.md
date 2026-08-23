# cloudflared bridge third-party notices

This directory contains the license and NOTICE files for the Go packages linked into
`libwekit_cloudflared.so`. It was generated from the bridge package with:

```bash
go run github.com/google/go-licenses@v1.6.0 save . --save_path <this-directory>
```

`go-licenses` reports the local WeKit bridge itself as unknown because that package does not carry
a standalone license file. That expected local-package diagnostic does not affect the copied
third-party files. WeKit's own license remains at the repository root.

Package paths are the import identities recorded by Go. For the two replacements required by the
pinned cloudflared module, the copied text comes from the replacement source:

- `github.com/quic-go/quic-go` uses Cloudflare's pinned
  `github.com/chungthuang/quic-go` revision `43229ad201fd`.
- `github.com/urfave/cli/v2` uses Cloudflare's pinned
  `github.com/ipostelnik/cli/v2` revision `b6ea8234fe3d`.

cloudflared itself is Apache-2.0. Its pinned checkout has `LICENSE` but no upstream `NOTICE` file.
Dependency NOTICE files, where supplied by their upstream packages, are retained beneath their
package paths here.
