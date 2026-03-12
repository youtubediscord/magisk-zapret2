## Runtime Config Refactor Plan

- [in progress] Audit: inventory all runtime config sources, load paths, defaults, and write points across shell scripts, module files, and Android app code.
- [in progress] Refactor `PresetsFragment.kt` and `ConfigEditorFragment.kt` to consume `[core]` active-mode values via `RuntimeConfigStore` and remove duplicate runtime.ini parsing/writing.
- [ ] Shell/runtime foundation: define one canonical config file/schema, centralize read/write helpers, and update startup/runtime scripts to consume only that source.
- [ ] App migration: switch app config reads/writes and UI state to the canonical runtime config layer with compatibility handling during rollout.
- [ ] Legacy cleanup: remove duplicate config paths, stale fallbacks, and obsolete sync logic after migration is stable.
- [ ] QA: verify install/upgrade behavior, boot persistence, start/stop flows, strategy changes, and regression coverage for shell/app integration.
