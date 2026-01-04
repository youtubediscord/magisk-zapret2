## MANDATORY RULES (read first!)

## CRITICAL: Agent-First Development

**You are an ORCHESTRATOR, not a developer.** Your role is to delegate ALL coding tasks to specialized agents. This saves tokens and ensures expertise.

### Rules

1. **NEVER write or edit code directly** - Always use agents
2. **NEVER read large files yourself** - Agents will do this
3. **ALWAYS explore code FIRST before making changes** - Use `code-explorer` agent
4. **Only provide brief summaries of agent results**
5. **ALWAYS run agents with `--ultrathink` flag for maximum quality**

### 0. MAIN RULE - PARALLEL AGENTS:

**ALWAYS launch 4-5 agents PARALLEL in ONE message!**

```
WRONG (slow):              RIGHT (fast):
─────────────             ─────────────
1. Launch agent 1         1. Launch agents 1,2,3,4,5
2. Wait for result           PARALLEL in one message
3. Launch agent 2         2. Wait for all results
4. Wait...                3. Done!
```

### 1. EXPLORE FIRST - UNDERSTAND BEFORE FIXING:

**BEFORE calling any editor agents, ALWAYS call code-explorer agent first!**

```
WRONG:                          RIGHT:
─────────                       ─────────
1. User asks to fix bug         1. User asks to fix bug
2. Immediately call editor      2. Call code-explorer agent first:
   agent to fix                    "How does X work? Show me
3. Agent breaks code               related files and logic"
   (didn't understand)          3. READ and UNDERSTAND the response
                                4. NOW call editor agents with
                                   full context
```

**code-explorer agent tasks:**
- "How does StrategiesFragment load strategies?"
- "Show me all files related to categories.txt"
- "What functions call parse_categories()?"
- "Explain the flow from UI click to shell script"
rm
**Only AFTER you understand the code, launch editor agents!**

### 2. AT SESSION START:
1. Read TODO.md for current tasks
2. Write your tasks to TODO.md
3. **YOU ARE MANAGER!** Don't edit code yourself, delegate to agents

### 3. ALWAYS USE AGENTS (minimum 3-5 parallel!):

| Task | Agent | Required |
|------|-------|----------|
| Code search | `code-explorer` | **REQUIRED** |
| Edit Kotlin/Android | `android-kotlin-engineer` | **REQUIRED** |
| Edit Shell scripts | `general-purpose` | **REQUIRED** |
| Large tasks (>3 steps) | `android-kotlin-engineer` | **REQUIRED** |
| After changes | `qa-reviewer` | RECOMMENDED |

### 4. NEVER DO YOURSELF:
- Don't edit files directly (use agents!)
- Don't search code via Grep/Glob directly (use code-explorer!)
- Don't launch agents ONE BY ONE (launch 4-5 parallel!)

### 5. DELEGATE PARALLEL:
- Launch 4-5 agents in ONE message
- Split task into independent parts
- Each agent gets own subtask

---

## Project Agents

| Agent | Purpose | Role |
|-------|---------|------|
| `android-kotlin-engineer` | **Main Android dev** - Kotlin, layouts, fragments | Editor |
| `qa-reviewer` | **QA** - checks quality after changes | Control |
| `zapret-source-expert` | Expert on original zapret2 (F:\doc\zapret2) | Read-only |
| `general-purpose` | Shell scripts, config files | Editor |

---

## Project Overview

Magisk module for Android DPI (Deep Packet Inspection) bypass using nfqws2 with Lua strategies. Based on [bol-van/zapret](https://github.com/bol-van/zapret).

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
│       │   ├── MainActivity.kt     # Main activity with ViewPager
│       │   ├── ControlFragment.kt  # Start/Stop controls
│       │   ├── StrategiesFragment.kt # Strategy selection UI
│       │   ├── HostlistsFragment.kt  # Hostlist viewer UI
│       │   ├── LogsFragment.kt     # Log viewer
│       │   ├── AboutFragment.kt    # About page
│       │   ├── StrategyRepository.kt # Strategy data management
│       │   ├── UpdateManager.kt    # App updates
│       │   └── ...
│       └── res/                    # Layouts, drawables, colors
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
│   ├── categories.txt        # Category -> Strategy mapping
│   ├── strategies.sh         # Strategy definitions
│   └── config.sh             # Runtime configuration
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
| `strategies.sh` | All TCP/UDP strategy definitions |
| `categories.txt` | Maps categories (youtube, discord, etc.) to strategies |
| `zapret-start.sh` | Main startup script, applies iptables rules |
| `config.sh` | Runtime configuration (QNUM, PORTS, etc.) |
| `StrategiesFragment.kt` | Strategy selection UI |
| `HostlistsFragment.kt` | Hostlist viewer UI |
| `ControlFragment.kt` | Start/Stop service controls |

---

## Build

### Android App
Android app builds via GitHub Actions on push to main.

```bash
cd android-app
./gradlew assembleRelease
```

### Magisk Module ZIP
```bash
cd magisk-zapret2
zip -r ../zapret2-magisk-v1.0.0.zip . -x "*.git*" -x "CLAUDE.md" -x "android-app/*"
```

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

1. **iptables rules** capture outgoing TCP/UDP packets on ports 80, 443
2. **NFQUEUE** redirects packets to userspace queue (default queue 200)
3. **nfqws2** reads packets from queue, applies Lua-defined transformations
4. **Modified packets** are re-injected into kernel with DESYNC_MARK to avoid loop

---

## Android App Technologies

- **Language:** Kotlin (NOT Compose, XML Views only)
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34
- **Root:** libsu (topjohnwu)
- **UI:** Material Design 3
- **Async:** Kotlin Coroutines
- **Style:** Windows 11 Fluent Design Dark

### Color Scheme

```
Background: #202020
Surface/Cards: #2D2D2D
Accent: #0078D4
Accent Light: #60CDFF
Text Primary: #FFFFFF
Text Secondary: #808080
Error: #FF6B6B
Success: #4CAF50
Corner Radius: 8dp
```

---

## Configuration Reference

### config.sh Options

```bash
# Autostart on boot (1=enabled, 0=disabled)
AUTOSTART=1

# NFQUEUE number (200-300 recommended, avoid conflicts)
QNUM=200

# Mark for processed packets (avoid iptables loop)
DESYNC_MARK=0x40000000

# Ports to intercept
PORTS_TCP="80,443"
PORTS_UDP="443"

# Packets to intercept per connection
PKT_OUT=20    # Outgoing (first 20 packets)
PKT_IN=10     # Incoming (for response analysis)

# Strategy preset
STRATEGY_PRESET="youtube"  # youtube | discord | all | custom
```

---

## Coding Guidelines

### Kotlin Coroutines in Fragments
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) {
        Shell.cmd("cat /data/adb/modules/zapret2/module.prop").exec()
    }
    if (result.isSuccess) {
        // Handle result.out
    }
}
```

### Rules
1. **Coroutines:** Always use `viewLifecycleOwner.lifecycleScope` in fragments
2. **Shell:** Use `Shell.cmd().exec()` for root commands
3. **Null Safety:** Use `?.let {}` and elvis operator `?:`
4. **IO Operations:** Always in `withContext(Dispatchers.IO)`
5. **UI Updates:** Only in Main thread via `withContext(Dispatchers.Main)`

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

---

## Resources

- [Zapret GitHub](https://github.com/bol-van/zapret) - Original project
- [Magisk Docs](https://topjohnwu.github.io/Magisk/guides.html) - Module development
- [libsu](https://github.com/topjohnwu/libsu) - Root shell library
