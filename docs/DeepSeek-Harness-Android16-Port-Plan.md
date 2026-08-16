# DeepSeek Harness Native Port to Android 16 Tablets — Technical Plan

> Version: v1.0 ｜ Target: Android 16 (API 36) tablets ｜ Deliverables: APK + this document
> Baseline: DeepSeek Harness (dsh) v0.1.0-rc.5 (open-sourced 2026-08-13, MIT license, developer preview)

---

## 1. Background & Core Understanding

DeepSeek Harness (dsh) is an agent framework open-sourced by DeepSeek AI. Its core design philosophy is **"Everything is a Plugin"**. Model adapters, the tool registry, session logs, and even the core agent loop itself are all pluggable, replaceable plugins. This design is built on the **Cordis plugin framework** (vendored inside dsh):

- **Service & Context architecture**: plugins collaborate through declarative dependencies (inject);
- **Typed events**: supports emit / waterfall / parallel / serial event patterns;
- **Reversible side effects**: all plugin registrations are reversible, guaranteeing state consistency during hot reload and unload.

dsh composes a plugin tree from **Profiles** and **Bundles**. For example, the `web` profile loads `dsh-base` (core capabilities) and `dsh-web-app` (Web UI). The default `npx @deepseek-ai/dsh web` starts a Web UI at `http://127.0.0.1:3080`. A full agent turn flows through core packages such as `session → system-prompt → tools → agent → agent-loop`.

**Key insight for the port**: porting dsh is not simply packaging a Node.js app into an APK. The critical part is to **fully preserve and gracefully expose its "Everything is a Plugin" architecture**, so users enjoy the same flexibility of configuration, composition, and extension on a tablet.

---

## 2. Technology Selection

### 2.1 Overall Approach: Embedded Runtime Snapshot + WebView Container

| Dimension | Choice | Rationale |
|-----------|--------|-----------|
| Runtime | **Embedded Termux runtime snapshot** (Node.js + bash + coreutils + dsh itself packaged into the APK) | Out of the box, no dependency on Termux or other external environments; ~10s first-launch extraction |
| UI container | **Android WebView** hosting the dsh Web UI | Reuses all capabilities of the official dsh Web UI, zero UI rewrite cost |
| Two-way communication | **JS Bridge** (`window.androidBridge`) | Web UI calls native capabilities (file picker, notifications, keep-screen-on, window mode, etc.) |
| Background keep-alive | **Foreground Service** + **Watchdog** | Engine process is not killed by the system; auto-restart on abnormal exit |
| Online updates | **Manifest-driven runtime snapshot updates** | Upgrade the dsh engine and plugins independently without updating the APK |
| File access | **SAF (Storage Access Framework) directory bridging** | Safe access to user-selected tablet storage directories |
| Sandbox | **bubblewrap fallback chain** (see §6.1) | Bypasses Android SELinux interception of user namespaces |

### 2.2 Reference Projects

| Reference project | What we borrow | Our enhancements |
|-------------------|----------------|------------------|
| `deepseek-harness-termux` | Android Bionic libc adaptation patches, node-pty build approach | Reuse its patch set directly to guarantee PTY/subprocess capability |
| `dsh-mobile-apk` | Embedded snapshot, WebView + JS Bridge, foreground service, watchdog, SAF bridge | Deep customization for Android 16 tablets (see §5) |

### 2.3 Build Environment

- JDK 17, Gradle 8.13, Android SDK (platform 36 / build-tools 36.0.0)
- compileSdk = 36 (Android 16), minSdk = 26, targetSdk = 34
- **Why targetSdk stays at 34**: Android 15/16 impose restrictions on the `exec()` syscall (when `targetSdk >= 35`, executing non-app-owned native libraries is forbidden). Every embedded node/bash binary and subcommand would need a linker64 wrapper to bypass this; keeping 34 lets the embedded engine `exec()` normally on Android 15/16 devices (see §6.2).

---

## 3. Architecture: Native + WebView Interaction

### 3.1 Overall Architecture

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

### 3.2 Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| `MainActivity` | WebView container, JS Bridge registration, tablet/desktop window layout adaptation, orientation control |
| `EngineService` | Foreground service managing the dsh engine process lifecycle |
| `EngineManager` | Snapshot extraction, engine start/stop, health checks, watchdog restart |
| `AndroidBridge` | JS Bridge interface layer (file picker, notifications, keep-screen-on, window mode, screen info, orientation) |
| `UpdateManager` | Manifest-driven online runtime snapshot updates |
| `ConsoleActivity` | Terminal console (PTY session, for debugging / command-line use) |
| `LogCollector` | Developer debug logs (written daily to dshdata/log/) |

### 3.3 JS Bridge Interface (`window.androidBridge`)

