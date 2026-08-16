# DSH for Android

**DeepSeek Harness (dsh) 原生移植至 Android 16 平板** —— 基于 `dsh-mobile-apk` 的深度定制版。

将 DeepSeek Harness 的"一切皆插件"架构完整移植到 Android 16 (API 36) 平板，提供开箱即用的原生体验：内嵌运行时快照（Node.js + bash + coreutils + dsh 本体）、WebView 承载 dsh Web UI、JS Bridge 双向通信、前台服务保活 + 看门狗、清单驱动在线快照更新、SAF 目录桥接。

## 特性

- **开箱即用**：APK 直接安装，首次启动自动解压运行时快照（~75MB，约 10 秒），不依赖 Termux 等外部环境
- **Android 16 桌面窗口模式适配**：`resizeableActivity` + 扩展 `configChanges`，窗口自由缩放/分屏/旋转不重建 Activity
- **平板限宽布局**：sw600dp+ 或桌面窗口模式下 WebView 限宽居中（最大 1280dp），模拟桌面浏览器体验
- **JS Bridge 环境感知**：`getWindowMode()` / `getScreenInfo()` / `setOrientation()` 供 Web UI 插件实现响应式布局
- **核心功能完整**：dsh web 服务、headless 模式、插件管理、HMR（`--expose-internals`）、node-pty 子进程/PTY、会话持久化
- **后台保活**：前台服务 + 看门狗自动重启异常退出的引擎进程
- **在线更新**：清单驱动的运行时快照更新，不更新 APK 即可单独升级 dsh 引擎与插件
- **文件访问**：SAF (Storage Access Framework) 目录桥接，安全访问平板存储

## 目录结构

```
DSH-for-Android/
├── app/                          # Android 工程源码
│   └── src/main/
│       ├── java/com/dshmobile/shell/   # MainActivity / EngineService / AndroidBridge 等
│       └── assets/               # 运行时快照（snapshot.tar.xz，构建时放入）
├── docs/
│   ├── DeepSeek-Harness-Android16-Port-Plan.md        # 完整技术方案（英文）
│   └── DeepSeek-Harness-Android16-移植技术方案.md      # 完整技术方案（中文）
├── releases/
│   └── dsh-mobile-a16-tablet-arm64.apk                # 可直接安装的 APK
├── gradle/                       # Gradle wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── LICENSE                       # MIT
```

## 构建

环境要求：JDK 17、Android SDK（platform 36 / build-tools 36.0.0）、Gradle 8.13。

```powershell
# 1. 配置 local.properties 指向本地 SDK
# sdk.dir=C:/path/to/android-sdk

# 2. 将运行时快照放入 assets（arm64 平板 ABI）
# 快照来源：dsh-mobile-apk 项目 releases 的 snapshot-arm64.tar.xz
# 并更新 app/src/main/assets/snapshot.sha256 为对应 SHA-256

# 3. 构建
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（release 已配置 debug 签名，可直接安装）。

## 安装

```powershell
adb install -r releases/dsh-mobile-a16-tablet-arm64.apk
```

或直接拷贝 APK 到平板点击安装。

## 首次使用

1. 打开 dsh 应用，首次启动自动解压运行时快照（约 10 秒）
2. 引擎启动后 WebView 自动加载 `http://127.0.0.1:3080`
3. 在 Web UI 中配置模型、工具与插件，开始使用

## 运行时快照说明

`app/src/main/assets/snapshot.tar.xz`（~75MB）为内嵌运行时快照，因体积原因**未提交**到本仓库。构建前需从 [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 的 releases 下载 `snapshot-arm64.tar.xz` 放入 assets 目录，并更新 `snapshot.sha256` 指纹。已构建好的 APK（`releases/`）已包含快照，可直接使用。

## 许可证

MIT License。dsh 本体为 MIT 协议；第三方依赖（Termux 运行时、node-pty、bubblewrap 等）均满足分发要求。
