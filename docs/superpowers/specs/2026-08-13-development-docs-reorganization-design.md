# WeKit Development Documentation Reorganization Design

**Date:** 2026-08-13

## Goal

Move the top-level development guide into the existing `docs/development/` directory, improve its
organization without fragmenting the build instructions, and add a contributor-facing i18n guide.

## Information Architecture

- `docs/development/README.md` becomes the development landing page.
  - It retains the current environment, `./x`, APK, and Zygisk instructions.
  - Mechanical section numbering is removed.
  - Terminology and punctuation are made consistent.
  - A short topic navigation section links to the DexKit and i18n guides.
- `docs/development/linux-dex-test.md` remains the focused DexKit resolver testing guide.
- `docs/development/i18n.md` documents the repository's localization workflow.

The former `docs/development.md` path is removed. `docs/README.md` and `docs/SUMMARY.md` are updated
to point to the new landing page, and the summary lists the DexKit and i18n guides as children of
the development guide.

## i18n Guide Scope

The i18n guide covers:

- the English source catalog and Simplified/Traditional Chinese target catalogs;
- system-following and explicit locale selection, including English fallback;
- the process boundary between the standalone module app and injected WeChat host process;
- `LocaleResourceMode.ModuleApp` and `LocaleResourceMode.InjectedHost` usage;
- Compose localization through `WeKitLocaleProvider` and separate window compositions through
  `WeKitWindowDialog`;
- non-Compose localization through a fresh localized context at the point of display;
- the rule against caching resolved localized `String` values;
- resource naming, placeholders, plurals, and non-translatable technical strings;
- catalog validation, build validation, and real-device checks.

The guide describes current implementation APIs and does not modify runtime behavior or replace
the detailed historical i18n design and migration plan.

## Validation

- Check all changed Markdown links resolve relative to their containing files.
- Run `./x i18n-check`.
- Run `git diff --check`.
- Review the final diff to confirm only documentation files changed.
