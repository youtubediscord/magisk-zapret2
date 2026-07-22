# Material 3 Expressive source completion audit

This file is the authoritative source-level traceability record for the Android app
and its privileged module boundary. It records what the current tree implements; it
does not replace compilation, test execution, rendered visual review, signed artifact
verification, or rooted-device evidence.

## Scope and support contract

- Android app: API 24–35, ARM/ARM64 module payloads, English and Russian resources.
- Verified release target: Magisk 20.4+; KernelSU/APatch remain outside the claimed
  release matrix until their device gates are explicitly completed.
- UI modes represented in source: compact/medium/expanded adaptive navigation,
  light/dark/dynamic color, RTL, 200% font scale, and reduced motion.
- Network contract: IPv4 iptables/NFQUEUE is mandatory. IPv6 is enabled only when
  its exact capability set is available; otherwise the module records canonical
  IPv6-inactive state instead of claiming partial ownership.
- App permission contract: network type uses `ACCESS_NETWORK_STATE`; the app does
  not request location-sensitive Wi-Fi SSID access. Update traffic is HTTPS-only and
  APK installation uses an explicit read grant from a private update-only cache path.
- Runtime configuration authority: `zapret2/runtime.ini`. `config.sh` and the old
  temporary user configuration are migration/bootstrap inputs only.

## Destination traceability

