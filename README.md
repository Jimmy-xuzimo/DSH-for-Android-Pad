# DSH for Android

**DeepSeek Harness (dsh) natively ported to Android 16 tablets** — a deep customization based on `dsh-mobile-apk`.

This project brings the full "Everything is a Plugin" architecture of DeepSeek Harness to Android 16 (API 36) tablets with an out-of-the-box native experience: an embedded runtime snapshot (Node.js + bash + coreutils + dsh itself), a WebView hosting the dsh Web UI, JS Bridge two-way communication, a foreground service with watchdog, manifest-driven online snapshot updates, and SAF directory bridging.

## Features

- **Out of the box**: install the APK directly; the runtime snapshot (~75MB) is extracted automatically on first launch (~10s). No dependency on Termux or other external environments.
- **Android 16 Desktop Windowing**: `resizeableActivity` + extended `configChanges`; free window resizing / split-screen / rotation without Activity recreation.
- **Tablet centered layout**: on sw600dp+ or desktop-window mode, the WebView is width-limited and centered (max 1280dp) for a desktop-browser-like experience.
- **JS Bridge environment awareness**: `getWindowMode()` / `getScreenInfo()` / `setOrientation()` let Web UI plugins build responsive layouts.
- **Full core functionality**: dsh web service, headless mode, plugin management, HMR (`--expose-internals`), node-pty subprocess/PTY, session persistence.
- **Background keep-alive**: foreground service + watchdog that auto-restarts a crashed engine process.
- **Online updates**: manifest-driven runtime snapshot updates — upgrade the dsh engine and plugins without updating the APK.
- **File access**: SAF (Storage Access Framework) directory bridging for safe access to tablet storage.

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

## Build

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

## Install

```powershell
adb install -r releases/dsh-mobile-a16-tablet-arm64.apk
```

Or copy the APK to the tablet and tap to install.

## First Run

1. Open the dsh app; the runtime snapshot is extracted automatically on first launch (~10s).
2. Once the engine is up, the WebView loads `http://127.0.0.1:3080` automatically.
3. Configure models, tools, and plugins in the Web UI and start using it.

## Runtime Snapshot Notes

`app/src/main/assets/snapshot.tar.xz` (~75MB) is the embedded runtime snapshot. Due to its size it is **not** committed to this repository. Before building, download `snapshot-arm64.tar.xz` from the [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) releases, place it in the assets directory, and update the `snapshot.sha256` fingerprint. The prebuilt APK in `releases/` already contains the snapshot and is ready to use.

## License

MIT License. dsh itself is MIT-licensed; all third-party dependencies (Termux runtime, node-pty, bubblewrap, etc.) satisfy distribution requirements.
