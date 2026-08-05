# Home Side Panel Wallet Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a Material 3 Wallet card below Weather in `HomeSidePanel`, with a persisted default-mask preference, temporary per-open reveal state, wallet settings, and the existing scan/payment actions without researching WeChat wallet internals.

**Architecture:** Extend the existing `HomeSidePanelUiState`, `HomeSidePanelState`, route, and preference patterns. Keep the balance as a static placeholder (`¥ 2,480.60`), reuse `HomeSidePanelShortcut` for the two actions, and reset temporary masking from the drawer’s actual open/close lifecycle.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Kotlin coroutines/StateFlow, MMKV-backed `WePrefs`.

## Global Constraints

- Do not inspect decompiled WeChat sources or investigate wallet data acquisition.
- Preserve all existing shortcut tiles, including `扫一扫` and `收付款`.
- Render masked balance as `******`; do not show “点击显示”.
- Persist only `默认隐藏余额`; never persist temporary reveal state.
- Do not add automated tests: this change is coupled to injected WeChat host UI/runtime behavior and falls under the `AGENTS.md` manual-testing rule.
- Validate compilation with `./x build`; validate behavior manually in the supported WeChat host.

## Tasks

### 1. Add wallet model and preference

- Create `HomeSidePanelWallet.kt` with `defaultMaskEnabled`, transient `isMasked`, `toggleFromCard()`, `reset()`, and the static placeholder balance.
- Add `HIDE_WALLET_BALANCE` and `HomeSidePanelPreferences.hideWalletBalance`, defaulting to `true`.

### 2. Integrate wallet lifecycle

- Add wallet state to `HomeSidePanelUiState`.
- Add `WALLET_SETTINGS`, settings mutation, and card toggle methods.
- Reset transient display state on both real drawer-open and drawer-close transitions.
- Reuse `runShortcut(SCAN)` and `runShortcut(PAYMENTS)` for actions.

### 3. Render card and settings

- Insert the wallet card immediately below Weather.
- Use a leading Wallet icon, `当前余额`, and equal horizontal icon-before-label buttons.
- Keep the existing shortcut section unchanged.
- Add `钱包设置` with the `默认隐藏余额` switch and transient-state explanation.

### 4. Verify

- Run `./x build` and require success for standard and legacy debug variants.
- Run `git diff --check`.
- Review the wallet diff for correctness and unnecessary complexity.
- Report physical WeChat validation as not run unless it was actually performed.