| Destination | Authoritative UI state | Data/operation boundary | Durable and failure behavior | Source evidence |
| --- | --- | --- | --- | --- |
| Control | `ControlUiState` / `ControlViewModel` | `ServiceLifecycleController`, `ControlDiagnosticsRepository`, `NetworkStatsManager`, `RuntimeLogRepository`, `UpdateManager` | One synchronously claimed exclusive gate across lifecycle toggle, setting writes, update checks/install and full rollback before coroutine launch, STARTED-lifecycle-bound polling plus authoritative runtime-settings revalidation on every return, cancellation-aware post-update refresh that suppresses delayed root I/O after screen exit, cancellation-safe packet-count transactions retaining one lifecycle lease across runtime write/restart/post-state verification, IO-confined protected failure-log reads, modal-owned interaction that quietly rejects stale lifecycle/settings/update calls, prevents hiding an active update, requires exact packet/update dialog identity, and binds late update-check results to the launching modal state, no-op setting suppression, one immutable machine-status snapshot per poll, independent secure owner/topology proof before RUNNING, verified post-state, durable last result/dialog state, fail-closed canonical restoration that drops incomplete invisible modal states, revalidated resource IDs, bounded redacted diagnostics rewritten safely after restoration, exact root/module failure states | `ControlDialogStateModelTest`, `FullRollbackAvailabilityPolicyTest`, `UpdateExecutionPresentationTest`, `ViewModelPrivilegedBoundaryPolicyTest` |
| Strategies | `StrategiesUiState` / `StrategiesViewModel` | `StrategyRepository`, `RuntimeConfigStore`, `ModuleMutationCoordinator`, `ServiceLifecycleController` | Validated category/strategy IDs and loaded settings, exact category-bound authoritative picker membership before selection, ViewModel-enforced filter-switch capability, no-op strategy/filter/packet/debug suppression before persistence or restart, captured desired-state snapshot, one cross-operation mutation gate, atomic category/runtime rollback, saved restart-failed outcome, bounded durable pending order/search state, authoritative filter resynchronization, picker generation invalidation across reload and mutation, reorder UI/save rejection while the exact picker catalog is loading, failed, or empty, and cancellation-safe order persistence bound to exact picker/catalog membership plus an atomic expected-runtime-order compare-and-set. Category write/validation and lifecycle restart use the cancellation-transparent mutation adapter, so cancellation is never converted into ordinary failure and ambiguous runtime rollback retains its distinct outcome | `StrategyRepositoryTest`, `StrategiesOrderStateTest`, `DurableAsyncUiStateTest`, `ViewModelPrivilegedBoundaryPolicyTest`, `ComposeLifecyclePolicyTest` |
| Presets | `PresetsUiState` / `PresetsViewModel` | `PresetRepository` | Strict names, UTF-8 byte-size limit, and parser; authoritative catalog/editor baseline gates; already-active preset/mode no-op rejection before repository mutation; restored-draft revalidation; a second open cannot replace existing editor state; optimistic source-change rejection; exact post-replace verification; serialized apply/save/mode switch; dirty dismissal rejected by the ViewModel without explicit discard acknowledgement; cross-resource compensation; durable result/issue. Boolean and thrown restart failures share the same compensation path for apply, save-and-apply, and category-mode switching, while coroutine cancellation is rethrown | `PresetRepositoryTest`, `PresetsViewModelSafetyTest`, `PresetNamePolicyTest` |
| Command editor | `ConfigEditorUiState` / `ConfigEditorViewModel` | `CommandLineRepository`, `RuntimeConfigStore`, command-builder machine validator | Bounded draft restoration with mandatory source revalidation, exact configured manual-file binding with a narrowly opted-in secure zero-byte read so missing/blank files are shown honestly as empty, no runtime-command fallback, clean-save suppression, mandatory restart for forced command-mode activation, UTF-8 byte-size rejection before mutation, optimistic source-change rejection, canonical post-write verification, exact versioned validation that read-only loads current core values, suppresses only the diagnostic sink, builds the complete effective command without state writes, verifies the installed root-owned single-link `0755` binary and requires its real `--dry-run` before success; only parser/dry-run rejection maps to invalid-command while unavailable runtime/binary/build infrastructure remains a fail-closed save failure. One protected `0600/0644` mode contract spans APK/runtime/package verification; runtime rejects oversized/non-root/hard-linked or concurrently changed command files by stable metadata plus SHA-256, rejects empty/traversing referenced paths, normalizes safe relative references, confines Lua/blob/hostlist/ipset inputs to direct children of their exact protected module roots, and rejects arbitrary external or nested files. Strict command-mode handling, ViewModel-owned busy rejection, serialized atomic write/rollback with companion-file compensation, recoverable read-only failure state, and explicit invalid/blocked/rollback-failed/source-changed results remain enforced. Logs is the sole runtime-command viewer | `ConfigEditorDurableStateTest`, `EditorContentLimitTest`, `PrivilegedProtocolParityPolicyTest`, `ViewModelPrivilegedBoundaryPolicyTest` |
| Hostlists | `HostlistsUiState` / `HostlistsViewModel` | `HostlistRepository`, `HostlistImportReader` | Bounded UTF-8 import, safe direct-child filename/path with the exact lowercase `.txt` suffix used by case-sensitive shell catalog enumeration, validated content, atomic write/rollback, authoritative-catalog gate, stale catalog clearing after read/ambiguous rollback failure, synchronous busy claims and stale-generation rejection | `HostlistRepositoryTest`, `HostlistsStateTest` |
| Hostlist content | `HostlistContentUiState` / `HostlistContentViewModel` | `HostlistRepository` | Filename-only route identity with the exact direct-child root path derived inside the repository and no externally carried path/count parameter, invalid-route scoped-state cleanup and search rejection, isolated viewer/editor generations and immutable search queries, immediate old-page invalidation across search debounce, authoritative count-bounded paging without an extra end-of-data root read, bounded paging/search/editor state and UTF-8 persisted size, immediate rewrite of a sanitized restored query so an oversized raw Bundle payload cannot survive reconstruction, atomic retirement of incomplete editing/draft/baseline saved state, narrowly opted-in secure zero-byte reads matching the documented empty-list edit state, restored-draft revalidation, authoritative baseline gate, optimistic source-change rejection, exact reread post-write baseline including newline normalization, canonical post-write verification, recoverable draft retention, and ViewModel-enforced explicit acknowledgement before dirty exit. A thrown post-write reread becomes typed failure inside the repository and still enters exact snapshot rollback | `HostlistContentDurableStateTest`, `HostlistRepositoryTest`, `EditorContentLimitTest`, `RootFileIoTest`, `ViewModelPrivilegedBoundaryPolicyTest` |
| Hosts editor | `HostsEditorUiState` / `HostsEditorViewModel` | `HostsOverlayRepository`, `HostsFileSyntax` | Bounded draft and UTF-8 persisted size, strict numeric-address/hostname syntax validation before lease admission or write with localized recoverable feedback, restored-draft revalidation, authoritative baseline gate, no-op save rejection, and repository-owned conditional write/remove that rechecks the exact overlay plus effective content and verifies the persisted or missing-overlay post-state. Write/remove and their post-state reads convert thrown command/read failures after mutation into typed failure so the outer transaction restores the captured overlay. Explicit dirty-draft acknowledgement before overlay reset, rollback and reboot-required outcomes remain enforced; rollback may restore an exact bounded pre-existing snapshot even when it predates the current syntax contract | `HostsEditorDurableStateTest`, `HostsFileSyntaxTest`, `EditorContentLimitTest`, `RootFileIoTest`, `ViewModelPrivilegedBoundaryPolicyTest` |
| DNS manager | `DnsManagerUiState` / `DnsManagerViewModel` | `HostsOverlayRepository`, `HostsIniParser`, `RuntimeConfigStore` | Fail-closed exact-section catalog parser with unique labels/domains/direct entries, validated hostnames/IPs and uniform bounded address cardinality; no unknown selection or invalid preset index can generate a mapping. No selection without authoritative hosts/runtime data or during operations, stale selection clearing after read failure, exact packaged-catalog revalidation under the lifecycle lease before apply with localized automatic reload on hot-update drift, and repository-owned conditional hosts mutation with a distinct localized source-drift outcome. Explicit localized reset confirmation, generated hosts byte-size rejection before write, serialized hosts/runtime mutation and rollback remain enforced | `HostsIniParserTest`, `EditorContentLimitTest`, `DurableAsyncUiStateTest`, `ViewModelPrivilegedBoundaryPolicyTest` |
| Logs | `LogsUiState` / `LogsViewModel` | `RuntimeLogRepository` | STARTED-lifecycle-bound polling/read jobs with loader-owned guards and stop-time generation invalidation, bounded protected reads and filter input, explicit and thrown read failures normalized into typed error state with stale-payload clearing, exact current-tab/READY/payload validation before copy or share, stale share rejection across tab/load/clear/lifecycle changes, bounded rendering/share payload, generation-safe refresh, serialized exception-safe clear bound to the exact confirmed tab with a tab-keyed confirmation and visible cross-action busy state, redacted user feedback | `LogsShareTest`, `ControlDiagnosticsRepositoryTest`, `ViewModelPrivilegedBoundaryPolicyTest`, `ComposeLifecyclePolicyTest` |
| About | Stateless | Android package metadata and explicit external intents | No privileged work and no retained mutable state | `LegacyUiAbsencePolicyTest`, destination previews |

