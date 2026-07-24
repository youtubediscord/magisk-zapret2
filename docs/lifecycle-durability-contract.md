# Lifecycle and firewall contract

Zapret2 has two mutation domains. They deliberately use different recovery
models.

## Boot-local lifecycle state

`nfqws2`, netfilter rules, PID ownership, and status snapshots disappear with
the current kernel boot. Their publications use private files plus atomic
rename, but never a filesystem durability barrier or a firewall write-ahead
log.

The firewall has one module-owned namespace in each available family:

- `OUTPUT -> ZAPRET2_OUT`;
- `INPUT -> ZAPRET2_IN` when `connbytes` is supported;
- complete NFQUEUE payload rules live directly in those chains.

Start derives the entire candidate from the validated preset. It first checks
that `iptables-restore` exists, removes the exact stable namespace
idempotently, validates the complete batch with `--test --noflush`, and applies
it at one `COMMIT`. If the `connbytes` form is rejected, start cleans the same
namespace and retries once with outgoing-only interception.

An interrupted start or stop is recovered by repeating the same cleanup or
reconcile operation under the lifecycle lock. There are no dynamic generation
chains, per-rule journals, capability-probe chains, teardown markers, or prior
firewall snapshots to replay. A foreign reference to either stable chain is
detected from one mangle snapshot before mutation and fails closed.

Boot-local lifecycle code must not invoke the global `sync` command. On Android
that operation can wait for unrelated dirty data on every mounted filesystem,
so its latency is unrelated to Zapret2 state.

## Persistent mutations

Runtime configuration, user-owned preset/list edits, package staging, full
rollback, purge, and uninstall markers can survive a reboot independently of
kernel firewall state. Their dedicated transaction owners retain bounded
durability barriers at publication or terminal cleanup boundaries.

Package installation never replaces files in the mounted active module tree.
It stages a new Magisk generation, authenticated by its generation receipt,
and activation requires a reboot.

## Regression policy

Shell tests cover whole-batch publication, missing `iptables-restore`,
`connbytes` fallback, failed `COMMIT`, idempotent cleanup, interrupted stable
state, foreign-reference refusal, lifecycle rollback, and the absence of global
`sync` in ordinary start paths.
