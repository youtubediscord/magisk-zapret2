## Runtime Config Refactor Plan

- [completed] Debug-overhead testing is done; refactor validation can now focus on config correctness instead of extra instrumentation.
- [completed] Shell/app `runtime.ini` migration is largely in place: `runtime.ini` is the main `[core]` source and app flows mostly use `RuntimeConfigStore`.
- [in progress] Clean up legacy drift: remove remaining compatibility reads, fallback paths, and mixed `config.sh` / `categories.ini` assumptions that still shadow `runtime.ini`.
- [ ] Decide category-state migration: either move category selection state into `runtime.ini` or keep `categories.ini` intentionally as a separate state file.
- [ ] Finish the chosen category path end-to-end in both shell and app code, then drop obsolete bridging logic.
- [ ] Re-run focused device/upgrade checks after cleanup: missing-file regeneration, preset/cmdline switching, and reboot persistence.
