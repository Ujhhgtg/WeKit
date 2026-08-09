# Final-review fix report — native quote builder selector

## Finding addressed

`sendNativeQuote` previously left the native builder's constructor selector at its
default value. Decompiled 8.0.65 and 8.0.76 `MsgRetransmitUI` sources set this
field to `4` before attaching source metadata and building the task. Their native
factory implementations branch on selector values `5`, `4`, and `2`; only the
`4` branch constructs the quote task carrying destination, content, scene, the
secondary int argument, and the source message local ID. The default branch
constructs an empty task.

The fix adds the strict `fieldQuoteBuilderConstructorId` delegate, resolves it,
sets it to `4` after the destination/content/scene setters and before source/build,
and leaves the fresh builder's separate zero-valued int at its default.

## Structural matcher rationale

The anchored `MsgRetransmitUI` quote branch writes exactly two `int` fields on the
resolved builder: the constructor selector and a payload argument. The selector is
the unique one of those writes with a native interceptor reader that:

- takes the builder as its only parameter; and
- reads that candidate as the sole `int` field from the builder.

The payload argument's one-builder-parameter quote reader also consumes the
selector and scene int, so it does not match. This relationship was confirmed from
DexKit reader metadata on 8.0.65, 8.0.76, and 8.0.77 before implementation. The
production resolver uses only DexKit metadata (`usingFields`, field descriptors,
parameter descriptors, and types), with no obfuscated names, field order,
reflection, or host-version branch.

## Verification

Focused verification on 8.0.69:

```text
run: 2026-08-09T11-14-56Z
WeMessageApi:fieldQuoteBuilderConstructorId SUCCESS Lpx0/q1;->i:I
wechat_8069.apk PASS: 588 success, 10 expected, 0 unexpected, 0 blocked, 0 incomplete
```

Final explicit supported matrix:

```text
command: ./x dex-test --apk wechat_8065.apk --apk wechat_8067.apk
         --apk wechat_8069.apk --apk wechat_8069_3020_play.apk
         --apk wechat_8074.apk --apk wechat_8076.apk
         --apk wechat_8077.apk
         --output-dir dex-test-results/native-quote-message-repetition
run: 2026-08-09T11-15-43Z
outcome: PASS
totals: 4066 success, 120 expected, 0 unexpected, 0 blocked, 0 incomplete
```

Per-APK counts:

| APK | Outcome | Success | Expected | Unexpected | Blocked | Incomplete |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 8.0.65 normal | PASS | 568 | 30 | 0 | 0 | 0 |
| 8.0.67 normal | PASS | 582 | 16 | 0 | 0 | 0 |
| 8.0.69 normal | PASS | 588 | 10 | 0 | 0 | 0 |
| 8.0.69 Google Play | PASS | 587 | 11 | 0 | 0 | 0 |
| 8.0.74 normal | PASS | 590 | 8 | 0 | 0 | 0 |
| 8.0.76 normal | PASS | 591 | 7 | 0 | 0 | 0 |
| 8.0.77 normal | PASS | 560 | 38 | 0 | 0 | 0 |

`fieldQuoteBuilderConstructorId` is `SUCCESS` in all seven reports, resolving to
the builder's selector field on each host. Every per-APK `infrastructureError` is
null. Reports are preserved at:

```text
dex-test-results/native-quote-message-repetition/2026-08-09T11-15-43Z/
```

Required build:

```text
./x build
BUILD SUCCESSFUL in 11s
```

The build regenerated and packaged both Android native ABIs and assembled standard
and legacy debug APKs. `git diff --check` is run again immediately before commit.

## Residual validation gap

Desktop Dex resolution and APK assembly do not prove hook-time behavior. Physical
device testing is still required for quote-of-text, quote-of-image, and the
local-ID fallback when the referenced message is unavailable, including ownership,
destination, fresh-envelope, and non-quote regression checks from the plan.
