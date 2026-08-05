# Wallet Card for Home Side Panel — Design Specification

**Date:** 2026-08-05  
**Status:** Approved for implementation planning  
**Scope:** Browser prototype and implementation-ready UI/interaction design only. Wallet data acquisition and WeChat source investigation are explicitly out of scope for this phase.

## 1. Goal

Add a Wallet card to the existing `HomeSidePanel` home view. The card gives the user a compact view of the current balance and two high-frequency wallet actions, while preserving the existing shortcut tiles as a future custom-capability area.

The card should visually belong to the current side panel: Material 3 surface tokens, rounded cards, existing spacing rhythm, and the same injected UI theme. It is placed directly below the Weather card and above the existing shortcut section.

## 2. Card content and visual hierarchy

The card uses the same calm summary hierarchy approved in the browser exploration:

1. Header row: a wallet icon on the left followed by `钱包`, styled like the Weather card header.
2. Balance label: `当前余额`.
3. Balance value: a sample/static value in the browser prototype; the eventual implementation receives a current-balance value through a separate data boundary that is not designed in this phase.
4. Two equal-width horizontal action buttons below the balance:
   - `扫一扫`, with its icon to the left of the label.
   - `付款码`, with its icon to the left of the label. This action represents opening WeChat’s existing `收付款` screen.

The masked value is rendered with asterisks (`******`), not round dots. The card must not display an instructional “点击显示” hint.

The existing lower shortcut tiles, including their current `扫一扫` and `收付款` entries, remain unchanged. This wallet card does not replace, remove, reorder, or otherwise modify that section.

## 3. Privacy and interaction contract

Wallet privacy is controlled from a dedicated `钱包设置` destination entered by long-pressing the wallet card.

The settings destination contains one persisted preference: `默认隐藏余额`.

### Default masking enabled

- Each time the side panel is opened, the wallet balance starts masked as `******`.
- Tapping the card body toggles the temporary display state between masked and visible.
- The temporary state is held only for the current open side-panel session.
- Closing the side panel always resets the temporary state to masked.
- The temporary visible/hidden state is never persisted.
- Tapping either action button invokes that action and does not toggle card visibility.
- Long-pressing the card opens `钱包设置` rather than toggling visibility.

### Default masking disabled

- The balance is always visible.
- Tapping the card body does not change the display.
- Closing and reopening the panel does not introduce masking while the preference remains disabled.
- The action buttons and long-press settings behavior remain available.

The card’s gesture handling must distinguish button interaction from card-body tap and long press. A button click must not bubble into a balance toggle or settings navigation.

## 4. Browser prototype

The browser prototype uses representative static data and simulated navigation. It demonstrates:

- The card’s placement below Weather.
- Visible and masked balance states using `******` for the masked state.
- The icon-before-label title treatment.
- Horizontal icon-before-label action buttons.
- The unchanged lower shortcut tile area.
- The `钱包设置` screen and default-mask switch.
- The interaction contract for temporary reveal and reset on panel close, represented through prototype states rather than real WeChat navigation.

The prototype must not claim to retrieve a real wallet balance or open a real WeChat screen.

## 5. Implementation boundaries

The eventual Android implementation should keep wallet UI state separate from the unresolved wallet data source:

- A wallet UI model exposes the balance display state, default-mask preference, and current-session temporary visibility state.
- A preference boundary persists only the default-mask setting.
- A navigation/action boundary represents `扫一扫`, `付款码`, and `钱包设置` callbacks.
- The side-panel session resets temporary visibility as part of its close/open lifecycle.

No WeChat decompiled-source research, wallet database lookup, wallet API resolution, or host hook for wallet navigation is part of this design approval. Those decisions require a later, explicitly scoped investigation.

## 6. Acceptance criteria for the prototype

- A reviewer can identify the Wallet card below Weather without confusion with the existing shortcut section.
- The title has a leading wallet icon, and both wallet actions have icons to the left of their labels.
- The masked balance visibly uses asterisks.
- No “点击显示” explanatory text appears on the card.
- The prototype shows both visible and masked states.
- The prototype shows `钱包设置` with the `默认隐藏余额` control and explains the temporary, non-persisted reveal behavior.
- The existing shortcut tiles remain represented and unchanged.
- No real wallet data or WeChat internals are required to view or evaluate the prototype.
