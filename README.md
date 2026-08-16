# DSH for Android

**[English](README.md) | [中文](README.zh.md)**

![Version](https://img.shields.io/badge/version-v0.11.0--a16--tablet-blue)
![Platform](https://img.shields.io/badge/platform-Android%2016%20(API%2036)-green)
![Arch](https://img.shields.io/badge/arch-arm64-orange)
![License](https://img.shields.io/badge/license-MIT-yellow)

**DeepSeek Harness (dsh) natively ported to Android 16 tablets** — a deep customization based on `dsh-mobile-apk` that brings the full **"Everything is a Plugin"** architecture of DeepSeek Harness to a tablet, out of the box.

> dsh is the agent framework open-sourced by DeepSeek AI (v0.1.0-rc.5, MIT, developer preview). This project packages it with an embedded Termux runtime snapshot and a WebView shell, so you get the complete dsh experience — model adapters, tool registry, agent loop, plugin system — directly on your Android 16 tablet, with no Termux or external dependencies.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Technical Path](#technical-path)
- [Directory Structure](#directory-structure)
- [Quick Start](#quick-start)
- [Build from Source](#build-from-source)
- [Key Technical Points](#key-technical-points)
- [Online Updates](#online-updates)
- [Roadmap](#roadmap)
- [License](#license)

---

## Features

| Capability | Description |
|------------|-------------|
| **Out of the box** | Install the APK directly; the embedded runtime snapshot (~75MB) is extracted automatically on first launch (~10s). No Termux or external environment needed. |
| **Android 16 Desktop Windowing** | `resizeableActivity` + extended `configChanges`; free window resizing / split-screen / rotation without Activity recreation. |
| **Tablet centered layout** | On sw600dp+ or desktop-window mode, the WebView is width-limited and centered (max 1280dp) with dark side backgrounds — a desktop-browser-like experience. |
| **JS Bridge environment awareness** | `getWindowMode()` / `getScreenInfo()` / `setOrientation()` let Web UI plugins build responsive layouts. |
| **Full dsh core** | Web service, headless mode, plugin management, HMR (`--expose-internals`), node-pty subprocess/PTY, session persistence. |
| **Background keep-alive** | Foreground service + watchdog that auto-restarts a crashed engine process. |
| **Online updates** | Manifest-driven runtime snapshot updates — upgrade the dsh engine and plugins without updating the APK. |
| **File access** | SAF (Storage Access Framework) directory bridging for safe access to tablet storage. |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android 16 Tablet (API 36)               │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  MainActivity (WebView container)                     │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │  dsh Web UI (http://127.0.0.1:3080)            │  │  │
│  │  │  window.androidBridge  ←→ native layer          │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  EngineService (foreground service)                  │  │
│  │  ├─ engine process management (node dsh web)          │  │
│  │  ├─ Watchdog (auto-restart on abnormal exit)          │  │
│  │  └─ snapshot extraction/verification (~10s first run) │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Embedded runtime snapshot (app-private dir)          │  │
│  │  ├─ node / bash / coreutils (Termux builds)           │  │
│  │  ├─ dsh itself + plugins (node_modules)               │  │
│  │  ├─ termux-exec (LD_PRELOAD to bypass exec limits)    │  │
│  │  └─ dshdata (session/config persistence)              │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  UpdateManager (manifest-driven online updates)       │  │
│  │  SAF directory bridge (Storage Access Framework)      │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**Key modules**

| Module | Responsibility |
|--------|----------------|
| `MainActivity` | WebView container, JS Bridge registration, tablet/desktop window layout adaptation, orientation control |
| `EngineService` | Foreground service managing the dsh engine process lifecycle |
| `EngineManager` | Snapshot extraction, engine start/stop, health checks, watchdog restart |
| `AndroidBridge` | JS Bridge interface layer (file picker, notifications, keep-screen-on, window mode, screen info, orientation) |
| `UpdateManager` | Manifest-driven online runtime snapshot updates |
| `ConsoleActivity` | Terminal console (PTY session, for debugging / command-line use) |
| `LogCollector` | Developer debug logs (written daily to dshdata/log/) |

---

## Technical Path

How dsh gets from a Node.js CLI to a native Android tablet app:

```
dsh (Node.js CLI, "Everything is a Plugin")
        │
        ▼
① Embed a Termux runtime snapshot
   └─ node + bash + coreutils + dsh + plugins, packaged into the APK assets
        │
        ▼
② Host the dsh Web UI in a WebView
   └─ engine starts `node dsh web` on 127.0.0.1:3080
   └─ WebView loads the UI, JS Bridge (window.androidBridge) for native calls
        │
        ▼
③ Keep it alive
   └─ foreground service + watchdog (HTTP probe, auto-restart)
        │
        ▼
④ Adapt to Android 16
   └─ desktop windowing (resizeableActivity + configChanges)
   └─ tablet centered layout (sw600dp+ / desktop mode, max 1280dp)
   └─ JS Bridge: getWindowMode() / getScreenInfo() / setOrientation()
        │
        ▼
⑤ Ship & update
   └─ debug-signed release APK, directly installable
   └─ manifest-driven online snapshot updates (engine/plugins, not the APK)
```

**Porting decisions**

| Decision | Choice | Why |
|----------|--------|-----|
| Runtime | Embedded Termux snapshot | Out of the box; no external dependency; ~10s first-launch extraction |
| UI | WebView hosting official dsh Web UI | Zero UI rewrite; full feature parity |
| Communication | JS Bridge (`window.androidBridge`) | Web UI calls native capabilities (file picker, notifications, window mode, etc.) |
| Keep-alive | Foreground service + watchdog | Engine survives backgrounding; auto-restart on crash |
| Updates | Manifest-driven snapshot updates | dsh is a fast-moving developer preview; upgrade engine/plugins without APK updates |
| Sandbox | bubblewrap fallback chain | Android SELinux forbids user namespaces; graceful degradation (see below) |

---

## Directory Structure

```
DSH-for-Android/
├── app/                          # Android project source
│   └── src/main/
│       ├── java/com/dshmobile/shell/   # MainActivity / EngineService / AndroidBridge etc.
│       └── assets/               # runtime snapshot (snapshot.tar.xz, placed at build time)
├── docs/
│   ├── DeepSeek-Harness-Android16-Port-Plan.md        # full technical plan (EN)
│   └── DeepSeek-Harness-Android16-移植技术方案.md      # full technical plan (ZH)
├── releases/
│   └── dsh-mobile-a16-tablet-arm64.apk                # installable APK
├── gradle/                       # Gradle wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── LICENSE                       # MIT
```

---

## Quick Start

### Install

```powershell
adb install -r releases/dsh-mobile-a16-tablet-arm64.apk
```

Or copy the APK to the tablet and tap to install. The APK is debug-signed, so it installs directly without extra steps.

### First Run

1. Open the dsh app; the runtime snapshot is extracted automatically on first launch (~10s, progress shown on the guide view).
2. Once the engine is up, the WebView loads `http://127.0.0.1:3080` automatically.
3. Configure models, tools, and plugins in the Web UI and start using it.

### Verification Checklist

| Feature | How to verify |
|---------|---------------|
| dsh web service | Web UI loads and completes a conversation turn |
| headless mode | Run `dsh headless` in ConsoleActivity |
| Plugin management | `dsh plugin list` / install / uninstall |
| HMR | Start with `--expose-internals`, modify a plugin file, observe hot reload |
| PTY/subprocess | Trigger a Bash tool to execute code |
| Session persistence | Restart the app and confirm sessions are restored |
| Desktop windowing | Resize the window freely in tablet desktop mode; layout adapts |
| Background keep-alive | Switch to background; engine is not killed, watchdog works |
| Online updates | Trigger a manifest update; snapshot swaps atomically |

---

## Build from Source

Requirements: JDK 17, Android SDK (platform 36 / build-tools 36.0.0), Gradle 8.13.

```powershell
# 1. Create local.properties pointing to your SDK
# sdk.dir=C:/path/to/android-sdk

# 2. Place the runtime snapshot into assets (arm64 tablet ABI)
# Snapshot source: snapshot-arm64.tar.xz from the dsh-mobile-apk project releases
# Then update app/src/main/assets/snapshot.sha256 to the matching SHA-256

# 3. Build
gradle assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (release is signed with the debug key for direct installation).

> **Note on the snapshot**: `app/src/main/assets/snapshot.tar.xz` (~75MB) is the embedded runtime snapshot. Due to its size it is **not** committed to this repository. Before building, download `snapshot-arm64.tar.xz` from the [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) releases, place it in the assets directory, and update the `snapshot.sha256` fingerprint. The prebuilt APK in `releases/` already contains the snapshot and is ready to use.

---

## Key Technical Points

### Android 15/16 `exec()` restriction

With `targetSdk >= 35`, Android forbids `exec()` of non-app-owned native libraries, which would break the embedded node/bash and every subcommand. This project:

- Keeps `targetSdk = 34` (the safest current choice);
- Embeds **termux-exec** (an `LD_PRELOAD` wrapper) that redirects `exec*` calls to real binaries in the app-private directory, reserving a path for raising targetSdk in the future.

### SELinux vs. bubblewrap sandbox

dsh's Bash sandbox depends on bubblewrap (bwrap), whose core is user namespaces (`unshare(CLONE_NEWUSER)`). Android's SELinux policy forbids ordinary apps from creating user namespaces, so bwrap fails outright. This project provides a **graceful fallback chain**:

1. **Preferred**: disable the sandbox and run directly — isolation is backed by the Android app sandbox (app-private dir + SELinux app domain);
2. **Alternative**: proot (emulates chroot via ptrace, no user namespaces);
3. **Backup**: Termux's `termux-am` helper mechanism;
4. **Fallback**: capability detection at startup — auto-degrade and log, so functionality is never interrupted.

### 16KB page size (mandatory on Android 16)

Android 16 requires all native libraries to be 16KB-aligned. `targetSdk=34` relaxes part of the mandatory checks; a `zipalign -P 16` step is planned for the snapshot build pipeline to fully resolve this.

### Build environment

- compileSdk = 36 (Android 16), minSdk = 26, targetSdk = 34
- JDK 17, Gradle 8.13, Android SDK platform 36 / build-tools 36.0.0

---

## Online Updates

`UpdateManager` fetches a remote manifest (snapshot version, SHA-256, download URL), verifies it, downloads the new snapshot, and swaps it atomically (the old snapshot is kept as a rollback point). Only the engine and plugins are updated — not the APK shell — matching dsh's fast iteration during the developer preview.

---

## Roadmap

1. **Responsive UI plugin**: wrap `getWindowMode()` / `getScreenInfo()` into a dsh plugin so the Web UI natively senses tablet window state.
2. **16KB-aligned snapshot**: add 16KB alignment to the build pipeline to fully satisfy Android 16's mandatory requirement.
3. **targetSdk bump**: raise targetSdk to 35/36 once the termux-exec approach matures, for Play compliance.
4. **Sandbox enhancement**: explore user-namespace options available on Android (e.g., rooted devices) to restore full bubblewrap isolation.
5. **Python SDK bridge**: complete the cross-device Python-driven dsh calling bridge.
6. **Multi-ABI support**: build an x86_64 snapshot for Android emulator debugging.

---

## License

MIT License. dsh itself is MIT-licensed; all third-party dependencies (Termux runtime, node-pty, bubblewrap, etc.) satisfy distribution requirements.
