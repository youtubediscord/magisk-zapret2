# Rooted-device smoke harness

`smoke.sh` is an evidence-oriented release check for a disposable, recoverable
Android device. It is not a general installer. Its default and `--dry-run`
behaviour is strictly read-only on the device.

## Safety contract

- Every invocation requires an exact ADB serial. The harness rejects missing,
  unauthorized, offline, ambiguous, or differently reported devices.
- A mutating stage is rejected before the first ADB call unless it has
  `--allow-mutations`, an exact serial acknowledgement, and the literal tested
  recovery-plan acknowledgement.
- `update`, `full-rollback`, and `uninstall` each have an additional opt-in.
- The initial preflight creates a private (0700) evidence directory containing
  a before-state bundle. Firewall dumps and device metadata may be sensitive;
  retain the directory as release evidence and do not publish it unreviewed.
- Update uses the public Magisk module-install CLI and Android package manager.
  ADB uploads to randomized shell-temporary names, then root verifies the
  source inode/owner/mode/link count and copies both artifacts into a new
  randomized root-owned 0700 directory. The root-private 0600 copies are
  revalidated and hashed immediately before Magisk consumes the ZIP and before
  the APK is streamed into `pm`; cleanup removes only that exact, revalidated
  owned directory. Host, private-stage, installed-generation, and installed-APK
  SHA-256 identities must all match. The internal update guard is never invoked.
- Uninstall is intentionally manual through the trusted root-manager UI. The
  harness records the last checkpoint with the canonical boot ID bound to the
  exact serial/evidence run and the private raw query's name, size, SHA-256, and
  canonical value, requires a different canonical boot ID during
  verification, and validates the exact secure five-field uninstall tombstone.
  It never uses Magisk's all-modules removal command or writes a removal marker.

## Prerequisites

- `adb` and a SHA-256 utility (`sha256sum`, `shasum`, or `openssl`) on the host.
- A disposable ARM/ARM64 device on API 24+ with an unlocked, authorized ADB
  session, Magisk 20.4+, working `su`, and a tested recovery route.
- Zapret2 already installed from the exact release under test.
- For update testing, both final signed CI artifacts and their independently
  retained SHA-256 values. Do not use a locally rebuilt substitute.

Preflight validates root, API/ABI, exact module/state paths and modes, packaged
entry points, `iptables`/`ip6tables`, NFQUEUE and `--queue-bypass`, `connbytes`,
`multiport`, `mark`, the 21-field status contract, and artifact hashes. IPv4 is
mandatory. IPv6 is recorded and may be skipped like runtime startup; use
`--require-ipv6` for an IPv6 matrix device.

The evidence bundle records exactly `schema=1`, `ipv4=ready`, and either
`ipv6=ready` or `ipv6=not_available`, in that order. Every device mutation
revalidates this record against the immediately preceding preflight. IPv4
always requires a successful `iptables-save`; recorded-ready IPv6 requires a
successful `ip6tables-save`, while unavailable IPv6 uses the canonical
`Z2_IP6TABLES_SAVE=not_available` evidence record.

Because preflight must not insert a rule, its capability result is based on the
installed firewall tools/extensions, their `--queue-bypass` interface, procfs
facts, and the status contract. The later `start` stage is what proves that the
kernel accepts and cleans up exact probe rules under the module's durable
transaction journal.

All privileged evidence queries carry an exact return-code/completion footer;
failed or truncated output is rejected rather than retained as successful
evidence. Rollback and uninstall additionally require successful IPv4/IPv6
firewall dumps with no owned chain, anchor, or rule, plus a `/proc` audit of
`exe` and `cmdline` bound to the exact owner metadata/PID files. A `ps` text
grep is not accepted as cleanup evidence. Status, rollback, root-stage, and
private-copy machine records reject missing, duplicate, reordered, and unknown
fields; root-stage creation must explicitly report `Z2_ROOT_STAGE_SECURE=1`.

## Ordered run

Start with read-only preflight. Supply artifacts here if this evidence sequence
will include an update, because later update must match the hashes bound here:

```sh
sh tests/device/smoke.sh \
  --serial EXACT_ADB_SERIAL \
  --evidence-dir /secure/evidence/zapret2-device-1 \
  --module-zip /artifacts/zapret2-magisk-vX.Y.Z.zip \
  --module-sha256 MODULE_SHA256 \
  --apk /artifacts/zapret2-control-vX.Y.Z.apk \
  --apk-sha256 APK_SHA256 \
  --require-ipv6
```

For every mutating invocation, repeat these three gates:

```sh
--allow-mutations \
--ack-disposable-device EXACT_ADB_SERIAL \
--ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
```

Use the same evidence directory and run one stage per invocation. The harness
refuses skipped, reordered, repeated, or post-uninstall mutation stages:

1. `--stage stop` (runs and verifies stop twice)
2. `--stage start` (runs and verifies start twice)
3. `--stage restart` (runs and verifies restart twice)
4. `--stage update --allow-update` plus the same four artifact arguments
5. `--stage full-rollback --allow-full-rollback`
6. `--stage uninstall --allow-uninstall`
7. Remove only Zapret2 in the trusted root-manager UI, reboot, then run the
   read-only `--stage uninstall-verify` with the same serial/evidence directory.
   Verification fails unless the boot ID changed and the remaining tombstone is
   a root-owned 0600 single-link file with ordered `version`, `pid`, `starttime`,
   `token`, and `module_dir` fields whose recorded process identity is proven
   absent or has a readable, different starttime. A present but unreadable or
   malformed `/proc/<pid>/stat` is unsafe and fails verification.

Before any mutating stage, append `--dry-run` to repeat all read-only checks and
show that the stage is eligible without executing it or advancing the sequence.
Full rollback's exact 10-field contract requires a reboot; the manual uninstall
checkpoint deliberately remains last. Reinstall verification is a separate
case: after uninstall verification, reinstall through the root manager and
start a new evidence directory from preflight.

## Host safety tests

The host test uses a fake ADB executable and cannot contact a device:

```sh
sh tests/device/test-smoke.sh
```

It uses a deny-by-default fake ADB/root/package-manager allowlist. Besides
proving that default/preflight and dry-run execute no mutation, it runs the
ordered update, full rollback, manual-uninstall verification, and rejects
unknown commands or modes, every modeled staging/cleanup ownership fault, both
install-time hash swaps, capability drift, mandatory dump failures, dirty IPv4
or IPv6 state, failed/truncated queries, malformed exact machine schemas, and
false-clean process audits. It also rejects missing/malformed/same-boot evidence,
tampered run binding, and malformed, live, foreign, symlinked, or wrong-mode
uninstall tombstones and raw proc states. The fake executes the production root
audit over modeled filesystem/proc primitives; temporary-mutant kill checks
cover the reboot binding and root stat/schema/proc predicates. A separate
ordered IPv4-only run reaches
`uninstall-verify` using canonical IPv6 non-availability evidence throughout.