| Method | Direction | Description |
|--------|-----------|-------------|
| `pickDirectory(callbackId)` | Web→Native | Pick a directory via SAF and bridge it to a Termux-visible path |
| `keepScreenOn(enable)` | Web→Native | Keep screen on control |
| `notify(title, text)` | Web→Native | System notification |
| `requestAllFilesAccess()` | Web→Native | Request all-files access permission |
| `getSystemDark()` | Web→Native | Read system dark mode |
| `restartEngine()` / `reloadWebUI()` | Web→Native | Engine restart / UI reload |
| `openConsole()` | Web→Native | Open the terminal console |
| `getDevLogEnabled()` / `setDevLogEnabled()` | Web→Native | Developer log toggle |
| **`getWindowMode()`** | Web→Native | **(new)** returns phone / tablet / desktop |
| **`getScreenInfo()`** | Web→Native | **(new)** returns screen/window size, density, multi-window state as JSON |
| **`setOrientation(mode)`** | Web→Native | **(new)** lock/unlock screen orientation (portrait/landscape/auto) |

---

## 4. Core Feature Implementation

### 4.1 dsh web service

- The snapshot embeds a complete dsh installation (`node_modules` includes `@deepseek-ai/dsh` and all dependencies).
- `EngineManager` starts the engine with `node <dsh>/bin/dsh web`, listening on `127.0.0.1:3080`.
- The WebView loads `http://127.0.0.1:3080` with `setJavaScriptEnabled(true)` + `addJavascriptInterface(androidBridge, "androidBridge")`.
- A guide view (`guideView`) is shown until the engine is ready, then the Web UI loads automatically.

### 4.2 dsh headless mode

- The snapshot fully supports the `dsh headless` command, runnable directly in the `ConsoleActivity` terminal.
- A headless session entry point can also be exposed via a JS Bridge extension (future version).

### 4.3 Plugin management (dsh plugin)

- The `dsh plugin` command (install / uninstall / list) is fully available in the snapshot.
- Plugin installs are written to the persistent `dshdata` directory and survive restarts.
- The online update mechanism (§4.7) can upgrade the plugin set independently.

### 4.4 Hot reload (HMR)

- In development mode the engine starts with `--expose-internals`, enabling Cordis hot reload.
- When plugin files change, the engine reloads automatically without restarting the APK.

### 4.5 Subprocess & PTY

- Reuses the **node-pty build approach** from `deepseek-harness-termux` (adapted for Android Bionic libc).
- Components that depend on node-pty (e.g., the Bash sandbox) run natively inside the snapshot — the foundation for advanced features like code execution.
- Android adaptation of the sandbox isolation layer (bubblewrap) is covered in §6.1.

### 4.6 Session persistence

- Session data is written to `dshdata` (app-private directory) and auto-restored by the engine after app restarts.
- Snapshot updates (§4.7) never touch `dshdata`, so sessions/config survive across versions.

### 4.7 Online runtime updates (manifest-driven)

- `UpdateManager` fetches a remote manifest containing snapshot version, SHA-256, and download URL.
- After verification, the new snapshot is downloaded and atomically swapped (the old snapshot is kept as a rollback point).
- Only the engine and plugins are updated — not the APK shell — matching dsh's fast iteration (frequent breaking changes during the developer preview).

### 4.8 Python SDK support

- Python (Termux build) can be installed inside the snapshot; `python/sdk` runs directly.
- Alternatively, the SAF directory bridge + JS Bridge can provide a Python-driven dsh calling bridge (cross-device scenarios).

---

## 5. Android 16 Feature Adaptation

### 5.1 Desktop Windowing

Android 16 introduces desktop windowing; app windows can be freely resized. This plan:

1. **Manifest declaration**: `MainActivity` / `ConsoleActivity` set `resizeableActivity="true"` for free window resizing.
2. **No recreation on config changes**: `configChanges` extended with `density|smallestScreenSize|fontScale`; window drag / split-screen / rotation do not recreate the Activity, avoiding WebView state loss.
3. **`onMultiWindowModeChanged` callback**: recompute layout when entering/leaving multi-window mode.
4. **Tablet width-limited layout** (`applyTabletLayout`):
   - When `smallestScreenWidthDp >= 600` (tablet) or in multi-window/desktop-window mode, the WebView container is **width-limited and centered** (max 1280dp) with dark side backgrounds, mimicking a desktop browser;
   - Full-screen on phones / narrow windows.
5. **JS Bridge environment awareness**: `getWindowMode()` / `getScreenInfo()` let Web UI plugins sense window state for responsive touch layouts.

### 5.2 Large-screen / split-screen / free-window touch optimization

- The Web UI uses `getScreenInfo()` to obtain window size and density, dynamically adjusting touch target sizes, sidebar collapse, and content column count.
- Split-screen automatically switches to a compact layout; free windows adapt to window shape.

### 5.3 Background keep-alive & stability

- **Foreground service**: `EngineService` stays resident; the engine process is not killed in the background.
- **Watchdog**: `EngineManager` periodically health-checks (HTTP probe of `127.0.0.1:3080`) and auto-restarts the engine on abnormal exit.
- **Notification**: a persistent foreground-service notification lets users perceive engine status.

### 5.4 16KB page size (mandatory on Android 16)

Android 16 requires all native libraries to be 16KB-aligned. Countermeasures:

