# Home Side Panel Wallet Card — Real Balance Design

## Goal

Correct the wallet card’s action styling and title hierarchy, and replace its prototype balance with the real balance already known by WeChat.

## Scope

- Give the `扫一扫` outlined button an explicit primary-colored border.
- Remove the standalone `钱包` title.
- Promote `当前余额` to the card title and keep the wallet icon immediately to its left.
- Replace the sample balance with WeChat’s cached numeric wallet balance.
- Read the balance when the panel loads or opens and observe later authoritative host updates.
- Preserve the existing temporary masking preference and wallet shortcuts.

The integration must not initiate wallet network requests or scrape rendered wallet text.

## UI Design

The card retains its existing shape, container color, spacing, amount typography, and two-button action row.

The first row contains the existing wallet icon followed by `当前余额`, styled as the single card title. The separate `钱包` text and the old secondary `当前余额` label are removed. The amount appears directly beneath the title row.

The scanner action remains an `OutlinedButton`, but its content and border both use `MaterialTheme.colorScheme.primary`. This avoids the generic gray `outline` token while retaining the visual distinction from the filled `付款码` button.

## Balance Model and Formatting

The wallet UI state stores `balanceFen: Long?` rather than a formatted sample string.

- A non-null value, including `0`, is a confirmed real balance in fen.
- A null value means WeChat has not supplied an available balance.
- Confirmed values are formatted as yuan with exactly two decimal places and the existing `¥ ` prefix, using grouping separators where applicable.
- A missing value is displayed as `¥ --`.
- Privacy masking replaces either the confirmed amount or unavailable placeholder with the existing mask.

The implementation must never treat a numeric zero as unavailable.

## Host Data Source

Add a focused wallet-balance source for the home side panel. Its responsibilities are:

1. Resolve the host’s balance cache access through stable DexKit evidence associated with `USERINFO_NEW_BALANCE_LONG_SYNC`, not hard-coded obfuscated names.
2. Read the current cached `long` balance in fen during panel preload/open.
3. Observe the authoritative host cache-write path and publish newly confirmed fen values to active consumers.
4. Keep rendered UI strings out of the data path so `ModifyWalletBalanceDisplay` cannot contaminate the real value.

The source consumes WeChat’s existing state only. It does not dispatch a balance-query scene or otherwise trigger wallet traffic.

The source exposes current balance updates to `HomeSidePanelState`, which copies them into `HomeSidePanelWalletUiState`. The state starts observation with the panel session and stops it when the session closes.

## Compatibility and Failure Behavior

The resolver must support WeChat 8.0.65–8.0.76, including normal and Google Play APKs where available. Resolver and inline matcher code must use `DexResolutionContext.host` and resolved DexKit metadata, never host JVM reflection during desktop resolution.

The balance declaration is expected to exist across the entire supported range, so it must not use `allowFailure`. Resolution errors remain visible and fail loudly rather than silently restoring the prototype amount.

If the host cache is present but does not yet expose a confirmed value, the card displays `¥ --`. The observer updates it when WeChat later stores a balance.

## Validation

- Add automated tests only for genuinely non-trivial WeKit-owned formatting or state logic; do not add tests for direct mappings or host UI/hook behavior.
- Run qualifying existing tests.
- Run affected `./x dex-test` cases for every supported WeChat APK variant because the implementation adds or changes Dex resolution.
- Run `./x build`.
- Run `git diff --check`.
- Manually verify on a real WeChat host that the title, scanner outline, zero balance, unavailable state, masking, panel-open refresh, and live observed update behave correctly. Desktop validation does not replace this device check.

## Non-Goals

- Initiating an active wallet balance refresh.
- Showing 零钱通, bank-card, WeCoin, or other balances.
- Reworking the card’s overall layout or navigation.
- Changing `ModifyWalletBalanceDisplay` behavior.
