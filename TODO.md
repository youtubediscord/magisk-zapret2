## Legacy Bootstrap Final Cleanup

- [completed] `runtime.ini` is the sole core runtime source; legacy bootstrap fallback should stay removed.
- [completed] `categories.ini` remains explicit category-state and should not absorb runtime config.
- [in progress] Run on-device verification for restart, reboot, reinstall, and app/module update flows.
- [ ] Confirm missing or partial `runtime.ini` self-heals cleanly on device without reviving legacy values.
- [ ] Do a final sweep for any residual legacy cleanup in shell/app paths found during verification.
