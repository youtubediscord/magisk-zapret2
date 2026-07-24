# Owner metadata schema v8

Schema v8 makes the compiled preset capture policy part of the authenticated
firewall generation through four fields:

- `tcp_pkt_out`
- `tcp_pkt_in`
- `udp_pkt_out`
- `udp_pkt_in`

All four are canonical positive decimal packet bounds copied from the active
TXT preset by `command-builder.sh`. They participate in the per-family
specification and firewall fingerprint. Start, health verification, teardown,
and rollback therefore use the same protocol-specific values;
none may read a competing runtime or Android setting.

The schema also binds the command SHA-256, boot identity, stable
`ZAPRET2_OUT`/`ZAPRET2_IN` namespace, exact port unions, capability flags, rule
counts, family specifications, and fingerprint. Health verification projects
that bounded receipt through the same reconciler that publishes the direct
chain rules. There are no generation-bound payload chains or firewall WAL.
Runtime code recognizes and publishes only v8; package replacement starts a
fresh generation after the mandatory reboot.
