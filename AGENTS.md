## Project Overview

Magisk module for Android DPI (Deep Packet Inspection) bypass using nfqws2 with Lua strategies. Based on a pinned, reviewed [bol-van/zapret2](https://github.com/bol-van/zapret2) revision.

**Target Platform:** Android with Magisk root (20.4+)
**Core Binary:** nfqws2 (compiled for Android ARM64/ARM)
**Bypass Method:** NFQUEUE packet manipulation

---

## Project Structure

```
magisk-zapret2/
├── android-app/              # Android Kotlin app
│   └── app/src/main/
│       ├── java/com/zapret2/app/   # Kotlin sources
│       │   ├── MainActivity.kt     # Adaptive Compose app shell
│       │   ├── ui/navigation/      # Compose NavHost and destinations
│       │   ├── ui/screen/          # Material 3 Expressive screens
│       │   ├── ui/components/      # Shared Compose components
│       │   ├── viewmodel/          # Screen state holders
│       │   ├── ProfileRepository.kt # Active preset profile editing
│       │   ├── UpdateManager.kt    # App updates
│       │   └── ...
│       └── res/                    # Strings, themes, icons, and other Android resources
├── zapret2/
│   ├── scripts/              # Shell scripts
│   │   ├── zapret-start.sh   # Main startup script, applies iptables
│   │   ├── zapret-stop.sh    # Stop script
│   │   └── zapret-status.sh  # Status checker
│   ├── lists/                # Hostlist files (.txt)
│   ├── lua/                  # Lua libraries
│   │   ├── zapret-lib.lua    # Core library functions
│   │   ├── zapret-antidpi.lua # DPI bypass functions
│   │   └── zapret-auto.lua   # Auto-detection helpers
│   ├── presets/              # Complete TXT launch presets
│   ├── strategy-catalogs/    # TCP/UDP/voice/http80 editor templates
│   ├── runtime.ini           # Authoritative runtime configuration
│   └── scripts/command-builder.sh # Safe preset compiler/launcher
├── module.prop               # Magisk module info
├── customize.sh              # Install script
├── service.sh                # Boot script
├── action.sh                 # Volume key action
└── uninstall.sh              # Cleanup on removal
```

---

## Key Files

| File | Purpose |
|------|---------|
| `presets/*.txt` | Complete launch presets; profiles are split by `--new` |
| `strategy-catalogs/*.txt` | Protocol-specific strategy templates for the profile editor |
| `zapret-start.sh` | Main startup script, applies iptables rules |
| `runtime.ini` | Authoritative live runtime configuration |
| `command-builder.sh` | Validates a preset and compiles a private argv artifact |
| `ProfilesScreen.kt` | Active preset profile and strategy editing UI |
| `HostlistsScreen.kt` | Hostlist browser UI |
| `ControlScreen.kt` | Start/Stop service controls |

---

## Build

### Android App
Android app builds via GitHub Actions on push to main.

Release builds intentionally fail closed unless `android-app/keystore.jks` exists and
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` are set. CI additionally requires
`KEYSTORE_BASE64` and `APK_SIGNING_CERT_SHA256`; the latter must be the expected signer
certificate SHA-256 digest. CI verifies the APK signature and publishes SHA-256 files
for both distributables.

```bash
cd android-app
./gradlew assembleRelease
```

### Magisk Module ZIP
```bash
./build.sh 2.0.0 dist
```

The local builder requires prebuilt, non-empty ARM binaries. Release artifacts are
created by the pinned CI workflow, which also validates the full package contract.
`version.properties` is the sole release-version source. Versions advance explicitly
as `2.0.0`, `2.0.1`, and so on; minor and major transitions are manual. Android
`versionCode` must equal `MAJOR*1000000 + MINOR*10000 + PATCH`, so its ordering is
identical to SemVer and independent of CI runs, Git history, or wall-clock time.

### Production publication workflow

When the user asks to synchronize or deploy production, use this order:

1. Validate the intended source changes, commit them, and push `main` first so the
   pinned GitHub Actions production build starts immediately.
2. Do not wait for the remote build unless the user explicitly asks to monitor it.
   Report the run URL/status observed immediately after the push.
3. After the push, build local artifacts from that exact pushed commit when the local
   machine is available and faster. Publish them in a separate GitHub prerelease with
   a `v<version>-local` tag, the source commit SHA, and SHA-256 checksum assets.
4. Never mark the local prerelease as `Latest`, and never point `update.json` at local
   artifacts. The pinned CI release remains the canonical production/update channel.
5. A local APK must use the production signing identity and its certificate digest
   must be verified before upload. If that signer is unavailable locally, fail closed:
   publish only artifacts that can be verified and report the missing APK; never
   substitute a debug, unsigned, or differently signed APK.
6. Do not overwrite an existing tag or release. Every local publication is immutable
   and tied to one pushed commit.

---

## Architecture

### How DPI Bypass Works

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Device                           │
├─────────────────────────────────────────────────────────────────┤
│  App (Browser/YouTube/Discord)                                  │
│          │                                                      │
│          ▼                                                      │
│  ┌───────────────┐                                              │
│  │   iptables    │  (mangle table, OUTPUT/INPUT chains)        │
│  │   NFQUEUE     │  Redirect first N packets to queue          │
│  └───────┬───────┘                                              │
│          │                                                      │
│          ▼                                                      │
│  ┌───────────────┐                                              │
│  │   nfqws2      │  Userspace packet manipulation              │
│  │   + Lua       │  Apply DPI bypass strategies                │
│  └───────┬───────┘                                              │
│          │                                                      │
│          ▼                                                      │
│  ┌───────────────┐                                              │
│  │   Kernel      │  Modified packets sent to network           │
│  │   Network     │                                              │
│  └───────────────┘                                              │
└─────────────────────────────────────────────────────────────────┘
```

### Traffic Interception Flow

1. **iptables rules** capture the exact TCP/UDP port union of enabled profiles
2. **NFQUEUE** redirects packets to userspace queue (default queue 200)
3. **nfqws2** reads packets from queue, applies Lua-defined transformations
4. **Modified packets** are re-injected into kernel with DESYNC_MARK to avoid loop

---

## Android App Technologies

- **Language:** Kotlin with Jetpack Compose (no XML View screens)
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 35
- **Root:** libsu (topjohnwu)
- **UI:** Material Design 3
- **Async:** Kotlin Coroutines
- **Style:** Material 3 Expressive with adaptive light, dark, and dynamic color

### Design System

Compose screens use semantic Material color roles, expressive typography, an 8–64dp
shape scale, shared spacing/elevation/size/motion tokens, and reduced-motion support.

---

## Configuration Reference

### runtime.ini `[core]` Options

```ini
[core]
schema_version=1
config_format=runtime-v1
runtime_source=repository-defaults
autostart=1
wifi_only=0
debug=0
qnum=200
desync_mark=0x40000000
pkt_out=20
pkt_in=10
active_preset=Default v1 (game filter).txt
nfqws_uid=0:0
log_mode=none
```

`runtime.ini` selects one `active_preset`; it does not duplicate port, filter, or
strategy state. `qnum` accepts 1 through 65535. The selected TXT is the only traffic
and strategy source. No legacy configuration migration exists.

Installer and in-app package updates create a fresh release generation. They do not
copy runtime configuration, hostlists, custom presets, Lua files, disable markers, or
other state from the active tree. Runtime edits are live-data operations within one
generation; package generations are never merged.

---

## Coding Guidelines

### Bug fixes and engineering quality

Every bug must be fixed at its root cause and at the architectural boundary that
owns the violated invariant. Do not present symptom suppression as a completed
fix. No-op stubs, disabled validation, catch-all fallbacks, arbitrary retries,
hard-coded happy paths, test-only production exceptions, and similar workarounds
are not acceptable substitutes for a correct design.

### Clean-state invariant

The steady state of every project-owned system must be minimal and unambiguous.
Keep exactly one authoritative active generation and only the runtime state that
the current contract requires. Old generations, retained backups, superseded
metadata, obsolete compatibility artifacts, abandoned transaction directories,
and other project-owned residue must not remain after a successful operation or
recovery.

Temporary files or quarantine directories are allowed only inside a journaled
atomic transaction when they are required for crash safety. They must have a
single owner, a bounded lifetime, and deterministic commit/recovery cleanup.
Service-health failures after a verified software-generation commit must be
reported as lifecycle failures; they must not restore an obsolete generation or
leave duplicate trees behind. Never delete user-owned configuration or data
outside the explicit replacement/preservation contract.

### Runtime-cost invariant

Deep archive, manifest, catalog, strategy, and immutable-byte validation belongs
to CI/release qualification or the single pre-publication staging boundary. A
published generation is represented by its authenticated generation receipt.
Status polling and ordinary lifecycle observation must be constant in the size
of the package and the device process table: they may verify bounded owner/PID,
boot, and snapshot metadata, but must not rescan every package file, preset,
iptables rule through repeated subprocesses, or every `/proc` entry.
The module's typed machine-status payload is the sole app-facing lifecycle and
firewall-health contract. Android code may strictly parse and project that
payload, but must not independently reinterpret `owner.meta`, reconstruct
firewall topology, or run a competing `iptables` health audit.

Never replace files in the active Magisk module tree. Every module package
install or update must go through Magisk's pending `modules_update` path and
require a reboot, because mounted module files are not safe to modify. Live
reload is limited to explicitly user-owned runtime configuration, presets, and
lists; it must never replace packaged scripts, binaries, manifests, or wrappers.

Before changing code:

1. Reproduce the failure and identify the violated contract, ownership boundary,
   state transition, or data-flow invariant.
2. Trace why the current architecture permits the invalid state; fix that cause
   and all affected entry points consistently.
3. Add or update tests that prove both the regression and the intended contract,
   including relevant failure and recovery paths.
4. Run the complete applicable validation suite and follow newly exposed failures
   to their own root causes instead of masking them.

If the correct architectural solution is unclear, research it before editing.
Search the web and consult primary sources first: official documentation,
standards, upstream source code, specifications, and authoritative issue trackers.
Use secondary sources only as supporting context. An emergency mitigation may be
used only when explicitly requested; label it as temporary, isolate it from the
design, and document the required permanent follow-up.

### Android state and privileged boundaries

```kotlin
viewModelScope.launch {
    val result = repository.readTypedState()
    _uiState.update { current -> current.copy(result = result) }
}
```

### Rules

1. **State ownership:** Each screen uses one immutable `UiState` exposed as `StateFlow`; Compose collects it lifecycle-aware.
2. **Privileged boundaries:** Compose and ViewModels never call libsu, protected paths, or raw filesystem/process APIs directly. Root commands and protected I/O belong in typed repositories/controllers.
3. **Command safety:** Privileged arguments use the shared quoting and strict path/name validators; never interpolate user-controlled text into shell commands.
4. **Coroutines:** ViewModels use `viewModelScope`; repositories/controllers place blocking work on `Dispatchers.IO` and preserve cancellation.
5. **Mutations:** Config, lifecycle, update, hostlist, hosts, DNS, category, and preset writes use the shared mutation lock plus atomic validation/rollback contracts.
6. **Results:** Privileged operations return typed states/results and publish success only after post-operation verification.
7. **UI:** Production screens use Compose Material 3 Expressive, semantic design tokens, localized resources, reduced-motion support, and explicit loading/empty/error/disabled states.

---

## Troubleshooting

### Module installs but doesn't work
```bash
# Check if nfqws2 is running
pgrep -f nfqws2

# Check iptables rules
iptables -t mangle -L OUTPUT -n | grep NFQUEUE

# Check logs
logcat -s Zapret2
```

### NFQUEUE not supported
Check kernel support:
```bash
cat /proc/net/netfilter/nf_queue
lsmod | grep nf
```