Every stateful destination collects a `StateFlow` lifecycle-aware. User-visible
operation messages are state-owned and consumed before snackbar suspension, so they
cannot disappear before a subscriber exists or replay after consumption. No
composable or ViewModel constructs a libsu command; protected filesystem and shell
work remain in typed data/lifecycle boundaries. All nine stateful production screens
use lifecycle-aware collection; production contains no raw `collectAsState`,
`observeForever`, `GlobalScope`, `MainScope`, or unowned `CoroutineScope`. Control's
only persistent preference read is dispatched to IO before its initial state publish.

Saved navigation stacks do not treat restored UI state as current module truth.
Strategies, Presets, Hostlists, DNS, Config, Hosts, and Hostlist Content invoke a
ViewModel-owned authoritative refresh whenever their destination returns to the
`STARTED` lifecycle. The first normal start reuses the ViewModel initializer instead of
duplicating root I/O; process-restored editors revalidate immediately, and dirty editor
drafts are kept above the newly read baseline. If return happens while a load, save,
import, restart, or catalog mutation is active, every one of these seven ViewModels
records a pending refresh and consumes it only after the active operation has released
its busy state; stale pre-return completion can therefore never become the final
unchallenged snapshot. Stopping the destination drops only that pending request so it
cannot trigger a no-longer-visible follow-up read; the next `STARTED` event creates a
fresh request. Hostlist return refresh also cancels any delayed search before publishing
the current query. `ViewModelPrivilegedBoundaryPolicyTest` and
`ComposeLifecyclePolicyTest` lock these entry, stop, and queued-completion boundaries.

Destructive editor transitions are not protected only by disabled controls or dialogs.
Config/Hosts reload and Preset/Hostlist editor exit reject a dirty draft unless their
ViewModel receives an explicit discard acknowledgement; hosts-overlay reset applies the
same rule while describing the combined draft/overlay loss. The normal clean paths keep
the default fail-closed argument. A preset open also refuses to replace any existing editor
state, forcing callers through that guarded close transition first.
`ViewModelPrivilegedBoundaryPolicyTest` locks the confirmed UI call sites to the
corresponding ViewModel guards.

Action confirmation is likewise state-owned for destructive operations without drafts.
DNS reset, hosts-overlay reset, and log clearing default to rejection unless their confirmed
UI branch supplies the explicit acknowledgement. Full rollback already requires the
ViewModel's durable `Confirmation` state before its coordinator can claim the operation.

## Material 3 Expressive traceability

- `MainActivity.kt` owns edge-to-edge setup and the explicit compact drawer, seven-item
  medium rail (six frequent destinations plus localized overflow sheet), and expanded
  grouped rail shell. Width-mode changes close an invisible drawer, reset double-back
  authority, and dismiss a stale exit prompt.
- `ui/theme` owns semantic colors, typography, shapes, motion and design tokens.
- `ui/components` owns shared cards, rows, overlays, status, snackbars, dialogs,
  selectors and reduced-motion behavior.
- The theme owns the only system animator-scale observer and publishes its result
  through one composition local, so nested screens/components cannot register
  duplicate observers or disagree about reduced-motion state. Settings reads execute
  on `Dispatchers.IO`; a generation-keyed composition effect cancels stale reads,
  unregisters on disposal, and defaults to reduced motion until a finite authority is
  known. Read failures and non-finite values stay fail-safe reduced.
- Owned tweens use semantic motion-duration tokens. Full-screen text inputs and the
  preset editor explicitly consume IME insets under edge-to-edge. The dialog editor
  grows by visible lines under font scaling, has no fixed minimum height, and retains
  a reviewed maximum; primary card/list labels can wrap to two lines before ellipsis.
- The destination registry contains nine unique navigation destinations plus one typed
  hostlist detail route, with exactly ten matching NavHost registrations and Control as the
  start destination; `NavigationDestinationPolicyTest` locks that graph. Ten production
  destination entry points are used by 37 previews. The 36 stateful
  preview invocations cover normal plus applicable loading, empty, error, disabled,
  degraded, root-denied and transient states; About is stateless.
