# Lifecycle error contract

Zapret2 lifecycle commands and the Android app share error schema `1`.
Human-readable diagnostics are supplementary; callers branch only on the
machine fields:

```text
Z2_ERROR_SCHEMA=1
Z2_ERROR_DOMAIN=FIREWALL
Z2_ERROR_CODE=FIREWALL_BUILD_FAILED
Z2_ERROR_STAGE=START_IPV4_BUILD_RULE
Z2_ERROR_RETRYABLE=1
```

All five fields are required exactly once. Domains, codes, stages, and their
allowed combinations are closed sets validated independently by the shell
adapter and APK. `NONE/NONE/NONE/0` is the only no-error record.

`zapret-status.sh --machine` remains the immutable version-1 status protocol
for older APKs. New clients request `--machine-v2`, which adds
`Z2_PROTOCOL=2` and the five error fields before the terminal
`Z2_COMPLETE=1` sentinel. A client may fall back to version 1 only when the
module explicitly rejects the version-2 argument without emitting machine
fields.

The module persists the last lifecycle error in the root-owned atomic status
snapshot. A successful start or stop clears it. Firewall mutations retain the
bounded command stderr so a typed code does not replace the actionable kernel
or xtables diagnostic.

APK-local root transport failures use the same schema. In particular,
`ROOT_COMMAND_QUEUE_BUSY` means another serialized root operation is still
running; it does not revoke a previously verified root grant and must not be
presented as a root timeout.
