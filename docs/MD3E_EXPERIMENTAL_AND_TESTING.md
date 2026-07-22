# Material 3 Expressive stability and UI-test boundary

The app currently pins `androidx.compose.material3:material3:1.5.0-alpha24`. This is
an alpha dependency and the project does not describe it as stable. The local
`ExperimentalMaterial3Api` and `ExperimentalMaterial3ExpressiveApi` opt-ins cover the
adaptive shell, expressive theme and motion scheme, loading indicators, dialogs,
pickers, and the screens that call those APIs.

The surrounding build toolchain is pinned independently: AGP 9.1.1, Gradle 9.3.1,
JDK 17 and Kotlin 2.3.21. As reviewed on 2026-07-22, the official AndroidX release
record lists Material 3 1.4.0 as stable and 1.5.0-alpha24 as the current alpha. The
stable line does not expose the complete Expressive surface used by this app, so
calling the whole UI dependency stack stable would be inaccurate. Review sources:

- <https://developer.android.com/build/releases/agp-9-1-0-release-notes>
- <https://developer.android.com/jetpack/androidx/releases/compose-material3>
- <https://kotlinlang.org/docs/releases.html>

Before removing an opt-in, upgrade only through a reviewed Compose BOM/Material 3
pair, compile every affected call site, and verify the replacement API at compact and
expanded widths. Remove each annotation only after the corresponding API is stable;
the dependency version string and absence of stale opt-ins are part of that review.

Pure JVM policy tests cover back decisions, exact destination/NavHost registration,
post-legacy resource linkage, design-token and accessibility semantics usage,
localization boundaries, and privacy-preserving log export. A local Compose UI/Robolectric matrix
and Roborazzi baselines are not configured. On 2026-07-21, Gradle could not establish
TLS to canonical Maven Central for the new runtime graph. Regional mirrors were not
accepted as canonical supply-chain evidence, so their repositories, temporary plugin
mapping, test dependencies, and provisional UI test source were removed.

Stateful destinations expose immutable `StateFlow` state collected with lifecycle
awareness. Control and Strategies keep user-facing operation messages in that state
until the shared snackbar effect consumes them; they do not use lossy `SharedFlow`
event channels that can discard a result while a destination is stopped. The remaining
`ServiceEventBus` flow is only a best-effort invalidation signal: each returning screen
reloads its authoritative state independently. Strategies, Presets, Hostlists, Hostlist
Content, DNS, Config, and Hosts also queue that return-triggered revalidation when an
operation is already active and consume it after the busy state is released, preserving
unsaved editor drafts above the newly read authoritative baseline where applicable. All
nine stateful destinations use `LifecycleStartEffect`; stop/disposal cancels polling for
Control/Logs and drops pending follow-up refresh ownership for the other seven.

## JVM regression-source matrix

These files prove that the scenarios have direct test sources; they are not current-tree
pass evidence until the deferred synchronized Gradle gate executes them:

- state/process recreation: `ConfigEditorDurableStateTest`,
  `HostsEditorDurableStateTest`, `HostlistContentDurableStateTest`,
  `PresetsViewModelSafetyTest`, and `ControlDialogStateModelTest`;
- rapid duplicate/busy actions: `ControlDialogStateModelTest`,
  `FullRollbackAvailabilityPolicyTest`, and `ViewModelPrivilegedBoundaryPolicyTest`;
- coroutine cancellation at privileged commit/lock boundaries:
  `CancellationSafeLockHandoffTest`, `CancellationSafeMutationLeaseTest`,
  `CancellationSafeTerminalCommitTest`, `ModuleUpdateRecoveryTest`, and
  `UpdateTransactionProtocolTest`; the mutation-lease source includes the late-release
  case where cancellation arrives after the block result but before owner retirement;
- denied/missing/timed-out root plus missing/partial module classification:
  `ServiceLifecycleControllerTest`, `ControlDiagnosticsRepositoryTest`, and
  `FullRollbackAvailabilityPolicyTest`;
- malformed, duplicate, reordered, truncated, unknown and contradictory machine output:
  `ServiceLifecycleControllerTest`, `OwnerStateSchemaTest`,
  `ControlDiagnosticsRepositoryTest`, `UpdateLockProtocolTest`,
  `UpdateTransactionProtocolTest`, and `ModuleUpdateRecoveryTest`.
- post-legacy UI/source graph consistency: `ResourceLinkagePolicyTest`,
  `NavigationDestinationPolicyTest`, `LegacyUiAbsencePolicyTest`,
  `AccessibilitySemanticsPolicyTest`, and `LocalizedPresentationPolicyTest`.
- APK/shell filename admission, control-character rejection, reserved command-line
  names, exact 255-byte UTF-8 component limits and suffix semantics:
  `RootFileIoTest`, `RuntimeConfigStoreTest`,
  `HostlistRepositoryTest`, `ModulePackageContractTest`, and
  `PrivilegedProtocolParityPolicyTest`.

The shell regression sources cover the corresponding host/module boundary:

- quoting, unsafe roots, shell metacharacters, path traversal and valid filenames with
  spaces: `tests/shell/run.sh` and `tests/shell/preset-contract.sh`;
- malformed/missing runtime, owner, cleanup and rollback evidence:
  `tests/shell/run.sh`, `owner-state-v6.sh`, `update-cleanup-v2.sh`,
  `wal-validator.sh`, and `full-rollback.sh`;
- missing/unsafe binaries, scripts, dependencies and package entries:
  `run.sh`, `preset-contract.sh`, `package-owner-protocol.sh`, and
  `packaging-recovery-flow.sh`;
- command/signal failure with exact prior-state restoration:
  `transactional-start.sh` and `lifecycle-safety.sh`;
- concurrent rule insertion, lifecycle ownership and stale/foreign lock handling:
  `detached-build.sh`, `lifecycle-lock-owner.sh`, and `owner-generation.sh`.

This is a source map only. Root-semantic behavior remains unclaimed until
`sudo sh tests/shell/run.sh` and the dedicated scripts pass in pinned Linux CI.

Do not add golden baselines until the canonical artifacts can be resolved with strict
dependency verification and exact lock/checksum metadata. Once unblocked, the first
matrix must cover 360 dp and 840 dp navigation/back/selection, loading/empty/error/
disabled semantics, Russian plus RTL at 200% font scale, reduced motion, and saved
state restoration. Golden capture comes only after those semantic tests pass; pin the
Roborazzi version and review threshold, and keep deterministic baseline assets in
source control.