- Production UI has no View/XML layout, Fragment, AppCompat, Material Components
  View, Fluent or Win11 implementation. Remaining XML is limited to platform startup
  themes/colors, launcher bitmaps, localized strings and the private cache
  `FileProvider` path.
- All 483 default-English/Russian translatable strings and 14 plurals have source key,
  formatted-attribute and argument-signature parity. Multi-argument resources use explicit
  positions, and every Russian plural defines `one`, `few`, `many`, and `other`; the complete
  catalog contract is guarded by `LocalizedPresentationPolicyTest` rather than a small sample
  set. A source sweep additionally confirms that all 37 references to the 34 formatted-string
  resources and all 17 plural calls supply their complete argument set and a distinct plural
  quantity.
  Reachable Compose UI rejects literal visible/semantic copy, while destination loading,
  error and disabled previews consume the same resources as runtime state so Russian previews
  do not silently fall back to English demo messages. Strategy category subtitles preserve
  exact technical protocol/file values but localize the app-owned no-filter state rather than
  exposing the internal `none` token.
- The four text-editor destinations share one process-death saved-state budget: each
  draft/baseline field is capped at 16 Ki characters and the eight known simultaneous
  fields total at most 128 Ki characters. Live editing and protected-file limits remain
  unchanged; oversized drafts are kept in memory but omitted from the Activity Bundle.
- Pending strategy-order recovery is independently capped at four categories and 16 Ki
  identifier characters per category (64 Ki characters combined). Restoration rewrites
  the bounded key set immediately, so stale per-category arrays cannot remain hidden in
  `SavedStateHandle` after being dropped from visible state.
- Enum and typed process-state restoration removes unknown, mistyped and obsolete payloads.
  Control dialog payloads are reconciled as one schema, so incomplete packet/error/rollback
  state cannot create an invisible blocking dialog; restored sensitive diagnostics are
  redacted and rewritten before they remain durable.
- Config, hosts, hostlist and preset editors apply an allocation-free UTF-8 byte budget
  before accepting new Compose state, so an oversized paste is rejected without retaining
  it. The repositories repeat the authoritative check after canonicalization and before the
  first protected write; all four checks count the exact canonical single trailing newline.
- All ten production lazy collections declare explicit stable keys. High-volume hostlist and
  log rows reuse their remembered formatter/text-style objects outside item composition rather
  than allocating them once per visible row; runtime scrolling/recomposition budgets remain an
  external profiling gate.
- Clickable non-button surfaces declare semantic roles and guarantee a 48 dp minimum
  target independently of current text/icon dimensions; Material minimum-target
  enforcement is never disabled. Icon-only controls retain an accessible name when
  their visual child changes to a loading indicator, busy controls expose disabled state,
  radio-button surfaces expose selectable groups, parent-owned selection rows clear the
  duplicate semantics of their visual checkbox/switch/radio indicator, grouped status and
  readiness surfaces merge their readable label with one state description, strategy selection
  and reorder position stay on their named interaction target, preset cards expose edit and apply
  as single distinct actions, and the blocking loading surface exposes one
  polite indeterminate progress region. Shared section titles, the active screen title,
  navigation group labels, and the medium overflow title expose semantic heading roles.
  These source invariants are guarded by
  `AccessibilitySemanticsPolicyTest`; hands-on TalkBack, traversal, contrast, scaling and
  touch-target verification remains an external release gate.
- The 36 fixed light/dark semantic foreground/background pairs all exceed the WCAG
  4.5:1 normal-text threshold (current minimum 5.45:1), and
  `ThemeContrastPolicyTest` recomputes the ratios directly from `Color.kt`. This does not
  claim dynamic-color, disabled/composited-control, screenshot or device contrast sign-off.
- Config, Hosts, DNS and Presets share one adaptive multi-action layout; the Hostlist
  editor footer and Control readiness badges use the same equal-width width/font-scale
  policy. Groups stack below the 640 dp action breakpoint and at any width once font scale
  reaches 1.3; bounded two-line trailing setting values can no longer displace their
  labels, and the preset editor stacks its two confirm actions instead of forcing them
  into one narrow dialog row. Logs uses a scrollable primary tab row, so long translated
  tab labels no longer compete for a fixed third of the screen. This removes the identified
  narrow/200%-text source risks; rendered large-text/display-scale verification remains
  external.

The project deliberately pins an alpha Material 3 version because the Expressive APIs
are not stable. `MD3E_EXPERIMENTAL_AND_TESTING.md` records every opt-in boundary and
the upgrade/removal rule; the dependency must not be described as stable.

## Privileged flow traceability