- Native libraries in the snapshot (node, bash, libtermux-exec, etc.) must be built 16KB-aligned;
- If some libraries are not aligned, `targetSdk=34` bypasses part of the mandatory checks (Android 16 relaxes 16KB checks for apps with targetSdk < 35);
- A `zipalign -P 16` step will be added to the snapshot build pipeline to fully resolve this.

### 5.5 Screen orientation control

- JS Bridge `setOrientation()` supports portrait / landscape / auto.
- On tablet desktop-window mode, `auto` is recommended (windows resize freely; orientation follows window shape).

---

## 6. Challenges & Countermeasures

### 6.1 SELinux interception of the bubblewrap sandbox (key issue)

**Problem**: dsh's Bash sandbox depends on bubblewrap (bwrap), whose core is user namespaces (`unshare(CLONE_NEWUSER)`). Android's SELinux policy **forbids** ordinary apps from creating user namespaces, so bwrap fails outright.

**Countermeasure (graceful fallback chain)**:

1. **Preferred: disable the sandbox and run directly**. Turn off the bubblewrap sandbox in dsh config; Bash tools execute directly as subprocesses. Full functionality; isolation is backed by the Android app sandbox (app-private dir + SELinux app domain).
2. **Alternative: proot**. proot emulates chroot via ptrace and does not depend on user namespaces; it runs on Android and provides lightweight filesystem isolation.
3. **Backup: Termux's termux-am approach**. Use Termux's `termux-am` helper mechanism to raise capabilities in restricted scenarios.
4. **Fallback: capability detection**. At engine startup, probe bwrap availability; if unavailable, automatically degrade to option 1/2 and log it, so functionality is never interrupted by a missing sandbox.

### 6.2 Android 15/16 exec() restrictions

**Problem**: with `targetSdk >= 35`, Android forbids `exec()` of non-app-owned native libraries, breaking the embedded node/bash and all subcommands.

**Countermeasure**:
- Keep `targetSdk = 34` (current choice, safest);
- Also embed **termux-exec** (an `LD_PRELOAD` wrapper) that redirects `exec*` calls to real binaries in the app-private directory, reserving a path for raising targetSdk in the future.

### 6.3 Snapshot size & first launch

- Snapshot ~75MB; total APK size ~78MB.
- First-launch extraction takes ~10s, with progress shown on the guide view.
- SHA-256 is verified after extraction; on failure it auto-retries / rolls back.

### 6.4 dsh fast iteration (developer preview)

- dsh is in Developer Preview and may have breaking changes.
- The **manifest-driven online snapshot update** (§4.7) upgrades the engine and plugins independently, without relying on APK updates.
- Snapshot updates keep a rollback point; failed upgrades auto-revert.

### 6.5 License compliance

- dsh itself: MIT license, freely portable and distributable.
- Third-party dependencies: Termux runtime (GPLv3 — runtime-only distribution does not constitute a derivative work), node-pty (MIT), bubblewrap (LGPL), etc., all satisfy distribution requirements.
- This port does not modify dsh source; it only packages and distributes it, in compliance with the MIT license.

---

## 7. Build & Installation Guide

### 7.1 Build

```powershell
# Environment: JDK 17 + Android SDK (platform 36) + Gradle 8.13
$env:JAVA_HOME = "<jdk17 path>"
$env:ANDROID_HOME = "<sdk path>"
gradle -p project\dsh-mobile-apk assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`

### 7.2 Install

```powershell
adb install -r dsh-mobile-a16-tablet-arm64.apk
```

Or copy the APK to the tablet and tap to install (signed with the debug key, installable directly).

### 7.3 First use

1. Open the dsh app; the runtime snapshot is extracted automatically on first launch (~10s);
2. Once the engine is up, the WebView loads `http://127.0.0.1:3080` automatically;
3. Configure models, tools, and plugins in the Web UI and start using it.

### 7.4 Verification checklist

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

## 8. Deliverables

| Deliverable | Path | Description |
|-------------|------|-------------|
| APK installer | `dsh-mobile-a16-tablet-arm64.apk` | Android 16 tablet arm64, installable directly |
| Technical plan | this document | Technology selection, architecture, implementation, adaptation, challenges & countermeasures |
| Customized source | `project/dsh-mobile-apk/` | Android 16 tablet deep-customized build based on dsh-mobile-apk |

---

## 9. Roadmap

1. **Responsive UI plugin**: wrap `getWindowMode()` / `getScreenInfo()` into a dsh plugin so the Web UI natively senses tablet window state.
2. **16KB-aligned snapshot**: add 16KB alignment to the build pipeline to fully satisfy Android 16's mandatory requirement.
3. **targetSdk bump**: raise targetSdk to 35/36 once the termux-exec approach matures, for Play compliance.
4. **Sandbox enhancement**: explore user-namespace options available on Android (e.g., rooted devices) to restore full bubblewrap isolation.
5. **Python SDK bridge**: complete the cross-device Python-driven dsh calling bridge.
6. **Multi-ABI support**: build an x86_64 snapshot for Android emulator debugging.
