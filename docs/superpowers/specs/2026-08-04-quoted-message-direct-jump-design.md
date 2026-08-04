# Quoted Message Direct Jump Design

## Goal

Extend `QuotedMessageDirectJump` so users can independently control direct
jumping for quoted messages in the chat message list and in the ChatFooter
input quote preview. Both switches default to enabled, preserving the current
message-list behavior and enabling the new input-box behavior by default.

## Evidence and Entry Point

WeChat 8.0.65 through 8.0.76 routes both surfaces through
`QuoteMsgSourceClickLogic.handleItemClickEvent`.

- A message-list click supplies a non-null second argument containing the
  message-row holder.
- A ChatFooter quote click supplies `null` for that argument and passes the
  quote context as the final wrapper argument.
- The original handler calls `handleItemClickToPositionEvent` only for a
  locate/jump action; otherwise it continues into the full preview behavior.

The implementation will therefore keep one stable DexKit hook and classify the
origin from the method's existing argument contract. It will not hook the
ChatFooter anonymous listener separately, avoiding version-specific synthetic
class and captured-field names.

## Runtime Behavior

`QuotedMessageDirectJump` becomes a `ClickableFeature` while retaining its
existing master feature switch. It adds two persisted boolean preferences:

1. `messageListDirectJump`, default `true`: controls clicks on quoted messages
   rendered inside the chat message list.
2. `inputBoxDirectJump`, default `true`: controls clicks on the quoted message
   shown at the bottom of ChatFooter.

The common hook returns immediately when the relevant preference is disabled,
leaving WeChat's original full-preview behavior untouched. When enabled, it
resolves the quoted message with the existing helper and invokes
`handleItemClickToPositionEvent` with the same version-dependent signature
handling already used by the feature. For ChatFooter calls, the message-row
holder is passed as `null`, matching WeChat's own call site; for message-list
calls, the holder's existing non-String field is passed through.

The hook keeps the existing `result = null` behavior so the original full
preview handler does not run after a direct jump. No `try/catch` is added around
the hook body.

## Settings UI

`onClick` displays a Compose dialog following `HideContacts.kt` conventions:

- title: `引用消息直达`;
- two clickable `ListItem`s with trailing `Switch` widgets;
- each switch is initialized from its persisted preference;
- the switch values are written immediately when toggled, consistent with the
  reference feature's settings behavior.

The top-level feature switch remains responsible for installing/removing the
hook. The two nested switches only select which click origins are redirected.

## Testing and Verification

Add a small JVM unit-testable rule for selecting the active preference from the
click origin, covering both origins with the preference enabled and disabled.
The test must be written and observed failing before the production rule is
implemented, then pass after the minimal implementation.

Because the existing DexKit declarations and matcher bodies remain unchanged,
the expensive supported-version desktop resolver run is not required for this
behavior-only change. Verification will include the focused unit test, the
project-mandated `./x build`, and `git diff --check`.

Physical-device behavior remains unverified unless a device test is explicitly
available; a successful desktop/build validation does not prove ChatFooter UI
behavior on-device.