| Flow | Android owner | Shell/module owner | Integrity and recovery contract |
| --- | --- | --- | --- |
| Root and module discovery | `ServiceLifecycleController`, `ControlDiagnosticsRepository` | module path and executable package contract | Root denial/unavailable/failure/timeout are distinct; missing, disabled, removal-pending, update/recovery, partial, unreadable and unsupported ABI are distinct |
| Status and firewall ownership | `ServiceLifecycleController`, `NetworkStatsManager`, `OwnerStateSchema` | `zapret-status.sh`, `common.sh` | Owner schema v7 has exactly 33 fields including a fixed-size process-command SHA-256, firewall tag and generation-bound chains; current boot/PID/starttime/spec/fingerprint must reconcile, while same-boot v6 is accepted by Android only as read-only compatibility evidence. The Android reader requires a root-owned mode-0700 state directory plus a root-owned mode-0600 regular single-link owner file whose identity and SHA-256 remain stable across the bounded read. Its independent live iptables proof reconstructs every ordinal payload from the owner contract and requires exact protocol, direction, ports/ranges, packet bound, connbytes, multiport, mark/mask/negation, NFQUEUE queue and bypass semantics before RUNNING |
| Start/stop/restart | `ServiceLifecycleController`, `ModuleMutationCoordinator` | `zapret-start.sh`, `zapret-stop.sh`, `zapret-restart.sh`, installed wrappers | Cross-process lifecycle lease, exact owned-state verification, transactional startup and idempotent cleanup |
| Runtime/category/preset/host mutations | typed repositories plus `ModuleMutationCoordinator` | `runtime.ini`, `categories.ini`, command builder and lifecycle scripts | Validate before mutation, snapshot, atomic replace, restart where required, rollback on ambiguous failure, explicit rollback-failed state. `RuntimeConfigStore` treats thrown primary writes and thrown/missing post-write reads as ambiguous, restores the previous exact content, and accepts rollback only after an independent secure reread |
| Manual command editing | `ConfigEditorViewModel`, `CommandLineRepository` | `runtime.ini`, protected command file, `command-builder.sh`, installed `nfqws2` | The editor binds only to the exact configured file and uses optimistic snapshot comparison, bounded atomic replacement and compensation. Machine validation is read-only, reconstructs the complete effective command, confines referenced files to direct children of `lua`, `bin` or `lists`, and accepts only a real installed-binary dry-run. Parser/dry-run rejection is distinct from unavailable runtime, binary, integrity tooling or build infrastructure, so the APK never misattributes an infrastructure failure to user content |
| Full rollback | `ServiceLifecycleController` | `zapret-full-rollback.sh`, installed wrapper | Durable transaction, disable fence, protected hosts backup, exact terminal machine result and resumable cleanup |
| Irreversible clean purge | `ModulePurgeController`, `ControlViewModel` | `scripts/lifecycle/purge-contract.sh`, `scripts/lifecycle/zapret-purge.sh`, Magisk `action.sh`, `uninstall.sh` | One canonical two-phase/one-time protocol shared by APK and Magisk; verified process/firewall teardown precedes allowlisted removal of module, state, install/update/recovery and legacy artifacts; APK bytes are preserved while app-owned data is reset; reboot is mandatory |
| App hot/first-install update | `UpdateManager`, `UpdateExecution`, `UpdateLockProtocol`, `UpdateTransactionProtocol` | update guard plus lifecycle scripts | repository-bound GitHub release/CDN URL allowlist, exact hash/package/current-signer-or-forward-lineage/primary-ABI checks, and APK/module version plus versionCode binding to the selected release; only the canonical primary ARM/ARM64 ABI accepted by the installer is eligible; combined downloads and package preflights finish before module mutation, followed by immediate APK revalidation at a cancellation-safe installer handoff after any confirmed module commit; the installed module version is re-read under lock to reject stale same/newer installs and all downgrades, with identity loss and same-version replacement limited to an explicitly checked repair; hot update first requires all exact update/backup/failed/recovery paths to be absent under the live owner and never blindly removes a pre-existing transaction tree, removes installer-only `customize.sh`, applies and verifies the complete canonical installed runtime-manifest mode plan before `candidate_ready`, then moves active-to-backup and candidate-to-active only through repeated owner-lock/transaction-digest CAS plus full tree/disable-marker predicates immediately around each move; first install rejects a conflicting `modules_update` publication, delegates extraction/default staging to Magisk, and accepts completion only after a cancellation-safe post-install check binds the exact full candidate in `modules_update` (not Magisk's live metadata stub) to the selected version and archive SHA-256; every lifecycle execution and terminal commit revalidates the root-owned non-link active directory, directly invoked scripts plus sourced `common.sh`/`command-builder.sh`, exact critical file modes/links and required generation metadata; rollback stop/copy/retention is bound to the live owner and exact journal digest and revalidates both active and backup trees at each mutation boundary; incomplete rollback evidence separately reports the exact safe backup and exact retained journal digest while stating that the live app owner is released for fresh recovery; update lock v2, transaction v3, cleanup v2, owner/digest CAS, terminal ambiguity preserved for recovery; non-cancellable owner release precedes and is structurally isolated from app-private staging cleanup, while download/preflight cleanup isolates each file deletion and connection disconnect so a cache failure cannot orphan ownership or mask the typed result; the private non-exported installer provider exposes only canonical `cache/updates/` artifacts and never the cache root |
| Interrupted update recovery | `ModuleUpdateRecovery` | update guard, status/start/stop scripts | Exact lock handoff; transaction/cleanup bytes must reconstruct to the same SHA-256 and remain unchanged across the read before schema validation; owner rebind binds the generated bytes to a precomputed digest before replacement; directory classification rejects links, non-root ownership and unexpected modes; every retained-backup copy/move/promotion repeats the live-owner/journal-digest guard and exact source/destination tree predicates immediately before mutation; lifecycle execution and terminal cleanup revalidate the exact safe active tree including sourced helpers, disable expectation and generation policy; retained backup restore, post-state verification and authenticated terminal cleanup remain retryable; acquisition/release handoffs have no early normal-return path around cancellation fences, so cancellation arriving during non-cancellable marker retirement is restored only after ownership is resolved |
| Install/update preservation | `ModulePackageContract`, `ModuleUpdatePreservation` | `customize.sh`, runtime manifest, `package-contract.sh` | Preserve runtime/category/bootstrap settings, hostlists, disable marker, the exact safe bounded command-line file selected by `runtime.ini`, and only approved bounded user Lua extension points; command preservation accepts only root-owned single-link `0600/0644` sources and verifies the normalized root-owned `0644` target, so update paths cannot legitimize a world-writable input. Never restore packaged core Lua or permit a configured user file to collide with release-owned content. READY and installed-tree validation also require an active command-line file or compatible preset to exist with safe ownership/mode. Kotlin and shell require the same exact 42-entry non-negotiable runtime/provenance minimum, preventing a release-controlled manifest from legitimizing an incomplete lifecycle/recovery/configuration/strategy/Lua/wrapper/binary surface. Local and CI ZIPs are assembled only from the declared closure and reject recovery metadata; exhaustive package/catalog validation stays in build/CI and APK preflight, while the boot-mode `customize.sh` performs only ABI selection, bounded preservation, generation publication and lifecycle serialization. Custom recovery flashing is intentionally unsupported because Magisk removes the live module before customization |
| Category execution | `StrategyRepository`, `ControlDiagnosticsRepository` | `command-builder.sh`, build/hot-update precommit | Category syntax and bindings are exact across APK/module: duplicate or unknown fields, unsafe or missing filter files, invalid inline domains, missing strategies and zero enabled categories fail closed. The module never silently drops a filter or substitutes a global default, and the exact machine validator gates candidate publication and READY state |
| Installed package identity | `ControlDiagnosticsRepository`, `ModulePackageContract` | root-secure `module.prop` read and critical installed-mode probe | READY/DISABLED requires the full canonical module identity, update channel, internally matching `vMAJOR.MINOR.versionCode`, the generated installed binary, every mandatory lifecycle/helper executable, exact critical `0644`/`0755` modes, a safe root-owned module directory and an exact empty root-owned `0600` disable marker; the nonexistent legacy `webRoot`/KSUWebUI surface is rejected and the module action exposes only the two-press canonical purge flow; damaged metadata or modes are classified PARTIAL and offered a repair update |
| Boot | no Android receiver | `service.sh` | Shell is the sole boot owner; update/removal/disable fences and recovery contracts are checked before start |
| Uninstall | no Android mutation | `uninstall.sh` | Persistent tombstone, bounded stop retries, exact clean process/firewall audit, authenticated rollback retirement and owned-only removal |

## Protocol invariants

- owner state: v6, exact 33-field current writer; v3–v5 are read-only recovery input.
- lifecycle mutation lease: cross-process owner identity and release proof.
- update lock: v2 current writer; legacy versions are parser/recovery input only.
- update transaction: v3 current writer with monotonic phases and owner/digest CAS.
- cleanup pending: v2 current writer with authenticated path allowlist and digest.
- active candidate promotion and recovery owner rebind: exact live owner plus transaction-digest CAS; generated journal bytes are bound to their precomputed digest before replacement.
- recovery directory classification and every update/recovery lifecycle command reject symlink, non-root-owned, unexpected-mode, hard-linked or generation-invalid active trees before execution or terminal deletion; the trusted lifecycle set includes `common.sh` and `command-builder.sh` because the entry scripts source them.
- rollback/recovery directory copies and moves repeat the live owner plus exact transaction-digest guard and validate active/backup/recovery identities immediately before each mutation instead of trusting an earlier classification snapshot.
- every lifecycle script argument is represented as `List<String>` and individually
  shell-quoted; callers do not append raw command suffixes.
- user-controlled file names and routes must pass direct-child/simple-name policies
  before any protected path is constructed. Android and shell reject the same path
  separators, quotes, boundary whitespace and full ISO control range (including DEL),
  reserve the same 16 release-owned command-line names case-insensitively, and enforce
  the filesystem's 255-byte UTF-8 component limit rather than a character-count proxy.
  Standalone package verification applies the same exact lowercase `.txt` preset-name
  policy both to runtime selection and to compatible/quarantined manifest entries.
  Android manifest/archive admission and shell manifest admission also reject every
  path component above 255 UTF-8 bytes before staging or protected publication. Both
  verifier paths also reject duplicate or file/child topology conflicts, including
  collisions that appear only after ABI-template expansion.
- manual command files are root-owned, single-link, bounded to 256 KiB and mode
  `0600`/`0644`; Lua, blob, hostlist and ipset references remain direct children of
  their exact protected module roots, and machine infrastructure failures never emit
  the exact user-content `INVALID` record.
- protected text, owner-v6 identity, and update transaction/cleanup reads bind parsed
  content to stable before/after SHA-256 evidence; hostlist search derives its count
  and bounded page from one file pass instead of combining two independently changing reads.
- release metadata, module archives, downloads, digest passes, and imported hostlists use
  one guaranteed-progress stream primitive; nonconforming zero-progress bulk reads cannot
  create a busy loop, while bounded consumers remain overflow-safe. Download percentages
  accept only byte counts and HTTP lengths inside the enforced artifact budget and remain
  in `0..100` even when a server under-reports `Content-Length`; presentation independently
  normalizes the typed progress object before it reaches Compose state. Artifact GETs follow
  only `301`, `302`, `303`, `307`, or `308`, resolving and revalidating every next URL against
  the HTTPS GitHub/release-CDN boundary.
- dynamic Control diagnostics, including restored state, pass the shared credential,
  private-identifier, local-address and app-private-path redactor before display or
  persistence; unexpected exceptions map to typed generic outcomes. A complete ViewModel/UI
  source sweep leaves Control as the only technical-diagnostic presenter; every other screen
  uses resource-backed `UiText` or exact trusted runtime/user data. Fatal update recovery
  details retain localized context across process death only as an allowlisted formatted
  resource plus one separately bounded/redacted argument; invalid pairs fail closed.
- localization policy covers both Compose call sites and ViewModel-produced `UiText`:
  stable app-owned copy must use resources, while exact runtime/user data and bounded,
  redacted diagnostics may remain dynamic. Config and Strategies runtime-mutation
  adapters rethrow coroutine cancellation before mapping ordinary failures.
- multi-step snapshot/write/verify/restore flows use the coordinator-owned
  `withNonCancellableMutation` boundary. Cancellation remains normal and immediately
  propagating before admission, while an admitted transaction keeps its cross-process lease
  until a commit or rollback outcome exists; ViewModels no longer carry their own scattered
  cancellation fences.
- Control applies the same boundary to every admitted runtime setting mutation, rather than
  only packet-limit changes: autostart, both packet limits, and one-time `wifi_only`
  normalization all finish their write/post-state contract after admission.
- Preset save/apply treats thrown mutating calls as ambiguous outcomes, not plain I/O failures:
  candidate artifacts are cleaned, a replaced or unreadable target is restored from its exact
  snapshot, and config plus preset-file rollback are both attempted even if either one fails.
- Config Editor also treats a thrown post-write cmdline snapshot or validator as failed proof of
  persistence and restores its captured manual-command snapshot before returning a typed result.
- Strategies converts exceptions during category or preset-mode compensation into failed rollback
  proof, while preserving cancellation, so an already-mutated category transaction cannot be
  downgraded to an ordinary save failure.
- Protected cmdline, hostlist, and hosts-overlay adapters distinguish coroutine cancellation from
  ordinary I/O exceptions across write, post-write read, validation, cleanup, and restore steps;
  cancellation is never converted into a normal failed result.
- Lifecycle start, stop, restart, and full rollback complete their admitted shell command plus
  authoritative status/post-state verification under a non-cancellable fence; caller cancellation
  is rethrown only after the lifecycle outcome is known and the serialization lock can be released.
- Hot-update compensation has the same rule for ordinary failures as explicit cancellation: after
  a transaction exists or the service has stopped, rollback/restore completes under
  `NonCancellable`, recovery-artifact retention is decided, and caller cancellation is restored
  only after that durable compensation outcome.
- Confirmed log clearing uses the same admitted-operation fence for its one- or two-file command,
  then restores caller cancellation; every surviving path is rechecked as a root-owned, single-link
  `0600` regular file after truncation and permission repair.
- update-check failures cross the data/presentation boundary as typed offline, timeout,
  TLS, HTTP, metadata-size/content, or generic request reasons. Control localizes each
  reason directly; Russian UI no longer embeds an English exception message. Missing
  release notes also remain empty data until the update dialog applies its localized fallback.
- update execution uses typed download, validation, unsupported-ABI, installer, module
  recovery/rejection/installation, and deferred reasons. The data layer no longer builds
  English `Module:`/`APK:` terminal prose; Control resolves app-owned reasons from resources
  and composes them inside localized partial-update copy. All 18 APK/module validation outcomes
  are typed and localized before presentation; only bounded, redacted recovery diagnostics
  remain dynamic.
- Android logcat output is confined to `AppDebugLog`; every call is guarded by
  `BuildConfig.DEBUG`, while release user diagnostics remain typed, bounded and redacted.
- `Application.onCreate` configures libsu but performs no root command; the active
  screen/controller owns the single authoritative root/module probe and its UI state.
- release CI checks whitespace across the complete tracked tree, treats Kotlin compiler,
  Android lint and custom-Lua compatibility warnings as fatal, rejects tracked secret
  containers/private-key PEM blocks, and requires a non-debuggable release build bound
  only to the fail-closed release signing configuration with R8 code optimization and
  resource shrinking enabled together. The project does not override the locked libsu
  AAR's targeted consumer rules with a broad package keep.

Primary regression sources are `AdaptiveShellPolicyTest`, `PrivilegedProtocolParityPolicyTest`,
`OwnerStateSchemaTest`, `LifecycleMutationLockProtocolTest`, `UpdateLockProtocolTest`,
`UpdateTransactionProtocolTest`, `ModuleUpdateRecoveryTest`, `ReleaseCiPolicyTest`, the
shell protocol suites, and the opt-in device harness. These are coverage artifacts, not
pass evidence until executed against the synchronized tree.

## Intentional compatibility allowlist

- Owner v3/v4/v5 and legacy update/cleanup parsers exist only to classify or recover
  already-persisted state; no current writer emits them.
- API-specific `values-v26`, `values-v27`, `values-v29`, `values-v31` and night themes
  exist only for correct platform bar/startup behavior on API 24+.
- The final resource tree contains only eight reachable file-backed launcher/provider/backup-policy
  resources plus the platform theme/value catalogs; every one of the 510 default
  string/plural IDs has a production consumer, all 510 unique Kotlin `R.*` references and
  22 manifest/resource XML references resolve against that tree, and all XML parses.
  `ResourceLinkagePolicyTest` locks this post-legacy linkage graph.
- `FileProvider` exists only for a private cache-backed APK installation URI.
- `config.sh` and the old temporary user configuration may be read only by the
  explicit migration/bootstrap path, never as live runtime fallback.
- The module shell surface has no declaration-only production functions after removal
  of ten superseded config, firewall-health, teardown and legacy-migration helpers;
  current lifecycle code uses the owner-v6 and journal-bound paths above.

## Android security source audit

- The manifest declares exactly `INTERNET`, `ACCESS_NETWORK_STATE`, and
  `REQUEST_INSTALL_PACKAGES`. `MainActivity` is the only exported component and has
  only the launcher filter; no deep link, service, receiver, or activity alias exists.
- The non-exported `FileProvider` maps only `cache/updates/`. Installer handoff accepts
  only the canonical bounded APK filename in that directory, immediately reasserts
  exact `0600`, grants read access explicitly, and follows a fresh package/version/
  signer validation.
- `allowBackup=false` is backed by explicit deny-all legacy `fullBackupContent` rules
  and Android 12+ cloud/device-transfer `dataExtractionRules`, so OEM D2D behavior
  cannot silently copy app-private state. Cache artifacts are independently excluded
  by the platform.
- Cleartext is disabled. Release metadata does not follow redirects; artifacts start
  at the exact repository release path, redirects stay on the reviewed GitHub CDN
  host allowlist, and every download is bounded and bound to release SHA-256 plus APK
  signing identity or the module package contract.
- About can open only four enum-owned HTTPS destinations. Log sharing uses an explicit
  chooser only after shared redaction and tail bounding. Privileged dynamic arguments
  remain typed, validated and individually shell-quoted; direct-child filesystem
  boundaries reject traversal and link replacement.
- An OSV query on 2026-07-22 found no advisory match in the 126 coordinates locked into
  `releaseRuntimeClasspath`. The broader 585-component verification catalog did expose
  advisory matches in internal Unified Test Platform/build-host coordinates and stale
  metadata not present in the current lockfile. Those host-tool findings are not APK
  runtime dependencies, but final security closure still requires a synchronized
  Gradle toolchain resolve/upgrade and a repeated vulnerability scan.

## Evidence still required before release completion

The following remain open by user direction and must be performed on one stable tree:

1. Strict dependency verification followed by unit tests, compile, lint and APK/module
   assembly; record exact tool versions, counts, warnings, sizes and hashes.
2. Compose semantics and screenshot/golden matrix once canonical dependencies are
   available, followed by manual compact/expanded, light/dark/dynamic, RTL, Russian
   200%, reduced-motion and TalkBack review.
3. Linux shell behavioral suite and symlink/permission/package contract gates.
4. Signed CI artifact provenance and signer-certificate verification.
5. Opt-in rooted-device install/start/stop/restart/update/rollback/reboot/uninstall
   matrix, including capability drift, interruption and IPv4/IPv6 ownership cleanup.

Until all five evidence groups pass, the source implementation may be called complete,
but the release and the full user goal must remain unverified.
