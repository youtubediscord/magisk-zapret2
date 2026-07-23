# Module error contract

Zapret2 module commands and the Android app share error schema `1`:

```text
Z2_ERROR_SCHEMA=1
Z2_ERROR_STATUS=ERROR
Z2_ERROR_DOMAIN=FIREWALL
Z2_ERROR_STAGE=START_IPV4_BUILD_RULE
Z2_ERROR_CODE=FIREWALL_BUILD_FAILED
Z2_ERROR_DETAIL=iptables rejected the detached IPv4 rule
```

All six fields are required exactly once. The APK validates only schema,
`OK`/`ERROR`, uppercase token syntax, and size limits. Domain, stage, and code
are opaque strings, so a newer module may add values without an APK rebuild.
`detail` is a non-empty single line of at most 512 bytes for an error.
`OK/NONE/NONE/NONE/""` is the only no-error record.

`zapret-status.sh --machine` remains the immutable version-1 status protocol
for older APKs. New clients request `--machine-v3`, which adds
`Z2_PROTOCOL=3` and the six error fields before the terminal
`Z2_COMPLETE=1` sentinel. A client may fall back to version 1 only when the
module explicitly rejects the version-3 argument without emitting machine
fields.

The module persists the last lifecycle error in the root-owned atomic status
snapshot. A successful start or stop clears it. Firewall mutations retain the
full lifecycle/logcat/file diagnostics: the bounded envelope is only the UI
summary and never removes or replaces the actionable kernel or xtables log.

APK-local root transport failures use the same schema. In particular,
`ROOT_COMMAND_QUEUE_BUSY` means another serialized root operation is still
running; it does not revoke a previously verified root grant and must not be
presented as a root timeout.
